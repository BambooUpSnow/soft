package com.signlanguage.app.util;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全App唯一的日志出口：既打到 logcat，也留一份内存环形缓冲给"设置页-通信日志"用。
 */
public final class LogUtil {

    private static final String TAG = "SignLang";
    private static final int MAX_LINES = 400;

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private static final ArrayDeque<String> LINES = new ArrayDeque<>(MAX_LINES + 1);
    private static final Object LOCK = new Object();

    public interface Listener {
        void onLog(String line);
    }

    private static volatile Listener sListener;

    private LogUtil() {
    }

    public static void setListener(Listener listener) {
        sListener = listener;
    }

    public static void i(String msg) {
        add("I", msg);
    }

    public static void w(String msg) {
        add("W", msg);
    }

    public static void e(String msg) {
        add("E", msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        add("E", msg + " : " + t);
    }

    private static void add(String level, String msg) {
        String line = TIME.format(new Date()) + " [" + level + "] " + msg;
        if ("E".equals(level)) {
            Log.e(TAG, msg);
        } else if ("W".equals(level)) {
            Log.w(TAG, msg);
        } else {
            Log.i(TAG, msg);
        }
        synchronized (LOCK) {
            while (LINES.size() >= MAX_LINES) {
                LINES.pollFirst();
            }
            LINES.addLast(line);
        }
        Listener l = sListener;
        if (l != null) {
            l.onLog(line);
        }
    }

    public static List<String> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
    }
}
