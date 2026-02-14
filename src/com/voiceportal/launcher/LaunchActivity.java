package com.voiceportal.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class LaunchActivity extends Activity implements ServerLauncher.StatusCallback, View.OnClickListener {

    private static final int RC_TERMUX_PERM = 100;
    private static final String TERMUX_RUN_PERM = "com.termux.permission.RUN_COMMAND";

    private ServerLauncher launcher;
    private Handler mainHandler;

    private View step1Dot, step2Dot, step3Dot;
    private TextView step1Text, step2Text, step3Text;
    private ProgressBar progressBar;
    private TextView errorText;
    private View errorButtons;
    private TextView logText;
    private ScrollView logScroll;
    private int port;
    private int idleTimeoutMin;
    private boolean needsProxy;
    private String projectPath, devCommand, voiceMode, appId, appName;
    private StringBuilder logBuffer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);

        mainHandler = new Handler(Looper.getMainLooper());
        logBuffer = new StringBuilder();

        appName = getIntent().getStringExtra("app_name");
        appId = getIntent().getStringExtra("app_id");
        projectPath = getIntent().getStringExtra("app_project_path");
        port = getIntent().getIntExtra("app_port", 5173);
        devCommand = getIntent().getStringExtra("app_dev_command");
        voiceMode = getIntent().getStringExtra("app_voice_mode");
        idleTimeoutMin = getIntent().getIntExtra("app_idle_timeout", 0);
        needsProxy = voiceMode != null && !voiceMode.isEmpty() && !voiceMode.equals("none");

        TextView appNameView = findViewById(R.id.launch_app_name);
        appNameView.setText(appName);

        step1Dot = findViewById(R.id.step1_dot);
        step2Dot = findViewById(R.id.step2_dot);
        step3Dot = findViewById(R.id.step3_dot);
        step1Text = findViewById(R.id.step1_text);
        step2Text = findViewById(R.id.step2_text);
        step3Text = findViewById(R.id.step3_text);
        progressBar = findViewById(R.id.progress_bar);
        errorText = findViewById(R.id.error_text);
        errorButtons = findViewById(R.id.error_buttons);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);

        Button retryBtn = findViewById(R.id.retry_button);
        Button cancelBtn = findViewById(R.id.cancel_button);
        Button copyBtn = findViewById(R.id.copy_log_button);
        retryBtn.setOnClickListener(this);
        cancelBtn.setOnClickListener(this);
        copyBtn.setOnClickListener(this);

        // Hide proxy step for apps that don't use VoicePortal
        if (!needsProxy) {
            step2Dot.setVisibility(View.GONE);
            step2Text.setVisibility(View.GONE);
        }

        ensurePermissionAndLaunch();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.retry_button) {
            ensurePermissionAndLaunch();
        } else if (id == R.id.cancel_button) {
            finish();
        } else if (id == R.id.copy_log_button) {
            ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clip != null) {
                clip.setPrimaryClip(ClipData.newPlainText("launch_log", logBuffer.toString()));
                Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void ensurePermissionAndLaunch() {
        if (checkSelfPermission(TERMUX_RUN_PERM) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{TERMUX_RUN_PERM}, RC_TERMUX_PERM);
            return;
        }
        startLaunch();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == RC_TERMUX_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLaunch();
            } else {
                onError("Termux RUN_COMMAND permission denied.\nGrant in Settings > Apps > VoicePortal > Permissions.");
            }
        }
    }

    private void startLaunch() {
        logBuffer = new StringBuilder();
        resetUI();
        AppConfig config = new AppConfig(appId, appName, "", projectPath, port, devCommand, voiceMode, idleTimeoutMin);
        launcher = new ServerLauncher(this, config);
        launcher.launch(this);
    }

    @Override
    public void onDevServerStarting() {
        mainHandler.post(new SetStepRunnable(1, R.color.status_pending, R.color.text_primary));
    }

    @Override
    public void onProxyStarting() {
        mainHandler.post(new SetStepRunnable(2, R.color.status_pending, R.color.text_primary));
    }

    @Override
    public void onWaitingForServers() {
        mainHandler.post(new SetStepRunnable(3, R.color.status_pending, R.color.text_primary));
    }

    @Override
    public void onServersReady() {
        mainHandler.post(new ServersReadyRunnable());
    }

    @Override
    public void onError(String message) {
        mainHandler.post(new ErrorRunnable(message));
    }

    @Override
    public void onLog(String message) {
        mainHandler.post(new LogRunnable(message));
    }

    private void resetUI() {
        int hintColor = getColor(R.color.text_hint);
        ColorStateList hintTint = ColorStateList.valueOf(hintColor);

        step1Dot.setBackgroundTintList(hintTint);
        step2Dot.setBackgroundTintList(hintTint);
        step3Dot.setBackgroundTintList(hintTint);
        step1Text.setTextColor(hintColor);
        step2Text.setTextColor(hintColor);
        step3Text.setTextColor(hintColor);
        step3Text.setText(R.string.waiting_servers);
        progressBar.setVisibility(View.VISIBLE);
        errorText.setVisibility(View.GONE);
        errorButtons.setVisibility(View.GONE);
        logText.setText("");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (launcher != null) {
            launcher.cancel();
        }
    }

    // Named inner Runnable classes to avoid anonymous classes that d8 can't handle
    private class SetStepRunnable implements Runnable {
        private final int step;
        private final int dotColor;
        private final int textColor;

        SetStepRunnable(int step, int dotColor, int textColor) {
            this.step = step;
            this.dotColor = dotColor;
            this.textColor = textColor;
        }

        @Override
        public void run() {
            // Mark previous steps as OK
            if (step >= 2) {
                step1Dot.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.status_ok)));
            }
            if (step >= 3) {
                step2Dot.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.status_ok)));
            }

            View dot;
            TextView text;
            if (step == 1) { dot = step1Dot; text = step1Text; }
            else if (step == 2) { dot = step2Dot; text = step2Text; }
            else { dot = step3Dot; text = step3Text; }

            dot.setBackgroundTintList(ColorStateList.valueOf(getColor(dotColor)));
            text.setTextColor(getColor(textColor));
        }
    }

    private class ServersReadyRunnable implements Runnable {
        @Override
        public void run() {
            step3Dot.setBackgroundTintList(
                ColorStateList.valueOf(getColor(R.color.status_ok)));
            step3Text.setText(R.string.servers_ready);
            progressBar.setVisibility(View.GONE);

            Intent intent = new Intent(LaunchActivity.this, WebViewActivity.class);
            intent.putExtra("url", "http://127.0.0.1:" + port);
            intent.putExtra("app_name", appName);
            intent.putExtra("app_port", port);
            startActivity(intent);
            finish();
        }
    }

    private class ErrorRunnable implements Runnable {
        private final String message;

        ErrorRunnable(String message) {
            this.message = message;
        }

        @Override
        public void run() {
            progressBar.setVisibility(View.GONE);
            errorText.setVisibility(View.VISIBLE);
            errorText.setText(message);
            errorButtons.setVisibility(View.VISIBLE);
        }
    }

    private class LogRunnable implements Runnable {
        private final String message;

        LogRunnable(String message) {
            this.message = message;
        }

        @Override
        public void run() {
            logBuffer.append(message).append('\n');
            logText.setText(logBuffer.toString());
            logScroll.post(new ScrollDownRunnable());
        }
    }

    private class ScrollDownRunnable implements Runnable {
        @Override
        public void run() {
            logScroll.fullScroll(View.FOCUS_DOWN);
        }
    }
}
