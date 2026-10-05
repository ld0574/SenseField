package com.openkhub.sensefield;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Optional experimental assistant; the existing local mode needs none of these grants. */
public final class AssistantSettingsActivity extends Activity {
    private EditText endpoint, token;
    private TextView status;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); UiKit.configureWindow(this);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout page = UiKit.page(this); scroll.addView(page);
        UiKit.addBrandHeader(page, "语音与画面助手");
        UiKit.add(page, UiKit.heading(this, "实验助手设置"), 12);
        SettingHelp.addGroup(this, page, "assistant_connection", 8);
        AssistantSettings settings = AssistantSettings.from(this);
        endpoint = field(page, "画面服务地址（HTTPS）", settings.endpoint, false);
        token = field(page, "体验连接码", settings.token, true);
        if (!BuildConfig.ASSISTANT_DEFAULT_ENDPOINT.isEmpty()) {
            button(page, "使用听野线上服务").setOnClickListener(v -> {
                String onlineEndpoint = BuildConfig.ASSISTANT_DEFAULT_ENDPOINT;
                endpoint.setText(onlineEndpoint);
                GameProfile.settings(this).edit()
                        .putString(AssistantSettings.ENDPOINT, onlineEndpoint).apply();
                status.setText("已选择听野线上服务。还需要负责人提供的体验连接码；此操作不会开启画面理解。");
            });
        }
        status = UiKit.body(this, "关闭功能会立即停止对应处理；开启或更改连接需要重新开始辅助。"); UiKit.add(page, status, 10);
        button(page, "保存服务连接").setOnClickListener(v -> {
            String address = endpoint.getText().toString().trim().replaceAll("/+$", "");
            String deviceToken = token.getText().toString().trim();
            if (!AssistantSettings.validEndpoint(address) || deviceToken.length() < 24 || deviceToken.length() > 256) {
                status.setText("请输入 HTTPS 服务地址与至少24位的体验连接码。"); return;
            }
            GameProfile.settings(this).edit().putString(AssistantSettings.ENDPOINT, address)
                    .putString(AssistantSettings.TOKEN, deviceToken).apply();
            status.setText("已保存。更改连接后请重新开始辅助。");
        });
        SettingHelp.addGroup(this, page, "assistant_group", 14);
        consentSwitch(page, "连续语音（可以插话）", AssistantSettings.VOICE, AssistantSettings.AUDIO_CONSENT,
                "听野会持续检测说话，在你说完后离线识别。原始语音只在手机内存里临时处理，不上传、不保存音频文件。第一次开启需要等待本地模型加载。\n\n请戴耳机，并确认游戏声音也在耳机里。听野只能检查自己的声音路线，无法确认游戏的实际路线；蓝牙音箱和外放不适合此实验。普通蓝牙耳机连接时，识别可能使用手机麦克风。游戏开麦、其他录音活动或路线不明时，助手暂停输入。\n\n开启画面理解后，问题文字和授权画面会发送到画面服务及其视觉模型。背景人声也可能被识别成问题，请留意周围环境。", true);
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
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (!SettingHelp.close(this)) super.onBackPressed();
    }
    private EditText field(LinearLayout page, String title, String value, boolean secret) {
        UiKit.add(page, UiKit.body(this, title), 8);
        EditText field = new EditText(this); field.setTextSize(20); field.setSingleLine(true);
        field.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(value); field.setMinHeight(UiKit.dp(this, 56)); UiKit.add(page, field, 10); return field;
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
                        if (microphone && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1501);
                    }).setNegativeButton("取消", (dialog, which) -> box.setChecked(false))
                    .setOnCancelListener(dialog -> box.setChecked(false)).show();
        });
    }
}
