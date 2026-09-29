package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Advanced profile and cue tuning for the selected game adapter. */
public final class GameTuningActivity extends Activity {
    private static final int REQUEST_IMPORT = 1002;
    private static final int REQUEST_OVERLAY = 1004;

    private CheckBox minimapOverlay;
    private RadioGroup presets;
    private boolean awaitingOverlayPermission;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);
        new CueSettings(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);

        UiKit.addBrandHeader(content, "王者荣耀");
        UiKit.add(content, UiKit.text(this, "配置与调参", 28, UiKit.INK, true), 20);

        LinearLayout profileCard = UiKit.card(this);
        UiKit.add(profileCard, UiKit.heading(this, "标定配置"), 12);
        Button importProfile = button("导入配置文件", false);
        importProfile.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent, REQUEST_IMPORT);
        });
        UiKit.add(profileCard, importProfile, 0);
        UiKit.add(content, profileCard, 14);

        LinearLayout recognition = UiKit.card(this);
        UiKit.add(recognition, UiKit.heading(this, "识别功能"), 8);
        CheckBox experimental = checkBox("启用小地图识别（实验）");
        experimental.setChecked(GameProfile.settings(this)
                .getBoolean(GameProfile.PREF_ALLOW_EXPERIMENTAL,
                        GameProfile.DEFAULT_ALLOW_EXPERIMENTAL));
        experimental.setOnCheckedChangeListener((button, checked) ->
                GameProfile.settings(this).edit()
                        .putBoolean(GameProfile.PREF_ALLOW_EXPERIMENTAL, checked).apply());
        UiKit.add(recognition, experimental, 4);

        minimapOverlay = checkBox("提醒新出现的敌方头像");
        minimapOverlay.setChecked(GameProfile.settings(this)
                .getBoolean(GameProfile.PREF_VISION_MEMORY,
                        GameProfile.DEFAULT_VISION_MEMORY));
        minimapOverlay.setOnClickListener(view -> {
            boolean checked = minimapOverlay.isChecked();
            GameProfile.settings(this).edit()
                    .putBoolean(GameProfile.PREF_VISION_MEMORY, checked).apply();
            if (checked && !Settings.canDrawOverlays(this)) {
                awaitingOverlayPermission = true;
                Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(permission, REQUEST_OVERLAY);
            }
        });
        UiKit.add(recognition, minimapOverlay, 0);
        UiKit.add(content, recognition, 14);

        LinearLayout cueCard = UiKit.card(this);
        UiKit.add(cueCard, UiKit.heading(this, "提示方案"), 8);
        presets = new RadioGroup(this);
        addPreset("精简", CueSettings.PRESET_COMPACT);
        addPreset("标准", CueSettings.PRESET_STANDARD);
        addPreset("详细", CueSettings.PRESET_DETAILED);
        UiKit.add(cueCard, presets, 10);
        selectCurrentPreset();
        Button alertSettings = button("提示通道与事件", false);
        alertSettings.setOnClickListener(view ->
                startActivity(new Intent(this, AlertSettingsActivity.class)));
        UiKit.add(cueCard, alertSettings, 0);
        UiKit.add(content, cueCard, 14);

        LinearLayout tuning = UiKit.card(this);
        UiKit.add(tuning, UiKit.heading(this, "提示调节"), 10);
        addVolumeControl(tuning);
        addIntervalControl(tuning);
        addCenterControl(tuning);
        UiKit.add(content, tuning, 18);

        Button gameSelection = button("返回游戏选择", false);
        gameSelection.setOnClickListener(view -> returnToGameSelection());
        UiKit.add(content, gameSelection, 0);

        setContentView(scroll);
    }

    private CheckBox checkBox(String label) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTextSize(18);
        checkBox.setTextColor(UiKit.INK);
        UiKit.styleCheckable(checkBox, this);
        return checkBox;
    }

    private Button button(String label, boolean primary) {
        Button button = UiKit.button(this, label, primary);
        button.setTextSize(18);
        button.setMinHeight(UiKit.dp(this, 60));
        button.setMinimumHeight(UiKit.dp(this, 60));
        return button;
    }

    private void addPreset(String label, String value) {
        RadioButton button = new RadioButton(this);
        button.setText(label);
        button.setTextSize(18);
        button.setTextColor(UiKit.INK);
        UiKit.styleCheckable(button, this);
        button.setTag(value);
        button.setOnClickListener(view -> {
            CueSettings.applyPreset(GameProfile.settings(this), value);
            selectCurrentPreset();
        });
        presets.addView(button);
    }

    private void selectCurrentPreset() {
        String current = GameProfile.settings(this)
                .getString("cue_preset", CueSettings.PRESET_STANDARD);
        for (int index = 0; index < presets.getChildCount(); index++) {
            RadioButton button = (RadioButton) presets.getChildAt(index);
            button.setChecked(current.equals(button.getTag()));
        }
    }

    private void addVolumeControl(LinearLayout parent) {
        TextView label = settingLabel("提示音量");
        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(GameProfile.settings(this).getInt("volume", 45));
        label.setText(getString(R.string.tuning_volume_value, volume.getProgress()));
        UiKit.styleSeekBar(volume, this);
        volume.setOnSeekBarChangeListener(new SimpleSeekListener(value -> {
            GameProfile.settings(this).edit().putInt("volume", value).apply();
            label.setText(getString(R.string.tuning_volume_value, value));
        }));
        UiKit.add(parent, label, 0);
        UiKit.add(parent, volume, 16);
    }

    private void addIntervalControl(LinearLayout parent) {
        TextView label = settingLabel("最短提示间隔");
        SeekBar interval = new SeekBar(this);
        interval.setMax(5);
        int savedInterval = GameProfile.settings(this).getInt("cue_gap_ms", 1000);
        interval.setProgress(Math.max(0, Math.min(5, (savedInterval - 500) / 500)));
        label.setText(getString(R.string.tuning_interval_value,
                (500 + interval.getProgress() * 500) / 1000f));
        UiKit.styleSeekBar(interval, this);
        interval.setOnSeekBarChangeListener(new SimpleSeekListener(value -> {
            int milliseconds = 500 + value * 500;
            GameProfile.settings(this).edit().putInt("cue_gap_ms", milliseconds).apply();
            label.setText(getString(R.string.tuning_interval_value, milliseconds / 1000f));
        }));
        UiKit.add(parent, label, 0);
        UiKit.add(parent, interval, 16);
    }

    private void addCenterControl(LinearLayout parent) {
        TextView label = settingLabel("中央免提示区");
        SeekBar center = new SeekBar(this);
        center.setMax(50);
        center.setProgress(Math.max(0, Math.min(50,
                GameProfile.settings(this).getInt("center_percent", profileCenterPercent()) - 10)));
        label.setText(getString(R.string.tuning_center_value, center.getProgress() + 10));
        UiKit.styleSeekBar(center, this);
        center.setOnSeekBarChangeListener(new SimpleSeekListener(value -> {
            int percent = value + 10;
            GameProfile.settings(this).edit().putInt("center_percent", percent).apply();
            label.setText(getString(R.string.tuning_center_value, percent));
        }));
        UiKit.add(parent, label, 0);
        UiKit.add(parent, center, 0);
    }

    private TextView settingLabel(String label) {
        return UiKit.text(this, label, 18, UiKit.INK, true);
    }

    private int profileCenterPercent() {
        try {
            return Math.round(GameProfile.load(this).rois[10] * 100);
        } catch (IOException | JSONException error) {
            return 30;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false;
            boolean optedIn = GameProfile.settings(this).getBoolean(
                    GameProfile.PREF_VISION_MEMORY, GameProfile.DEFAULT_VISION_MEMORY);
            if (minimapOverlay != null) minimapOverlay.setChecked(optedIn);
            if (!Settings.canDrawOverlays(this))
                toast("未开启悬浮层权限，语音和触觉仍可使用");
        }
        if (presets != null) selectCurrentPreset();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null) {
            importProfile(data.getData());
        }
    }

    private void importProfile(Uri uri) {
        if (uri == null) return;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法打开文件");
            String text = GameProfile.readText(input);
            if (text.length() > 2_000_000) throw new IOException("配置文件过大");
            GameProfile.parse(text, GameProfile.settings(this));
            File destination = new File(getFilesDir(), GameProfile.IMPORTED_FILE);
            File temporary = new File(getFilesDir(), "game_profile.tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!temporary.renameTo(destination)) throw new IOException("无法保存配置");
            toast("配置已导入");
        } catch (IOException | JSONException error) {
            toast("导入失败：" + error.getMessage());
        }
    }

    private void returnToGameSelection() {
        Intent selection = new Intent(this, GameSelectionActivity.class);
        selection.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(selection);
        finish();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private interface OnProgress { void changed(int value); }

    private static final class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        private final OnProgress callback;
        SimpleSeekListener(OnProgress callback) { this.callback = callback; }
        @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser) callback.changed(progress);
        }
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    }
}
