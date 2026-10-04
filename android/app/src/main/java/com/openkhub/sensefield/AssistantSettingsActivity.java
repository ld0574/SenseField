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
        UiKit.add(page, UiKit.body(this, "不开启助手时，实时预警继续在手机本地运行。连续语音实验适用于 Android 10 及以上，进入游戏前开始会话。应用只核对本应用默认 USAGE_GAME 探测音轨的耳机路由，不能确认某个游戏音轨的实际路线。首次试用请先确认游戏声音本身也从耳机传出；若仍从扬声器播放或无法确认，请勿开启连续语音。其他录音会话会使助手停止上传。"), 12);
        AssistantSettings settings = AssistantSettings.from(this);
        endpoint = field(page, "自己的服务器地址（HTTPS）", settings.endpoint, false);
        token = field(page, "设备连接令牌", settings.token, true);
        UiKit.add(page, UiKit.body(this, "这里填写设备令牌，不是 GLM API 密钥。模型密钥只放在自己的服务器。"), 10);
        status = UiKit.body(this, "关闭立即停止该功能上传；开启或更改连接需要重新开始辅助。"); UiKit.add(page, status, 10);
        button(page, "保存服务器设置").setOnClickListener(v -> {
            String address = endpoint.getText().toString().trim().replaceAll("/+$", "");
            String deviceToken = token.getText().toString().trim();
            if (!AssistantSettings.validEndpoint(address) || deviceToken.length() < 24 || deviceToken.length() > 256) {
                status.setText("请输入 HTTPS 地址与至少24位的设备令牌。"); return;
            }
            GameProfile.settings(this).edit().putString(AssistantSettings.ENDPOINT, address)
                    .putString(AssistantSettings.TOKEN, deviceToken).apply();
            status.setText("已保存。更改连接后请重新开始辅助。");
        });
        consentSwitch(page, "连续语音（可以插话）", AssistantSettings.VOICE, AssistantSettings.AUDIO_CONSENT,
                "开启前请佩戴耳机或耳塞，并确认你正在玩的游戏声音确实在耳机里。应用只能核对本应用默认 USAGE_GAME 探测音轨是否路由到有线、USB、普通蓝牙 A2DP 或 BLE 耳机，不能读取或证明某个游戏音轨实际走耳机；A2DP 路由类型也不能区分耳塞与蓝牙音箱。蓝牙音箱、外放或无法确认游戏声音在耳机里时，请勿继续。开启后，会话期间持续使用麦克风，把本地检测到的语音片段及前300毫秒、末尾短静音发送到你的自托管服务器识别。片段可能包含背景人声或误识别声音；默认不保存原始语音。若探测音轨路由未知、不是支持的耳机或检测到其他录音会话，助手停止上传。", true);
        consentSwitch(page, "画面理解", AssistantSettings.VISION, AssistantSettings.IMAGE_CONSENT,
                "开启后，提问或已开启的低频主动观察会把当前共享屏幕的缩图发送到自托管服务器，再送到智谱 GLM API。授权画面可能包含游戏聊天或系统通知，请留意共享范围。默认不保存截图；第三方服务按其条款处理数据。", false);
        simpleSwitch(page, "低频主动描述（需要画面理解）", AssistantSettings.PROACTIVE);
        button(page, "授权左侧小圆点").setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        UiKit.add(page, UiKit.body(this, "小圆点默认收起，点击后展开记录。没有置顶权限时仍可使用语音，并从运行通知暂停或标记问题。"), 14);
        UiKit.add(page, UiKit.heading(this, "呈现层实验（默认关闭）"), 14);
        simpleSwitch(page, "左右双耳线索（需双耳耳机）", "presentation_spatial");
        simpleSwitch(page, "距离强度与节奏震动", "presentation_distance_haptic");
        simpleSwitch(page, "两字方位提示", "near_two_word");
        UiKit.add(page, UiKit.body(this, "这些模式尚需玩家对照验证，开启双耳线索后请重新开始辅助。左右双耳线索不提供完整 HRTF；普通手机的单马达不提供左右分离震动。"), 12);
        button(page, "返回").setOnClickListener(v -> finish());
        setContentView(scroll);
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
