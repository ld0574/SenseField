package com.openkhub.sensefield;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Detailed output channel and event controls, linked from game tuning. */
public final class AlertSettingsActivity extends Activity {
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

        UiKit.addBrandHeader(content, "王者荣耀");
        UiKit.add(content, UiKit.text(this, "提示偏好", 28, UiKit.INK, true), 18);

        LinearLayout channels = UiKit.card(this);
        UiKit.add(channels, UiKit.heading(this, "输出方式"), 8);
        addToggle(channels, "视觉提示", "cue_channel_visual", true);
        addToggle(channels, "提示音", "cue_channel_tone", true);
        addToggle(channels, "语音", "cue_channel_speech", true);
        addToggle(channels, "触觉", "cue_channel_haptic", true);
        UiKit.add(content, channels, 14);

        LinearLayout categories = UiKit.card(this);
        UiKit.add(categories, UiKit.heading(this, "事件提示"), 8);
        addToggle(categories, "视野记忆", "cue_category_vision", true);
        addToggle(categories, "危险接近（默认关闭）", "cue_category_danger", false);
        addToggle(categories, "玩家死亡／复活", "cue_category_player", true);
        addToggle(categories, "系统状态", "cue_category_system", true);
        UiKit.add(content, categories, 18);

        Button done = UiKit.button(this, "完成", true);
        done.setTextSize(20);
        done.setMinHeight(UiKit.dp(this, 60));
        done.setMinimumHeight(UiKit.dp(this, 60));
        done.setOnClickListener(view -> finish());
        UiKit.add(content, done, 0);
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
        UiKit.add(content, toggle, 2);
    }
}
