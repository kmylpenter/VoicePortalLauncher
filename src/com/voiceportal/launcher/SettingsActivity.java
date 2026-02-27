package com.voiceportal.launcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;

public class SettingsActivity extends Activity implements View.OnClickListener {

    private static final String PREFS_NAME = "voiceportal_settings";
    public static final String KEY_HIDE_TAB_BAR = "hide_tab_bar";
    public static final String KEY_KIOSK_MODE = "kiosk_mode";

    private SharedPreferences prefs;
    private CheckBox hideTabBarCheckbox;
    private CheckBox kioskModeCheckbox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        findViewById(R.id.back_button).setOnClickListener(this);

        hideTabBarCheckbox = findViewById(R.id.setting_hide_tab_bar);
        kioskModeCheckbox = findViewById(R.id.setting_kiosk_mode);

        hideTabBarCheckbox.setChecked(prefs.getBoolean(KEY_HIDE_TAB_BAR, false));
        kioskModeCheckbox.setChecked(prefs.getBoolean(KEY_KIOSK_MODE, false));

        hideTabBarCheckbox.setOnCheckedChangeListener(new HideTabBarListener());
        kioskModeCheckbox.setOnCheckedChangeListener(new KioskModeListener());
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.back_button) {
            finish();
        }
    }

    public static boolean getHideTabBar(Activity activity) {
        return activity.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_HIDE_TAB_BAR, false);
    }

    public static boolean getKioskMode(Activity activity) {
        return activity.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_KIOSK_MODE, false);
    }

    private class HideTabBarListener implements CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            prefs.edit().putBoolean(KEY_HIDE_TAB_BAR, isChecked).apply();
        }
    }

    private class KioskModeListener implements CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            prefs.edit().putBoolean(KEY_KIOSK_MODE, isChecked).apply();
        }
    }
}
