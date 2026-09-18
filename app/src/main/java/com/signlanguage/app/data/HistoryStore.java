package com.signlanguage.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.signlanguage.app.util.LogUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 手势历史记录存储（SharedPreferences + JSON，无需数据库）。
 * 最多保留 {@link #MAX_COUNT} 条，超出后丢最旧的。
 */
public class HistoryStore {

    private static final String FILE = "sign_language_history";
    private static final String KEY = "records";
    public static final int MAX_COUNT = 500;

    private static volatile HistoryStore sInstance;

    private final SharedPreferences sp;
    private final List<HistoryEntry> cache = new ArrayList<>();

    private HistoryStore(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        load();
    }

    public static HistoryStore get(Context context) {
        if (sInstance == null) {
            synchronized (HistoryStore.class) {
                if (sInstance == null) {
                    sInstance = new HistoryStore(context);
                }
            }
        }
        return sInstance;
    }

    private synchronized void load() {
        cache.clear();
        String raw = sp.getString(KEY, null);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    cache.add(HistoryEntry.fromJson(o));
                }
            }
        } catch (JSONException e) {
            LogUtil.e("历史记录解析失败，已忽略", e);
        }
    }

    private synchronized void persist() {
        JSONArray arr = new JSONArray();
        try {
            for (HistoryEntry e : cache) {
                arr.put(e.toJson());
            }
        } catch (JSONException e) {
            LogUtil.e("历史记录序列化失败", e);
            return;
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }

    /** 新增一条（会插到最前面），返回新记录 */
    public synchronized HistoryEntry add(String gesture, int heartRate, int spo2, String raw) {
        HistoryEntry e = new HistoryEntry(gesture, heartRate, spo2, raw);
        cache.add(0, e);
        while (cache.size() > MAX_COUNT) {
            cache.remove(cache.size() - 1);
        }
        persist();
        return e;
    }

    /** 倒序列表（最新的在最前） */
    public synchronized List<HistoryEntry> all() {
        return new ArrayList<>(cache);
    }

    public synchronized int size() {
        return cache.size();
    }

    public synchronized void delete(String id) {
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(id)) {
                cache.remove(i);
                break;
            }
        }
        persist();
    }

    public synchronized void clear() {
        cache.clear();
        persist();
    }

    /** 导出成 CSV 文本 */
    public synchronized String exportCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff'); // BOM，让 Excel 正确识别 UTF-8
        sb.append("序号,时间,手势,心率(bpm),血氧(%),原始数据\n");
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        int n = 0;
        // 导出时按时间正序，方便阅读
        for (int i = cache.size() - 1; i >= 0; i--) {
            HistoryEntry e = cache.get(i);
            n++;
            sb.append(n).append(',')
                    .append(fmt.format(new Date(e.time))).append(',')
                    .append(csv(e.gesture)).append(',')
                    .append(e.heartRate > 0 ? String.valueOf(e.heartRate) : "").append(',')
                    .append(e.spo2 > 0 ? String.valueOf(e.spo2) : "").append(',')
                    .append(csv(e.raw)).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}
