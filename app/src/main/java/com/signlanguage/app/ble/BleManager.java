package com.signlanguage.app.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import com.signlanguage.app.util.LogUtil;
import com.signlanguage.app.util.Prefs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HC-05 / HC-06 经典蓝牙串口（SPP）通信管理器。
 *
 * <p>要点：
 * <ul>
 *   <li>用标准 SPP UUID 00001101-0000-1000-8000-00805F9B34FB 连接，HC-05 默认走这个。</li>
 *   <li>连接 + 读循环全在后台线程，回调统一切回主线程，UI 里可以直接更新控件。</li>
 *   <li>断线自动重连（可关），主线程 500ms 心跳：驱动 {@link ProtocolParser#tick()}
 *       半行超时、定时发 "HR?" 轮询心率血氧、检测 socket 静默断开。</li>
 * </ul>
 */
public class BleManager {

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long HEARTBEAT_MS = 500;
    private static final long RETRY_MS = 3000;
    private static final int CONNECT_RETRY = 3;

    public enum State {
        IDLE,
        CONNECTING,
        CONNECTED
    }

    public interface Listener {
        void onConnectionState(State state, String deviceName, String message);

        /** 收到一条原始数据行（已解码） */
        void onFrame(String text);

        /** 识别到手势 */
        void onGesture(String gesture, String raw);

        /** 心率/血氧，null 表示这次没有有效值 */
        void onVitals(Integer heartRate, Integer spo2, String raw);

        /** 固件状态提示 */
        void onStatus(String text);
    }

    private static volatile BleManager sInstance;

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final ProtocolParser parser;
    private final Object socketLock = new Object();

    private BluetoothSocket socket;
    private OutputStream out;
    private InputStream in;
    private Thread readerThread;
    private Thread connectThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private volatile State state = State.IDLE;
    private volatile String deviceAddress = "";
    private volatile String deviceName = "";
    private volatile boolean autoReconnect = true;
    private volatile long lastRxAt = 0;
    private volatile long lastPollAt = 0;
    private volatile int pollIntervalSec = 15;
    private volatile boolean pollVitals = true;

    private volatile boolean demoMode = false;
    private int demoIndex = 0;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (stopRequested.get()) {
                return;
            }
            parser.tick();
            if (state == State.CONNECTED && !demoMode) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (pollVitals && now - lastPollAt >= pollIntervalSec * 1000L) {
                    lastPollAt = now;
                    send("HR?");
                }
                // 静默 20 秒说明链路可能已经断了（HC-05 掉电/超距离不会通知）
                if (lastRxAt > 0 && now - lastRxAt > 20000) {
                    LogUtil.w("超过20秒没收到数据，判定链路断开");
                    lastRxAt = now;
                    handleBrokenConnection("长时间无数据");
                }
            }
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    // ---------------------------------------------------------------- 单例

    private BleManager(Context context) {
        appContext = context.getApplicationContext();
        parser = new ProtocolParser(new ProtocolParser.Callback() {
            @Override
            public void onFrame(String text) {
                dispatchFrame(text);
            }

            @Override
            public void onGesture(String gesture, String raw) {
                dispatchGesture(gesture, raw);
            }

            @Override
            public void onVitals(Integer heartRate, Integer spo2, String raw) {
                dispatchVitals(heartRate, spo2, raw);
            }

            @Override
            public void onStatus(String text) {
                dispatchStatus(text);
            }
        });
        main.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    public static BleManager get(Context context) {
        if (sInstance == null) {
            synchronized (BleManager.class) {
                if (sInstance == null) {
                    sInstance = new BleManager(context);
                }
            }
        }
        return sInstance;
    }

    // ---------------------------------------------------------------- 监听

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public State getState() {
        return state;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public boolean isDemoMode() {
        return demoMode;
    }

    // ---------------------------------------------------------------- 设置

    public void applyPrefs(Prefs prefs) {
        parser.setGb2312(!Prefs.ENC_UTF8.equals(prefs.getEncoding()));
        autoReconnect = prefs.isAutoReconnect();
        pollVitals = prefs.isPollVitals();
        pollIntervalSec = prefs.getPollIntervalSec();
    }

    // ---------------------------------------------------------------- 扫描/配对

    public static boolean hasPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public boolean isBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    /** 已配对的设备（HC-05 一般已经配好，直接从列表选就行） */
    @SuppressLint("MissingPermission")
    public List<DeviceInfo> getBondedDevices() {
        List<DeviceInfo> list = new ArrayList<>();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !hasPermission(appContext)) {
            return list;
        }
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String name = d.getName();
                if (TextUtils.isEmpty(name)) {
                    name = "(未知设备)";
                }
                list.add(new DeviceInfo(d.getAddress(), name, d.getBondState() == BluetoothDevice.BOND_BONDED));
            }
        } catch (SecurityException e) {
            LogUtil.e("读取已配对设备失败", e);
        }
        return list;
    }

    private final Map<String, DeviceInfo> discovered = new LinkedHashMap<>();

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) {
                    return;
                }
                try {
                    String name = device.getName();
                    if (TextUtils.isEmpty(name)) {
                        name = "(未知设备)";
                    }
                    synchronized (discovered) {
                        discovered.put(device.getAddress(),
                                new DeviceInfo(device.getAddress(), name, false));
                    }
                } catch (SecurityException ignored) {
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                LogUtil.i("扫描结束，发现 " + discovered.size() + " 个新设备");
            }
        }
    };

    @SuppressLint("MissingPermission")
    public void startDiscovery() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            toastMain("本机没有蓝牙适配器");
            return;
        }
        if (!hasPermission(appContext)) {
            toastMain("缺少蓝牙权限");
            return;
        }
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            synchronized (discovered) {
                discovered.clear();
            }
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            // 反复注册会抛异常，先尝试注销
            try {
                appContext.unregisterReceiver(discoveryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            appContext.registerReceiver(discoveryReceiver, filter);
            adapter.startDiscovery();
            LogUtil.i("开始扫描蓝牙设备…");
        } catch (SecurityException e) {
            LogUtil.e("扫描失败", e);
        }
    }

    /** 扫描到的新设备（未配对），用于配对 HC-05 */
    public List<DeviceInfo> getDiscoveredDevices() {
        synchronized (discovered) {
            return new ArrayList<>(discovered.values());
        }
    }

    @SuppressLint("MissingPermission")
    public void stopDiscovery() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && hasPermission(appContext) && adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }
    }

    // ---------------------------------------------------------------- 连接

    public void connect(String address, String name, boolean autoReconnect) {
        if (TextUtils.isEmpty(address)) {
            return;
        }
        this.autoReconnect = autoReconnect;
        stopRequested.set(false);
        deviceAddress = address;
        deviceName = name;
        disconnectInternal(false);
        setState(State.CONNECTING, name, "正在连接 " + name + " …");

        connectThread = new Thread(() -> doConnect(address, name), "ble-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    @SuppressLint("MissingPermission")
    private void doConnect(String address, String name) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setState(State.IDLE, name, "本机没有蓝牙适配器");
            return;
        }
        if (!adapter.isEnabled()) {
            setState(State.IDLE, name, "蓝牙未开启，请先打开蓝牙");
            return;
        }
        if (!hasPermission(appContext)) {
            setState(State.IDLE, name, "缺少蓝牙权限");
            return;
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            setState(State.IDLE, name, "蓝牙地址无效: " + address);
            return;
        }

        IOException lastError = null;
        for (int attempt = 1; attempt <= CONNECT_RETRY && !stopRequested.get(); attempt++) {
            BluetoothSocket s = null;
            try {
                adapter.cancelDiscovery();
                s = device.createRfcommSocketToServiceRecord(SPP_UUID);
                LogUtil.i("连接尝试 " + attempt + "/" + CONNECT_RETRY + " -> " + address);
                s.connect();
                synchronized (socketLock) {
                    socket = s;
                    out = s.getOutputStream();
                    in = s.getInputStream();
                }
                lastRxAt = android.os.SystemClock.elapsedRealtime();
                lastPollAt = android.os.SystemClock.elapsedRealtime();
                parser.reset();
                startReader(s);
                running.set(true);
                setState(State.CONNECTED, name, "已连接 " + name);
                // 固件会周期性上报，这里再主动要一次
                main.postDelayed(() -> send("HR?"), 1200);
                return;
            } catch (IOException e) {
                lastError = e;
                closeQuietly(s);
                LogUtil.w("第 " + attempt + " 次连接失败: " + e.getMessage());
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (SecurityException e) {
                setState(State.IDLE, name, "连接被系统拒绝（权限）");
                return;
            }
        }
        if (!stopRequested.get()) {
            String msg = "连接失败" + (lastError != null ? "：" + lastError.getMessage() : "");
            setState(State.IDLE, name, msg);
            scheduleRetry();
        }
    }

    private void startReader(BluetoothSocket s) {
        readerThread = new Thread(() -> {
            byte[] buf = new byte[256];
            while (running.get() && !stopRequested.get()) {
                int n;
                try {
                    InputStream stream = in;
                    if (stream == null) {
                        break;
                    }
                    n = stream.read(buf);
                } catch (IOException e) {
                    if (!stopRequested.get()) {
                        LogUtil.w("读取出错: " + e.getMessage());
                    }
                    break;
                }
                if (n < 0) {
                    LogUtil.w("对端关闭了连接");
                    break;
                }
                if (n > 0) {
                    lastRxAt = android.os.SystemClock.elapsedRealtime();
                    parser.feed(buf, n);
                }
            }
            running.set(false);
            if (!stopRequested.get()) {
                main.post(() -> handleBrokenConnection("连接已断开"));
            }
        }, "ble-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void handleBrokenConnection(String reason) {
        if (state == State.IDLE && !demoMode) {
            return;
        }
        disconnectInternal(true);
        setState(State.IDLE, deviceName, reason);
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (!autoReconnect || demoMode || TextUtils.isEmpty(deviceAddress) || stopRequested.get()) {
            return;
        }
        final String addr = deviceAddress;
        final String name = deviceName;
        LogUtil.i(RETRY_MS / 1000 + " 秒后自动重连 " + name);
        main.postDelayed(() -> {
            if (autoReconnect && !demoMode && state == State.IDLE && !stopRequested.get()
                    && addr.equals(deviceAddress)) {
                connect(addr, name, true);
            }
        }, RETRY_MS);
    }

    /** 用户主动断开 */
    public void disconnect() {
        stopRequested.set(true);
        disconnectInternal(true);
        setState(State.IDLE, deviceName, "已断开");
    }

    private void disconnectInternal(boolean stopThreads) {
        running.set(false);
        synchronized (socketLock) {
            closeQuietly(socket);
            socket = null;
            out = null;
            in = null;
        }
        if (stopThreads) {
            Thread r = readerThread;
            readerThread = null;
            if (r != null) {
                r.interrupt();
            }
            Thread c = connectThread;
            connectThread = null;
            if (c != null) {
                c.interrupt();
            }
        }
    }

    // ---------------------------------------------------------------- 发送

    /** 发送一行命令，会自动补 '\n'（固件用 '\n' 判定一帧结束） */
    public void send(String command) {
        if (command == null) {
            return;
        }
        String line = command.endsWith("\n") ? command : command + "\n";
        if (demoMode) {
            LogUtil.i("TX> " + line.trim() + "（演示模式，未真正发送）");
            dispatchDemoResponse(line.trim());
            return;
        }
        OutputStream os = out;
        if (os == null || state != State.CONNECTED) {
            LogUtil.w("未连接，发送失败: " + line.trim());
            return;
        }
        try {
            os.write(line.getBytes("US-ASCII"));
            os.flush();
            LogUtil.i("TX> " + line.trim());
        } catch (IOException e) {
            LogUtil.e("发送失败", e);
            handleBrokenConnection("发送失败，连接可能已断开");
        } catch (java.io.UnsupportedEncodingException e) {
            LogUtil.e("编码不支持", e);
        }
    }

    // ---------------------------------------------------------------- 演示模式

    /** 没有硬件时用来跑通App的模拟数据源 */
    public void setDemoMode(boolean enabled) {
        demoMode = enabled;
        main.removeCallbacks(demoTick);
        if (enabled) {
            stopRequested.set(true);
            disconnectInternal(true);
            setState(State.CONNECTED, "演示设备", "演示模式（无硬件）");
            parser.reset();
            demoIndex = 0;
            LogUtil.i("已开启演示模式：模拟手势/心率/血氧数据");
            main.post(demoTick);
        } else {
            setState(State.IDLE, deviceName, "已关闭演示模式");
            LogUtil.i("已关闭演示模式");
        }
    }

    private static final String[] DEMO_GESTURES = {
            "我爱你", "我想回家", "你好，你吃了什么", "不行", "没有", "谢谢", "再见", "你的名字是什么"
    };

    private final Runnable demoTick = new Runnable() {
        @Override
        public void run() {
            if (!demoMode || stopRequested.get()) {
                return;
            }
            String g = DEMO_GESTURES[demoIndex % DEMO_GESTURES.length];
            demoIndex++;
            try {
                parser.feed(("Gesture: " + g + "\r\n").getBytes("GB2312"));
            } catch (java.io.UnsupportedEncodingException e) {
                LogUtil.e("演示数据编码失败", e);
            }
            int hr = 66 + (int) (Math.random() * 22);
            int spo2 = 95 + (int) (Math.random() * 5);
            try {
                parser.feed(("HR=" + hr + ",SpO2=" + spo2 + "\r\n").getBytes("US-ASCII"));
            } catch (java.io.UnsupportedEncodingException e) {
                LogUtil.e("演示数据编码失败", e);
            }
            main.postDelayed(this, 4000);
        }
    };

    private void dispatchDemoResponse(String command) {
        String upper = command.toUpperCase();
        if (upper.startsWith("HR?")) {
            int hr = 66 + (int) (Math.random() * 22);
            int spo2 = 95 + (int) (Math.random() * 5);
            dispatchVitals(hr, spo2, "HR=" + hr + ",SpO2=" + spo2);
        } else if (upper.startsWith("PLAY")) {
            dispatchStatus("演示模式：收到播放指令 " + command.substring(4));
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private void setState(State s, String name, String message) {
        state = s;
        if (!TextUtils.isEmpty(name)) {
            deviceName = name;
        }
        LogUtil.i("状态: " + s + " / " + message);
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onConnectionState(s, deviceName, message);
            }
        });
    }

    private void toastMain(String msg) {
        LogUtil.w(msg);
    }

    private void dispatchFrame(final String text) {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onFrame(text);
            }
        });
    }

    private void dispatchGesture(final String gesture, final String raw) {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onGesture(gesture, raw);
            }
        });
    }

    private void dispatchVitals(final Integer hr, final Integer spo2, final String raw) {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onVitals(hr, spo2, raw);
            }
        });
    }

    private void dispatchStatus(final String text) {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onStatus(text);
            }
        });
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 蓝牙设备信息（简易 POJO） */
    public static class DeviceInfo {
        public final String address;
        public final String name;
        public final boolean bonded;

        public DeviceInfo(String address, String name, boolean bonded) {
            this.address = address;
            this.name = name;
            this.bonded = bonded;
        }

        @Override
        public String toString() {
            return name + "\n" + address;
        }
    }
}
