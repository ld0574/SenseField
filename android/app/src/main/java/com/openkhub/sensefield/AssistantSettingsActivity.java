package com.openkhub.sensefield;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Optional experimental assistant; the existing local mode needs none of these grants. */
public final class AssistantSettingsActivity extends Activity {
    private TextView status, resources;
    private Button retryResources;
    private boolean preparing;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); UiKit.configureWindow(this);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout page = UiKit.page(this); scroll.addView(page);
        UiKit.addBrandHeader(page, "语音与画面助手");
        UiKit.add(page, UiKit.heading(this, "实验助手设置"), 12);
        SettingHelp.addGroup(this, page, "assistant_connection", 8);
        status = UiKit.body(this, "默认使用听野线上服务，无需填写地址或连接码。开启功能后请重新开始辅助；关闭会立即停止对应处理。");
        UiKit.add(page, status, 10);
        SettingHelp.addGroup(this, page, "assistant_group", 14);
        consentSwitch(page, "连续语音（可以插话）", AssistantSettings.VOICE, AssistantSettings.AUDIO_CONSENT,
                "听野会持续检测说话，在你说完后离线识别。原始语音只在手机内存里临时处理，不上传、不保存音频文件。第一次开启会从听野 CDN 下载约153 MiB语音资源，请在网络稳定时准备，并预留约400 MiB空间。下载后离线识别，已缓存资源会复用。\n\n请戴耳机，并确认游戏声音也在耳机里。听野只能检查自己的声音路线，无法确认游戏的实际路线；蓝牙音箱和外放不适合此实验。普通蓝牙耳机连接时，识别可能使用手机麦克风。游戏开麦、其他录音活动或路线不明时，助手暂停输入。\n\n开启画面理解后，问题文字和授权画面会发送到画面服务及其视觉模型。背景人声也可能被识别成问题，请留意周围环境。", true);
        resources = UiKit.body(this, "首次开启连续语音后准备资源，完成后可离线识别。");
        resources.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(page, resources, 8);
        retryResources = button(page, "准备语音资源／重试");
        retryResources.setOnClickListener(v -> prepareResources());
        consentSwitch(page, "画面理解", AssistantSettings.VISION, AssistantSettings.IMAGE_CONSENT,
                "开启后，提问会把当前共享画面和最多两张最近画面的缩图发送到画面服务及其配置的视觉模型；低频主动观察只发送当前画面。助手可以结合可见信息解释装备、选人和对战建议，信息不足时会说明。授权画面可能包含游戏聊天或系统通知，请留意共享范围。助手近期画面只缓存在内存，诊断截图由独立设置管理；第三方服务按其条款处理数据。", false);
        simpleSwitch(page, "低频主动描述（需要画面理解）", AssistantSettings.PROACTIVE);
        button(page, "授权左侧小圆点").setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        SettingHelp.addGroup(this, page, "presentation_group", 14);
        simpleSwitch(page, "左右双耳线索（需双耳耳机）", "presentation_spatial");
        simpleSwitch(page, "距离强度与节奏震动", "presentation_distance_haptic");
        simpleSwitch(page, "两字方位提示", "near_two_word");
        button(page, "返回").setOnClickListener(v -> finish());
        setContentView(scroll);
        if (GameProfile.settings(this).getBoolean(AssistantSettings.VOICE, false)) prepareResources();
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (!SettingHelp.close(this)) super.onBackPressed();
    }
    private void prepareResources() {
        if (preparing) return;
        if (!GameProfile.settings(this).getBoolean(AssistantSettings.VOICE, false)) {
            resources.setText("请先开启连续语音，再准备资源。"); return;
        }
        preparing = true; retryResources.setEnabled(false);
        resources.setText("正在检查语音资源；首次下载约153 MiB…");
        AsrModelStore.prepare(this, percent -> runOnUiThread(() -> {
            if (!isDestroyed()) resources.setText(percent < 100
                    ? "正在下载语音资源：" + percent + "%（约153 MiB）"
                    : "正在校验语音资源…");
        }), message -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            preparing = false; retryResources.setEnabled(true);
            resources.setText(message + " 准备完成后重新开始辅助。");
        }));
    }
    private Button button(LinearLayout page, String title) {
        Button button = UiKit.button(this, title, false); button.setMinHeight(UiKit.dp(this, 64));
        UiKit.add(page, button, 10); return button;
    }
    private CheckBox box(LinearLayout page, String label, String key) {
        CheckBox box = new CheckBox(this); box.setText(label); box.setTextSize(22); UiKit.styleCheckable(box, this);
        box.setChecked(GameProfile.settings(this).getBoolean(key, false)); UiKit.add(page, box, 8); return box;
    }
    private void simpleSwitch(LinearLayout page, String label, String key) {
        box(page, label, key).setOnCheckedChangeListener((button, checked) ->
                GameProfile.settings(this).edit().putBoolean(key, checked).apply());
    }
    private void consentSwitch(LinearLayout page, String label, String key, String consent, String explanation, boolean microphone) {
        CheckBox box = box(page, label, key);
        box.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) { GameProfile.settings(this).edit().putBoolean(key, false).apply(); return; }
            new AlertDialog.Builder(this).setTitle(label).setMessage(explanation)
                    .setPositiveButton("同意并开启", (dialog, which) -> {
                        GameProfile.settings(this).edit().putBoolean(consent, true).putBoolean(key, true).apply();
                        if (microphone) prepareResources();
                        if (microphone && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1501);
                    }).setNegativeButton("取消", (dialog, which) -> box.setChecked(false))
                    .setOnCancelListener(dialog -> box.setChecked(false)).show();
        });
    }
}
