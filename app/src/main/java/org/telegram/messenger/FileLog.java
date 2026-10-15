package org.telegram.messenger;

import android.util.Log;
import com.teleflip.app.report.AdbReporter;

public class FileLog {
    private static final String TAG = "TELEFLIP_TELEGRAM";

    public static void e(Throwable e) {
        Log.e(TAG, "Telegram error", e);
        if (e != null) {
            AdbReporter.INSTANCE.reportError(TAG, e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }
    }

    public static void e(String message) {
        Log.e(TAG, message);
        AdbReporter.INSTANCE.reportError(TAG, message, null);
    }

    public static void e(String message, Throwable e) {
        Log.e(TAG, message, e);
        AdbReporter.INSTANCE.reportError(TAG, message, e);
    }
}
