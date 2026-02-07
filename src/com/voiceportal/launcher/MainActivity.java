package com.voiceportal.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.Toast;
import java.util.List;

public class MainActivity extends Activity
        implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener,
                   View.OnClickListener {

    private List<AppConfig> apps;
    private AppListAdapter adapter;
    private ListView listView;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.app_list);
        emptyView = findViewById(R.id.empty_view);
        findViewById(R.id.add_button).setOnClickListener(this);
        findViewById(R.id.monitor_button).setOnClickListener(this);

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.add_button) {
            showAppDialog(-1);
        } else if (v.getId() == R.id.monitor_button) {
            startActivity(new Intent(this, ServerMonitorActivity.class));
        }
    }

    private void loadApps() {
        apps = AppConfig.loadAll(this);

        if (apps.isEmpty()) {
            listView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            listView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }

        adapter = new AppListAdapter(this, apps);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this);
        listView.setOnItemLongClickListener(this);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Toast.makeText(this, "Launching " + apps.get(position).name + "...", Toast.LENGTH_SHORT).show();
        launchApp(apps.get(position));
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        showEditOrDeleteDialog(position);
        return true;
    }

    private void showEditOrDeleteDialog(final int position) {
        String[] options = {"Edit", "Delete"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        builder.setTitle(apps.get(position).name);
        builder.setItems(options, new EditDeleteClickListener(position));
        builder.show();
    }

    /** Show dialog for adding (position = -1) or editing (position >= 0). */
    private void showAppDialog(int position) {
        boolean isNew = (position < 0);
        AppConfig app = isNew ? null : apps.get(position);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_app, null);
        EditText nameInput = dialogView.findViewById(R.id.edit_name);
        EditText descInput = dialogView.findViewById(R.id.edit_description);
        EditText pathInput = dialogView.findViewById(R.id.edit_project_path);
        EditText portInput = dialogView.findViewById(R.id.edit_port);
        EditText cmdInput = dialogView.findViewById(R.id.edit_dev_command);
        CheckBox vpCheckbox = dialogView.findViewById(R.id.edit_use_voiceportal);
        EditText modeInput = dialogView.findViewById(R.id.edit_voice_mode);
        EditText timeoutInput = dialogView.findViewById(R.id.edit_idle_timeout);

        if (!isNew) {
            nameInput.setText(app.name);
            descInput.setText(app.description);
            pathInput.setText(app.projectPath);
            portInput.setText(String.valueOf(app.port));
            cmdInput.setText(app.devCommand);
            boolean usesVP = app.voicePortalMode != null
                && !app.voicePortalMode.isEmpty()
                && !app.voicePortalMode.equals("none");
            vpCheckbox.setChecked(usesVP);
            modeInput.setText(usesVP ? app.voicePortalMode : "default");
            timeoutInput.setText(String.valueOf(app.idleTimeoutMin));
        } else {
            timeoutInput.setText("60");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        builder.setTitle(isNew ? "Add app" : "Edit app");
        builder.setView(dialogView);
        builder.setPositiveButton("Save", new SaveClickListener(position, nameInput, descInput,
                pathInput, portInput, cmdInput, vpCheckbox, modeInput, timeoutInput));
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void deleteApp(int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        builder.setTitle("Delete " + apps.get(position).name + "?");
        builder.setMessage("This will remove the app from the list. Your project files are not affected.");
        builder.setPositiveButton("Delete", new DeleteConfirmListener(position));
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void launchApp(AppConfig app) {
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.putExtra("app_id", app.id);
        intent.putExtra("app_name", app.name);
        intent.putExtra("app_project_path", app.projectPath);
        intent.putExtra("app_port", app.port);
        intent.putExtra("app_dev_command", app.devCommand);
        intent.putExtra("app_voice_mode", app.voicePortalMode);
        intent.putExtra("app_idle_timeout", app.idleTimeoutMin);
        startActivity(intent);
    }

    // --- Named listener classes (no anonymous classes - d8 compatibility) ---

    private class EditDeleteClickListener implements DialogInterface.OnClickListener {
        private final int position;
        EditDeleteClickListener(int position) { this.position = position; }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == 0) {
                showAppDialog(position);
            } else {
                deleteApp(position);
            }
        }
    }

    private class SaveClickListener implements DialogInterface.OnClickListener {
        private final int position;
        private final EditText nameInput, descInput, pathInput, portInput, cmdInput, modeInput, timeoutInput;
        private final CheckBox vpCheckbox;

        SaveClickListener(int position, EditText nameInput, EditText descInput,
                          EditText pathInput, EditText portInput,
                          EditText cmdInput, CheckBox vpCheckbox,
                          EditText modeInput, EditText timeoutInput) {
            this.position = position;
            this.nameInput = nameInput;
            this.descInput = descInput;
            this.pathInput = pathInput;
            this.portInput = portInput;
            this.cmdInput = cmdInput;
            this.vpCheckbox = vpCheckbox;
            this.modeInput = modeInput;
            this.timeoutInput = timeoutInput;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            String name = nameInput.getText().toString().trim();
            String desc = descInput.getText().toString().trim();
            String path = pathInput.getText().toString().trim();
            String portStr = portInput.getText().toString().trim();
            String cmd = cmdInput.getText().toString().trim();
            String mode;
            if (vpCheckbox.isChecked()) {
                mode = modeInput.getText().toString().trim();
                if (mode.isEmpty()) mode = "default";
            } else {
                mode = "none";
            }
            String timeoutStr = timeoutInput.getText().toString().trim();

            if (name.isEmpty() || path.isEmpty() || portStr.isEmpty()) {
                Toast.makeText(MainActivity.this, "Name, path and port are required", Toast.LENGTH_SHORT).show();
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                Toast.makeText(MainActivity.this, "Invalid port number", Toast.LENGTH_SHORT).show();
                return;
            }

            int timeout = 0;
            if (!timeoutStr.isEmpty()) {
                try { timeout = Integer.parseInt(timeoutStr); } catch (NumberFormatException e) { /* keep 0 */ }
            }

            if (cmd.isEmpty()) cmd = "npm run dev";

            String id = name.toLowerCase().replaceAll("[^a-z0-9]", "");
            if (id.isEmpty()) id = "app" + System.currentTimeMillis();

            if (position < 0) {
                // Add new
                apps.add(new AppConfig(id, name, desc, path, port, cmd, mode, timeout));
            } else {
                // Update existing
                AppConfig app = apps.get(position);
                app.name = name;
                app.description = desc;
                app.projectPath = path;
                app.port = port;
                app.devCommand = cmd;
                app.voicePortalMode = mode;
                app.idleTimeoutMin = timeout;
            }

            AppConfig.saveAll(MainActivity.this, apps);
            loadApps();
        }
    }

    private class DeleteConfirmListener implements DialogInterface.OnClickListener {
        private final int position;
        DeleteConfirmListener(int position) { this.position = position; }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            apps.remove(position);
            AppConfig.saveAll(MainActivity.this, apps);
            loadApps();
        }
    }
}
