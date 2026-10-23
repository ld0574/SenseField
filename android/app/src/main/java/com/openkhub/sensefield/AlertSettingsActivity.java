package com.openkhub.sensefield;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Detailed output channel and event controls, linked from game tuning. */
public final class AlertSettingsActivity extends UiActivity {
    private SharedPreferences preferences;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);
        preferences = GameProfile.settings(this);
        new CueSettings(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);

        UiKit.pageHeader(this, content, "提示偏好", "王者荣耀辅助");

        LinearLayout channels = UiKit.card(this);
        SettingHelp.addGroup(this, channels, "channel_group", 8);
        addToggle(channels, "视觉提示", "cue_channel_visual", true);
        addToggle(channels, "提示音", "cue_channel_tone", true);
        addToggle(channels, "语音", "cue_channel_speech", true);
        addToggle(channels, "触觉", "cue_channel_haptic", true);
        UiKit.add(content, channels, UiKit.GAP_SECTION);

        LinearLayout categories = UiKit.card(this);
        SettingHelp.addGroup(this, categories, "events_group", 8);
        addToggle(categories, "附近敌人提醒（小地图近区）", CueSettings.PREF_CATEGORY_NEAR, true);
        addToggle(categories, "附近敌人震动", CueSettings.PREF_NEAR_HAPTIC, true);
        addToggle(categories, "远处新敌人提示音", CueSettings.PREF_FAR_APPEAR, false);
        addToggle(categories, "小地图新目标", "cue_category_vision", true);
        addToggle(categories, "屏幕边缘威胁", "cue_category_peripheral", true);
        addToggle(categories, "危险接近", "cue_category_danger", false);
        addToggle(categories, "玩家死亡／复活", "cue_category_player", true);
        addToggle(categories, "系统状态", "cue_category_system", true);
        UiKit.add(content, categories, 24);

        setContentView(scroll);
    }

    private void addToggle(LinearLayout content, String label, String key, boolean fallback) {
        CheckBox toggle = new CheckBox(this);
        toggle.setText(label);
        toggle.setChecked(preferences.getBoolean(key, fallback));
        toggle.setTextSize(18);
        toggle.setTextColor(UiKit.INK);
        UiKit.styleCheckable(toggle, this);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            CueSettings.markCustom(preferences);
        });
        UiKit.add(content, toggle, 6);
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (!SettingHelp.close(this)) super.onBackPressed();
    }
}
