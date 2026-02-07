package com.voiceportal.launcher;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

public class ServerLauncher {
    private static final String TAG = "ServerLauncher";
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final int PROXY_PORT = 3456;
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int TIMEOUT_MS = 30000;
    private static final String LOG_DIR = HOME + "/.voiceportal/logs";
    private static final int LOG_PORT_OFFSET = 10000;

    public interface StatusCallback {
        void onDevServerStarting();
        void onProxyStarting();
        void onWaitingForServers();
        void onServersReady();
        void onError(String message);
        void onLog(String message);
    }

    private final Context context;
    private final AppConfig app;
    private volatile boolean cancelled = false;
    private Thread idleWatchdog;

    public ServerLauncher(Context context, AppConfig app) {
        this.context = context;
        this.app = app;
    }

    public void cancel() {
        cancelled = true;
        stopIdleWatchdog();
    }

    public void launch(StatusCallback callback) {
        new Thread(new LaunchRunnable(callback)).start();
    }

    public void startIdleWatchdog() {
        if (app.idleTimeoutMin <= 0) return;

        stopIdleWatchdog();
        idleWatchdog = new Thread(new IdleWatchdogRunnable());
        idleWatchdog.setDaemon(true);
        idleWatchdog.start();
        Log.d(TAG, "Idle watchdog started: " + app.idleTimeoutMin + " min timeout");
    }

    public void stopIdleWatchdog() {
        if (idleWatchdog != null) {
            idleWatchdog.interrupt();
            idleWatchdog = null;
        }
    }

    /** Kill dev server, proxy, and log reader via Termux. */
    public static void stopServers(Context context, int port) {
        // Kill dev server: try pkill patterns then fuser as fallback
        String killDev = "pkill -f ' " + port + "$' 2>/dev/null; " +
            "pkill -f ' " + port + " ' 2>/dev/null; " +
            "pkill -f ':" + port + "' 2>/dev/null; " +
            "fuser -k " + port + "/tcp 2>/dev/null; " +
            "echo 'Dev server on port " + port + " killed'";
        TermuxCommandRunner.runInBackground(context, killDev, null);

        // Kill VoicePortal proxy
        String killProxy = "pkill -f voiceportal-daemon 2>/dev/null; " +
            "pkill -f 'node.*proxy/server.js' 2>/dev/null; " +
            "fuser -k " + PROXY_PORT + "/tcp 2>/dev/null; " +
            "echo 'VoicePortal proxy killed'";
        TermuxCommandRunner.runInBackground(context, killProxy, null);

        // Kill log reader
        int logPort = Math.min(port + LOG_PORT_OFFSET, 65530);
        String killReader = "pkill -f 'logreader.py.*" + logPort + "' 2>/dev/null; " +
            "fuser -k " + logPort + "/tcp 2>/dev/null";
        TermuxCommandRunner.runInBackground(context, killReader, null);

        Log.d(TAG, "Stop servers command sent for port " + port);
    }

