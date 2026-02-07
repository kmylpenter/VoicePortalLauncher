package com.voiceportal.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

public class TermuxCommandRunner {
    private static final String TAG = "TermuxCmd";
    private static final String TERMUX_PKG = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_COMMAND = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";

    public static boolean isTermuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static String runCommand(Context context, String command, String workdir) {
        Intent intent = new Intent(ACTION_RUN_COMMAND);
        intent.setClassName(TERMUX_PKG, RUN_COMMAND_SERVICE);
        intent.putExtra(EXTRA_COMMAND, "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra(EXTRA_ARGUMENTS, new String[]{"-c", command});
        if (workdir != null) {
            intent.putExtra(EXTRA_WORKDIR, workdir);
        }
        intent.putExtra(EXTRA_SESSION_ACTION, "0");
        intent.putExtra(EXTRA_BACKGROUND, false);
        return sendServiceIntent(context, intent);
    }

    public static String runInBackground(Context context, String command, String workdir) {
        Intent intent = new Intent(ACTION_RUN_COMMAND);
        intent.setClassName(TERMUX_PKG, RUN_COMMAND_SERVICE);
        intent.putExtra(EXTRA_COMMAND, "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra(EXTRA_ARGUMENTS, new String[]{"-c", command});
        if (workdir != null) {
            intent.putExtra(EXTRA_WORKDIR, workdir);
        }
        intent.putExtra(EXTRA_BACKGROUND, true);
        return sendServiceIntent(context, intent);
    }

    /**
     * Send intent to Termux RunCommandService.
     * Returns null on success, error message on failure.
     */
    private static String sendServiceIntent(Context context, Intent intent) {
        try {
            ComponentName cn = context.startForegroundService(intent);
            if (cn == null) {
                return "startForegroundService returned null";
            }
            return null; // success
        } catch (Exception e) {
            String err1 = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, "startForegroundService failed: " + err1);
            try {
                ComponentName cn = context.startService(intent);
                if (cn == null) {
                    return "startService returned null (after: " + err1 + ")";
                }
                return null; // fallback success
            } catch (Exception e2) {
                String err2 = e2.getClass().getSimpleName() + ": " + e2.getMessage();
                Log.e(TAG, "startService also failed: " + err2);
                return err1 + " | " + err2;
            }
        }
    }
}
