package com.openkhub.sensefield;

import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

/** Everyday settings with speech separated and direct access to optional recognition tools. */
public final class Match3SettingsActivity extends UiActivity {
    private View overlayPermission;
    private Button listen;
    private CuePlayer preview;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        UiKit.configureWindow(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page);
        UiKit.pageHeader(this, page, "设置", "开心消消乐辅助");
        LinearLayout speech = UiKit.card(this);
        UiKit.add(speech, UiKit.heading(this, "语音"), 12);
        UiKit.add(speech, UiKit.navigationRow(this, "语音引擎与语速", "手机语音引擎、语速与试听", () ->
                startActivity(new Intent(this, GameTuningActivity.class)
                        .putExtra(GameTuningActivity.EXTRA_DYNAMIC_SPEECH_ONLY, true))), 0);
        UiKit.add(page, speech, 24);

        LinearLayout hints = UiKit.card(this);
        UiKit.add(hints, UiKit.heading(this, "交换提示"), 12);
        CheckBox highlight = new CheckBox(this);
        highlight.setText("交换位置高亮");
        highlight.setTextSize(UiKit.TEXT_BODY);
        highlight.setTextColor(UiKit.INK);
        highlight.setMinHeight(UiKit.dp(this, 56));
        UiKit.styleCheckable(highlight, this);
        highlight.setChecked(GameProfile.settings(this).getBoolean("match3_hint_highlight_enabled", true));
        highlight.setOnCheckedChangeListener((button, checked) -> {
            GameProfile.settings(this).edit().putBoolean("match3_hint_highlight_enabled", checked).apply();
            refreshPermission();
            if (Match3LiveService.isRunning()) startService(new Intent(this, Match3LiveService.class)
                    .setAction(Match3LiveService.ACTION_REFRESH_VISUAL));
        });
        UiKit.add(hints, highlight, 8);
        UiKit.add(hints, UiKit.hint(this, "框出两颗棋子和交换方向，局面变化后更新。"), 12);
        overlayPermission = UiKit.navigationRow(this, "允许显示高亮", "开启悬浮显示权限；语音仍可单独使用", () -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException unavailable) { toast("请在系统设置中允许听野悬浮显示"); }
        });
        UiKit.add(hints, overlayPermission, 12);
        UiKit.add(page, hints, 24);

        LinearLayout numbering = UiKit.details(this, "行列怎么数", Match3Hint.NUMBERING);
        LinearLayout numberingBody = numbering.findViewWithTag("ui_details_body");
        listen = UiKit.button(this, "听行列说明", false);
        listen.setOnClickListener(v -> readNumbering());
        numberingBody.addView(listen);
        UiKit.add(page, numbering, 24);

        LinearLayout tools = UiKit.card(this);
        UiKit.add(tools, UiKit.heading(this, "识别与反馈"), 12);
        UiKit.add(tools, UiKit.hint(this, "棋盘范围和行列数会自动确认，通常无需校准。"), 12);
        UiKit.add(tools, UiKit.navigationRow(this, "截图校准与棋子学习", "识别不准时使用，日常无需设置", () ->
                startActivity(new Intent(this, Match3ToolsActivity.class))), 12);
        UiKit.add(tools, UiKit.navigationRow(this, "游戏内触屏点读", "需 Android 14 及听野读屏服务", this::toggleExplore), 0);
        UiKit.add(tools, UiKit.navigationRow(this, "测试记录与反馈", "查看本地记录，标记和导出问题", () ->
                startActivity(new Intent(this, DiagnosticsActivity.class))), 0);
        UiKit.add(page, tools, 24);
        setContentView(scroll);
        refreshPermission();
    }

    private void refreshPermission() {
        if (overlayPermission != null) overlayPermission.setVisibility(
                GameProfile.settings(this).getBoolean("match3_hint_highlight_enabled", true)
                        && !Settings.canDrawOverlays(this) ? View.VISIBLE : View.GONE);
    }

    private void toggleExplore() {
        if (!Match3LiveService.isRunning()) { toast("先开始辅助，再开启游戏内点读"); return; }
        if (Build.VERSION.SDK_INT < 34) { toast("游戏内点读需要 Android 14；仍可使用截图点读"); return; }
        if (!SenseFieldReaderService.isConnected()) {
            toast("请先开启听野读屏服务");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return;
        }
        boolean enable = !Match3LiveService.isExploreMode();
        startService(new Intent(this, Match3LiveService.class).setAction(enable
                ? Match3LiveService.ACTION_EXPLORE_ON : Match3LiveService.ACTION_EXPLORE_OFF));
        toast(enable ? "触屏点读已开启" : "触屏点读已关闭");
    }

    private void readNumbering() {
        if (CaptureService.isRunning() || Match3LiveService.isRunning()) {
            toast("请先停止辅助，再试听行列说明"); return;
        }
        stopPreview();
        preview = CuePlayer.speechProbe(this);
        listen.setText("重新试听行列说明");
        preparePreview(preview, SystemClock.elapsedRealtime() + 20000);
    }
    private void preparePreview(CuePlayer expected, long deadline) {
        if (preview != expected) return;
        if (!expected.speechPreparationFinished() && SystemClock.elapsedRealtime() < deadline) {
            handler.postDelayed(() -> preparePreview(expected, deadline), 150); return;
        }
        if (!expected.speechReady()) {
            toast(expected.speechStatusText()); stopPreview(); return;
        }
        long now = SystemClock.elapsedRealtime();
        CueRequest request = new CueRequest("m3-settings", "numbering:" + now, "m3:numbering", "行列说明",
                CueRequest.Category.SYSTEM, 70, now, now + 10000, CueRequest.CHANNEL_SPEECH,
                0, 0, 0, Match3Hint.NUMBERING);
        if (!expected.speak(request, true, new CueDispatcher.PlaybackCallback() {
            @Override public void onStarted(long at) { }
            @Override public void onFinished(long at, boolean success) {
                handler.post(() -> { if (preview == expected) stopPreview(); });
            }
        })) stopPreview();
    }
    private void stopPreview() {
        handler.removeCallbacksAndMessages(null);
        if (preview != null) { preview.close(); preview = null; }
        if (listen != null) listen.setText("听行列说明");
    }
    @Override protected void onResume() {
        super.onResume(); refreshPermission();
        if (Match3LiveService.isRunning() && Settings.canDrawOverlays(this))
            startService(new Intent(this, Match3LiveService.class).setAction(Match3LiveService.ACTION_REFRESH_VISUAL));
    }
    @Override protected void onPause() { stopPreview(); super.onPause(); }
    @Override protected void onDestroy() { stopPreview(); super.onDestroy(); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
