package com.openkhub.sensefield;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
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
import java.util.List;

/** Advanced profile and cue tuning for the selected game adapter. */
public final class GameTuningActivity extends Activity {
    private static final int REQUEST_IMPORT = 1002;
    private static final int REQUEST_OVERLAY = 1004;

    private CheckBox minimapOverlay;
    private RadioGroup presets;
    private boolean awaitingOverlayPermission;
    private Button test;
    private Button hapticTest;
    private TextView testStatus;
    private CuePlayer testPlayer;
    private CueDispatcher testDispatcher;
    private final Handler testHandler = new Handler(Looper.getMainLooper());
    private int testGeneration;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
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

        LinearLayout voices = UiKit.card(this);
        UiKit.add(voices, UiKit.heading(this, "提醒测试与语音"), 10);
        testStatus = UiKit.body(this, "调高媒体音量，标准模式直接播报方位。");
        UiKit.add(voices, testStatus, 10);
        Button engines = button("选择语音引擎", false);
        engines.setOnClickListener(view -> chooseVoiceEngine());
        UiKit.add(voices, engines, 12);
        addSpeechRateControl(voices);
        test = button("测试提醒", false);
        test.setOnClickListener(view -> testCue());
        UiKit.add(voices, test, 0);
        hapticTest = button("测试震动", false);
        hapticTest.setOnClickListener(view -> testHaptic());
        UiKit.add(voices, hapticTest, 10);
        UiKit.add(content, voices, 14);

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
        UiKit.add(recognition, minimapOverlay, 4);

        CheckBox baseline = checkBox("使用单类基线模型（关闭附近敌人提醒）");
        baseline.setChecked(GameProfile.settings(this)
                .getBoolean(GameProfile.PREF_BASELINE_MODEL, false));
        baseline.setOnCheckedChangeListener((button, checked) ->
                GameProfile.settings(this).edit()
                        .putBoolean(GameProfile.PREF_BASELINE_MODEL, checked).apply());
        UiKit.add(recognition, baseline, 0);
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

        Button back = button("返回", false);
        back.setOnClickListener(view -> finish());
        UiKit.add(content, back, 0);

