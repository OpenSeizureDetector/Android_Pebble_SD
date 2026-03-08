package uk.org.openseizuredetector.data.logging;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import uk.org.openseizuredetector.utils.OsdUtil;

/**
 * Log wrapper for the data.logging package.
 * Mirrors Android logcat output and optionally writes selected levels to the persistent syslog file.
 */
public final class Log {
    private static final String PREF_KEY_SYSLOG_LEVEL = "SysLogLevel";
    private static final String DEFAULT_SYSLOG_LEVEL = "INFO";

    private static volatile Context sContext;
    private static volatile OsdUtil sUtil;

    private Log() {
    }

    public static void init(Context context, OsdUtil util) {
        sContext = context != null ? context.getApplicationContext() : null;
        sUtil = util;
    }

    public static int v(String tag, String msg) {
        int ret = android.util.Log.v(tag, msg);
        writeToFileIfEnabled("VERBOSE", tag, msg, null);
        return ret;
    }

    public static int d(String tag, String msg) {
        int ret = android.util.Log.d(tag, msg);
        writeToFileIfEnabled("DEBUG", tag, msg, null);
        return ret;
    }

    public static int i(String tag, String msg) {
        int ret = android.util.Log.i(tag, msg);
        writeToFileIfEnabled("INFO", tag, msg, null);
        return ret;
    }

    public static int w(String tag, String msg) {
        int ret = android.util.Log.w(tag, msg);
        writeToFileIfEnabled("WARN", tag, msg, null);
        return ret;
    }

    public static int w(String tag, String msg, Throwable tr) {
        int ret = android.util.Log.w(tag, msg, tr);
        writeToFileIfEnabled("WARN", tag, msg, tr);
        return ret;
    }

    public static int e(String tag, String msg) {
        int ret = android.util.Log.e(tag, msg);
        writeToFileIfEnabled("ERROR", tag, msg, null);
        return ret;
    }

    public static int e(String tag, String msg, Throwable tr) {
        int ret = android.util.Log.e(tag, msg, tr);
        writeToFileIfEnabled("ERROR", tag, msg, tr);
        return ret;
    }

    private static void writeToFileIfEnabled(String level, String tag, String msg, Throwable tr) {
        OsdUtil util = sUtil;
        Context context = sContext;
        if (util == null || context == null) {
            return;
        }

        if (!shouldWriteLevel(level, context)) {
            return;
        }

        String text = tag + ": " + msg;
        if (tr != null) {
            text = text + "\n" + android.util.Log.getStackTraceString(tr);
        }
        util.writeToSysLogFile(text, level);
    }

    private static boolean shouldWriteLevel(String level, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String threshold = prefs.getString(PREF_KEY_SYSLOG_LEVEL, DEFAULT_SYSLOG_LEVEL);
        return toPriority(level) >= toPriority(threshold);
    }

    private static int toPriority(String level) {
        if (level == null) {
            return android.util.Log.INFO;
        }
        switch (level.toUpperCase()) {
            case "VERBOSE":
                return android.util.Log.VERBOSE;
            case "DEBUG":
                return android.util.Log.DEBUG;
            case "INFO":
                return android.util.Log.INFO;
            case "WARN":
                return android.util.Log.WARN;
            case "ERROR":
                return android.util.Log.ERROR;
            default:
                return android.util.Log.INFO;
        }
    }
}

