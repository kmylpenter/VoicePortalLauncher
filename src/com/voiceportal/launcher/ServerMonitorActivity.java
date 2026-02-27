package com.voiceportal.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class ServerMonitorActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "ServerMonitor";
    private static final int PROXY_PORT = 3456;
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int[] SCAN_PORTS = {
        3000, 3001, 4200, 5000, 5173, 5174,
        8000, 8080, 8081, 8082, 8085, 8888, 8889,
        9000, 9090, 19876
    };

    private LinearLayout serverListContainer;
    private TextView logText;
    private ScrollView logScroll;
    private Handler handler;
    private volatile boolean polling = false;
    private List<ServerEntry> servers;
    private final StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_monitor);

        serverListContainer = findViewById(R.id.server_list);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        handler = new Handler(Looper.getMainLooper());

        findViewById(R.id.refresh_button).setOnClickListener(this);
        findViewById(R.id.copy_log_button).setOnClickListener(this);

        appendLog("Monitor started");
        buildServerList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        new Thread(new PollRunnable()).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.refresh_button) {
            appendLog("Manual refresh");
            buildServerList();
            new Thread(new PollRunnable()).start();
        } else if (v.getId() == R.id.copy_log_button) {
            ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clip.setPrimaryClip(ClipData.newPlainText("ServerMonitor Log", logBuffer.toString()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void appendLog(String msg) {
        String line = System.currentTimeMillis() % 100000 + " " + msg;
        logBuffer.append(line).append("\n");
        handler.post(new AppendLogRunnable(line));
    }

    private void buildServerList() {
        servers = new ArrayList<>();
        servers.add(new ServerEntry("VoicePortal Proxy", PROXY_PORT, true, null));

        List<AppConfig> apps = AppConfig.loadAll(this);
        java.util.Set<Integer> knownPorts = new java.util.HashSet<>();
        knownPorts.add(PROXY_PORT);
        for (int i = 0; i < apps.size(); i++) {
            AppConfig app = apps.get(i);
            servers.add(new ServerEntry(app.name, app.port, false, app.projectPath));
            knownPorts.add(app.port);
        }

        // Scan common dev ports not in config
        for (int scanPort : SCAN_PORTS) {
            if (!knownPorts.contains(scanPort)) {
                servers.add(new ServerEntry("Discovered", scanPort, false, null, true));
            }
        }

        serverListContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < servers.size(); i++) {
            ServerEntry entry = servers.get(i);
            View card = inflater.inflate(R.layout.item_server_card, serverListContainer, false);

            TextView nameView = card.findViewById(R.id.server_name);
            TextView portView = card.findViewById(R.id.server_port);
            nameView.setText(entry.name);
            portView.setText(":" + entry.port);

            View dot = card.findViewById(R.id.server_status_dot);
            setDotColor(dot, 0xFF757575);

            card.findViewById(R.id.server_details).setVisibility(View.GONE);
            card.findViewById(R.id.server_stop_button).setVisibility(View.GONE);

            Button stopBtn = card.findViewById(R.id.server_stop_button);
            stopBtn.setOnClickListener(new StopClickListener(entry.port, entry.name, entry.isProxy, entry.projectPath));

            // Scanned entries hidden until they respond
            if (entry.isScanned) {
                card.setVisibility(View.GONE);
            }

            entry.cardView = card;
            serverListContainer.addView(card);
        }

        int configured = 0;
        for (int i = 0; i < servers.size(); i++) {
            if (!servers.get(i).isScanned) configured++;
        }
        appendLog("Tracking " + configured + " configured + " + SCAN_PORTS.length + " scanned ports");
    }

    private void setDotColor(View dot, int color) {
        GradientDrawable bg = (GradientDrawable) dot.getBackground().mutate();
        bg.setColor(color);
    }

    // --- Data model ---

    private static class ServerEntry {
        final String name;
        final int port;
        final boolean isProxy;
        final String projectPath;
        final boolean isScanned;
        boolean running;
        String details;
        View cardView;

        ServerEntry(String name, int port, boolean isProxy, String projectPath) {
            this(name, port, isProxy, projectPath, false);
        }

        ServerEntry(String name, int port, boolean isProxy, String projectPath, boolean isScanned) {
            this.name = name;
            this.port = port;
            this.isProxy = isProxy;
            this.projectPath = projectPath;
            this.isScanned = isScanned;
            this.running = false;
            this.details = "";
        }
    }

    // --- Background polling ---

    private class PollRunnable implements Runnable {
        @Override
        public void run() {
            while (polling) {
                for (int i = 0; i < servers.size(); i++) {
                    ServerEntry entry = servers.get(i);
                    boolean wasBefore = entry.running;
                    if (entry.isProxy) {
                        checkProxyHealth(entry);
                    } else {
                        checkPortAlive(entry);
                    }
                    if (wasBefore != entry.running) {
                        final String change = entry.name + ":" + entry.port +
                            " " + (entry.running ? "UP" : "DOWN");
                        appendLog(change);
                    }
                }
                handler.post(new UpdateUIRunnable());

                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void checkProxyHealth(ServerEntry entry) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + entry.port + "/health");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();

            if (code >= 200 && code < 300) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                entry.running = true;
                entry.details = parseHealthJson(sb.toString());
            } else {
                entry.running = true;
                entry.details = "HTTP " + code;
            }
        } catch (Exception e) {
            entry.running = false;
            entry.details = "Not responding";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String parseHealthJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            StringBuilder sb = new StringBuilder();

            if (obj.has("sessionId")) {
                String sid = obj.getString("sessionId");
                if (sid != null && !sid.equals("null") && !sid.isEmpty()) {
                    sb.append("Session: ").append(sid);
                }
            }
            if (obj.has("requestCount")) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append("Req: ").append(obj.getInt("requestCount"));
            }
            if (obj.has("uptime")) {
                if (sb.length() > 0) sb.append(" | ");
                int secs = obj.getInt("uptime");
                if (secs >= 3600) {
                    sb.append(secs / 3600).append("h").append((secs % 3600) / 60).append("m");
                } else if (secs >= 60) {
                    sb.append(secs / 60).append("m").append(secs % 60).append("s");
                } else {
                    sb.append(secs).append("s");
                }
            }
            if (sb.length() == 0) sb.append("Healthy");
            return sb.toString();
        } catch (Exception e) {
            return "Healthy (parse err)";
        }
    }

    private void checkPortAlive(ServerEntry entry) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + entry.port);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            entry.running = (code >= 200 && code < 500);
            entry.details = entry.running ? "HTTP " + code : "Not responding";
        } catch (Exception e) {
            entry.running = false;
            entry.details = "Not responding";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // --- UI updates on main thread ---

    private class UpdateUIRunnable implements Runnable {
        @Override
        public void run() {
            for (int i = 0; i < servers.size(); i++) {
                ServerEntry entry = servers.get(i);
                if (entry.cardView == null) continue;

                // Scanned entries: only show when running
                if (entry.isScanned) {
                    entry.cardView.setVisibility(entry.running ? View.VISIBLE : View.GONE);
                    if (!entry.running) continue;
                }

                View dot = entry.cardView.findViewById(R.id.server_status_dot);
                TextView detailsView = entry.cardView.findViewById(R.id.server_details);
                Button stopBtn = entry.cardView.findViewById(R.id.server_stop_button);

                if (entry.running) {
                    setDotColor(dot, 0xFF4CAF50);
                    detailsView.setText(entry.details);
                    detailsView.setVisibility(View.VISIBLE);
                    stopBtn.setVisibility(View.VISIBLE);
                } else {
                    setDotColor(dot, 0xFFF44336);
                    detailsView.setText(entry.details);
                    detailsView.setVisibility(View.VISIBLE);
                    stopBtn.setVisibility(View.GONE);
                }
            }
        }
    }

    private class AppendLogRunnable implements Runnable {
        private final String line;
        AppendLogRunnable(String line) { this.line = line; }

        @Override
        public void run() {
            logText.append(line + "\n");
            logScroll.post(new ScrollBottomRunnable());
        }
    }

    private class ScrollBottomRunnable implements Runnable {
        @Override
        public void run() {
            logScroll.fullScroll(View.FOCUS_DOWN);
        }
    }

    // --- Stop button listener ---

    private class StopClickListener implements View.OnClickListener {
        private final int port;
        private final String name;
        private final boolean isProxy;
        private final String projectPath;

        StopClickListener(int port, String name, boolean isProxy, String projectPath) {
            this.port = port;
            this.name = name;
            this.isProxy = isProxy;
            this.projectPath = projectPath;
        }

        @Override
        public void onClick(View v) {
            // Kill strategy overview:
            // /proc/net/tcp is permission-denied on Android, so fuser/lsof/ss don't work.
            // pkill -f only works if the port number appears in the process cmdline.
            // Many servers (node server.js, npm run dev) read port from config, NOT cmdline.
            //
            // Reliable strategy: use a Python one-liner to find which PID holds the
            // listening socket by scanning /proc/*/fd for socket inodes that match
            // the port in /proc/<our_pid>/net/tcp6. Then kill the entire process group.
            // Fallback: pkill patterns + fuser.
            // Kill strategy for Termux/Android:
            // - /proc/net/tcp is permission-denied → fuser/lsof/ss/netstat don't work
            // - /proc/*/fd/ IS readable (same UID in Termux)
            // - /proc/*/cmdline IS readable
            //
            // Approach: multi-strategy Python script that tries:
            // 1. Find processes with socket fds whose cmdline matches port/path
            // 2. Find bash wrappers (from TermuxCommandRunner or Claude) and kill trees
            // 3. Kill each candidate tree, check if port freed, stop early
            // 4. Fallback: pkill patterns
            StringBuilder cmd = new StringBuilder();
            if (isProxy) {
                cmd.append("pkill -f 'node.*proxy/server.js'; ");
                cmd.append("pkill -f voiceportal-daemon; ");
            } else {
                String pyScript = "import os,signal,socket,sys,time\n"
                    + "port=int(sys.argv[1])\n"
                    + "path=sys.argv[2] if len(sys.argv)>2 else ''\n"
                    + "my_pid=os.getpid()\n"
                    + "my_ppid=os.getppid()\n"
                    + "def children(pp):\n"
                    + " r=[]\n"
                    + " for d in os.listdir('/proc'):\n"
                    + "  if not d.isdigit():continue\n"
                    + "  try:\n"
                    + "   f=open(f'/proc/{d}/status')\n"
                    + "   for l in f:\n"
                    + "    if l.startswith('PPid:'):\n"
                    + "     if int(l.split()[1])==pp:r.append(int(d))\n"
                    + "     break\n"
                    + "   f.close()\n"
                    + "  except:pass\n"
                    + " return r\n"
                    + "def tree(pid):\n"
                    + " t=[pid]\n"
                    + " for c in children(pid):t.extend(tree(c))\n"
                    + " return t\n"
                    + "def kill_tree(pids):\n"
                    + " for p in reversed(pids):\n"
                    + "  if p==my_pid or p==my_ppid:continue\n"
                    + "  try:os.kill(p,signal.SIGTERM)\n"
                    + "  except:pass\n"
                    + "def port_free(pt):\n"
                    + " s=socket.socket()\n"
                    + " s.settimeout(0.5)\n"
                    + " try:s.connect(('127.0.0.1',pt));s.close();return False\n"
                    + " except:return True\n"
                    + "def has_socket(pid):\n"
                    + " try:\n"
                    + "  for fd in os.listdir(f'/proc/{pid}/fd'):\n"
                    + "   try:\n"
                    + "    if 'socket:' in os.readlink(f'/proc/{pid}/fd/{fd}'):return True\n"
                    + "   except:pass\n"
                    + " except:pass\n"
                    + " return False\n"
                    + "def get_cmd(pid):\n"
                    + " try:\n"
                    + "  f=open(f'/proc/{pid}/cmdline')\n"
                    + "  c=f.read().replace(chr(0),' ')\n"
                    + "  f.close()\n"
                    + "  return c\n"
                    + " except:return ''\n"
                    + "port_s=str(port)\n"
                    + "candidates=[]\n"
                    + "for d in os.listdir('/proc'):\n"
                    + " if not d.isdigit():continue\n"
                    + " pid=int(d)\n"
                    + " if pid==my_pid or pid==my_ppid:continue\n"
                    + " c=get_cmd(pid)\n"
                    + " if not c:continue\n"
                    + " # Skip the main claude binary itself (not its child shells)\n"
                    + " if c.strip()=='claude' or c.startswith('claude '):continue\n"
                    + " # Strategy 1: exact project path match (highest priority)\n"
                    + " if path and path in c:\n"
                    + "  candidates.insert(0,(pid,c,'path_match'))\n"
                    + " # Strategy 2: port in cmdline (any format)\n"
                    + " elif port_s in c:\n"
                    + "  candidates.append((pid,c,'port_in_cmd'))\n"
                    + " # Strategy 3: bash wrapper with projekty (from TermuxCommandRunner or Claude shell)\n"
                    + " elif 'bash' in c and 'projekty' in c:\n"
                    + "  candidates.append((pid,c,'bash_wrapper'))\n"
                    + " # Strategy 4: process with socket fd under projekty\n"
                    + " elif 'projekty' in c and has_socket(pid):\n"
                    + "  candidates.append((pid,c,'socket_projekty'))\n"
                    + "# Kill path-match candidates first\n"
                    + "if path:\n"
                    + " for cpid,ccmd,reason in candidates:\n"
                    + "  if reason=='path_match':\n"
                    + "   pids=tree(cpid)\n"
                    + "   kill_tree(pids)\n"
                    + "   print(f'killed {reason} {cpid}: {pids}')\n"
                    + " time.sleep(0.5)\n"
                    + " if port_free(port):\n"
                    + "  print(f'port {port} freed');sys.exit(0)\n"
                    + "# Kill remaining candidates one by one, check port after each\n"
                    + "for cpid,ccmd,reason in candidates:\n"
                    + " if reason=='path_match':continue\n"
                    + " pids=tree(cpid)\n"
                    + " kill_tree(pids)\n"
                    + " print(f'killed {reason} {cpid}: {pids}')\n"
                    + " time.sleep(0.5)\n"
                    + " if port_free(port):\n"
                    + "  print(f'port {port} freed');sys.exit(0)\n"
                    + "print('done')\n";
                String b64 = android.util.Base64.encodeToString(
                    pyScript.getBytes(), android.util.Base64.NO_WRAP);
                cmd.append("echo '").append(b64).append("' | base64 -d | python3 - ")
                   .append(port);
                if (projectPath != null && !projectPath.isEmpty()) {
                    cmd.append(" '").append(projectPath).append("'");
                }
                cmd.append(" 2>/dev/null; ");

                // Fallback: pkill patterns (works when port is in cmdline)
                cmd.append("pkill -f ' ").append(port).append("$' 2>/dev/null; ");
                cmd.append("pkill -f ' ").append(port).append(" ' 2>/dev/null; ");
                cmd.append("pkill -f ':").append(port).append("' 2>/dev/null; ");
                cmd.append("pkill -f '=").append(port).append("' 2>/dev/null; ");
                cmd.append("pkill -f -- '--port=").append(port).append("' 2>/dev/null; ");
                if (projectPath != null && !projectPath.isEmpty()) {
                    cmd.append("pkill -f '").append(projectPath).append("' 2>/dev/null; ");
                }

                // Kill the logreader for this port
                int logPort = Math.min(port + 10000, 65530);
                cmd.append("pkill -f 'logreader.py.*").append(logPort).append("' 2>/dev/null; ");
            }
            cmd.append("echo DONE");

            String cmdStr = cmd.toString();
            appendLog("STOP " + name + ":" + port);
            appendLog("CMD: " + cmdStr);
            String err = TermuxCommandRunner.runInBackground(ServerMonitorActivity.this, cmdStr, null);
            if (err != null) {
                appendLog("ERR: " + err);
            } else {
                appendLog("Intent sent OK");
            }
            Toast.makeText(ServerMonitorActivity.this,
                "Stopping " + name + "...", Toast.LENGTH_SHORT).show();
        }
    }
}
