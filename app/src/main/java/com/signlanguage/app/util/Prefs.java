package com.signlanguage.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置项统一入口（SharedPreferences）。
 */
public class Prefs {

    private static final String FILE = "sign_language_prefs";

    private static final String K_AUTO_READ = "auto_read";
    private static final String K_VOLUME = "volume_percent";
    private static final String K_SPEECH_RATE = "speech_rate";
    private static final String K_AUTO_RECONNECT = "auto_reconnect";
    private static final String K_ENCODING = "decode_encoding";
    private static final String K_LOG_ENABLED = "log_enabled";
    private static final String K_TTS_ENGINE = "tts_engine";
    private static final String K_LAST_MAC = "last_device_mac";
    private static final String K_LAST_NAME = "last_device_name";
    private static final String K_POLL_HR = "poll_hr";
    private static final String K_POLL_INTERVAL = "poll_interval_sec";

    /** 解码方式：GB2312（和当前固件一致）或 UTF-8（若以后改了固件） */
    public static final String ENC_GB2312 = "GB2312";
    public static final String ENC_UTF8 = "UTF-8";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return sp;
    }

    public boolean isAutoRead() {
        return sp.getBoolean(K_AUTO_READ, true);
    }

    public void setAutoRead(boolean v) {
        sp.edit().putBoolean(K_AUTO_READ, v).apply();
    }

    /** 0-100 */
    public int getVolumePercent() {
        return sp.getInt(K_VOLUME, 80);
    }

    public void setVolumePercent(int v) {
        sp.edit().putInt(K_VOLUME, Math.max(0, Math.min(100, v))).apply();
    }

    /** 语速倍率 0.5 - 2.0，1.0 为正常 */
    public float getSpeechRate() {
        return sp.getFloat(K_SPEECH_RATE, 1.0f);
    }

    public void setSpeechRate(float v) {
        sp.edit().putFloat(K_SPEECH_RATE, v).apply();
    }

    public boolean isAutoReconnect() {
        return sp.getBoolean(K_AUTO_RECONNECT, true);
    }

    public void setAutoReconnect(boolean v) {
        sp.edit().putBoolean(K_AUTO_RECONNECT, v).apply();
    }

    public String getEncoding() {
        return sp.getString(K_ENCODING, ENC_GB2312);
    }

    public void setEncoding(String v) {
        sp.edit().putString(K_ENCODING, v).apply();
    }

    public boolean isLogEnabled() {
        return sp.getBoolean(K_LOG_ENABLED, false);
    }

    public void setLogEnabled(boolean v) {
        sp.edit().putBoolean(K_LOG_ENABLED, v).apply();
    }

    public String getTtsEngine() {
        return sp.getString(K_TTS_ENGINE, "");
    }

    public void setTtsEngine(String v) {
        sp.edit().putString(K_TTS_ENGINE, v == null ? "" : v).apply();
    }

    public String getLastMac() {
        return sp.getString(K_LAST_MAC, "");
    }

    public String getLastName() {
        return sp.getString(K_LAST_NAME, "");
    }

    public void setLastDevice(String mac, String name) {
        sp.edit().putString(K_LAST_MAC, mac == null ? "" : mac)
                .putString(K_LAST_NAME, name == null ? "" : name)
                .apply();
    }

    /** 是否定时向单片机要心率血氧（配合固件里的 "HR?" 指令） */
    public boolean isPollVitals() {
        return sp.getBoolean(K_POLL_HR, true);
    }

    public void setPollVitals(boolean v) {
        sp.edit().putBoolean(K_POLL_HR, v).apply();
    }

    /** 轮询间隔（秒） */
    public int getPollIntervalSec() {
        return sp.getInt(K_POLL_INTERVAL, 15);
    }

    public void setPollIntervalSec(int v) {
        sp.edit().putInt(K_POLL_INTERVAL, Math.max(3, Math.min(120, v))).apply();
    }
}
