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

import java.io.IOException;
import java.util.List;

/** Advanced profile and cue tuning for the selected game adapter. */
public final class GameTuningActivity extends UiActivity {
    private static final int REQUEST_OVERLAY = 1004;

    private CheckBox minimapOverlay;
    private RadioGroup presets;
    private boolean awaitingOverlayPermission;
    private Button test;
    private TextView testStatus;
    private CuePlayer testPlayer;
    private CueDispatcher testDispatcher;
    private final Handler testHandler = new Handler(Looper.getMainLooper());
    private int testGeneration;
    private final Handler voiceCheckHandler = new Handler(Looper.getMainLooper());
    private CuePlayer voiceProbe;
    private TextView voiceStatus;

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

        UiKit.pageHeader(this, content, "配置与调参", "王者荣耀辅助");

        LinearLayout voices = UiKit.card(this);
        SettingHelp.addGroup(this, voices, "voice_group", 12);
        Button engines = button("选择语音引擎", false);
        engines.setOnClickListener(view -> chooseVoiceEngine());
        UiKit.add(voices, engines, 8);
        voiceStatus = UiKit.hint(this, "正在检查离线中文语音……");
        voiceStatus.setTag("offline_tts_status");
        voiceStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(voices, voiceStatus, 12);
        Button voiceData = button("管理离线中文语音", false);
        voiceData.setOnClickListener(view -> manageOfflineVoice());
        UiKit.add(voices, voiceData, 16);
        addSpeechRateControl(voices);
        Button sounds = button("提醒音效", false);
        sounds.setOnClickListener(view -> startActivity(new Intent(this, CueSoundSettingsActivity.class)));
        UiKit.add(voices, sounds, 12);
        test = button("测试提醒与振动", false);
        test.setOnClickListener(view -> testCue());
        UiKit.add(voices, test, 6);
        // The result appears right under its button instead of as a paragraph above the group.
        testStatus = UiKit.hint(this, "按当前附近敌人提醒设置测试；结果受设备与系统影响。");
        testStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(voices, testStatus, 16);
        Button hapticSettings = button("震感与节奏", false);
        hapticSettings.setOnClickListener(view ->
                startActivity(new Intent(this, HapticSettingsActivity.class)));
        UiKit.add(voices, hapticSettings, 8);
        CheckBox introduction = checkBox("每次开始前重听完整说明");
        introduction.setChecked(GameProfile.settings(this)
                .getBoolean(ReminderGuide.PREF_REPEAT_BEFORE_START, false));
        introduction.setOnCheckedChangeListener((view, checked) -> GameProfile.settings(this)
                .edit().putBoolean(ReminderGuide.PREF_REPEAT_BEFORE_START, checked).apply());
        UiKit.add(voices, introduction, 8);
        Button guide = button("提醒说明与试听", false);
        guide.setOnClickListener(view ->
                startActivity(new Intent(this, ReminderGuideActivity.class)));
        UiKit.add(voices, guide, 0);
        UiKit.add(content, voices, UiKit.GAP_SECTION);

        LinearLayout recognition = UiKit.card(this);
        SettingHelp.addGroup(this, recognition, "recognition_group", 8);
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

        UiKit.add(content, recognition, UiKit.GAP_SECTION);

        LinearLayout cueCard = UiKit.card(this);
        SettingHelp.addGroup(this, cueCard, "preset_group", 8);
        presets = new RadioGroup(this);
        addPreset("精简", CueSettings.PRESET_COMPACT);
        addPreset("标准", CueSettings.PRESET_STANDARD);
        addPreset("详细", CueSettings.PRESET_DETAILED);
        UiKit.add(cueCard, presets, 12);
        selectCurrentPreset();
        Button alertSettings = button("提示通道与事件", false);
        alertSettings.setOnClickListener(view ->
                startActivity(new Intent(this, AlertSettingsActivity.class)));
        UiKit.add(cueCard, alertSettings, 0);
        UiKit.add(content, cueCard, UiKit.GAP_SECTION);

        LinearLayout tuning = UiKit.card(this);
        SettingHelp.addGroup(this, tuning, "tuning_group", 8);
        addVolumeControl(tuning);
        addIntervalControl(tuning);
        addCenterControl(tuning);
        UiKit.add(content, tuning, UiKit.GAP_SECTION);
        LinearLayout presentation = UiKit.card(this);
        SettingHelp.addGroup(this, presentation, "presentation_group", 8);
        presentationSwitch(presentation, "左右双耳线索（需双耳耳机）", "presentation_spatial");
        presentationSwitch(presentation, "距离强度与节奏震动", "presentation_distance_haptic");
        presentationSwitch(presentation, "两字方位提示", "near_two_word");
        UiKit.add(content, presentation, 0);

