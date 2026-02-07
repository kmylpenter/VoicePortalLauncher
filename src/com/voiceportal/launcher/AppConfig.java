package com.voiceportal.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    private static final String PREFS_NAME = "voiceportal_apps";
    private static final String KEY_APPS = "apps_json";
    private static final String KEY_INITIALIZED = "initialized";

    public String id;
    public String name;
    public String description;
    public String projectPath;
    public int port;
    public String devCommand;
    public String voicePortalMode;
    public int idleTimeoutMin;  // 0 = never stop

    public AppConfig(String id, String name, String description,
                     String projectPath, int port, String devCommand,
                     String voicePortalMode, int idleTimeoutMin) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.projectPath = projectPath;
        this.port = port;
        this.devCommand = devCommand;
        this.voicePortalMode = voicePortalMode;
        this.idleTimeoutMin = idleTimeoutMin;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("name", name);
            obj.put("description", description);
            obj.put("projectPath", projectPath);
            obj.put("port", port);
            obj.put("devCommand", devCommand);
            obj.put("voicePortalMode", voicePortalMode);
            obj.put("idleTimeoutMin", idleTimeoutMin);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return obj;
    }

    public static AppConfig fromJson(JSONObject obj) {
        try {
            return new AppConfig(
                obj.getString("id"),
                obj.getString("name"),
                obj.optString("description", ""),
                obj.getString("projectPath"),
                obj.getInt("port"),
                obj.optString("devCommand", "npm run dev"),
                obj.optString("voicePortalMode", "default"),
                obj.optInt("idleTimeoutMin", 60)
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Load all apps. First run seeds from assets, then always reads from SharedPreferences. */
    public static List<AppConfig> loadAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // First run: seed from assets/apps.json
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            List<AppConfig> seed = loadFromAssets(context);
            saveAll(context, seed);
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply();
            return seed;
        }

        // Normal: read from prefs
        List<AppConfig> apps = new ArrayList<>();
        String json = prefs.getString(KEY_APPS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                AppConfig app = fromJson(arr.getJSONObject(i));
                if (app != null) apps.add(app);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return apps;
    }

    /** Save full app list to SharedPreferences. */
    public static void saveAll(Context context, List<AppConfig> apps) {
        JSONArray arr = new JSONArray();
        for (AppConfig app : apps) {
            arr.put(app.toJson());
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APPS, arr.toString())
            .apply();
    }

    private static List<AppConfig> loadFromAssets(Context context) {
        List<AppConfig> apps = new ArrayList<>();
        try {
            InputStream is = context.getAssets().open("apps.json");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();

            JSONArray arr = new JSONArray(new String(buffer, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                AppConfig app = fromJson(arr.getJSONObject(i));
                if (app != null) apps.add(app);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return apps;
    }
}
