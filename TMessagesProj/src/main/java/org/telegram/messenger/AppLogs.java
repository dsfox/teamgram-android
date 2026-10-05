package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;

import org.telegram.ui.ProfileActivity;

/**
 * The app's own log, sent in the open from Settings > "Send app logs" (#227).
 *
 * Telegram reached its log through a developer menu behind hidden taps, which
 * is gone from every build; this is the one way left, the twin of the row on
 * the iOS Server screen. A release build keeps no log until asked to, so the
 * first tap also starts one - what it sends then is short, and the next tap,
 * after the problem has happened again, carries it.
 */
public final class AppLogs {

    private AppLogs() {
    }

    public static void send(Activity activity) {
        if (activity == null) {
            return;
        }
        if (!BuildVars.LOGS_ENABLED) {
            BuildVars.LOGS_ENABLED = true;
            ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE)
                    .edit().putBoolean("logsEnabled", true).commit();
            FileLog.d("logs started from Settings > Send app logs, app start time = " + ApplicationLoader.startTime);
        }
        ProfileActivity.sendLogs(activity, false);
    }
}
