package com.signlanguage.app.ble;

import android.os.SystemClock;
import android.text.TextUtils;

import com.signlanguage.app.util.LogUtil;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 HC-05 的字节流切成"一行一帧"，再解析成结构化数据。
 *
 * <p>和 Keil 工程（User/main.c + HardWare/HC05.c）的协议约定：
 * <pre>
 *   单片机 -> 手机:  "Gesture: 我爱你\r\n"    识别到的新手势
 *                    "HR=75,SpO2=98\r\n"     心率血氧（每约30秒一次，或收到 "HR?" 时）
 *                    "STM32 Ready\r\n"       上电提示
 *   手机  -> 单片机: "HR?\n"                 查询当前心率血氧
 *                    "PLAY0011\n"            让 JR6001 播放语音文件
 * </pre>
 *
 * <p>汉字是 GB2312 编码（Python 侧 {@code .encode("gb2312")} 发出的），所以这里必须走
 * {@link Gb2312Decoder}。
 */
public class ProtocolParser {

    /** HC05.h 里 #define HC05_RX_BUF_LEN 128，这里留点余量 */
    private static final int MAX_LINE_BYTES = 512;
    /** 超过这个时间没收到换行，就把缓冲当一帧处理 */
    private static final long FLUSH_TIMEOUT_MS = 500;

    public interface Callback {
        /** 收到一条完整数据行（已解码成文本） */
        void onFrame(String text);

        /**
         * 解析出一个手势。
         *
         * @param gesture 手势文字，例如 "我爱你"
         * @param raw     原始整行文本
         */
        void onGesture(String gesture, String raw);

        /**
         * 解析出心率/血氧。
         *
         * @param heartRate 心率 bpm；无效为 null
         * @param spo2      血氧 %；无效为 null
         */
        void onVitals(Integer heartRate, Integer spo2, String raw);

        /** 固件上电/状态提示等非业务文本 */
        void onStatus(String text);
    }

    private static final Pattern P_HR = Pattern.compile(
            "(?:HR|HRT|HEART|心率|BPM|HRV)\\s*[=:：]\\s*(-?\\d{1,3})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SPO2 = Pattern.compile(
            "(?:SPO2|SPO₂|O2|OXI|血氧)\\s*[=:：]\\s*(-?\\d{1,3})",
            Pattern.CASE_INSENSITIVE);
    /** "Gesture: xxx" / "手势: xxx" / "GESTURE=xxx" */
    private static final Pattern P_GESTURE = Pattern.compile(
            "^(?:GESTURE|GES|手势)\\s*[=:：]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);
    /** 含中日韩统一表意文字（真正的识别结果） */
    private static final Pattern P_HAN = Pattern.compile("[\\u4e00-\\u9fff]");
    /** 上电/状态类提示，不要当成手势 */
    private static final Pattern P_NOISE = Pattern.compile(
            "^(?:STM32|READY|RESET|BOOT|OK|ACK|START|INIT|WAIT|"
                    + "就绪|准备好|初始化|等待|上电|复位)"
                    + "(?:\\s+(?:READY|RESET|BOOT|OK|ACK|START|INIT|WAIT))*\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
    private final Callback callback;
    private volatile boolean gb2312 = true;
    private long lastByteAt = 0;

    public ProtocolParser(Callback callback) {
        this.callback = callback;
    }

    /** true=GB2312 解码（默认，匹配当前固件），false=UTF-8 */
    public void setGb2312(boolean gb2312) {
        this.gb2312 = gb2312;
    }

    /** 喂入串口/蓝牙收到的原始字节 */
    public synchronized void feed(byte[] data, int length) {
        if (data == null || length <= 0) {
            return;
        }
        for (int i = 0; i < length; i++) {
            byte b = data[i];
            if (b == '\n') {
                emit(snapshotAndReset());
            } else if (b == '\r') {
                // 忽略，等 \n
            } else {
                if (buffer.size() >= MAX_LINE_BYTES) {
                    // 防溢出：超长行直接丢弃，避免内存无限增长
                    LogUtil.w("单行超过 " + MAX_LINE_BYTES + " 字节，已截断");
                    emit(snapshotAndReset());
                }
                buffer.write(b);
            }
        }
        lastByteAt = SystemClock.elapsedRealtime();
    }

    /**
     * 主循环定期调用：如果半行数据停留太久（固件不发换行），也当成一帧处理。
     * 这样即使固件协议变了也不会"卡住不出字"。
     */
    public synchronized void tick() {
        if (buffer.size() > 0 && lastByteAt > 0
                && SystemClock.elapsedRealtime() - lastByteAt > FLUSH_TIMEOUT_MS) {
            emit(snapshotAndReset());
        }
    }

    public synchronized void reset() {
        buffer.reset();
        lastByteAt = 0;
    }

    private byte[] snapshotAndReset() {
        byte[] out = buffer.toByteArray();
        buffer.reset();
        lastByteAt = 0;
        return out;
    }

    private String decode(byte[] bytes) {
        return gb2312 ? Gb2312Decoder.decode(bytes, 0, bytes.length)
                : Gb2312Decoder.decodeUtf8(bytes, 0, bytes.length);
    }

    private void emit(byte[] bytes) {
        if (bytes.length == 0) {
            return;
        }
        String text = decode(bytes).trim();
        if (text.isEmpty()) {
            return;
        }
        LogUtil.i("RX< " + text);
        callback.onFrame(text);
        parse(text);
    }

    private void parse(String text) {
        String upper = text.toUpperCase(Locale.ROOT);

        // 1) 上电提示 / 状态
        if (P_NOISE.matcher(text).matches() || P_NOISE.matcher(upper).matches()) {
            callback.onStatus(text);
            return;
        }

        // 2) 心率血氧
        Integer hr = null;
        Integer spo2 = null;
        Matcher m = P_HR.matcher(upper);
        if (m.find()) {
            try {
                hr = Integer.valueOf(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        m = P_SPO2.matcher(upper);
        if (m.find()) {
            try {
                spo2 = Integer.valueOf(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        if (hr != null || spo2 != null) {
            // 固件里 0xFF(-1) 或 0 表示无效
            callback.onVitals(normalizeHr(hr), normalizeSpo2(spo2), text);
            return;
        }

        // 3) 手势
        String gesture = null;
        m = P_GESTURE.matcher(text);
        if (m.matches()) {
            gesture = m.group(1).trim();
        } else if (hasHan(text) && !upper.startsWith("PLAY")) {
            // 裸中文行，例如固件直接发 "我爱你"
            gesture = text;
        }
        if (!TextUtils.isEmpty(gesture)) {
            callback.onGesture(gesture, text);
            return;
        }

        // 4) 其他：当状态处理
        callback.onStatus(text);
    }

    private static boolean hasHan(String s) {
        return P_HAN.matcher(s).find();
    }

    /** 有效范围参考固件 User/main.c: heart > 40 && heart < 180 */
    private static Integer normalizeHr(Integer v) {
        if (v == null || v == 255 || v == -1 || v == 0) {
            return null;
        }
        return (v > 40 && v < 180) ? v : null;
    }

    /** 有效范围参考固件 User/main.c: spo2 > 70 && spo2 <= 100 */
    private static Integer normalizeSpo2(Integer v) {
        if (v == null || v == 255 || v == -1 || v == 0) {
            return null;
        }
        return (v > 70 && v <= 100) ? v : null;
    }
}