        setContentView(scroll);
    }

    private void testCue() {
        if (isLiveSessionRunning()
                || GameProfile.settings(this).getBoolean("capture_active", false)) {
            testStatus.setText("请先停止游戏辅助或实时对局，再测试提醒与振动。");
            return;
        }

        CueSettings settings = new CueSettings(this);
        if (!settings.categoryEnabled(CueRequest.Category.NEAR_ZONE)) {
            testStatus.setText("附近敌人提醒已关闭，请在配置与调参中开启。");
            return;
        }
        int configuredChannels = settings.nearRequestedChannels();
        if (configuredChannels == 0) {
            testStatus.setText("当前提示方案下附近敌人没有开启可用输出通道。");
            return;
        }

        AudioManager audio = getSystemService(AudioManager.class);
        boolean audioAvailable = audio != null
                && NearCueTestPolicy.audioAvailable(
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                        audio.isStreamMute(AudioManager.STREAM_MUSIC),
                        GameProfile.settings(this).getInt("volume", 45));
        int channels = NearCueTestPolicy.channelsForTest(configuredChannels, audioAvailable);
        if (channels == 0) {
            testStatus.setText(audioAvailable
                    ? "当前附近敌人设置没有可测试的声音或振动通道。"
                    : "媒体静音或媒体／应用提示音量为 0，且当前没有开启振动；请调整设置后重试。");
            Log.w("MapAssistAudio", "AudioSelfTest blocked=no_audible_test_channel");
            return;
        }
        boolean audioSkipped = (channels & NearCueTestPolicy.AUDIO_CHANNELS) == 0
                && (configuredChannels & NearCueTestPolicy.AUDIO_CHANNELS) != 0;
        if (audioSkipped) {
            Log.i("MapAssistAudio", "AudioSelfTest audio_skipped=media_muted_or_volume_zero");
        }

        stopTestCue();
        stopVoiceProbe();
        final int generation = testGeneration;
        test.setEnabled(false);
        testStatus.setText((channels & CueRequest.CHANNEL_SPEECH) != 0
                ? "正在准备测试提醒……" : "正在发送测试提醒……");
        testPlayer = new CuePlayer(this, (channels & CueRequest.CHANNEL_SPEECH) != 0);
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
                                "语音引擎未能完成这次测试播放；请检查语音引擎和音量设置。");
                    });
                } else if ("TONE".equals(channel) && "FAILED".equals(result)) {
                    testHandler.post(() -> {
                        if (generation == testGeneration) testStatus.setText(
                                "系统未能完成这次提示音播放；请检查媒体音量和设备设置。");
                    });
                }
            }
        }, SystemClock::elapsedRealtime);
        prepareTestCue(generation, channels, audioSkipped,
                SystemClock.elapsedRealtime() + 3000);
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
                item.setTextSize(18);
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
                    checkOfflineVoice();
                }).setNegativeButton("取消", null).show();
        UiKit.styleDialog(picker, UiKit.ButtonStyle.OUTLINED);
    }

    private void prepareTestCue(int generation, int channels, boolean audioSkipped,
                                long prepareUntilMs) {
        if (generation != testGeneration || testPlayer == null) return;
        long now = SystemClock.elapsedRealtime();
        CueRequest example = new CueRequest("near-cue-test", "near-cue-test:" + now,
                "near-cue-test:" + now, "NEAR_CUE_TEST", CueRequest.Category.NEAR_ZONE,
                NearZoneRouting.NEAR_PRIORITY, now, now + 1200, channels,
                NearZoneRouting.TONE_NEAR, 0, 0, NearZoneRouting.speech(3), 0f);
        if ((channels & CueRequest.CHANNEL_SPEECH) != 0 && !testPlayer.speechReady()
                && !testPlayer.speechPreparationFinished() && now < prepareUntilMs) {
            testHandler.postDelayed(() -> prepareTestCue(generation, channels, audioSkipped,
                    prepareUntilMs), 100);
            return;
        }
        if ((channels & CueRequest.CHANNEL_SPEECH) != 0 && testPlayer.speechReady()
                && !testPlayer.hasPreparedSpeech(example) && now < prepareUntilMs) {
            testHandler.postDelayed(() -> prepareTestCue(generation, channels, audioSkipped,
                    prepareUntilMs), 100);
            return;
        }
        CueDispatcher.DispatchResult result = testDispatcher.submit(example);
        testStatus.setText(testDispatchStatus(result, channels, audioSkipped));
        if ((channels & CueRequest.CHANNEL_SPEECH) != 0 && !testPlayer.speechReady())
            testStatus.append(" " + testPlayer.speechStatusText());
        testHandler.postDelayed(() -> {
            if (generation == testGeneration) test.setEnabled(true);
        }, 4500);
    }

    private static String testDispatchStatus(CueDispatcher.DispatchResult result, int channels,
                                             boolean audioSkipped) {
        int accepted = result.acceptedChannels & channels;
        if (accepted == 0) {
            if ((channels & CueRequest.CHANNEL_HAPTIC) != 0) {
                return "未能播放这次测试，请检查语音或振动设置。";
            }
            return "未能播放声音，请检查语音引擎和媒体音量。";
        }
        StringBuilder status = new StringBuilder();
        if (audioSkipped) status.append("声音通道因媒体静音或音量为 0 已跳过；");
        status.append("已发送").append(channelNames(accepted)).append("测试");
        int notAccepted = channels & ~accepted;
        if (notAccepted != 0) {
            status.append("；未能发送").append(channelNames(notAccepted)).append("测试");
        }
        status.append("。请确认是否能听到声音或感觉到振动。");
        return status.toString();
    }

    private static String channelNames(int channels) {
        StringBuilder names = new StringBuilder();
        appendChannelName(names, channels, CueRequest.CHANNEL_TONE, "提示音");
        appendChannelName(names, channels, CueRequest.CHANNEL_SPEECH, "语音");
        appendChannelName(names, channels, CueRequest.CHANNEL_HAPTIC, "振动");
        return names.toString();
    }

    private static void appendChannelName(StringBuilder names, int channels, int bit,
                                          String name) {
        if ((channels & bit) == 0) return;
        if (names.length() > 0) names.append("、");
        names.append(name);
    }

    private boolean isLiveSessionRunning() {
        return CaptureService.isRunning() || Match3LiveService.isRunning();
    }

    private void stopTestCue() {
        testGeneration++;
        testHandler.removeCallbacksAndMessages(null);
        if (testDispatcher != null) testDispatcher.close();
        if (testPlayer != null) testPlayer.close();
        testDispatcher = null;
        testPlayer = null;
        if (test != null) test.setEnabled(true);
    }

    @Override protected void onPause() {
        stopTestCue();
        stopVoiceProbe();
        super.onPause();
    }

    private void manageOfflineVoice() {
        Intent settings = new Intent("com.android.settings.TTS_SETTINGS");
        try { startActivity(settings); }
        catch (android.content.ActivityNotFoundException unavailable) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    private void checkOfflineVoice() {
        stopVoiceProbe();
        if (isLiveSessionRunning()) {
            voiceStatus.setText("辅助运行中；结束后可检查离线中文语音。");
            return;
        }
        voiceStatus.setText("正在检查离线中文语音……");
        voiceProbe = CuePlayer.speechProbe(this);
        pollOfflineVoice(voiceProbe, SystemClock.elapsedRealtime() + 20_000);
    }

    private void pollOfflineVoice(CuePlayer probe, long deadline) {
        if (voiceProbe != probe) return;
        if (probe.speechPreparationFinished() || SystemClock.elapsedRealtime() >= deadline) {
            voiceStatus.setText(probe.speechPreparationFinished() ? probe.speechStatusText()
                    : "离线中文语音检查超时，请重新进入本页，或管理中文语音数据。");
            stopVoiceProbe();
            return;
        }
        voiceCheckHandler.postDelayed(() -> pollOfflineVoice(probe, deadline), 150);
    }

    private void stopVoiceProbe() {
        voiceCheckHandler.removeCallbacksAndMessages(null);
        if (voiceProbe != null) { voiceProbe.close(); voiceProbe = null; }
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
        UiKit.add(parent, rate, 16);
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (!SettingHelp.close(this)) super.onBackPressed();
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
        return UiKit.button(this, label, primary);
    }

    private void presentationSwitch(LinearLayout parent, String label, String key) {
        CheckBox box = checkBox(label);
        box.setChecked(GameProfile.settings(this).getBoolean(key, false));
        box.setOnCheckedChangeListener((button, checked) ->
                GameProfile.settings(this).edit().putBoolean(key, checked).apply());
        UiKit.add(parent, box, 6);
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
        if (voiceStatus != null) checkOfflineVoice();
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
