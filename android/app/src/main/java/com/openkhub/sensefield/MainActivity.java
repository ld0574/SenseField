package com.openkhub.sensefield;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;

/** Compact run screen for the Honor of Kings adapter. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PROJECTION = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1003;

    private TextView status;
    private Button pause;
    private Button stop;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);

        UiKit.addBrandHeader(content, "王者荣耀");
        UiKit.add(content, UiKit.text(this, "王者荣耀辅助", 28, UiKit.INK, true), 4);
        UiKit.add(content, UiKit.body(this, "关键情况会通过声音与触觉提醒。"), 18);

        LinearLayout statusCard = UiKit.card(this);
        UiKit.add(statusCard, UiKit.heading(this, "运行状态"), 8);
        status = UiKit.text(this, "尚未开始", 20, UiKit.MUTED, true);
        UiKit.add(statusCard, status, 0);
        UiKit.add(content, statusCard, 14);

        LinearLayout quickStart = UiKit.accentCard(this);
        UiKit.add(quickStart, UiKit.text(this, "快速开始", 17, UiKit.INK, true), 4);
        UiKit.add(quickStart, UiKit.body(this, "1. 点击授权按钮，选择共享整个屏幕"), 2);
        UiKit.add(quickStart, UiKit.body(this, "2. 返回游戏，听取声音与触觉提示"), 0);
        UiKit.add(content, quickStart, 18);

        Button start = largeButton("授权截屏并开始", true);
        start.setOnClickListener(view -> requestCapture());
        UiKit.add(content, start, 14);

        LinearLayout sessionActions = UiKit.horizontal(this);
        pause = largeButton("暂停", false);
        pause.setOnClickListener(view -> {
            sendServiceAction(CaptureService.ACTION_TOGGLE_PAUSE);
            status.postDelayed(this::refreshStatus, 300);
        });
        stop = largeButton("停止", false);
        stop.setOnClickListener(view -> {
            sendServiceAction(CaptureService.ACTION_STOP);
            status.postDelayed(this::refreshStatus, 300);
        });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        actionParams.rightMargin = UiKit.dp(this, 6);
        sessionActions.addView(pause, actionParams);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        stopParams.leftMargin = UiKit.dp(this, 6);
        sessionActions.addView(stop, stopParams);
        UiKit.add(content, sessionActions, 16);

        Button tuning = largeButton("配置与调参", false);
        tuning.setOnClickListener(view ->
                startActivity(new Intent(this, GameTuningActivity.class)));
        UiKit.add(content, tuning, 0);

        UiKit.gap(content, 28);
        Button gameSelection = largeButton("返回游戏选择", false);
        gameSelection.setOnClickListener(view -> returnToGameSelection());
        UiKit.add(content, gameSelection, 0);

        setContentView(scroll);
        refreshStatus();
    }

    private Button largeButton(String label, boolean primary) {
        Button button = UiKit.button(this, label, primary);
        button.setTextSize(22);
        int height = UiKit.dp(this, 68);
        button.setMinHeight(height);
        button.setMinimumHeight(height);
        return button;
    }

    private void refreshStatus() {
        if (status == null) return;
        android.content.SharedPreferences preferences = GameProfile.settings(this);
        boolean active = preferences.getBoolean("capture_active", false);
        boolean paused = preferences.getBoolean("capture_paused", false);
        if (active) {
            status.setText(paused ? "已暂停" : "正在运行");
            pause.setText(paused ? "继续" : "暂停");
        } else {
            String lastStatus = preferences.getString("last_capture_status", "");
            status.setText(lastStatus.startsWith("无法开始") ? "未能启动"
                    : lastStatus.isEmpty() ? "尚未开始" : "已停止");
            pause.setText("暂停");
        }
        pause.setEnabled(active);
        stop.setEnabled(active);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void requestCapture() {
        try {
            GameProfile.load(this);
        } catch (IOException | JSONException error) {
            toast("配置无效");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            toast("请先允许通知，再重新点击开始");
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, CaptureService.class);
        intent.setAction(action);
        startService(intent);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            toast("未授权截屏");
            return;
        }
        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_START);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
        status.setText("正在启动");
        status.postDelayed(this::refreshStatus, 1200);
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
}
