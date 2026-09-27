package org.openrd.mapassist;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
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

public final class MainActivity extends Activity {
    private static final int REQUEST_PROJECTION = 1001;
    private static final int REQUEST_IMPORT = 1002;
    private static final int REQUEST_NOTIFICATIONS = 1003;
    private static final int REQUEST_OVERLAY = 1004;

    private TextView status;
    private CheckBox minimapOverlay;
    private boolean awaitingOverlayPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(getResources().getDisplayMetrics().density * 20);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);

        TextView title = new TextView(this);
        title.setText("地图感知助手 · 实验原型");
        title.setTextSize(24);
        content.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText("只读取授权的屏幕画面，提示画面中可见的事件。不操作游戏。可选的小地图提示层只显示本地识别结果，不接收触控；授权时请选择整个屏幕，再切换到游戏练习场景。\n\n内置配置尚未标定，默认不会发出识别提示。");
        explanation.setTextSize(16);
        content.addView(explanation);

        status = new TextView(this);
        status.setTextSize(16);
        content.addView(status);

        TextView restartHint = new TextView(this);
        restartHint.setText("配置、实验识别、视野记忆开关、提示间隔和中央区域在下一次截屏会话生效。");
        content.addView(restartHint);

        Button importProfile = button("导入标定配置 JSON", content);
        importProfile.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent, REQUEST_IMPORT);
        });

        CheckBox experimental = new CheckBox(this);
        experimental.setText("允许未通过真人录像评测的实验识别器");
        experimental.setChecked(GameProfile.settings(this).getBoolean("allow_experimental", false));
        experimental.setOnCheckedChangeListener((button, checked) -> {
            GameProfile.settings(this).edit().putBoolean("allow_experimental", checked).apply();
            refreshStatus();
        });
        content.addView(experimental);

        minimapOverlay = new CheckBox(this);
        minimapOverlay.setText("启用视野记忆：视觉残影、事件语音与方向震动（实验）");
        minimapOverlay.setChecked(GameProfile.settings(this)
                .getBoolean("vision_memory", false) && Settings.canDrawOverlays(this));
        minimapOverlay.setOnClickListener(view -> {
            boolean checked = minimapOverlay.isChecked();
            if (checked && !Settings.canDrawOverlays(this)) {
                minimapOverlay.setChecked(false);
                awaitingOverlayPermission = true;
                Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(permission, REQUEST_OVERLAY);
                toast("请允许听野显示非交互式小地图提示层");
                return;
            }
            GameProfile.settings(this).edit().putBoolean("vision_memory", checked).apply();
        });
        content.addView(minimapOverlay);

        TextView volumeLabel = new TextView(this);
        content.addView(volumeLabel);
        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(GameProfile.settings(this).getInt("volume", 45));
        volumeLabel.setText("提示音量：" + volume.getProgress() + "%");
        volume.setOnSeekBarChangeListener(new SimpleSeekListener(value -> {
            GameProfile.settings(this).edit().putInt("volume", value).apply();
            volumeLabel.setText("提示音量：" + value + "%");
        }));
        content.addView(volume);

        TextView intervalLabel = new TextView(this);
        content.addView(intervalLabel);
        SeekBar interval = new SeekBar(this);
        interval.setMax(5);
        int savedInterval = GameProfile.settings(this).getInt("cue_gap_ms", 1000);
        interval.setProgress(Math.max(0, Math.min(5, (savedInterval - 500) / 500)));
        intervalLabel.setText("最短提示间隔：" + (500 + interval.getProgress() * 500) / 1000f + " 秒");
        interval.setOnSeekBarChangeListener(new SimpleSeekListener(value -> {
            int milliseconds = 500 + value * 500;
            GameProfile.settings(this).edit().putInt("cue_gap_ms", milliseconds).apply();
            intervalLabel.setText("最短提示间隔：" + milliseconds / 1000f + " 秒");
        }));
        content.addView(interval);

        TextView centerLabel = new TextView(this);
        content.addView(centerLabel);
        SeekBar center = new SeekBar(this);
        center.setMax(50);
        center.setProgress(GameProfile.settings(this).getInt("center_percent", profileCenterPercent()) - 10);
        centerLabel.setText("中央免提示区：" + (center.getProgress() + 10) + "%");
        center.setOnSeekBarChangeListener(new SimpleSeekListener(value -> {
            int percent = value + 10;
            GameProfile.settings(this).edit().putInt("center_percent", percent).apply();
            centerLabel.setText("中央免提示区：" + percent + "%");
        }));
        content.addView(center);

        Button start = button("授权截屏并开始", content);
        start.setOnClickListener(view -> requestCapture());
        Button pause = button("暂停／继续提示", content);
        pause.setOnClickListener(view -> sendServiceAction(CaptureService.ACTION_TOGGLE_PAUSE));
        Button stop = button("停止截屏", content);
        stop.setOnClickListener(view -> {
            sendServiceAction(CaptureService.ACTION_STOP);
            status.postDelayed(this::refreshStatus, 500);
        });

        TextView privacy = new TextView(this);
        privacy.setText("画面在手机本地处理，应用不上传、不保存游戏画面。只读截屏也不能保证游戏厂商允许在正式对战使用。");
        content.addView(privacy);
        setContentView(scroll);
        refreshStatus();
    }

    private Button button(String label, LinearLayout parent) {
        Button button = new Button(this);
        button.setText(label);
        parent.addView(button);
        return button;
    }

    private int profileCenterPercent() {
        try {
            return Math.round(GameProfile.load(this).rois[10] * 100);
        } catch (IOException | JSONException error) {
            return 30;
        }
    }

    private void refreshStatus() {
        try {
            GameProfile profile = GameProfile.load(this);
            int enabled = profile.flags[0] + profile.flags[1] + profile.flags[2] + profile.flags[3]
                    + (profile.minimapYolox ? 1 : 0);
            String lastCapture = GameProfile.settings(this).getString("last_capture_status", "");
            status.setText("配置：" + profile.name + " · " + profile.version
                    + (profile.verified ? "（已标定）" : "（未标定）")
                    + "\n当前启用识别器：" + enabled + "/5"
                    + (lastCapture.isEmpty() ? "" : "\n上次截屏：" + lastCapture));
        } catch (IOException | JSONException error) {
            status.setText("配置读取失败：" + error.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false;
            boolean allowed = Settings.canDrawOverlays(this);
            GameProfile.settings(this).edit().putBoolean("vision_memory", allowed).apply();
            if (minimapOverlay != null) minimapOverlay.setChecked(allowed);
            if (!allowed) toast("未开启小地图提示层权限；声音提示不受影响");
        }
        if (status != null) refreshStatus();
    }

    private void requestCapture() {
        try {
            GameProfile.load(this);
        } catch (IOException | JSONException error) {
            toast("配置无效：" + error.getMessage());
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            toast("请允许通知后再次点击开始；通知栏用于暂停和停止截屏");
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
        if (requestCode == REQUEST_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                toast("已取消截屏授权");
                return;
            }
            Intent service = new Intent(this, CaptureService.class);
            service.setAction(CaptureService.ACTION_START);
            service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            startForegroundService(service);
        } else if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null) {
            importProfile(data.getData());
        } else if (requestCode == REQUEST_OVERLAY) {
            // Some Android versions report RESULT_CANCELED even when the
            // special-access toggle changed. onResume checks the real state.
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
            toast("配置已导入；下一次开始截屏时生效");
            refreshStatus();
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
