package com.openkhub.sensefield;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import java.io.IOException;
import java.util.UUID;

/** All device grants are checked here; projection consent is never reused. */
public final class CapturePermissionsActivity extends Activity {
    static final String EXTRA_START = "start_capture_after_permissions";
    private static final int REQUEST_PROJECTION = 1101;
    private static final int REQUEST_NOTIFICATIONS = 1102;
    private static final int REQUEST_MICROPHONE = 1103;
    private TextView screenStatus;
    private TextView notificationStatus;
    private TextView overlayStatus;
    private TextView batteryStatus;
    private Button capture;
    private boolean startRequested;
    private boolean attemptedForStart;
    private final Handler startHandler = new Handler(Looper.getMainLooper());
    private String pendingStartId;
    private long startDeadlineMs;
    private boolean resumed;
    private final Runnable checkStart = this::checkCaptureStart;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        UiKit.configureWindow(this);
        startRequested = getIntent().getBooleanExtra(EXTRA_START, false);
        attemptedForStart = savedInstanceState != null
                && savedInstanceState.getBoolean("attempted_start", false);
        if (savedInstanceState != null) {
            pendingStartId = savedInstanceState.getString("pending_start_id");
            startDeadlineMs = savedInstanceState.getLong("pending_start_deadline_ms");
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);
        UiKit.addBrandHeader(content, "授权与运行设置");
        UiKit.add(content, UiKit.text(this, "授权与运行设置", 28, UiKit.INK, true), 8);
        UiKit.add(content, UiKit.body(this, "先完成必要授权，再开始辅助。"), 18);

        LinearLayout screen = card(content, "屏幕采集");
        screenStatus = UiKit.body(this, "每次开始新会话时，由系统确认共享整个屏幕。");
        UiKit.add(screen, screenStatus, 10);
        capture = button(screen, "授权截屏并开始");
        capture.setOnClickListener(view -> requestCapture());
        SettingHelp.add(this, screen, "capture_screen", 8);

