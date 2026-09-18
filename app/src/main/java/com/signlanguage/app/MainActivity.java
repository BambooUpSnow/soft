package com.signlanguage.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.signlanguage.app.ble.BleManager;
import com.signlanguage.app.data.HistoryStore;
import com.signlanguage.app.speech.SpeechHelper;
import com.signlanguage.app.ui.HistoryFragment;
import com.signlanguage.app.ui.MonitorFragment;
import com.signlanguage.app.ui.SettingsFragment;
import com.signlanguage.app.util.LogUtil;
import com.signlanguage.app.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 唯一 Activity：底部三个页签（实时监测 / 历史记录 / 设置）。
 *
 * <p>同时它是蓝牙数据的分发中心：{@link BleManager} 的回调统一在这里处理，
 * 更新心率血氧、写历史记录、触发朗读，再把事件通过 {@link UiListener} 通知当前显示的页面。
 */
public class MainActivity extends AppCompatActivity implements BleManager.Listener {

    private static final int REQ_PERM = 1001;

    /** 心率血氧有效期：超过这个时间的旧值不再写进历史 */
    private static final long VITALS_TTL_MS = 60_000;

    public interface UiListener {
        void onBleState(BleManager.State state, String deviceName, String message);

        void onGesture(String gesture, String raw);

        void onVitals(Integer heartRate, Integer spo2, String raw);

        void onStatus(String text);

        void onVolumeChanged(int percent);
    }

    private final List<UiListener> uiListeners = new ArrayList<>();

    private BleManager ble;
    private SpeechHelper speech;
    private HistoryStore history;
    private Prefs prefs;
    private AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---------------- 当前状态（供各页面读取） ----------------
    private volatile Integer currentHr;
    private volatile Integer currentSpo2;
    private volatile long vitalsAt;
    private volatile String currentGesture = "";
    private volatile BleManager.State lastState = BleManager.State.IDLE;
    private volatile String lastStateMessage = "";

    private boolean activityStarted = false;

    /** 用户手动断开后，本次运行不再自动重连（onStart 里会检查） */
    private boolean userDisconnected = false;