    private boolean isPortResponding(int port) {
        try {
            URL url = new URL("http://127.0.0.1:" + port);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            Log.d(TAG, "Port " + port + " responded with: " + code);
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /** Check if port is bound at TCP level (regardless of HTTP). */
    private boolean isPortInUse(int port) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int getLogPort() {
        return Math.min(app.port + LOG_PORT_OFFSET, 65530);
    }

    /** Fetch server log content from log reader HTTP server. */
    private String fetchLogContent(int logPort) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + logPort);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            conn.setRequestMethod("GET");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // --- Named Runnable classes (d8 compatibility) ---

    private class LaunchRunnable implements Runnable {
        private final StatusCallback callback;
        LaunchRunnable(StatusCallback callback) { this.callback = callback; }

        @Override
        public void run() {
            try {
                boolean needsProxy = app.voicePortalMode != null
                    && !app.voicePortalMode.isEmpty()
                    && !app.voicePortalMode.equals("none");

                String projectDir = HOME + "/" + app.projectPath;
                String logFile = LOG_DIR + "/" + app.id + ".log";
                int logPort = getLogPort();

                // Pre-flight: if dev server already responding, skip launch
                if (isPortResponding(app.port)) {
                    callback.onLog("Server already running on port " + app.port);
                    boolean proxyOk = !needsProxy || isPortResponding(PROXY_PORT);
                    if (proxyOk) {
                        callback.onServersReady();
                        startIdleWatchdog();
                        return;
                    }
                }

                // Pre-flight: port bound by non-HTTP process
                if (isPortInUse(app.port)) {
                    callback.onError("Port " + app.port +
                        " is in use by another process (not HTTP).\n\n" +
                        "Change the port in launcher config,\nor kill the process:\n" +
                        "  fuser -k " + app.port + "/tcp");
                    return;
                }

                // Setup log capture: create dir, write log reader script, clear log
                String pyScript =
                    "import http.server,sys\n" +
                    "class H(http.server.BaseHTTPRequestHandler):\n" +
                    "    def do_GET(self):\n" +
                    "        try:\n" +
                    "            f=open(sys.argv[1])\n" +
                    "            d=f.read()[-8192:]\n" +
                    "            f.close()\n" +
                    "        except: d=''\n" +
                    "        self.send_response(200)\n" +
                    "        self.end_headers()\n" +
                    "        self.wfile.write(d.encode())\n" +
                    "    def log_message(self,*a):pass\n" +
                    "http.server.HTTPServer(('127.0.0.1',int(sys.argv[2])),H).serve_forever()\n";
                String b64 = Base64.encodeToString(pyScript.getBytes(), Base64.NO_WRAP);
                String readerScript = HOME + "/.voiceportal/logreader.py";
                String setupCmd = "mkdir -p " + LOG_DIR + " " + HOME + "/.voiceportal && " +
                    "echo '" + b64 + "' | base64 -d > " + readerScript + " && " +
                    ": > " + logFile;
                TermuxCommandRunner.runInBackground(context, setupCmd, null);

                Thread.sleep(500);
                if (cancelled) return;

                // Start log reader HTTP server
                String startReader = "python3 " + readerScript + " " + logFile + " " + logPort;
                TermuxCommandRunner.runInBackground(context, startReader, null);

                // Step 1: Start dev server with output captured to log file
                callback.onDevServerStarting();
                String fixShebangs = "termux-fix-shebang " + projectDir +
                    "/node_modules/.bin/* 2>/dev/null; ";
                String devCmd = "cd " + projectDir + " && " + fixShebangs +
                    app.devCommand + " >> " + logFile + " 2>&1";
                callback.onLog("CMD: " + app.devCommand);
                String devErr = TermuxCommandRunner.runInBackground(context, devCmd, projectDir);
                if (devErr != null) {
                    callback.onLog("ERR dev: " + devErr);
                } else {
                    callback.onLog("Intent sent OK");
                }

                Thread.sleep(2000);
                if (cancelled) return;

                // Step 2: Start VoicePortal proxy (only if needed)
                String vpDir = HOME + "/projekty/VoicePortal";
                String proxyCmd = "cd " + vpDir + " && bash voiceportal-daemon.sh --restart " + projectDir;
                if (needsProxy) {
                    callback.onProxyStarting();
                    callback.onLog("CMD: " + proxyCmd);
                    String proxyErr = TermuxCommandRunner.runInBackground(context, proxyCmd, vpDir);
                    if (proxyErr != null) {
                        callback.onLog("ERR proxy: " + proxyErr);
                    } else {
                        callback.onLog("Intent sent OK");
                    }

                    Thread.sleep(1000);
                    if (cancelled) return;
                }

                // Step 3: Poll for server readiness
                callback.onWaitingForServers();
                callback.onLog("Polling dev:" + app.port +
                    (needsProxy ? " proxy:" + PROXY_PORT : ""));
                long start = System.currentTimeMillis();
                boolean devReady = false;
                boolean proxyReady = !needsProxy;
                int pollCount = 0;
                String lastLogContent = "";

                while (!cancelled && (System.currentTimeMillis() - start) < TIMEOUT_MS) {
                    if (!devReady && isPortResponding(app.port)) {
                        devReady = true;
                        callback.onLog("+ dev:" + app.port + " OK");
                    }
                    if (!proxyReady && isPortResponding(PROXY_PORT)) {
                        proxyReady = true;
                        callback.onLog("+ proxy:" + PROXY_PORT + " OK");
                    }
                    if (devReady && proxyReady) break;

                    pollCount++;

                    // Fetch server output every ~6 seconds
                    if (pollCount % 3 == 0) {
                        String logContent = fetchLogContent(logPort);
                        if (logContent != null && !logContent.isEmpty()
                                && !logContent.equals(lastLogContent)) {
                            String newLines = logContent;
                            if (!lastLogContent.isEmpty()
                                    && logContent.startsWith(lastLogContent)) {
                                newLines = logContent.substring(lastLogContent.length());
                            }
                            String trimmed = newLines.trim();
                            if (!trimmed.isEmpty()) {
                                String[] lines = trimmed.split("\n");
                                int from = Math.max(0, lines.length - 5);
                                for (int i = from; i < lines.length; i++) {
                                    callback.onLog("[srv] " + lines[i]);
                                }
                            }
                            lastLogContent = logContent;
                        }
                    }

                    long elapsed = (System.currentTimeMillis() - start) / 1000;
                    if (pollCount % 5 == 0) {
                        callback.onLog(elapsed + "s dev:" + (devReady ? "OK" : "waiting")
                            + (needsProxy ? " proxy:" + (proxyReady ? "OK" : "waiting") : ""));
                    }

                    Thread.sleep(POLL_INTERVAL_MS);
                }
                boolean ready = devReady && proxyReady;

                if (cancelled) return;

                if (ready) {
                    callback.onServersReady();
                    startIdleWatchdog();
                } else {
                    // Fetch final log content for error details
                    String logContent = fetchLogContent(logPort);

                    StringBuilder msg = new StringBuilder();
                    if (!devReady && !proxyReady) {
                        msg.append("Dev server (port ").append(app.port)
                           .append(") and proxy (port ").append(PROXY_PORT)
                           .append(") not responding.");
                    } else if (!devReady) {
                        msg.append("Dev server not responding on port ")
                           .append(app.port).append(".");
                    } else {
                        msg.append("VoicePortal proxy not responding on port ")
                           .append(PROXY_PORT).append(".");
                    }

                    if (logContent != null && !logContent.trim().isEmpty()) {
                        String[] lines = logContent.trim().split("\n");
                        int from = Math.max(0, lines.length - 15);
                        msg.append("\n\nServer output:\n");
                        for (int i = from; i < lines.length; i++) {
                            msg.append(lines[i]).append("\n");
                        }
                    } else {
                        msg.append("\n\nNo server output captured.");
                        msg.append("\nRun manually in Termux:\n")
                           .append("  cd ").append(projectDir)
                           .append(" && ").append(app.devCommand);
                    }

                    callback.onError(msg.toString());
                }
            } catch (InterruptedException e) {
                if (!cancelled) {
                    callback.onError("Launch interrupted");
                }
            } catch (Exception e) {
                callback.onError("Launch failed: " + e.getMessage());
            }
        }
    }

    private class IdleWatchdogRunnable implements Runnable {
        @Override
        public void run() {
            long timeoutMs = app.idleTimeoutMin * 60L * 1000L;
            long lastActivity = System.currentTimeMillis();

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(30000);

                    if (isPortResponding(app.port)) {
                        lastActivity = System.currentTimeMillis();
                    }

                    long idle = System.currentTimeMillis() - lastActivity;
                    if (idle >= timeoutMs) {
                        Log.d(TAG, "Idle timeout reached (" + app.idleTimeoutMin +
                              " min). Stopping servers.");
                        stopServers(context, app.port);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // Watchdog cancelled
            }
        }
    }
}
