package com.openkhub.sensefield;

import android.app.AlertDialog;
import android.content.Intent;
import android.media.AudioManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

/** Run screen. The live service confirms the board automatically; tools are optional. */
public final class Match3AssistActivity extends UiActivity {
    private static final int REQ_PROJECTION = 2002;
    private static final int REQ_OVERLAY = 2003;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button start, stop;
    private boolean projectionAfterOverlay, authorizationPending, overlayExplanationOpen, resumed;
    private AlertDialog overlayExplanation;
    private String pendingStartId;
    private long startDeadlineMs;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed || isFinishing() || isDestroyed()) return;
            checkStart();
            refreshStatus();
            handler.postDelayed(this, pendingStartId == null ? 500 : 100);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (state != null) {
            projectionAfterOverlay = state.getBoolean("projection_after_overlay");
            authorizationPending = state.getBoolean("authorization_pending");
            overlayExplanationOpen = state.getBoolean("overlay_explanation_open");
            pendingStartId = state.getString("pending_start_id");
            startDeadlineMs = state.getLong("start_deadline_ms");
        }
        UiKit.configureWindow(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page);
        UiKit.addBrandHeader(page, "开心消消乐");
        UiKit.add(page, UiKit.pageTitle(this, "开心消消乐辅助"), 8);
        UiKit.add(page, UiKit.body(this, "识别棋盘，用语音和高亮提示交换。"), 24);
        LinearLayout stateCard = UiKit.accentCard(this);
        UiKit.addSignatureLabel(stateCard, "运行状态 · 体验版");
        status = UiKit.text(this, "尚未开始", 24, UiKit.INK, true);
        status.setTag("match3_run_status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(stateCard, status, 0);
        UiKit.add(page, stateCard, 24);
        ReminderSampleGrid actions = new ReminderSampleGrid(this, 2, false, 20).fillRow();
        start = UiKit.button(this, "开始辅助", true);
        start.setTag("match3_start");
        start.setMinHeight(UiKit.dp(this, 64));
        start.setOnClickListener(v -> startLive());
        stop = UiKit.button(this, "停止", false);
        stop.setMinHeight(UiKit.dp(this, 64));
        stop.setOnClickListener(v -> {
            pendingStartId = null;
            authorizationPending = projectionAfterOverlay = false;
            startService(new Intent(this, Match3LiveService.class).setAction(Match3LiveService.ACTION_STOP));
            UiKit.setTextIfChanged(status, "正在停止");
            stop.setEnabled(false);
        });
        actions.addView(start); actions.addView(stop);
        UiKit.add(page, actions, 16);
        UiKit.add(page, UiKit.hint(this, "授权后自动打开游戏，棋盘无需手动标定。"), 24);
        View spacer = new View(this);
        page.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 32), 1f));
        UiKit.add(page, UiKit.navigationRow(this, "设置", "语音、交换高亮与识别工具", () ->
                startActivity(new Intent(this, Match3SettingsActivity.class))), 12);
        UiKit.add(page, UiKit.navigationRow(this, "返回游戏选择", "切换游戏辅助", () -> {
            startActivity(new Intent(this, GameSelectionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        }), 0);
        setContentView(scroll);
        refreshStatus();
    }

    private void startLive() {
        if (authorizationPending || pendingStartId != null || Match3LiveService.isRunning()) return;
        if (CaptureService.isRunning()) {
            toast("请先停止王者荣耀辅助，再开始消消乐识别"); return;
        }
        authorizationPending = true;
        refreshStatus();
        if (GameProfile.settings(this).getBoolean("match3_hint_highlight_enabled", true)
                && !Settings.canDrawOverlays(this)
                && !GameProfile.settings(this).getBoolean("match3_overlay_permission_explained", false)) {
            GameProfile.settings(this).edit().putBoolean("match3_overlay_permission_explained", true).apply();
            showOverlayExplanation();
        } else requestProjection();
    }

    private void showOverlayExplanation() {
        if (overlayExplanation != null && overlayExplanation.isShowing()) return;
        overlayExplanationOpen = true;
        overlayExplanation = new AlertDialog.Builder(this).setTitle("显示交换位置")
                .setMessage("允许听野悬浮显示，就能框出要交换的棋子。暂不允许也可以继续听语音。")
                .setPositiveButton("设置权限", (d, which) -> {
                    overlayExplanationOpen = false; openOverlaySettings();
                }).setNegativeButton("先用语音", (d, which) -> {
                    overlayExplanationOpen = false; requestProjection();
                }).setOnCancelListener(d -> {
                    overlayExplanationOpen = authorizationPending = false; refreshStatus();
                }).show();
        UiKit.styleDialog(overlayExplanation, UiKit.ButtonStyle.OUTLINED);
    }

    private void openOverlaySettings() {
        projectionAfterOverlay = true;
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
        } catch (RuntimeException unavailable) {
            projectionAfterOverlay = false;
            requestProjection();
        }
    }

    private void requestProjection() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) {
            authorizationPending = false;
            toast("本机不支持录屏"); refreshStatus(); return;
        }
        try {
            Intent consent = Build.VERSION.SDK_INT >= 34 ? manager.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
            startActivityForResult(consent, REQ_PROJECTION);
        } catch (RuntimeException unavailable) {
            authorizationPending = false;
            toast("无法打开录屏授权，请重试"); refreshStatus();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            boolean proceed = projectionAfterOverlay;
            projectionAfterOverlay = false;
            if (proceed) requestProjection();
        } else if (requestCode == REQ_PROJECTION) {
            authorizationPending = false;
            if (resultCode != RESULT_OK || data == null) {
                toast("录屏授权已取消"); refreshStatus(); return;
            }
            pendingStartId = UUID.randomUUID().toString();
            startDeadlineMs = SystemClock.elapsedRealtime() + 5000;
            Intent service = new Intent(this, Match3LiveService.class)
                    .setAction(Match3LiveService.ACTION_START)
                    .putExtra(Match3LiveService.EXTRA_START_REQUEST_ID, pendingStartId)
                    .putExtra(Match3LiveService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(Match3LiveService.EXTRA_FULL_DISPLAY, true)
                    .putExtra(Match3LiveService.EXTRA_DATA, data);
            try { startForegroundService(service); }
            catch (RuntimeException failed) {
                pendingStartId = null;
                toast("无法启动实时识别，请重新授权后重试");
            }
            refreshStatus();
        }
    }

    private void checkStart() {
        if (pendingStartId == null || !resumed || isFinishing() || isDestroyed()) return;
        Match3LiveService.StartResult result = Match3LiveService.startResult(pendingStartId);
        if (result != null) {
            // Consume first: a return from the game or rotation must not launch it again.
            pendingStartId = null;
            if (result.ready && Match3LiveService.isRunning()) GameLauncher.openHappyAnipop(this);
            else toast("实时识别启动失败，请重新开始");
        } else if (SystemClock.elapsedRealtime() >= startDeadlineMs) {
            pendingStartId = null;
            toast("实时识别启动超时，请检查运行状态后重试");
        }
    }

    private void refreshStatus() {
        if (status == null) return;
        boolean active = Match3LiveService.isRunning();
        UiKit.setTextIfChanged(status, pendingStartId != null ? "正在准备，随后打开游戏"
                : authorizationPending ? "等待运行授权" : active ? "正在识别棋盘" : "尚未开始");
        start.setEnabled(!active && !authorizationPending && pendingStartId == null);
        stop.setEnabled(active || pendingStartId != null);
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (overlayExplanationOpen) showOverlayExplanation();
        handler.removeCallbacks(refresh); refresh.run();
    }
    @Override protected void onPause() {
        resumed = false; handler.removeCallbacks(refresh); super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("projection_after_overlay", projectionAfterOverlay);
        state.putBoolean("authorization_pending", authorizationPending);
        state.putBoolean("overlay_explanation_open", overlayExplanationOpen);
        state.putString("pending_start_id", pendingStartId);
        state.putLong("start_deadline_ms", startDeadlineMs);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (overlayExplanation != null) overlayExplanation.dismiss();
        handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
