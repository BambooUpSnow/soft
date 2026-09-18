package com.signlanguage.app.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** 一条手势识别记录 */
public class HistoryEntry {

    public String id = UUID.randomUUID().toString();
    /** 墙上时间（毫秒） */
    public long time = System.currentTimeMillis();
    /** 识别出的手势文字，例如 "我爱你" */
    public String gesture = "";
    /** 当时的心率 bpm，0 表示没有有效值 */
    public int heartRate = 0;
    /** 当时的血氧 %，0 表示没有有效值 */
    public int spo2 = 0;
    /** 原始数据行，便于排查 */
    public String raw = "";

    public HistoryEntry() {
    }

    public HistoryEntry(String gesture, int heartRate, int spo2, String raw) {
        this.gesture = gesture == null ? "" : gesture;
        this.heartRate = heartRate;
        this.spo2 = spo2;
        this.raw = raw == null ? "" : raw;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("t", time);
        o.put("g", gesture);
        o.put("hr", heartRate);
        o.put("spo2", spo2);
        o.put("raw", raw);
        return o;
    }

    public static HistoryEntry fromJson(JSONObject o) {
        HistoryEntry e = new HistoryEntry();
        e.id = o.optString("id", UUID.randomUUID().toString());
        e.time = o.optLong("t", System.currentTimeMillis());
        e.gesture = o.optString("g", "");
        e.heartRate = o.optInt("hr", 0);
        e.spo2 = o.optInt("spo2", 0);
        e.raw = o.optString("raw", "");
        return e;
    }

    public boolean hasVitals() {
        return heartRate > 0 || spo2 > 0;
    }
}
