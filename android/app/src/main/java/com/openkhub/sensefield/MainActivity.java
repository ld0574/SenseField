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
public final class MainActivity extends Activity {
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
        UiKit.add(content, UiKit.text(this, "王者荣耀辅助", 32, UiKit.INK, true), 4);
        UiKit.add(content, UiKit.body(this, "关键情况会通过语音与触觉提醒。"), 18);
        LinearLayout statusCard = UiKit.card(this);
        UiKit.add(statusCard, UiKit.heading(this, "运行状态"), 8);
        status = UiKit.text(this, "尚未开始", 24, UiKit.MUTED, true);
        UiKit.add(statusCard, status, 0);
        UiKit.add(content, statusCard, 18);

        LinearLayout actions = UiKit.horizontal(this);
        start = largeButton("开始", true);
        start.setOnClickListener(view -> requestStart());
        stop = largeButton("停止", false);
        stop.setOnClickListener(view -> {
            stop.setEnabled(false);
            Intent intent = new Intent(this, CaptureService.class);
            intent.setAction(CaptureService.ACTION_STOP);
            startService(intent);
            starting = false;
            status.setText("正在停止");
            status.postDelayed(this::refreshStatus, 300);
        });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.rightMargin = UiKit.dp(this, 6);
        actions.addView(start, left);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        right.leftMargin = UiKit.dp(this, 6);
        actions.addView(stop, right);
        UiKit.add(content, actions, 14);
        UiKit.add(content, UiKit.body(this,
                "提醒含义可以在下方设置中重听，授权与声音配置也在设置中。"), 0);
        android.view.View spacer = new android.view.View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 28), 1f));
        Button settings = largeButton("设置", false);
        settings.setOnClickListener(view ->
                startActivity(new Intent(this, AppSettingsActivity.class)));
        UiKit.add(content, settings, 14);
        Button gameSelection = largeButton("返回游戏选择", false);
        gameSelection.setOnClickListener(view -> {
            Intent selection = new Intent(this, GameSelectionActivity.class);
            selection.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(selection);
            finish();
        });
        UiKit.add(content, gameSelection, 0);
        setContentView(scroll);
        refreshStatus();
    }

    private Button largeButton(String label, boolean primary) {
        Button button = UiKit.button(this, label, primary);
        button.setTextSize(26);
        button.setMinHeight(UiKit.dp(this, 84));
        button.setMinimumHeight(UiKit.dp(this, 84));
        return button;
    }

    private void requestStart() {
        if (GameProfile.settings(this).getBoolean(ReminderGuide.PREF_READ_BEFORE_START, true)) {
            startActivityForResult(new Intent(this, ReminderGuideActivity.class)
                    .putExtra(ReminderGuideActivity.EXTRA_START, true), REQUEST_START);
            return;
        }
        boolean ready = CapturePermissionsActivity.requiredSettingsReady(this);
        status.setText(ready ? "等待本次截屏授权" : "请先完成必要授权");
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
            status.setText(preferences.getBoolean("capture_paused", false) ? "已暂停"
                    : preferences.getBoolean("capture_waiting_for_image", false)
                            ? "等待可用画面" : "正在运行");
        } else if (starting) {
            status.setText("正在启动");
        } else {
            String last = preferences.getString("last_capture_status", "");
            status.setText(last.isEmpty() ? "尚未开始" : last);
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
