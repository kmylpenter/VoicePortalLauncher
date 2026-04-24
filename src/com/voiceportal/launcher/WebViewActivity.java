package com.voiceportal.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.ParcelUuid;
import android.webkit.ValueCallback;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
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
    private static final int FILE_CHOOSER_CODE = 2;
    private static final int BLE_PERMISSION_CODE = 3;

    private LeicaBleBridge leicaBridge;

    private FrameLayout webviewContainer;
    private HorizontalScrollView tabStrip;
    private LinearLayout tabStripContent;

    private ArrayList<TabInfo> tabs;
    private int activeTabIndex;
    private PermissionRequest pendingPermissionRequest;
    private ValueCallback<Uri[]> fileUploadCallback;
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
            enterImmersiveMode();
        }

        micPermissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        if (!micPermissionGranted) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_CODE);
        } else {
            addTabFromIntent(getIntent());
        }

        // Initialize the Leica BLE bridge (doesn't connect yet, just sets up adapter handle)
        leicaBridge = new LeicaBleBridge();

        // Cold-start with ACTION_SEND (DISTO Plan → share → VPL killed and relaunched)
        // defer handling until after WebView is likely loaded
        final Intent startIntent = getIntent();
        if (startIntent != null && Intent.ACTION_SEND.equals(startIntent.getAction())) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { handleShareIntent(startIntent); }
            }, 2500);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Handle incoming shared files (e.g. DXF from DISTO Plan App).
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            handleShareIntent(intent);
            return;
        }
        addTabFromIntent(intent);
    }

    /**
     * Reads a shared file from Intent.EXTRA_STREAM, base64-encodes it, and
     * dispatches it to the active WebView via window.onDxfImported(base64, filename).
     * Used for the DISTO Plan → DistoKML hybrid flow.
     */
    private void handleShareIntent(Intent intent) {
        android.net.Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            Log.w(TAG, "ACTION_SEND without EXTRA_STREAM");
            return;
        }
        try {
            // Read bytes from content URI
            java.io.InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) throw new RuntimeException("openInputStream returned null");
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            is.close();
            byte[] bytes = baos.toByteArray();
            String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);

            // Resolve display name
            String filename = "shared.dxf";
            try {
                android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && cursor.moveToFirst()) {
                        String name = cursor.getString(idx);
                        if (name != null && !name.isEmpty()) filename = name;
                    }
                    cursor.close();
                }
            } catch (Exception e) { Log.w(TAG, "display name query failed: " + e.getMessage()); }

            // Dispatch to the active tab's WebView (if any)
            final WebView target;
            if (activeTabIndex >= 0 && activeTabIndex < tabs.size()) {
                target = tabs.get(activeTabIndex).webView;
            } else {
                target = null;
            }
            if (target == null) {
                Log.w(TAG, "handleShareIntent: no active WebView to dispatch to");
                return;
            }
            // Escape filename for JS string literal
            final String safeName = filename.replace("\\", "\\\\").replace("'", "\\'");
            final String jsCall = "(function(){ if (window.onDxfImported) window.onDxfImported('"
                + base64 + "', '" + safeName + "'); else console.warn('window.onDxfImported not defined'); })();";
            Log.d(TAG, "handleShareIntent: dispatching " + bytes.length + " bytes (" + filename + ")");
            target.post(new Runnable() {
                @Override public void run() {
                    target.evaluateJavascript(jsCall, null);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "handleShareIntent failed", e);
            android.widget.Toast.makeText(this, "Błąd importu DXF: " + e.getMessage(),
                android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLE_PERMISSION_CODE) {
            // Notify the WebView which permissions were granted/denied so DistoKML
            // can react (retry scan, show error, etc.)
            StringBuilder sb = new StringBuilder("{\"granted\":[");
            StringBuilder denied = new StringBuilder("],\"denied\":[");
            boolean firstG = true, firstD = true;
            for (int i = 0; i < permissions.length; i++) {
                boolean ok = (grantResults.length > i && grantResults[i] == PackageManager.PERMISSION_GRANTED);
                if (ok) {
                    if (!firstG) sb.append(",");
                    sb.append("\"").append(permissions[i]).append("\"");
                    firstG = false;
                } else {
                    if (!firstD) denied.append(",");
                    denied.append("\"").append(permissions[i]).append("\"");
                    firstD = false;
                }
            }
            sb.append(denied).append("]}");
            final String json = sb.toString();
            if (activeTabIndex >= 0 && activeTabIndex < tabs.size()) {
                final WebView target = tabs.get(activeTabIndex).webView;
                if (target != null) {
                    target.post(new Runnable() {
                        @Override public void run() {
                            target.evaluateJavascript(
                                "(function(){ if(window.onLeicaEvent) window.onLeicaEvent('permissions', " + json + "); })();",
                                null);
                        }
                    });
                }
            }
            return;
        }
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_CODE) {
            if (fileUploadCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    android.content.ClipData clipData = data.getClipData();
                    if (clipData != null) {
                        int count = clipData.getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = clipData.getItemAt(i).getUri();
                        }
                        Log.d(TAG, "File chooser: " + count + " files via ClipData");
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                        Log.d(TAG, "File chooser: 1 file via getData: " + results[0]);
                    } else {
                        String dataString = data.getDataString();
                        if (dataString != null) {
                            results = new Uri[]{Uri.parse(dataString)};
                            Log.d(TAG, "File chooser: 1 file via getDataString fallback");
                        } else {
                            Log.w(TAG, "File chooser: RESULT_OK but no ClipData/Data/DataString");
                        }
                    }
                } else {
                    Log.d(TAG, "File chooser: cancelled or no data (resultCode=" + resultCode + ")");
                }
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
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

    private void showExitKioskDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        builder.setTitle("Exit to main screen?");
        builder.setPositiveButton("Exit", new ExitKioskClickListener());
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private class ExitKioskClickListener implements DialogInterface.OnClickListener {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            try { stopLockTask(); } catch (Exception e) { /* ignore */ }
            goToMainActivity();
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
        webView.setDownloadListener(new VPDownloadListener());
        webView.addJavascriptInterface(new VPNativeBridge(this), "VPNative");
    }

    @Override
    public void onBackPressed() {
        if (kioskMode) {
            if (activeTabIndex >= 0 && activeTabIndex < tabs.size()) {
                WebView active = tabs.get(activeTabIndex).webView;
                if (active.canGoBack()) {
                    active.goBack();
                    return;
                }
            }
            // No more in-page history - offer exit to main screen
            showExitKioskDialog();
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
        public boolean onShowFileChooser(WebView webView,
                ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            Log.d(TAG, "onShowFileChooser triggered");
            if (fileUploadCallback != null) {
                fileUploadCallback.onReceiveValue(null);
            }
            fileUploadCallback = callback;
            Intent intent = params.createIntent();
            try {
                startActivityForResult(intent, FILE_CHOOSER_CODE);
            } catch (Exception e) {
                Log.e(TAG, "File chooser failed, trying fallback", e);
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("image/*");
                try {
                    startActivityForResult(fallback, FILE_CHOOSER_CODE);
                } catch (Exception e2) {
                    Log.e(TAG, "Fallback file chooser also failed", e2);
                    fileUploadCallback.onReceiveValue(null);
                    fileUploadCallback = null;
                }
            }
            return true;
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage msg) {
            Log.d(TAG, "[JS:" + msg.messageLevel() + "] " +
                msg.sourceId() + ":" + msg.lineNumber() + " " + msg.message());
            return true;
        }
    }

    /** DownloadListener: handles real HTTP(S) downloads via Android DownloadManager.
     *  Blob and data URLs do NOT fire this — web pages should call VPNative.saveFile()
     *  directly for those. */
    private class VPDownloadListener implements android.webkit.DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimetype, long contentLength) {
            try {
                if (url == null) return;
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    Log.w(TAG, "Download refused for " + url.substring(0, Math.min(24, url.length()))
                        + "… — web page should call VPNative.saveFile() instead.");
                    runOnUiThread(new ToastRunnable(
                        "Użyj window.VPNative.saveFile() dla blob URLs",
                        android.widget.Toast.LENGTH_LONG));
                    return;
                }
                String filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
                android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(android.net.Uri.parse(url));
                req.setMimeType(mimetype != null ? mimetype : "application/octet-stream");
                req.addRequestHeader("User-Agent", userAgent);
                req.setDescription("VoicePortal download");
                req.setTitle(filename);
                req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, filename);
                android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) throw new RuntimeException("DownloadManager unavailable");
                dm.enqueue(req);
                runOnUiThread(new ToastRunnable("Pobieranie: " + filename, android.widget.Toast.LENGTH_SHORT));
            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + e.getMessage(), e);
                runOnUiThread(new ToastRunnable(
                    "Błąd pobierania: " + e.getMessage(), android.widget.Toast.LENGTH_LONG));
            }
        }
    }

    /** JavaScript bridge: web pages call window.VPNative.saveFile(base64, filename, mime)
     *  to write blob-generated files directly to /storage/emulated/0/Download via MediaStore.
     *  Returns "Downloads/<filename>" on success or "ERROR: <reason>" on failure. */
    public class VPNativeBridge {
        private final android.content.Context ctx;

        public VPNativeBridge(android.content.Context ctx) {
            this.ctx = ctx;
        }

        // 3-arg version kept for backward compatibility (delegates with null subPath)
        @android.webkit.JavascriptInterface
        public String saveFile(String base64Content, String filename, String mimeType) {
            return saveFile(base64Content, filename, mimeType, null);
        }

        @android.webkit.JavascriptInterface
        public String saveFile(String base64Content, String filename, String mimeType, String subPath) {
            try {
                if (filename == null || filename.isEmpty()) return "ERROR: filename required";
                if (base64Content == null) return "ERROR: content null";
                if (mimeType == null || mimeType.isEmpty()) mimeType = "application/octet-stream";
                byte[] data = android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT);
                return writeToDownloads(filename, mimeType, subPath, data);
            } catch (Exception e) {
                Log.e(TAG, "VPNative.saveFile failed", e);
                return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        // 3-arg version kept for backward compatibility
        @android.webkit.JavascriptInterface
        public String saveTextFile(String text, String filename, String mimeType) {
            return saveTextFile(text, filename, mimeType, null);
        }

        @android.webkit.JavascriptInterface
        public String saveTextFile(String text, String filename, String mimeType, String subPath) {
            try {
                if (filename == null || filename.isEmpty()) return "ERROR: filename required";
                if (text == null) return "ERROR: text null";
                if (mimeType == null || mimeType.isEmpty()) mimeType = "text/plain";
                byte[] data = text.getBytes("UTF-8");
                return writeToDownloads(filename, mimeType, subPath, data);
            } catch (Exception e) {
                Log.e(TAG, "VPNative.saveTextFile failed", e);
                return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        @android.webkit.JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        /** List files + subdirectories in a subPath under /Download/.
         *  Returns JSON array: [{"name":"x","size":123,"mtime":1710000000,"isDir":false}, ...]
         *  On error returns "ERROR: ..." string. JS distinguishes by startsWith("[").
         *  Used by DistoKML Organizer to auto-discover session folders at startup. */
        @android.webkit.JavascriptInterface
        public String listFiles(String subPath) {
            try {
                java.io.File downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
                java.io.File dir = (subPath == null || subPath.isEmpty())
                    ? downloads
                    : new java.io.File(downloads, subPath);
                if (!dir.exists()) return "ERROR: Not found: " + dir.getAbsolutePath();
                if (!dir.isDirectory()) return "ERROR: Not a directory: " + dir.getAbsolutePath();
                java.io.File[] files = dir.listFiles();
                if (files == null) return "ERROR: Cannot list (permission?)";
                org.json.JSONArray arr = new org.json.JSONArray();
                for (java.io.File f : files) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("name", f.getName());
                    o.put("size", f.length());
                    o.put("mtime", f.lastModified());
                    o.put("isDir", f.isDirectory());
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) {
                Log.e(TAG, "VPNative.listFiles failed", e);
                return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        /** Read a UTF-8 text file from /Download/<subPath>/<filename>.
         *  Returns JSON {"content": "<full text>"} on success, "ERROR: ..." otherwise.
         *  JS distinguishes by startsWith("{"). Max 10 MB per call. */
        @android.webkit.JavascriptInterface
        public String readTextFile(String subPath, String filename) {
            try {
                if (filename == null || filename.isEmpty()) return "ERROR: filename required";
                java.io.File downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
                java.io.File dir = (subPath == null || subPath.isEmpty())
                    ? downloads
                    : new java.io.File(downloads, subPath);
                java.io.File file = new java.io.File(dir, filename);
                if (!file.exists()) return "ERROR: File not found: " + file.getAbsolutePath();
                if (!file.isFile()) return "ERROR: Not a file";
                if (file.length() > 10 * 1024 * 1024) return "ERROR: File too large (>10MB)";
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = fis.read(chunk)) > 0) buf.write(chunk, 0, n);
                fis.close();
                org.json.JSONObject result = new org.json.JSONObject();
                result.put("content", new String(buf.toByteArray(), "UTF-8"));
                return result.toString();
            } catch (Exception e) {
                Log.e(TAG, "VPNative.readTextFile failed", e);
                return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        // ---- Leica DISTO direct BLE bridge ----
        // DistoKML calls these to scan for, connect to, and receive measurements
        // from a Leica DISTO X3 (and eventually DST360). Results arrive as JS events
        // via window.onLeicaEvent(type, data).

        @android.webkit.JavascriptInterface
        public String leicaIsSupported() {
            return leicaBridge != null ? leicaBridge.isSupported() : "NO_BRIDGE";
        }

        @android.webkit.JavascriptInterface
        public String leicaGetState() {
            return leicaBridge != null ? leicaBridge.getState() : "no-bridge";
        }

        @android.webkit.JavascriptInterface
        public String leicaRequestPermissions() {
            // JS bridge runs on a background thread → post to UI thread because
            // requestPermissions() must be called from the main thread.
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    String[] perms;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        perms = new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        };
                    } else {
                        perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
                    }
                    requestPermissions(perms, BLE_PERMISSION_CODE);
                }
            });
            return "REQUESTED";
        }

        @android.webkit.JavascriptInterface
        public String leicaStartScan() {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.startScan();
        }

        @android.webkit.JavascriptInterface
        public String leicaListBonded() {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.listBondedDevices();
        }

        @android.webkit.JavascriptInterface
        public String leicaStopScan() {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.stopScan();
        }

        @android.webkit.JavascriptInterface
        public String leicaConnect(String address) {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.connect(address);
        }

        @android.webkit.JavascriptInterface
        public String leicaDisconnect() {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.disconnect();
        }

        @android.webkit.JavascriptInterface
        public String leicaTriggerMeasure() {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.triggerMeasure();
        }

        @android.webkit.JavascriptInterface
        public String leicaWriteCommand(String hexBytes) {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.writeCommand(hexBytes);
        }

        @android.webkit.JavascriptInterface
        public String leicaWriteAscii(String text) {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.writeAsciiCommand(text);
        }

        @android.webkit.JavascriptInterface
        public String leicaProbe() {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.isReallyConnected() ? "OK" : "STALE";
        }

        @android.webkit.JavascriptInterface
        public String leicaForceReset() {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.forceReset();
        }

        @android.webkit.JavascriptInterface
        public String leicaReadAllChars() {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.readAllChars();
        }

        public String leicaReadBattery() {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.readBatteryLevel();
        }

        public String leicaReadDeviceInfo() {
            if (leicaBridge == null) return "NO_BRIDGE";
            return leicaBridge.readDeviceInfo();
        }

        // Sanitize a user-provided subPath to make it safe for MediaStore RELATIVE_PATH.
        // Rules: strip leading/trailing slashes, replace Windows-invalid chars, drop ..
        // segments, clamp total length. Returns empty string if the result is empty.
        private String sanitizeSubPath(String sub) {
            if (sub == null) return "";
            String cleaned = sub.trim();
            // Replace invalid filesystem chars
            cleaned = cleaned.replaceAll("[:*?\"<>|]", "_");
            // Strip leading/trailing slashes + backslashes
            cleaned = cleaned.replaceAll("^[/\\\\]+", "").replaceAll("[/\\\\]+$", "");
            // Normalize backslashes to forward slashes
            cleaned = cleaned.replace('\\', '/');
            // Drop any ".." segments for safety
            String[] parts = cleaned.split("/");
            StringBuilder out = new StringBuilder();
            for (String p : parts) {
                String trimmed = p.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.equals("..") || trimmed.equals(".")) continue;
                if (out.length() > 0) out.append('/');
                out.append(trimmed);
            }
            String result = out.toString();
            // Clamp length to avoid MediaStore path errors
            if (result.length() > 120) result = result.substring(0, 120);
            return result;
        }

        private String writeToDownloads(String filename, String mimeType, String subPath, byte[] data) throws Exception {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType);

            // MediaStore RELATIVE_PATH expects paths rooted at Download/ etc.
            // For nested folders, append the sanitized sub-path.
            String relativePath = android.os.Environment.DIRECTORY_DOWNLOADS;
            String cleanSub = sanitizeSubPath(subPath);
            if (!cleanSub.isEmpty()) {
                relativePath = relativePath + "/" + cleanSub;
            }
            values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, relativePath);
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);

            android.content.ContentResolver resolver = ctx.getContentResolver();
            android.net.Uri collection = android.provider.MediaStore.Downloads.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
            android.net.Uri item = resolver.insert(collection, values);
            if (item == null) throw new RuntimeException("MediaStore insert returned null");

            java.io.OutputStream os = null;
            try {
                os = resolver.openOutputStream(item);
                if (os == null) throw new RuntimeException("openOutputStream returned null");
                os.write(data);
                os.flush();
            } finally {
                if (os != null) try { os.close(); } catch (Exception ignore) {}
            }

            android.content.ContentValues clear = new android.content.ContentValues();
            clear.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(item, clear, null, null);

            Log.d(TAG, "VPNative.saveFile wrote " + data.length + " bytes to " + relativePath + "/" + filename);
            return relativePath + "/" + filename;
        }
    }

    /** Small runnable for posting toasts from background threads */
    private class ToastRunnable implements Runnable {
        private final String text;
        private final int duration;
        ToastRunnable(String text, int duration) { this.text = text; this.duration = duration; }
        @Override public void run() {
            android.widget.Toast.makeText(WebViewActivity.this, text, duration).show();
        }
    }

    /**
     * LeicaBleBridge — direct BLE connection to Leica DISTO X3 + DST360.
     * Phase 1: scan by service UUID, connect, subscribe to distance characteristic,
     * emit JS events. UUIDs from community research (legal under EU interop exception).
     *
     * Known characteristics (phase 1):
     *   service  3ab10100-f831-4395-b29d-570977d5bf94
     *   distance 3ab10101-f831-4395-b29d-570977d5bf94  (float32 LE, 4 bytes, notification)
     *   unit     3ab10102-f831-4395-b29d-570977d5bf94  (u16)
     * Unknown (phase 2 via sniffing): DST360 H/V angle, command/trigger, face, battery.
     *
     * JS event types (dispatched via window.onLeicaEvent(type, data)):
     *   state       — {state: "idle|scanning|connecting|connected|error"}
     *   deviceFound — {address, name, rssi}
     *   connected   — {}
     *   disconnected— {}
     *   measurement — {distance, rawHex, length}
     *   error       — {stage, msg|code}
     */
    private class LeicaBleBridge {
        static final String BTAG = "LeicaBle";
        final UUID SVC_UUID  = UUID.fromString("3ab10100-f831-4395-b29d-570977d5bf94");
        final UUID DIST_UUID = UUID.fromString("3ab10101-f831-4395-b29d-570977d5bf94");
        final UUID UNIT_UUID = UUID.fromString("3ab10102-f831-4395-b29d-570977d5bf94");
        final UUID CMD_UUID  = UUID.fromString("3ab10120-f831-4395-b29d-570977d5bf94"); // write-only command char
        final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
        // Standard BLE Battery Service (not Leica-specific)
        final UUID BATTERY_SVC_UUID  = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
        final UUID BATTERY_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
        // Standard BLE Device Information Service
        final UUID DEVINFO_SVC_UUID  = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");

        private BluetoothAdapter adapter;
        private BluetoothLeScanner scanner;
        private BluetoothGatt gatt;
        private String state = "idle";
        private boolean scanning = false;
        // Queue of characteristics whose CCCD descriptor still needs to be written
        // to enable notifications. Android only allows one pending GATT write at a
        // time, so we write them sequentially via onDescriptorWrite callbacks.
        private final java.util.ArrayDeque<BluetoothGattCharacteristic> pendingNotifyQueue =
            new java.util.ArrayDeque<>();

        private void writeNextNotifyDescriptor(BluetoothGatt g) {
            if (pendingNotifyQueue.isEmpty()) {
                // All writes done — we're ready to receive notifications.
                setState("connected");
                dispatchEvent("connected", "{}");
                return;
            }
            BluetoothGattCharacteristic ch = pendingNotifyQueue.poll();
            try {
                if (!g.setCharacteristicNotification(ch, true)) {
                    Log.w(BTAG, "setCharacteristicNotification false for " + ch.getUuid());
                    // Try next anyway
                    writeNextNotifyDescriptor(g);
                    return;
                }
                BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD_UUID);
                if (cccd == null) {
                    Log.w(BTAG, "no CCCD for " + ch.getUuid());
                    writeNextNotifyDescriptor(g);
                    return;
                }
                boolean isIndicate =
                    (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                cccd.setValue(isIndicate
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!g.writeDescriptor(cccd)) {
                    Log.w(BTAG, "writeDescriptor returned false for " + ch.getUuid());
                    writeNextNotifyDescriptor(g);
                }
                // Else wait for onDescriptorWrite callback to invoke writeNextNotifyDescriptor
            } catch (SecurityException e) {
                dispatchEvent("error",
                    "{\"stage\":\"notify\",\"msg\":\"" + jsEscape(e.getMessage()) + "\"}");
            }
        }

        LeicaBleBridge() {
            try {
                BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                if (bm != null) adapter = bm.getAdapter();
            } catch (Exception e) {
                Log.e(BTAG, "init failed", e);
            }
        }

        String isSupported() {
            if (adapter == null) return "NO_BLE";
            if (!adapter.isEnabled()) return "BT_DISABLED";
            return "OK";
        }

        String getState() { return state; }

        String startScan() {
            String chk = isSupported();
            if (!"OK".equals(chk)) return chk;
            if (!hasScanPermission()) return "NO_PERMISSION";
            if (scanning) return "ALREADY_SCANNING";
            try {
                scanner = adapter.getBluetoothLeScanner();
                if (scanner == null) return "NO_SCANNER";
                // NO filter — Leica X3 does not advertise its service UUID in
                // advertisement packets (only exposes it after GATT connect).
                // We scan broadly and filter by device name in onScanResult.
                ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build();
                scanner.startScan(null, settings, scanCallback);
                scanning = true;
                setState("scanning");
                Log.d(BTAG, "startScan OK (no filter, matching by name)");
                return "OK";
            } catch (SecurityException e) {
                Log.e(BTAG, "startScan permission denied", e);
                return "NO_PERMISSION";
            } catch (Exception e) {
                Log.e(BTAG, "startScan failed", e);
                return "ERROR: " + e.getMessage();
            }
        }

        // Returns true if the device name looks like a Leica DISTO.
        // Leica typically advertises names like "DISTO X3 ABC123" or "Leica DISTO ..."
        private boolean isLeicaDevice(String name) {
            if (name == null) return false;
            String n = name.toLowerCase(Locale.US);
            return n.contains("disto") || n.contains("leica");
        }

        /**
         * List currently bonded/paired Bluetooth devices that look like Leica.
         * Emits each match as a deviceFound event with bonded=true flag.
         * Critical for the case where DISTO Plan has an active connection and
         * X3 is no longer advertising — we can still see it in the system's
         * bonded devices list and connect directly (after user disconnects
         * DISTO Plan, since X3 only supports one central at a time).
         */
        String listBondedDevices() {
            if (adapter == null) return "NO_BLE";
            if (!hasConnectPermission()) return "NO_PERMISSION";
            try {
                java.util.Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                int matched = 0;
                for (BluetoothDevice d : bonded) {
                    String name = null;
                    try { name = d.getName(); } catch (SecurityException e) {}
                    Log.d(BTAG, "bonded: " + d.getAddress() + " name=" + name);
                    if (!isLeicaDevice(name)) continue;
                    String json = String.format(Locale.US,
                        "{\"address\":\"%s\",\"name\":\"%s\",\"rssi\":0,\"bonded\":true}",
                        jsEscape(d.getAddress()), jsEscape(name));
                    dispatchEvent("deviceFound", json);
                    matched++;
                }
                Log.d(BTAG, "listBondedDevices: found " + matched + " Leica matches");
                return "OK";
            } catch (SecurityException e) {
                return "NO_PERMISSION";
            } catch (Exception e) {
                Log.e(BTAG, "listBondedDevices failed", e);
                return "ERROR: " + e.getMessage();
            }
        }

        String stopScan() {
            if (!scanning) return "NOT_SCANNING";
            try {
                if (scanner != null) scanner.stopScan(scanCallback);
                scanning = false;
                setState("idle");
                return "OK";
            } catch (SecurityException e) {
                return "NO_PERMISSION";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        String connect(String address) {
            if (adapter == null) return "NO_BLE";
            if (!hasConnectPermission()) return "NO_PERMISSION";
            BluetoothDevice device;
            try {
                device = adapter.getRemoteDevice(address);
            } catch (Exception e) {
                return "INVALID_ADDRESS";
            }
            // Clean up any stale connection
            if (gatt != null) {
                try { gatt.disconnect(); gatt.close(); } catch (Exception ignore) {}
                gatt = null;
            }
            // Stop scanning during connect (saves power + avoids contention)
            if (scanning) { try { stopScan(); } catch (Exception ignore) {} }
            setState("connecting");
            try {
                gatt = device.connectGatt(WebViewActivity.this, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
                Log.d(BTAG, "connectGatt() → " + address);
                return "OK";
            } catch (SecurityException e) {
                return "NO_PERMISSION";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        String disconnect() {
            if (gatt == null) return "NOT_CONNECTED";
            try {
                gatt.disconnect();
                return "OK";
            } catch (SecurityException e) {
                return "NO_PERMISSION";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        /**
         * Sanity check — is the GATT connection REALLY alive? A bare
         * `gatt != null` isn't enough: WebView reloads, screen-offs and BT
         * stack hiccups can leave us with a dead handle that looks connected.
         * We check adapter is enabled + services were discovered.
         */
        boolean isReallyConnected() {
            if (gatt == null) return false;
            if (adapter == null || !adapter.isEnabled()) return false;
            try {
                java.util.List<BluetoothGattService> svcs = gatt.getServices();
                if (svcs == null || svcs.isEmpty()) return false;
                // Also confirm the Leica service is still in the list
                for (BluetoothGattService s : svcs) {
                    if (SVC_UUID.equals(s.getUuid())) return true;
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * Diagnostic: queue reads for all R-capable characteristics in the Leica
         * service. Results arrive via onCharacteristicRead → dispatched as
         * "charRead" events with uuid + hex + ascii. Used to inspect what each
         * char currently holds — might reveal protocol version, device state,
         * or unexpected values that hint at the command format.
         */
        private final java.util.ArrayDeque<BluetoothGattCharacteristic> pendingReadQueue =
            new java.util.ArrayDeque<>();

        String readAllChars() {
            if (gatt == null) return "NOT_CONNECTED";
            BluetoothGattService svc = gatt.getService(SVC_UUID);
            if (svc == null) return "NO_SERVICE";
            pendingReadQueue.clear();
            for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    pendingReadQueue.add(ch);
                }
            }
            int n = pendingReadQueue.size();
            Log.d(BTAG, "readAllChars: queued " + n + " reads");
            readNextChar(gatt);
            return "QUEUED:" + n;
        }

        /**
         * Read all characteristics from Device Information Service (0x180A).
         * Includes: Model Number, Serial Number, FW/HW/SW Revision, Manufacturer.
         * All are read-only strings. Results arrive via onCharacteristicRead.
         */
        String readDeviceInfo() {
            if (gatt == null) return "NOT_CONNECTED";
            BluetoothGattService svc = gatt.getService(DEVINFO_SVC_UUID);
            if (svc == null) return "NO_DEVINFO_SERVICE";
            int count = 0;
            for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    pendingReadQueue.add(ch);
                    count++;
                }
            }
            if (count > 0 && pendingReadQueue.size() == count) {
                readNextChar(gatt);
            }
            Log.d(BTAG, "readDeviceInfo: queued " + count + " reads");
            return "QUEUED:" + count;
        }

        /**
         * Read battery level from standard BLE Battery Service (0x180F / 0x2A19).
         * Returns uint8 0-100 as percentage. Result dispatched via onCharacteristicRead
         * as a "measurement" event with isAscii=false, uuid=00002a19.
         */
        String readBatteryLevel() {
            if (gatt == null) return "NOT_CONNECTED";
            BluetoothGattService batSvc = gatt.getService(BATTERY_SVC_UUID);
            if (batSvc == null) return "NO_BATTERY_SERVICE";
            BluetoothGattCharacteristic batChar = batSvc.getCharacteristic(BATTERY_CHAR_UUID);
            if (batChar == null) return "NO_BATTERY_CHAR";
            try {
                // Queue it into the pending read queue so onCharacteristicRead handles it
                pendingReadQueue.add(batChar);
                if (pendingReadQueue.size() == 1) {
                    readNextChar(gatt);  // kick off if queue was empty
                }
                Log.d(BTAG, "readBatteryLevel: queued");
                return "OK";
            } catch (SecurityException e) {
                return "SECURITY:" + e.getMessage();
            }
        }

        private void readNextChar(BluetoothGatt g) {
            if (pendingReadQueue.isEmpty()) return;
            BluetoothGattCharacteristic ch = pendingReadQueue.poll();
            try {
                g.readCharacteristic(ch);
            } catch (SecurityException e) {
                dispatchEvent("error", "{\"stage\":\"read\",\"msg\":\"" + jsEscape(e.getMessage()) + "\"}");
                readNextChar(g);
            }
        }

        /**
         * Nuclear option — tear down scan + GATT connection, clear all queues,
         * reset to idle. Used by auto-heal and user-triggered "Force reset BLE".
         */
        String forceReset() {
            if (scanning) {
                try { if (scanner != null) scanner.stopScan(scanCallback); }
                catch (Exception ignore) {}
                scanning = false;
            }
            if (gatt != null) {
                try { gatt.disconnect(); } catch (Exception ignore) {}
                try { gatt.close(); } catch (Exception ignore) {}
                gatt = null;
            }
            pendingNotifyQueue.clear();
            state = "idle";
            dispatchEvent("state", "{\"state\":\"idle\"}");
            dispatchEvent("disconnected", "{\"reason\":\"forceReset\"}");
            Log.d(BTAG, "forceReset done");
            return "OK";
        }

        /**
         * Write arbitrary bytes to the Leica command characteristic (3ab10120, W).
         * Used to trigger measurements, switch face, rotate DST360 etc.
         */
        String writeCommand(String hexBytes) {
            if (gatt == null) return "NOT_CONNECTED";
            if (hexBytes == null || hexBytes.isEmpty()) return "EMPTY_BYTES";
            byte[] data = parseHex(hexBytes);
            if (data == null) return "INVALID_HEX";
            try {
                BluetoothGattService svc = gatt.getService(SVC_UUID);
                if (svc == null) return "NO_SERVICE";
                BluetoothGattCharacteristic cmdChar = svc.getCharacteristic(CMD_UUID);
                if (cmdChar == null) return "NO_COMMAND_CHAR";
                cmdChar.setValue(data);
                int wt = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                if ((cmdChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    wt = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                }
                cmdChar.setWriteType(wt);
                boolean ok = gatt.writeCharacteristic(cmdChar);
                Log.d(BTAG, "writeCommand bytes=" + hexBytes + " queued=" + ok);
                return ok ? "OK" : "WRITE_FAILED";
            } catch (SecurityException e) {
                return "NO_PERMISSION";
            } catch (Exception e) {
                Log.e(BTAG, "writeCommand failed", e);
                return "ERROR: " + e.getMessage();
            }
        }

        /**
         * Default trigger: writes "@DIST\r\n" — based on the X3 response format
         * "@E_UNKNOWN_CMD\r\n", the protocol appears to be ASCII with @ prefix.
         * If this doesn't work, user can experiment via writeAsciiCommand.
         */
        String triggerMeasure() {
            return writeAsciiCommand("@DIST\r\n");
        }

        /**
         * Writes an ASCII string to the command characteristic. Escapes \r\n etc.
         * User-facing entry point for command experimentation.
         */
        String writeAsciiCommand(String text) {
            if (text == null || text.isEmpty()) return "EMPTY_TEXT";
            byte[] bytes;
            try {
                bytes = text.getBytes("UTF-8");
            } catch (Exception e) {
                return "ENCODING_ERROR";
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return writeCommand(hex.toString());
        }

        private byte[] parseHex(String s) {
            String clean = s.replaceAll("[^0-9a-fA-F]", "");
            if ((clean.length() & 1) != 0) return null;
            byte[] out = new byte[clean.length() / 2];
            for (int i = 0; i < out.length; i++) {
                int hi = Character.digit(clean.charAt(i * 2), 16);
                int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
                if (hi < 0 || lo < 0) return null;
                out[i] = (byte) ((hi << 4) | lo);
            }
            return out;
        }

        private final ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String rawName = null;
                // Try to get the name from both the scan record (advertised)
                // and the device itself (cached after prior connection)
                try {
                    if (result.getScanRecord() != null) {
                        rawName = result.getScanRecord().getDeviceName();
                    }
                } catch (Exception e) {}
                if (rawName == null) {
                    try { rawName = device.getName(); } catch (SecurityException e) {}
                }
                final String displayName = rawName != null ? rawName : "(unknown)";
                final String address = device.getAddress();
                final int rssi = result.getRssi();
                // Log every scan result to logcat so `adb logcat -s LeicaBle` shows
                // exactly what's advertising nearby — critical for debugging match logic
                Log.d(BTAG, "scanResult: " + address + " name=" + displayName + " rssi=" + rssi);
                // Only show matches (Leica/DISTO name) in the UI, otherwise UI gets spammed
                if (!isLeicaDevice(rawName)) return;
                String json = String.format(Locale.US,
                    "{\"address\":\"%s\",\"name\":\"%s\",\"rssi\":%d}",
                    jsEscape(address), jsEscape(displayName), rssi);
                dispatchEvent("deviceFound", json);
            }
            @Override
            public void onScanFailed(int errorCode) {
                Log.e(BTAG, "scan failed code=" + errorCode);
                dispatchEvent("error",
                    String.format(Locale.US, "{\"stage\":\"scan\",\"code\":%d}", errorCode));
                scanning = false;
                setState("error");
            }
        };

        private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                Log.d(BTAG, "onConnectionStateChange status=" + status + " newState=" + newState);
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try { g.discoverServices(); } catch (SecurityException e) {
                        dispatchEvent("error",
                            "{\"stage\":\"discoverServices\",\"msg\":\"" + jsEscape(e.getMessage()) + "\"}");
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    try { g.close(); } catch (Exception ignore) {}
                    gatt = null;
                    setState("idle");
                    dispatchEvent("disconnected", "{}");
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    dispatchEvent("error",
                        String.format(Locale.US, "{\"stage\":\"discover\",\"status\":%d}", status));
                    return;
                }
                // Dump ALL services so the JS log has full visibility
                StringBuilder svcDump = new StringBuilder();
                for (BluetoothGattService s : g.getServices()) {
                    svcDump.append(s.getUuid().toString()).append(" ");
                }
                Log.d(BTAG, "services: " + svcDump.toString());
                dispatchEvent("servicesDumped", "{\"services\":\"" + jsEscape(svcDump.toString()) + "\"}");

                BluetoothGattService svc = g.getService(SVC_UUID);
                if (svc == null) {
                    dispatchEvent("error",
                        "{\"stage\":\"service\",\"msg\":\"Leica service " + SVC_UUID
                        + " not found. Discovered: " + jsEscape(svcDump.toString()) + "\"}");
                    return;
                }

                // Dump ALL characteristics of the Leica service with their properties
                StringBuilder chDump = new StringBuilder();
                java.util.ArrayList<BluetoothGattCharacteristic> notifiable = new java.util.ArrayList<>();
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    int p = ch.getProperties();
                    StringBuilder props = new StringBuilder();
                    if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) props.append("R");
                    if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) props.append("W");
                    if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) props.append("w");
                    if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        props.append("N");
                        notifiable.add(ch);
                    }
                    if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        props.append("I");
                        notifiable.add(ch);
                    }
                    chDump.append(ch.getUuid().toString())
                          .append("[").append(props.toString()).append("] ");
                }
                Log.d(BTAG, "chars in Leica svc: " + chDump.toString());
                dispatchEvent("charsDumped",
                    "{\"chars\":\"" + jsEscape(chDump.toString())
                    + "\",\"notifiableCount\":" + notifiable.size() + "}");

                // Subscribe to ALL notifiable characteristics — whatever fires when
                // the user presses DIST will come through as a measurement event
                // tagged with its source UUID so we can identify which one to use.
                if (notifiable.isEmpty()) {
                    dispatchEvent("error",
                        "{\"stage\":\"characteristic\",\"msg\":\"No notifiable characteristics in Leica service\"}");
                    return;
                }
                // Queue CCCD writes so they don't clobber each other (Android only allows
                // one pending GATT write at a time). We track them via onDescriptorWrite.
                pendingNotifyQueue.clear();
                for (BluetoothGattCharacteristic ch : notifiable) {
                    pendingNotifyQueue.add(ch);
                }
                writeNextNotifyDescriptor(g);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor desc, int status) {
                Log.d(BTAG, "descWrite " + desc.getCharacteristic().getUuid()
                    + " status=" + status);
                writeNextNotifyDescriptor(g);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
                byte[] raw = ch.getValue();
                if (raw == null) raw = new byte[0];
                String hex = bytesToHex(raw);
                // Try ASCII decode
                StringBuilder sb = new StringBuilder();
                boolean allPrintable = true;
                for (byte b : raw) {
                    int v = b & 0xFF;
                    if ((v >= 0x20 && v <= 0x7E) || v == 0x0D || v == 0x0A || v == 0x09) {
                        if (v == 0x0D) sb.append("\\r");
                        else if (v == 0x0A) sb.append("\\n");
                        else if (v == 0x09) sb.append("\\t");
                        else sb.append((char) v);
                    } else { allPrintable = false; break; }
                }
                String ascii = allPrintable ? sb.toString() : "";
                String json = String.format(Locale.US,
                    "{\"uuid\":\"%s\",\"rawHex\":\"%s\",\"ascii\":\"%s\",\"isAscii\":%s,\"length\":%d,\"status\":%d}",
                    jsEscape(ch.getUuid().toString()), hex, jsEscape(ascii),
                    allPrintable ? "true" : "false", raw.length, status);
                Log.d(BTAG, "charRead: " + json);
                dispatchEvent("charRead", json);
                // Continue queue
                readNextChar(g);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
                byte[] raw = ch.getValue();
                if (raw == null || raw.length == 0) return;
                String chUuid = ch.getUuid().toString();
                String hex = bytesToHex(raw);
                // ASCII decode
                StringBuilder sb = new StringBuilder();
                boolean allPrintable = true;
                for (byte b : raw) {
                    int v = b & 0xFF;
                    if ((v >= 0x20 && v <= 0x7E) || v == 0x0D || v == 0x0A || v == 0x09) {
                        if (v == 0x0D) sb.append("\\r");
                        else if (v == 0x0A) sb.append("\\n");
                        else if (v == 0x09) sb.append("\\t");
                        else sb.append((char) v);
                    } else {
                        allPrintable = false;
                        break;
                    }
                }
                String ascii = allPrintable ? sb.toString() : "";
                // X3 distance packet format (observed empirically):
                //   [0:4]  float32 LE distance (meters)
                //   [4:8]  float32 LE field2 (likely tilt in radians, or possibly
                //          unused — needs ground-truth calibration to confirm)
                //   [16:18] uint16 LE counter
                float meters = 0f;
                float field2 = 0f;
                int counter = -1;
                if (raw.length >= 4) {
                    meters = ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                }
                if (raw.length >= 8) {
                    field2 = ByteBuffer.wrap(raw, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                }
                if (raw.length >= 18) {
                    counter = ByteBuffer.wrap(raw, 16, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
                }
                String json = String.format(Locale.US,
                    "{\"uuid\":\"%s\",\"distance\":%f,\"field2\":%f,\"counter\":%d,\"rawHex\":\"%s\",\"ascii\":\"%s\",\"isAscii\":%s,\"length\":%d}",
                    jsEscape(chUuid), meters, field2, counter, hex, jsEscape(ascii),
                    allPrintable ? "true" : "false", raw.length);
                Log.d(BTAG, "measurement: " + json);
                dispatchEvent("measurement", json);
            }
        };

        private void setState(String s) {
            state = s;
            dispatchEvent("state",
                String.format(Locale.US, "{\"state\":\"%s\"}", jsEscape(s)));
        }

        private void dispatchEvent(final String type, final String jsonData) {
            if (activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
                Log.w(BTAG, "dispatchEvent no tab for " + type);
                return;
            }
            final WebView target = tabs.get(activeTabIndex).webView;
            if (target == null) return;
            final String js = "(function(){ if(window.onLeicaEvent) window.onLeicaEvent('"
                + type + "', " + jsonData + "); })();";
            target.post(new Runnable() {
                @Override public void run() {
                    try { target.evaluateJavascript(js, null); }
                    catch (Exception e) { Log.e(BTAG, "evaluateJs failed", e); }
                }
            });
        }

        private boolean hasScanPermission() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
            } else {
                return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            }
        }

        private boolean hasConnectPermission() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        private String jsEscape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
        }

        private String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02X", b));
            return sb.toString();
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
