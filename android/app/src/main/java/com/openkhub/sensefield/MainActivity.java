package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Run screen; all grants and configuration live in the settings menu. */
public final class MainActivity extends UiActivity {
    private static final int REQUEST_START = 1001;
    private TextView status;
    private Button start;
    private Button stop;
    private boolean starting;
    private final Runnable statusUpdater = new Runnable() {
        @Override public void run() {
            refreshStatus();
            if (status != null) status.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        UiKit.configureWindow(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);

        UiKit.addBrandHeader(content, "王者荣耀");
        UiKit.add(content, UiKit.pageTitle(this, "王者荣耀辅助"), 8);
        UiKit.add(content, UiKit.body(this, "关键情况会通过语音与触觉提醒。"), 24);
        LinearLayout statusCard = UiKit.accentCard(this);
        UiKit.addSignatureLabel(statusCard, "运行状态");
        status = UiKit.text(this, "尚未开始", 24, UiKit.INK, true);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(statusCard, status, 0);
        UiKit.add(content, statusCard, 24);

        // Start and stop sit far enough apart that a slip cannot hit the other one.
        ReminderSampleGrid actions = new ReminderSampleGrid(this, 2, false, 20).fillRow();
        start = largeButton("开始辅助", true);
        start.setOnClickListener(view -> requestStart());
        stop = largeButton("停止", false);
        stop.setOnClickListener(view -> {
            stop.setEnabled(false);
            Intent intent = new Intent(this, CaptureService.class);
            intent.setAction(CaptureService.ACTION_STOP);
            startService(intent);
            starting = false;
            UiKit.setTextIfChanged(status, "正在停止");
            status.postDelayed(this::refreshStatus, 300);
        });
        actions.addView(start);
        actions.addView(stop);
        UiKit.add(content, actions, 16);
        UiKit.add(content, UiKit.hint(this, "提醒含义、授权和声音都在“设置”里。"), 0);
        android.view.View spacer = new android.view.View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 32), 1f));
        UiKit.add(content, UiKit.navigationRow(this, "设置", "声音、提醒与运行授权", () ->
                startActivity(new Intent(this, AppSettingsActivity.class))), 12);
        UiKit.add(content, UiKit.navigationRow(this, "返回游戏选择", "切换游戏辅助", () -> {
            Intent selection = new Intent(this, GameSelectionActivity.class);
            selection.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(selection);
            finish();
        }), 0);
        setContentView(scroll);
        refreshStatus();
    }

    private Button largeButton(String label, boolean primary) {
        Button button = UiKit.button(this, label, primary);
        button.setMinHeight(UiKit.dp(this, 64));
        button.setMinimumHeight(UiKit.dp(this, 64));
        return button;
    }

    private void requestStart() {
        android.content.SharedPreferences preferences = GameProfile.settings(this);
        if (!preferences.getBoolean(ReminderGuide.PREF_FULL_GUIDE_COMPLETED, false)
                || preferences.getBoolean(ReminderGuide.PREF_REPEAT_BEFORE_START, false)) {
            startActivityForResult(new Intent(this, ReminderGuideActivity.class)
                    .putExtra(ReminderGuideActivity.EXTRA_START, true)
                    .putExtra(ReminderGuideActivity.EXTRA_FULL, true)
                    .putExtra(ReminderGuideActivity.EXTRA_AUTO_READ, true), REQUEST_START);
            return;
        }
        boolean ready = CapturePermissionsActivity.requiredSettingsReady(this);
        UiKit.setTextIfChanged(status, ready ? "等待本次截屏授权" : "请先完成必要授权");
        Intent authorization = new Intent(this, CapturePermissionsActivity.class);
        authorization.putExtra(CapturePermissionsActivity.EXTRA_START, true);
        startActivityForResult(authorization, REQUEST_START);
    }

    private void refreshStatus() {
        if (status == null) return;
        android.content.SharedPreferences preferences = GameProfile.settings(this);
        boolean active = preferences.getBoolean("capture_active", false)
                && CaptureService.isRunning();
        if (!active && !CaptureService.isRunning()
                && preferences.getBoolean("capture_active", false)) {
            preferences.edit().putBoolean("capture_active", false)
                    .putBoolean("capture_paused", false)
                    .putString("last_capture_status", "辅助已结束，请重新开始").apply();
        }
        if (active) {
            UiKit.setTextIfChanged(status, preferences.getBoolean("capture_paused", false) ? "已暂停"
                    : preferences.getBoolean("capture_waiting_for_image", false)
                            ? "等待可用画面" : "正在运行");
        } else if (starting) {
            UiKit.setTextIfChanged(status, "正在启动");
        } else {
            String last = preferences.getString("last_capture_status", "");
            UiKit.setTextIfChanged(status, last.isEmpty() ? "尚未开始" : last);
        }
        start.setEnabled(!active && !starting);
        stop.setEnabled(active || starting);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        status.removeCallbacks(statusUpdater);
        status.postDelayed(statusUpdater, 500);
    }

    @Override protected void onPause() {
        status.removeCallbacks(statusUpdater);
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_START) return;
        if (resultCode == RESULT_OK) {
            starting = true;
            refreshStatus();
            status.postDelayed(() -> {
                starting = false;
                refreshStatus();
            }, 1200);
        } else refreshStatus();
    }
}
