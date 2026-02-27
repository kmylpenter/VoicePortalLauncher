package com.voiceportal.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.ArrayList;

public class WebViewActivity extends Activity {
    private static final String TAG = "VPWebView";
    private static final int MIC_PERMISSION_CODE = 1;

    private FrameLayout webviewContainer;
    private HorizontalScrollView tabStrip;
    private LinearLayout tabStripContent;

    private ArrayList<TabInfo> tabs;
    private int activeTabIndex;
    private PermissionRequest pendingPermissionRequest;
    private boolean micPermissionGranted;
    private boolean desktopMode = false;
    private boolean kioskMode = false;

    private static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** Tab data model */
    private static class TabInfo {
        final String id;
        final String name;
        final int port;
        final String url;
        final WebView webView;

        TabInfo(String id, String name, int port, String url, WebView webView) {
            this.id = id;
            this.name = name;
            this.port = port;
            this.url = url;
            this.webView = webView;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        WebView.setWebContentsDebuggingEnabled(true);
        initAudioManager();

        webviewContainer = findViewById(R.id.webview_container);
        tabStrip = findViewById(R.id.tab_strip);
        tabStripContent = findViewById(R.id.tab_strip_content);

        tabs = new ArrayList<TabInfo>();
        activeTabIndex = -1;

        // Apply settings
        if (SettingsActivity.getHideTabBar(this)) {
            tabStrip.setVisibility(View.GONE);
        }

        kioskMode = SettingsActivity.getKioskMode(this);
        if (kioskMode) {
            startLockTask();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            enterImmersiveMode();
        }

        micPermissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        if (!micPermissionGranted) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_CODE);
        } else {
            addTabFromIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        addTabFromIntent(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Mic permission GRANTED by user");
                micPermissionGranted = true;
                if (pendingPermissionRequest != null) {
                    pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                    pendingPermissionRequest = null;
                }
            } else {
                Log.d(TAG, "Mic permission DENIED by user");
                micPermissionGranted = false;
                if (pendingPermissionRequest != null) {
                    pendingPermissionRequest.deny();
                    pendingPermissionRequest = null;
                }
            }
            if (tabs.isEmpty()) {
                addTabFromIntent(getIntent());
            }
        }
    }

    /** Pre-initialize Android AudioManager to warm up audio subsystem */
    private void initAudioManager() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                int mode = audioManager.getMode();
                Log.d(TAG, "AudioManager mode: " + mode);
            }
        } catch (Exception e) {
            Log.w(TAG, "AudioManager init failed: " + e.getMessage());
        }
    }

    private void addTabFromIntent(Intent intent) {
        String url = intent.getStringExtra("url");
        if (url == null) return;
        String name = intent.getStringExtra("app_name");
        int port = intent.getIntExtra("app_port", 0);
        if (name == null || name.isEmpty()) {
            name = "Tab " + (tabs.size() + 1);
        }
        addTab(name, url, port);
    }

    private void addTab(String name, String url, int port) {
        // Check if a tab with this URL already exists - switch to it instead
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).url.equals(url)) {
                switchToTab(i);
                return;
            }
        }

        WebView webView = new WebView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        webView.setLayoutParams(lp);
        setupWebView(webView);
        webviewContainer.addView(webView);

        String id = "tab_" + System.currentTimeMillis();
        TabInfo tab = new TabInfo(id, name, port, url, webView);
        tabs.add(tab);

        int newIndex = tabs.size() - 1;
        switchToTab(newIndex);

        Log.d(TAG, "Loading URL in tab '" + name + "': " + url);
        webView.loadUrl(url);

        updateTabStrip();
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        if (activeTabIndex >= 0 && activeTabIndex < tabs.size()) {
            tabs.get(activeTabIndex).webView.setVisibility(View.GONE);
        }

        activeTabIndex = index;
        tabs.get(activeTabIndex).webView.setVisibility(View.VISIBLE);

        updateTabStrip();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        TabInfo tab = tabs.get(index);
        Log.d(TAG, "Closing tab: " + tab.name);

        webviewContainer.removeView(tab.webView);
        tab.webView.destroy();
        tabs.remove(index);

        if (tabs.isEmpty()) {
            finish();
            return;
        }

        if (index == activeTabIndex) {
            int newIndex = index >= tabs.size() ? tabs.size() - 1 : index;
            activeTabIndex = -1;
            switchToTab(newIndex);
        } else if (index < activeTabIndex) {
            activeTabIndex--;
        }

        updateTabStrip();
    }

    private void updateTabStrip() {
        tabStripContent.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < tabs.size(); i++) {
            TabInfo tab = tabs.get(i);
            View chipView = inflater.inflate(R.layout.item_tab_chip, tabStripContent, false);

            TextView nameView = chipView.findViewById(R.id.tab_name);
            TextView portBadge = chipView.findViewById(R.id.tab_port_badge);
            View indicator = chipView.findViewById(R.id.tab_active_indicator);
            TextView closeBtn = chipView.findViewById(R.id.tab_close_btn);

            nameView.setText(tab.name);
            if (tab.port > 0) {
                portBadge.setText(String.valueOf(tab.port));
                portBadge.setVisibility(View.VISIBLE);
            } else {
                portBadge.setVisibility(View.GONE);
            }

            boolean isActive = (i == activeTabIndex);
            if (isActive) {
                chipView.setBackgroundResource(R.drawable.tab_chip_active_bg);
                indicator.setVisibility(View.VISIBLE);
                closeBtn.setVisibility(View.VISIBLE);
                closeBtn.setOnClickListener(new TabCloseClickListener(i));
            } else {
                chipView.setBackgroundResource(R.drawable.tab_chip_bg);
                indicator.setVisibility(View.GONE);
                closeBtn.setVisibility(View.GONE);
            }

            chipView.setOnClickListener(new TabClickListener(i));
            chipView.setOnLongClickListener(new TabLongClickListener(i));

            tabStripContent.addView(chipView);
        }

        // Add "+" button at the end
        TextView addBtn = new TextView(this);
        addBtn.setText("+");
        addBtn.setTextColor(getColor(R.color.accent_light));
        addBtn.setTextSize(20);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setTypeface(null, Typeface.BOLD);

        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(dp(36), dp(34));
        addLp.setMargins(dp(4), dp(4), dp(4), dp(4));
        addBtn.setLayoutParams(addLp);
        addBtn.setBackgroundResource(R.drawable.tab_chip_bg);
        addBtn.setOnClickListener(new AddTabClickListener());

        tabStripContent.addView(addBtn);

        // Desktop/mobile toggle button
        TextView modeBtn = new TextView(this);
        modeBtn.setText(desktopMode ? "\uD83D\uDDA5" : "\uD83D\uDCF1");
        modeBtn.setTextSize(16);
        modeBtn.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(dp(36), dp(34));
        modeLp.setMargins(dp(2), dp(4), dp(4), dp(4));
        modeBtn.setLayoutParams(modeLp);
        modeBtn.setBackgroundResource(desktopMode
                ? R.drawable.tab_chip_active_bg : R.drawable.tab_chip_bg);
        modeBtn.setOnClickListener(new ToggleDesktopClickListener());

        tabStripContent.addView(modeBtn);
    }

    private void enterImmersiveMode() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && kioskMode) {
            enterImmersiveMode();
        }
    }

    private void goToMainActivity() {
        Intent homeIntent = new Intent(this, MainActivity.class);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(homeIntent);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private void setupWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // Apply current desktop/mobile mode
        applyViewportMode(webView);

        webView.setWebViewClient(new VPWebViewClient());
        webView.setWebChromeClient(new VPWebChromeClient());
    }

    @Override
    public void onBackPressed() {
        if (kioskMode) {
            // In kiosk mode, only allow in-page back navigation
            if (activeTabIndex >= 0 && activeTabIndex < tabs.size()) {
                WebView active = tabs.get(activeTabIndex).webView;
                if (active.canGoBack()) {
                    active.goBack();
                }
            }
            return;
        }
        if (activeTabIndex >= 0 && activeTabIndex < tabs.size()) {
            WebView active = tabs.get(activeTabIndex).webView;
            if (active.canGoBack()) {
                active.goBack();
                return;
            }
            if (tabs.size() > 1) {
                closeTab(activeTabIndex);
                return;
            }
        }
        // Don't finish - go back to MainActivity keeping tabs alive
        goToMainActivity();
    }

    @Override
    protected void onDestroy() {
        for (TabInfo tab : tabs) {
            tab.webView.destroy();
        }
        tabs.clear();
        super.onDestroy();
    }

    // --- Named inner classes for d8 compatibility ---

    private class TabClickListener implements View.OnClickListener {
        private final int index;
        TabClickListener(int index) { this.index = index; }
        @Override
        public void onClick(View v) {
            switchToTab(index);
        }
    }

    private class TabLongClickListener implements View.OnLongClickListener {
        private final int index;
        TabLongClickListener(int index) { this.index = index; }
        @Override
        public boolean onLongClick(View v) {
            closeTab(index);
            return true;
        }
    }

    private class TabCloseClickListener implements View.OnClickListener {
        private final int index;
        TabCloseClickListener(int index) { this.index = index; }
        @Override
        public void onClick(View v) {
            closeTab(index);
        }
    }

    private class AddTabClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            goToMainActivity();
        }
    }

    private class ToggleDesktopClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            toggleDesktopMode();
        }
    }

    private void toggleDesktopMode() {
        desktopMode = !desktopMode;
        Log.d(TAG, "Desktop mode: " + desktopMode);

        // Apply WebView settings to all tabs
        for (TabInfo tab : tabs) {
            applyViewportMode(tab.webView);
        }

        // Inject viewport override on active tab immediately, then reload
        if (activeTabIndex >= 0 && activeTabIndex < tabs.size()) {
            WebView active = tabs.get(activeTabIndex).webView;
            if (desktopMode) {
                active.evaluateJavascript(
                    "(function(){var m=document.querySelector('meta[name=viewport]');" +
                    "if(m)m.setAttribute('content','width=1280')})()", null);
            } else {
                active.evaluateJavascript(
                    "(function(){var m=document.querySelector('meta[name=viewport]');" +
                    "if(m)m.setAttribute('content','width=device-width,initial-scale=1.0,user-scalable=no')})()", null);
            }
            active.reload();
        }

        updateTabStrip();
    }

    private void applyViewportMode(WebView webView) {
        WebSettings settings = webView.getSettings();
        if (desktopMode) {
            settings.setUserAgentString(UA_DESKTOP);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
        } else {
            settings.setUserAgentString(null); // reset to default
            settings.setUseWideViewPort(false);
            settings.setLoadWithOverviewMode(false);
        }
    }

    /** WebViewClient that pre-warms audio after page loads and handles renderer crashes */
    private class VPWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Log.d(TAG, "Page loaded: " + url);

            // Override viewport meta tag for desktop mode
            if (desktopMode) {
                view.evaluateJavascript(
                    "(function(){" +
                    "var m=document.querySelector('meta[name=viewport]');" +
                    "if(m){m.setAttribute('content','width=1280')}" +
                    "else{m=document.createElement('meta');m.name='viewport';" +
                    "m.content='width=1280';document.head.appendChild(m)}" +
                    "console.log('[VP] Desktop viewport applied')" +
                    "})()",
                    null
                );
            }

            // Pre-warm audio subsystem
            view.evaluateJavascript(
                "(function(){" +
                "if(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia){" +
                "navigator.mediaDevices.getUserMedia({audio:true})" +
                ".then(function(s){s.getTracks().forEach(function(t){t.stop()});console.log('[VP] Audio pre-warm OK')})" +
                ".catch(function(e){console.log('[VP] Audio pre-warm: '+e.message)})" +
                "}else{console.log('[VP] No mediaDevices')}" +
                "})()",
                null
            );
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.e(TAG, "Renderer process gone! crashed=" + detail.didCrash()
                + " priority=" + detail.rendererPriorityAtExit());

            for (int i = 0; i < tabs.size(); i++) {
                if (tabs.get(i).webView == view) {
                    webviewContainer.removeView(view);
                    view.destroy();
                    String crashedUrl = tabs.get(i).url;
                    String crashedName = tabs.get(i).name;
                    int crashedPort = tabs.get(i).port;
                    tabs.remove(i);

                    if (tabs.isEmpty()) {
                        activeTabIndex = -1;
                        addTab(crashedName, crashedUrl, crashedPort);
                    } else {
                        if (i == activeTabIndex) {
                            int newIndex = i >= tabs.size() ? tabs.size() - 1 : i;
                            activeTabIndex = -1;
                            switchToTab(newIndex);
                        } else if (i < activeTabIndex) {
                            activeTabIndex--;
                        }
                        updateTabStrip();
                    }
                    break;
                }
            }
            return true;
        }
    }

    /** WebChromeClient with mic permission handling */
    private class VPWebChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            Log.d(TAG, "WebView permission request: " + java.util.Arrays.toString(request.getResources()));
            runOnUiThread(new GrantPermissionRunnable(request));
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            Log.d(TAG, "WebView permission request canceled");
            if (pendingPermissionRequest == request) {
                pendingPermissionRequest = null;
            }
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage msg) {
            Log.d(TAG, "[JS:" + msg.messageLevel() + "] " +
                msg.sourceId() + ":" + msg.lineNumber() + " " + msg.message());
            return true;
        }
    }

    /** Named Runnable for granting WebView permission (d8 compatible) */
    private class GrantPermissionRunnable implements Runnable {
        private final PermissionRequest request;

        GrantPermissionRunnable(PermissionRequest request) {
            this.request = request;
        }

        @Override
        public void run() {
            try {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Granting WebView permission (Android mic OK)");
                    request.grant(request.getResources());
                } else {
                    Log.d(TAG, "Need Android mic permission first, requesting...");
                    pendingPermissionRequest = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_CODE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error granting permission: " + e.getMessage());
                try {
                    request.grant(request.getResources());
                } catch (Exception e2) {
                    Log.e(TAG, "Fallback grant also failed: " + e2.getMessage());
                }
            }
        }
    }
}
