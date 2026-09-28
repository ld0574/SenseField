package org.openrd.mapassist;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Presets plus intentionally small advanced controls for accessibility output. */
public final class AlertSettingsActivity extends Activity {
    private SharedPreferences preferences;
    private RadioGroup presets;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = GameProfile.settings(this);
        new CueSettings(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(getResources().getDisplayMetrics().density * 20);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);

        TextView title = new TextView(this);
        title.setText("无障碍提示设置");
        title.setTextSize(24);
        content.addView(title);

        presets = new RadioGroup(this);
        addPreset("精简", CueSettings.PRESET_COMPACT);
        addPreset("标准（推荐）", CueSettings.PRESET_STANDARD);
        addPreset("详细", CueSettings.PRESET_DETAILED);
        content.addView(presets);
        selectCurrentPreset();

        TextView channels = new TextView(this);
        channels.setText("高级：输出通道");
        channels.setTextSize(18);
        content.addView(channels);
        addToggle(content, "视觉提示", "cue_channel_visual", true);
        addToggle(content, "提示音", "cue_channel_tone", true);
        addToggle(content, "语音", "cue_channel_speech", true);
        addToggle(content, "触觉", "cue_channel_haptic", true);

        TextView categories = new TextView(this);
        categories.setText("高级：事件类别");
        categories.setTextSize(18);
        content.addView(categories);
        addToggle(content, "视野记忆", "cue_category_vision", true);
        addToggle(content, "危险接近（识别器就绪前保持关闭）",
                "cue_category_danger", false);
        addToggle(content, "玩家死亡／复活", "cue_category_player", true);
        addToggle(content, "系统状态", "cue_category_system", true);

        Button done = new Button(this);
        done.setText("完成");
        done.setOnClickListener(view -> finish());
        content.addView(done);
        setContentView(scroll);
    }

    private void addPreset(String label, String value) {
        RadioButton button = new RadioButton(this);
        button.setText(label);
        button.setTag(value);
        button.setOnClickListener(view -> {
            CueSettings.applyPreset(preferences, value);
            recreate();
        });
        presets.addView(button);
    }

    private void selectCurrentPreset() {
        String current = preferences.getString("cue_preset", CueSettings.PRESET_STANDARD);
        for (int index = 0; index < presets.getChildCount(); index++) {
            RadioButton button = (RadioButton) presets.getChildAt(index);
            button.setChecked(current.equals(button.getTag()));
        }
    }

    private void addToggle(LinearLayout content, String label, String key, boolean fallback) {
        CheckBox toggle = new CheckBox(this);
        toggle.setText(label);
        toggle.setChecked(preferences.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            CueSettings.markCustom(preferences);
            selectCurrentPreset();
        });
        content.addView(toggle);
    }
}
