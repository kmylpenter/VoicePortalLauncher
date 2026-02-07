package com.voiceportal.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;

public class WebViewActivity extends Activity {
    private static final String TAG = "VPWebView";
    private static final int MIC_PERMISSION_CODE = 1;
    private WebView webView;
    private String pendingUrl;
    private PermissionRequest pendingPermissionRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        // Enable WebView debugging
        WebView.setWebContentsDebuggingEnabled(true);

        // Pre-initialize audio subsystem
        initAudioManager();

        webView = findViewById(R.id.webview);
        setupWebView();

        pendingUrl = getIntent().getStringExtra("url");

        // Always request mic permission first
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_CODE);
        } else {
            Log.d(TAG, "Mic permission already granted");
            loadPage();
        }
    }

    /** Pre-initialize Android AudioManager to warm up audio subsystem */
    private void initAudioManager() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                // Request audio focus briefly to initialize audio routing
                int mode = audioManager.getMode();
                Log.d(TAG, "AudioManager mode: " + mode);
                // Just accessing it warms up the audio subsystem
            }
        } catch (Exception e) {
            Log.w(TAG, "AudioManager init failed: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Mic permission GRANTED by user");
                // Grant any pending WebView permission request
                if (pendingPermissionRequest != null) {
                    Log.d(TAG, "Granting pending WebView permission request");
                    pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                    pendingPermissionRequest = null;
                }
            } else {
                Log.d(TAG, "Mic permission DENIED by user");
                // Deny any pending WebView permission request
                if (pendingPermissionRequest != null) {
                    pendingPermissionRequest.deny();
                    pendingPermissionRequest = null;
                }
            }
        }
        loadPage();
    }

    private void loadPage() {
        if (pendingUrl != null) {
            Log.d(TAG, "Loading URL: " + pendingUrl);
            webView.loadUrl(pendingUrl);
            pendingUrl = null;
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Allow third-party cookies (needed for some auth flows)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new VPWebViewClient());
        webView.setWebChromeClient(new VPWebChromeClient());
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    /** WebViewClient that pre-warms audio after page loads and handles renderer crashes */
    private class VPWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Log.d(TAG, "Page loaded: " + url);

            // Pre-warm audio capture: request getUserMedia once to initialize
            // the WebView audio subsystem. This fixes "Could not start audio source"
            // on some Android WebView versions where the first getUserMedia fails
            // unless the audio pipeline has been initialized.
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

            // Destroy the dead WebView to free resources
            if (webView != null) {
                webView.destroy();
                webView = null;
            }

            // Restart: recreate the activity to get a fresh WebView
            recreate();
            return true;
        }
    }

    /** WebChromeClient with mic permission handling */
    private class VPWebChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            Log.d(TAG, "WebView permission request: " + java.util.Arrays.toString(request.getResources()));

            // Must grant on UI thread for reliability across Android versions
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
                // Try to grant anyway
                try {
                    request.grant(request.getResources());
                } catch (Exception e2) {
                    Log.e(TAG, "Fallback grant also failed: " + e2.getMessage());
                }
            }
        }
    }
}