    private final ContentObserver volumeObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            if (activityStarted) {
                notifyVolumeChanged(speech.getVolumePercent());
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        history = HistoryStore.get(this);
        ble = BleManager.get(this);
        ble.applyPrefs(prefs);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        speech = new SpeechHelper(this);
        speech.init();
        speech.setRate(prefs.getSpeechRate());
        speech.setVolumePercent(prefs.getVolumePercent(), false);

        ble.addListener(this);

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.tab_monitor) {
                showFragment(new MonitorFragment());
            } else if (id == R.id.tab_history) {
                showFragment(new HistoryFragment());
            } else if (id == R.id.tab_settings) {
                showFragment(new SettingsFragment());
            }
            return true;
        });

        // 监听媒体音量变化（用户按音量键时同步滑块）
        try {
            getContentResolver().registerContentObserver(
                    Settings.System.CONTENT_URI, true, volumeObserver);
        } catch (Exception e) {
            LogUtil.w("注册音量监听失败: " + e.getMessage());
        }

        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.tab_monitor);
        }

        requestBluetoothPermissions();
        LogUtil.i("App 启动完成（历史记录 " + history.size() + " 条）");
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        // 有上次设备且开了自动重连，就悄悄连回去（用户手动断开的除外）
        if (prefs.isAutoReconnect() && !userDisconnected && !TextUtils.isEmpty(prefs.getLastMac())
                && !ble.isConnected() && !ble.isDemoMode()) {
            ble.connect(prefs.getLastMac(), prefs.getLastName(), true);
        }
    }

    @Override
    protected void onDestroy() {
        activityStarted = false;
        ble.removeListener(this);
        try {
            getContentResolver().unregisterContentObserver(volumeObserver);
        } catch (Exception ignored) {
        }
        speech.shutdown();
        super.onDestroy();
    }

    private void showFragment(Fragment f) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, f)
                .commitAllowingStateLoss();
    }

    // ---------------------------------------------------------------- 页面注册

    public void registerUiListener(UiListener l) {
        if (l != null && !uiListeners.contains(l)) {
            uiListeners.add(l);
        }
    }

    public void unregisterUiListener(UiListener l) {
        uiListeners.remove(l);
    }

    // ---------------------------------------------------------------- 供 Fragment 调用

    public BleManager ble() {
        return ble;
    }

    public SpeechHelper speech() {
        return speech;
    }

    public HistoryStore history() {
        return history;
    }

    public Prefs prefs() {
        return prefs;
    }

    public Integer getCurrentHr() {
        return currentHr;
    }

    public Integer getCurrentSpo2() {
        return currentSpo2;
    }

    public boolean isVitalsFresh() {
        return vitalsAt > 0 && System.currentTimeMillis() - vitalsAt < VITALS_TTL_MS;
    }

    public String getCurrentGesture() {
        return currentGesture;
    }

    public BleManager.State getLastState() {
        return lastState;
    }

    public String getLastStateMessage() {
        return lastStateMessage;
    }

    public void connectDevice(BleManager.DeviceInfo info) {
        userDisconnected = false;
        prefs.setLastDevice(info.address, info.name);
        ble.applyPrefs(prefs);
        ble.connect(info.address, info.name, prefs.isAutoReconnect());
    }

    /** 用户主动断开：同时记住"不要再自动连回来" */
    public void disconnectDevice() {
        userDisconnected = true;
        ble.disconnect();
    }

    public void requestVitals() {
        ble.send("HR?");
        Toast.makeText(this, R.string.toast_hr_requested, Toast.LENGTH_SHORT).show();
    }

    /** 朗读一段文字（受"自动朗读"开关控制的可选参数） */
    public void speak(String text) {
        if (!speech.isReady()) {
            Toast.makeText(this, R.string.toast_tts_not_ready, Toast.LENGTH_SHORT).show();
        }
        speech.speak(text);
    }

    public void refreshVolumeFromSystem() {
        notifyVolumeChanged(speech.getVolumePercent());
    }

    private void notifyVolumeChanged(int percent) {
        for (UiListener l : new ArrayList<>(uiListeners)) {
            l.onVolumeChanged(percent);
        }
    }

    // ---------------------------------------------------------------- 权限

    private void requestBluetoothPermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) {
            return;
        }
        boolean all = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                all = false;
            }
        }
        if (all) {
            LogUtil.i("蓝牙权限已授予");
            for (UiListener l : new ArrayList<>(uiListeners)) {
                l.onBleState(ble.getState(), ble.getDeviceName(), getString(R.string.msg_perm_ok));
            }
        } else {
            Toast.makeText(this, R.string.toast_need_bt_permission, Toast.LENGTH_LONG).show();
            LogUtil.e("蓝牙权限被拒绝，无法连接 HC-05");
        }
    }

    // ---------------------------------------------------------------- BleManager.Listener

    @Override
    public void onConnectionState(BleManager.State state, String deviceName, String message) {
        lastState = state;
        lastStateMessage = message == null ? "" : message;
        if (state != BleManager.State.CONNECTED) {
            currentHr = null;
            currentSpo2 = null;
            vitalsAt = 0;
        }
        for (UiListener l : new ArrayList<>(uiListeners)) {
            l.onBleState(state, deviceName, lastStateMessage);
        }
    }

    @Override
    public void onFrame(String text) {
        // 原始帧内容已经在 LogUtil 里记录了，这里不用再处理
    }

    @Override
    public void onGesture(String gesture, String raw) {
        if (TextUtils.isEmpty(gesture)) {
            return;
        }
        boolean changed = !gesture.equals(currentGesture);
        currentGesture = gesture;

        // 写历史：同一个手势连续上报不重复记录
        if (changed) {
            int hr = isVitalsFresh() && currentHr != null ? currentHr : 0;
            int spo2 = isVitalsFresh() && currentSpo2 != null ? currentSpo2 : 0;
            history.add(gesture, hr, spo2, raw);
        }

        for (UiListener l : new ArrayList<>(uiListeners)) {
            l.onGesture(gesture, raw);
        }

        if (changed && prefs.isAutoRead()) {
            speak(gesture);
        }
    }

    @Override
    public void onVitals(Integer heartRate, Integer spo2, String raw) {
        if (heartRate != null) {
            currentHr = heartRate;
        }
        if (spo2 != null) {
            currentSpo2 = spo2;
        }
        if (heartRate != null || spo2 != null) {
            vitalsAt = System.currentTimeMillis();
        }
        if (heartRate == null && spo2 == null) {
            LogUtil.w("收到无效心率血氧值: " + raw);
        }
        for (UiListener l : new ArrayList<>(uiListeners)) {
            l.onVitals(heartRate, spo2, raw);
        }
    }

    @Override
    public void onStatus(String text) {
        for (UiListener l : new ArrayList<>(uiListeners)) {
            l.onStatus(text);
        }
    }
}