        setContentView(scroll);
    }

    private void testCue() {
        if (GameProfile.settings(this).getBoolean("capture_active", false)) {
            toast("请先停止辅助，再测试提醒");
            return;
        }
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                || audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            testStatus.setText("媒体音量为 0 或已静音。请按音量＋调高，再点击测试提醒。");
            Log.w("MapAssistAudio", "AudioSelfTest blocked=media_muted");
            return;
        }
        if (GameProfile.settings(this).getInt("volume", 45) == 0) {
            testStatus.setText("应用提示音量为 0，请在配置与调参中调高。");
            return;
        }
        CueSettings settings = new CueSettings(this);
        int channels = settings.nearRequestedChannels();
        if (!settings.categoryEnabled(CueRequest.Category.NEAR_ZONE) || channels == 0) {
            testStatus.setText("附近敌人提醒已关闭，请在配置与调参中开启。");
            return;
        }
        stopTestCue();
        final int generation = testGeneration;
        test.setEnabled(false);
        testStatus.setText("正在准备测试提醒……");
        testPlayer = new CuePlayer(this);
        testDispatcher = new CueDispatcher(testPlayer, settings, new CueDispatcher.Listener() {
            @Override public void onDispatch(CueRequest request,
                    CueDispatcher.DispatchResult result) {
                Log.i("MapAssistAudio", "AudioSelfTest dispatch=" + result.outcome
                        + " acceptedMask=" + result.acceptedChannels + " reason=" + result.reason);
            }
            @Override public void onPlayback(CueRequest request, String channel,
                    long atMs, String result) {
                Log.i("MapAssistAudio", "AudioSelfTest channel=" + channel + " result=" + result);
                if ("SPEECH".equals(channel) && ("FAILED".equals(result)
                        || "UNAVAILABLE".equals(result))) {
                    testHandler.post(() -> {
                        if (generation == testGeneration) testStatus.setText(
                                "语音未能正常播放，请选择其他语音引擎，再测试提醒。");
                    });
                }
            }
        }, SystemClock::elapsedRealtime);
        prepareTestCue(generation, channels, SystemClock.elapsedRealtime() + 3000);
    }

    private void chooseVoiceEngine() {
        List<ResolveInfo> engines = getPackageManager().queryIntentServices(
                new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
        String[] labels = new String[engines.size() + 1];
        String[] packages = new String[labels.length];
        labels[0] = "跟随手机系统";
        packages[0] = "";
        String selected = GameProfile.settings(this).getString(CuePlayer.PREF_TTS_ENGINE, "");
        int current = 0;
        for (int i = 0; i < engines.size(); i++) {
            ResolveInfo engine = engines.get(i);
            labels[i + 1] = engine.serviceInfo.applicationInfo.loadLabel(getPackageManager()).toString();
            packages[i + 1] = engine.serviceInfo.packageName;
            if (packages[i + 1].equals(selected)) current = i + 1;
        }
        ArrayAdapter<String> choices = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, convertView, parent);
                item.setTextSize(22);
                item.setMinimumHeight(UiKit.dp(GameTuningActivity.this, 72));
                return item;
            }
        };
        AlertDialog picker = new AlertDialog.Builder(this).setTitle("听野使用的语音引擎")
                .setSingleChoiceItems(choices, current, (dialog, which) -> {
                    stopTestCue();
                    GameProfile.settings(this).edit()
                            .putString(CuePlayer.PREF_TTS_ENGINE, packages[which]).apply();
                    testStatus.setText(getString(R.string.tuning_voice_engine_selected, labels[which]));
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
        picker.getButton(AlertDialog.BUTTON_NEGATIVE).setTextSize(22);
    }

    private void testHaptic() {
        if (CaptureService.isRunning()) {
            testStatus.setText("请先停止辅助，再测试震动。");
            return;
        }
        CueSettings settings = new CueSettings(this);
        if (!settings.nearHapticEnabled()
                || (settings.enabledChannels() & CueRequest.CHANNEL_HAPTIC) == 0) {
            testStatus.setText("请先在提示通道与事件中开启触觉和附近敌人震动。");
            return;
        }
        stopTestCue();
        final int generation = testGeneration;
        hapticTest.setEnabled(false);
        testPlayer = new CuePlayer(this);
        long now = SystemClock.elapsedRealtime();
        boolean requested = testPlayer.vibrate(new CueRequest("haptic-test",
                "haptic-test:" + now, "haptic-test:" + now, "HAPTIC_TEST",
                CueRequest.Category.NEAR_ZONE, NearZoneRouting.NEAR_PRIORITY,
                now, now + 1200, CueRequest.CHANNEL_HAPTIC, 0, 0, 0, null));
        testStatus.setText(requested ? "已请求两次短震动，请确认能否感觉到。"
                : "未能请求震动，请检查手机是否支持震动。");
        testHandler.postDelayed(() -> {
            if (generation == testGeneration) hapticTest.setEnabled(true);
        }, 600);
    }

    private void prepareTestCue(int generation, int channels, long prepareUntilMs) {
        if (generation != testGeneration || testPlayer == null) return;
        long now = SystemClock.elapsedRealtime();
        if ((channels & CueRequest.CHANNEL_SPEECH) != 0 && !testPlayer.speechReady()
                && now < prepareUntilMs) {
            testHandler.postDelayed(() -> prepareTestCue(generation, channels, prepareUntilMs), 100);
            return;
        }
        testStatus.setText((channels & CueRequest.CHANNEL_SPEECH) != 0
                ? "请确认能否听到“上方有敌人”。" : "请确认能否听到提示音。");
        testDispatcher.submit(new CueRequest("audio-test", "audio-test:" + now,
                "audio-test:" + now, "AUDIO_TEST", CueRequest.Category.NEAR_ZONE, 80,
                now, now + 1200, channels, NearZoneRouting.TONE_NEAR, 0, 0,
                NearZoneRouting.speech(3), 0f));
        testHandler.postDelayed(() -> {
            if (generation == testGeneration) test.setEnabled(true);
        }, 4500);
    }

    private void stopTestCue() {
        testGeneration++;
        testHandler.removeCallbacksAndMessages(null);
        if (testDispatcher != null) testDispatcher.close();
        if (testPlayer != null) testPlayer.close();
        testDispatcher = null;
        testPlayer = null;
        if (test != null) test.setEnabled(true);
        if (hapticTest != null) hapticTest.setEnabled(true);
    }

    @Override protected void onPause() {
        stopTestCue();
        super.onPause();
    }

    private void addSpeechRateControl(LinearLayout parent) {
        TextView label = settingLabel("语速");
        SeekBar rate = new SeekBar(this);
        rate.setMax(16);
        int saved = GameProfile.settings(this).getInt(CuePlayer.PREF_TTS_RATE,
                CuePlayer.DEFAULT_TTS_RATE_PERCENT);
        rate.setProgress(Math.max(0, Math.min(16, (saved - 80) / 10)));
        label.setText(getString(R.string.tuning_speech_rate_value,
                (80 + rate.getProgress() * 10) / 100f));
        UiKit.styleSeekBar(rate, this);
        rate.setOnSeekBarChangeListener(new SimpleSeekListener(value -> {
            int percent = 80 + value * 10;
            GameProfile.settings(this).edit().putInt(CuePlayer.PREF_TTS_RATE, percent).apply();
            label.setText(getString(R.string.tuning_speech_rate_value, percent / 100f));
        }));
        UiKit.add(parent, label, 0);
        UiKit.add(parent, rate, 12);
    }

    private CheckBox checkBox(String label) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTextSize(22);
        checkBox.setTextColor(UiKit.INK);
        UiKit.styleCheckable(checkBox, this);
        return checkBox;
    }

    private Button button(String label, boolean primary) {
        Button button = UiKit.button(this, label, primary);
        button.setTextSize(22);
        button.setMinHeight(UiKit.dp(this, 72));
        button.setMinimumHeight(UiKit.dp(this, 72));
        return button;
    }

    private void addPreset(String label, String value) {
        RadioButton button = new RadioButton(this);
        button.setText(label);
        button.setTextSize(22);
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
        return UiKit.text(this, label, 22, UiKit.INK, true);
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