        LinearLayout notifications = card(content, "运行通知");
        notificationStatus = UiKit.body(this, "");
        UiKit.add(notifications, notificationStatus, 10);
        SettingHelp.add(this, notifications, "capture_notifications", 8);
        button(notifications, "授权运行通知").setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS);
            } else {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }
        });

        LinearLayout overlay = card(content, "置顶视觉提示");
        overlayStatus = UiKit.body(this, "");
        UiKit.add(overlay, overlayStatus, 6);
        SettingHelp.add(this, overlay, "capture_overlay", 8);
        CheckBox visual = new CheckBox(this);
        visual.setText("允许使用视觉提示");
        visual.setTextSize(22);
        visual.setTextColor(UiKit.INK);
        UiKit.styleCheckable(visual, this);
        visual.setChecked(GameProfile.settings(this).getBoolean("cue_channel_visual", true));
        visual.setOnCheckedChangeListener((view, checked) -> {
            GameProfile.settings(this).edit().putBoolean("cue_channel_visual", checked).apply();
            CueSettings.markCustom(GameProfile.settings(this));
            refreshPermissions();
        });
        UiKit.add(overlay, visual, 6);
        SettingHelp.add(this, overlay, "capture_visual", 8);
        button(overlay, "授权置顶显示").setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));

        LinearLayout battery = card(content, "后台省电策略");
        batteryStatus = UiKit.body(this, "");
        UiKit.add(battery, batteryStatus, 10);
        SettingHelp.add(this, battery, "capture_battery", 8);
        button(battery, "设置后台省电策略").setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        SettingHelp.add(this, battery, "capture_battery_app", 8);
        button(battery, "系统省电优化设置").setOnClickListener(view -> startActivity(
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        SettingHelp.add(this, battery, "capture_battery_system", 8);
        UiKit.add(battery, UiKit.body(this,
                "小米手机还需检查电量与性能，允许此应用后台运行；各品牌设置可能不同。"), 0);

        button(content, "声音与提示配置").setOnClickListener(view ->
                startActivity(new Intent(this, GameTuningActivity.class)));
        button(content, "返回").setOnClickListener(view -> finish());
        setContentView(scroll);
        refreshPermissions();
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = UiKit.card(this);
        UiKit.add(card, UiKit.heading(this, title), 8);
        UiKit.add(parent, card, 14);
        return card;
    }

    private Button button(LinearLayout parent, String title) {
        Button button = UiKit.button(this, title, false);
        button.setTextSize(22);
        button.setMinHeight(UiKit.dp(this, 72));
        button.setMinimumHeight(UiKit.dp(this, 72));
        UiKit.add(parent, button, 8);
        return button;
    }

    private static boolean notificationGranted(Activity activity) {
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        return (Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && manager != null && manager.areNotificationsEnabled();
    }

    private static boolean overlayRequired(Activity activity) {
        return GameProfile.settings(activity).getBoolean(GameProfile.PREF_VISION_MEMORY,
                GameProfile.DEFAULT_VISION_MEMORY)
                && GameProfile.settings(activity).getBoolean("cue_channel_visual", true);
    }

    static boolean requiredSettingsReady(Activity activity) {
        // Power policy is advisory: vendor-specific background restrictions
        // cannot be inferred from the standard Doze exemption alone.
        return notificationGranted(activity)
                && (!overlayRequired(activity) || Settings.canDrawOverlays(activity));
    }

    private void refreshPermissions() {
        if (capture == null) return;
        boolean active = GameProfile.settings(this).getBoolean("capture_active", false)
                && CaptureService.isRunning();
        boolean ready = requiredSettingsReady(this);
        screenStatus.setText(pendingStartId != null ? "正在准备辅助，随后打开王者荣耀"
                : active ? "本次会话已授权。停止后再次开始需要重新确认。"
                : ready ? "每次开始新会话时，由系统确认共享整个屏幕。"
                : "请先完成下方的必要授权，再确认本次屏幕共享。");
        capture.setEnabled(ready && !active && pendingStartId == null);
        notificationStatus.setText(notificationGranted(this) ? "已允许" : "需要授权，用于查看辅助运行状态");
        overlayStatus.setText(Settings.canDrawOverlays(this) ? "已允许"
                : overlayRequired(this) ? "视觉提示已开启，需要置顶显示授权"
                : "可选。只使用语音与触觉时无需此授权。");
        PowerManager power = getSystemService(PowerManager.class);
        batteryStatus.setText(power != null && power.isIgnoringBatteryOptimizations(getPackageName())
                ? "已豁免系统省电优化；仍需检查手机厂商的后台限制。"
                : "建议允许后台运行，避免辅助在游戏中被省电系统结束。");
    }

    private void requestCapture() {
        if (pendingStartId != null) return;
        if (Match3LiveService.isRunning()) {
            toast("请先停止消消乐实时辅助，再启动王者辅助");
            return;
        }
        if (!requiredSettingsReady(this)) {
            toast("请先完成运行通知和已开启视觉提示所需的授权");
            refreshPermissions();
            return;
        }
        if (GameProfile.settings(this).getBoolean("capture_active", false)
                && CaptureService.isRunning()) return;
        AssistantSettings assistant = AssistantSettings.from(this);
        if (assistant.enabled() && assistant.voice && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        CueSettings cues = new CueSettings(this);
        int channels = cues.nearRequestedChannels();
        AudioManager audio = getSystemService(AudioManager.class);
        if (cues.categoryEnabled(CueRequest.Category.NEAR_ZONE)
                && (channels & (CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH)) != 0
                && (audio == null || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                    || audio.isStreamMute(AudioManager.STREAM_MUSIC)
                    || GameProfile.settings(this).getInt("volume", 45) == 0)) {
            toast("提醒音量为 0 或已静音，请调高媒体音量并在声音配置中测试");
            return;
        }
        try { GameProfile.load(this); }
        catch (IOException | JSONException error) { toast("配置无效，请先检查配置"); return; }
        attemptedForStart = true;
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(consent, REQUEST_PROJECTION);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshPermissions();
        if (pendingStartId != null) checkCaptureStart();
        else if (startRequested && !attemptedForStart && requiredSettingsReady(this)) requestCapture();
    }

    @Override protected void onPause() {
        resumed = false;
        startHandler.removeCallbacks(checkStart);
        super.onPause();
    }

    @Override protected void onDestroy() {
        startHandler.removeCallbacks(checkStart);
        super.onDestroy();
    }

    private void checkCaptureStart() {
        startHandler.removeCallbacks(checkStart);
        if (!resumed || pendingStartId == null || isFinishing() || isDestroyed()) return;
        CaptureService.StartResult result = CaptureService.startResult(pendingStartId);
        if (result != null) {
            // Consume before launching: resuming or rotating must not open the game twice.
            pendingStartId = null;
            if (result.ready && CaptureService.isRunning()
                    && GameProfile.settings(this).getBoolean("capture_active", false)) {
                GameLauncher.openHonorOfKings(this);
                setResult(RESULT_OK);
                finish();
            } else {
                toast(GameProfile.settings(this).getString("last_capture_status", "辅助启动失败，请重新开始"));
                refreshPermissions();
            }
        } else if (SystemClock.elapsedRealtime() >= startDeadlineMs) {
            pendingStartId = null;
            toast("辅助启动超时，请检查运行状态后重新开始");
            refreshPermissions();
        } else startHandler.postDelayed(checkStart, 100);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPermissions();
        if (requestCode == REQUEST_NOTIFICATIONS && !notificationGranted(this))
            toast("未开启运行通知，可以在系统通知设置中继续授权");
        if (requestCode == REQUEST_MICROPHONE) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                GameProfile.settings(this).edit().putBoolean(AssistantSettings.VOICE, false).apply();
                toast("未授权麦克风，连续语音关闭；本地预警仍可使用");
            }
            requestCapture();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("attempted_start", attemptedForStart);
        state.putString("pending_start_id", pendingStartId);
        state.putLong("pending_start_deadline_ms", startDeadlineMs);
        super.onSaveInstanceState(state);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            toast("未授权截屏，辅助未开始");
            return;
        }
        if (pendingStartId != null) return;
        pendingStartId = UUID.randomUUID().toString();
        startDeadlineMs = SystemClock.elapsedRealtime() + 15000;
        // Pass this fresh one-use result directly to the foreground service.
        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_START);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        service.putExtra(CaptureService.EXTRA_START_REQUEST_ID, pendingStartId);
        try {
            // Remain visible until both projection and microphone foreground types are ready.
            startForegroundService(service);
            refreshPermissions();
            if (resumed) checkCaptureStart();
        } catch (RuntimeException error) {
            pendingStartId = null;
            toast("系统未能启动辅助，请检查授权后重试");
            refreshPermissions();
        }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
