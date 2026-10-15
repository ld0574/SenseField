package com.openkhub.sensefield;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explicit eight-second feasibility probe, shipped only in the test APK. */
public final class NativeDemoProbeActivity extends Activity {
    private TextView status;
    private Button start;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setPadding(24, 48, 24, 24);
        status = new TextView(this); status.setTextSize(24);
        status.setText("听野端内录制验证\n先停止听野辅助，再开始本项短测。\n8 秒录屏＋手机麦克风＋设备播放声音。\n测试画面，使用短测试音和随包语音；文件仅保存在本机。\n左声道为麦克风，右声道为设备内录，便于独立核验。");
        page.addView(status);
        start = new Button(this); start.setText("开始8秒端内录制验证");
        start.setMinHeight(72); start.setOnClickListener(v -> authorize()); page.addView(start);
        page.addView(new MotionCard(this), new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }
    private void authorize() {
        start.setEnabled(false);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 20); return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(consent, 21);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 20 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) authorize();
        else if (request == 20) start.setEnabled(true);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 21 && result == RESULT_OK && data != null) {
            start.setEnabled(false);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            startForegroundService(new Intent(this, NativeDemoProbeService.class)
                    .putExtra("result", result).putExtra("data", data));
            status.setText("正在验证，8 秒后自动保存。\n左声道：手机麦克风\n右声道：设备播放声音\n无需说话，也不需要开游戏。");
            status.postDelayed(() -> {
                try {
                    java.io.File file = new java.io.File(getExternalFilesDir("native-demo-probe"), "report.json");
                    org.json.JSONObject report = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                    status.setText(report.optBoolean("success")
                            ? "验证视频已保存。\n文件：native-probe.mp4\n左声道：手机麦克风\n右声道：设备内录\n仅限测试，请勿与游戏辅助同时启动另一份截屏授权。"
                            : "录制未完整完成，请查看本机测试报告。\n" + report.optString("error"));
                } catch (Exception error) { status.setText("保存尚未完成，请查看本机测试报告。"); }
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                start.setEnabled(true);
            }, 11000);
        } else if (request == 21) start.setEnabled(true);
    }
    private static final class MotionCard extends View {
        private final Paint paint = new Paint(3);
        MotionCard(Activity context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xff17365b); paint.setColor(Color.WHITE); paint.setTextSize(42);
            canvas.drawText("屏幕＋输入声音＋输出声音", 20, 100, paint);
            long tick = SystemClock.elapsedRealtime();
            canvas.drawText("端内录制测试 " + tick / 100, 20, 180, paint);
            paint.setColor(0xffffd54f);
            canvas.drawCircle(40 + (tick % 2000) / 2000f * Math.max(1, getWidth() - 80),
                    Math.max(250, getHeight() / 2f), 35, paint);
            postInvalidateDelayed(80);
        }
    }
}
