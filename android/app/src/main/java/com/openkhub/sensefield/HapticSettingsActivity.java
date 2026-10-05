package com.openkhub.sensefield;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Large-text controls for reminder rhythm and haptic strength. */
public final class HapticSettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private TextView capabilityStatus;
    private TextView testStatus;
    private Button testButton;
    private CuePlayer previewPlayer;
    private Runnable finishPreview;
    private boolean vibratorAvailable;
    private boolean amplitudeControl;
    private boolean capabilitiesReadable;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);
        preferences = GameProfile.settings(this);
        readCapabilities();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);

        UiKit.addBrandHeader(content, "王者荣耀");
        UiKit.add(content, UiKit.text(this, "触觉提醒", 30, UiKit.INK, true), 12);
        UiKit.add(content, UiKit.body(this,
                "调整节奏和震感后，可以试听并比较效果。这些设置会用于所有触觉提醒。"), 16);

        LinearLayout rhythmCard = UiKit.card(this);
        UiKit.add(rhythmCard, UiKit.heading(this, "提醒节奏"), 8);
        UiKit.add(rhythmCard, UiKit.body(this,
                "短促节奏会缩短每次震动和间隔，保留原有次数与顺序。"), 8);
        RadioGroup rhythm = new RadioGroup(this);
        addChoice(rhythm, "保留原有节奏（默认）", "保留各类提醒原有的震动节奏。",
                HapticPolicy.PREF_MODE, HapticPolicy.MODE_ORIGINAL);
        addChoice(rhythm, "短促节奏（保留单／双震）",
                "按原有的单震或双震数量与顺序缩短脉冲和间隔。",
                HapticPolicy.PREF_MODE, HapticPolicy.MODE_SHORT);
        UiKit.add(rhythmCard, rhythm, 0);
        UiKit.add(content, rhythmCard, 14);
        selectSaved(rhythm, HapticPolicy.PREF_MODE, HapticPolicy.MODE_ORIGINAL);

        LinearLayout strengthCard = UiKit.card(this);
        UiKit.add(strengthCard, UiKit.heading(this, "震感档位"), 8);
        RadioGroup strength = new RadioGroup(this);
        addChoice(strength, "设备默认（默认）", "使用设备默认振幅或原有脉冲时长。",
                HapticPolicy.PREF_STRENGTH, HapticPolicy.STRENGTH_SYSTEM);
        addChoice(strength, "较轻", "请求较轻的振幅；不支持可调振幅时缩短脉冲。",
                HapticPolicy.PREF_STRENGTH, HapticPolicy.STRENGTH_LIGHT);
        addChoice(strength, "较强", "请求较强的振幅；不支持可调振幅时延长脉冲。",
                HapticPolicy.PREF_STRENGTH, HapticPolicy.STRENGTH_STRONG);
        UiKit.add(strengthCard, strength, 8);
        capabilityStatus = UiKit.body(this, "正在读取设备振动能力……");
        UiKit.add(strengthCard, capabilityStatus, 0);
        UiKit.add(content, strengthCard, 14);
        selectSaved(strength, HapticPolicy.PREF_STRENGTH, HapticPolicy.STRENGTH_SYSTEM);
        refreshCapabilityText();

        LinearLayout testCard = UiKit.card(this);
        UiKit.add(testCard, UiKit.heading(this, "试听"), 8);
        UiKit.add(testCard, UiKit.body(this,
                "试听会发出一次附近提醒的震动，帮助你比较当前节奏和震感。"), 10);
        testButton = UiKit.button(this, "试听当前触觉设置", true);
        testButton.setContentDescription("发送一次当前节奏与震感档位的触觉试听");
        testButton.setOnClickListener(view -> playPreview());
        UiKit.add(testCard, testButton, 8);
        testStatus = UiKit.body(this, "点击上方按钮，感受当前设置的震动。");
        UiKit.add(testCard, testStatus, 0);
        UiKit.add(content, testCard, 16);

        Button done = UiKit.button(this, "完成", false);
        done.setOnClickListener(view -> finish());
        UiKit.add(content, done, 0);
        setContentView(scroll);
    }

    private void addChoice(RadioGroup group, String label, String explanation,
                           String preferenceKey, String value) {
        RadioButton choice = new RadioButton(this);
        choice.setText(label);
        choice.setTextSize(22);
        choice.setTextColor(UiKit.INK);
        choice.setMinHeight(UiKit.dp(this, 72));
        choice.setContentDescription(label + "。" + explanation);
        UiKit.styleCheckable(choice, this);
        choice.setTag(value);
        choice.setOnClickListener(view -> {
            stopPreview();
            preferences.edit().putString(preferenceKey, value).apply();
        });
        group.addView(choice, new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
    }

    private void selectSaved(RadioGroup group, String key, String fallback) {
        String selected = preferences.getString(key, fallback);
        boolean matched = false;
        for (int index = 0; index < group.getChildCount(); index++) {
            RadioButton choice = (RadioButton) group.getChildAt(index);
            if (selected.equals(choice.getTag())) {
                choice.setChecked(true);
                matched = true;
            }
        }
        if (!matched) {
            for (int index = 0; index < group.getChildCount(); index++) {
                RadioButton choice = (RadioButton) group.getChildAt(index);
                if (fallback.equals(choice.getTag())) choice.setChecked(true);
            }
        }
    }

    private void readCapabilities() {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = getSystemService(VibratorManager.class);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = getSystemService(Vibrator.class);
            }
            vibratorAvailable = vibrator != null && vibrator.hasVibrator();
            amplitudeControl = vibratorAvailable && vibrator.hasAmplitudeControl();
            capabilitiesReadable = true;
        } catch (RuntimeException error) {
            vibratorAvailable = false;
            amplitudeControl = false;
            capabilitiesReadable = false;
        }
    }

    private void refreshCapabilityText() {
        if (capabilityStatus == null) return;
        if (!capabilitiesReadable) {
            capabilityStatus.setText("无法读取设备振动能力。试听结果可能无法判断震动是否受理。");
        } else if (!vibratorAvailable) {
            capabilityStatus.setText("设备未报告可用振动器，试听可能无法发出震动。震感档位在此设备上无法确认。 ");
        } else if (amplitudeControl) {
            capabilityStatus.setText("设备报告支持振动和可调振幅。“较轻／较强”会请求不同振幅；实际感受因设备而异。");
        } else {
            capabilityStatus.setText("设备报告支持振动，但不支持可调振幅。“较轻／较强”通过缩短或延长脉冲时长调整，实际感受因设备而异。");
        }
    }

    private void playPreview() {
        if (CaptureService.isRunning() || Match3LiveService.isRunning()) {
            testStatus.setText("辅助或实时对局正在运行，请结束后再试听。");
            return;
        }
        stopPreview();
        long now = SystemClock.elapsedRealtime();
        CueRequest request = new CueRequest("haptic-settings-preview",
                "haptic-settings-preview:" + now, "haptic-settings-preview:" + now,
                "HAPTIC_PREVIEW", CueRequest.Category.NEAR_ZONE,
                NearZoneRouting.NEAR_PRIORITY, now, now + 2000,
                CueRequest.CHANNEL_HAPTIC, 0, 0, 0, null,
                0.08f, 1f, -1, () -> true);
        try {
            previewPlayer = new CuePlayer(this);
            long durationMs = previewPlayer.hapticDurationMs(request);
            boolean accepted = previewPlayer.vibrate(request);
            if (!accepted) {
                previewPlayer.close();
                previewPlayer = null;
                testStatus.setText(!capabilitiesReadable
                        ? "无法读取设备振动能力，未能完成试听。"
                        : vibratorAvailable
                        ? "系统未能受理这次触觉请求。请检查设备设置后重试。"
                        : "设备未报告可用振动器，无法完成试听。");
                return;
            }
            testStatus.setText("已发送试听震动。若没有感觉到，请检查手机的振动设置。");
            testButton.setEnabled(false);
            CuePlayer player = previewPlayer;
            finishPreview = () -> {
                if (previewPlayer != player) return;
                player.cancelHaptics();
                player.close();
                previewPlayer = null;
                finishPreview = null;
                testButton.setEnabled(true);
            };
            handler.postDelayed(finishPreview, Math.max(250, durationMs + 150));
        } catch (RuntimeException error) {
            stopPreview();
            testStatus.setText("无法创建触觉试听，请稍后重试。");
        }
    }

    private void stopPreview() {
        if (finishPreview != null) handler.removeCallbacks(finishPreview);
        finishPreview = null;
        if (previewPlayer != null) {
            previewPlayer.cancelHaptics();
            previewPlayer.close();
            previewPlayer = null;
        }
        if (testButton != null) testButton.setEnabled(true);
    }

    @Override protected void onPause() {
        stopPreview();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        readCapabilities();
        refreshCapabilityText();
    }
}
