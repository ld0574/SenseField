package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static com.openkhub.sensefield.GameProfile.settings;

/** Large, directly visible settings entries for low-vision users. */
public final class AppSettingsActivity extends UiActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);
        UiKit.pageHeader(this, content, "设置", "王者荣耀辅助");
        section(content, "授权与运行");
        entry(content, "授权与运行设置", "屏幕共享、通知与后台运行", CapturePermissionsActivity.class);
        UiKit.gap(content, 12);
        section(content, "提醒与学习");
        entry(content, "配置与调参", "提示方案、声音与触觉", GameTuningActivity.class);
        entry(content, "提醒说明与试听", "按项试听，按段收听完整说明", ReminderGuideActivity.class);
        UiKit.gap(content, 12);
        section(content, "实验与反馈");
        entry(content, "语音与画面助手（实验）", "默认关闭，可按需开启", AssistantSettingsActivity.class);
        UiKit.add(content, UiKit.navigationRow(this, "测试记录与反馈", "标记问题，导出本地诊断记录",
                () -> startActivity(DiagnosticsActivity.intent(this, DiagnosticGame.HONOR))), UiKit.GAP_CONTROL);
        /* 养老风险标签（方案一 MVP）：默认关，通过 GameProfile.settings 持久化，
         * 读屏服务每次窗口变化时从同一 key 读取。真机验收前手动开。 */
        UiKit.gap(content, 8);
        LinearLayout elderCard = UiKit.card(this);
        UiKit.add(content, elderCard, UiKit.GAP_SECTION);
        CheckBox elderSwitch = new CheckBox(this);
        elderSwitch.setText("养老风险标签（实验）");
        elderSwitch.setTextSize(18);
        elderSwitch.setTextColor(UiKit.INK);
        UiKit.styleCheckable(elderSwitch, this);
        elderSwitch.setChecked(settings(this).getBoolean(
                SenseFieldReaderService.PREF_ELDER_RISK_LABELS, false));
        UiKit.add(elderCard, elderSwitch, 6);
        elderSwitch.setOnCheckedChangeListener((button, checked) ->
                settings(this).edit().putBoolean(SenseFieldReaderService.PREF_ELDER_RISK_LABELS, checked).apply());
        TextView note = UiKit.hint(this,
                "默认关闭。开启后，当屏幕上出现疑似诈骗或推广内容时会用语音提醒老人，并记录审核队列。"
                        + "仅读取无障碍节点文本，不上传图片或录屏。");
        UiKit.add(elderCard, note, 12);
        setContentView(scroll);
    }

    private void section(LinearLayout page, String title) {
        UiKit.add(page, UiKit.heading(this, title), 12);
    }

    private void entry(LinearLayout page, String title, String description, Class<?> target) {
        UiKit.add(page, UiKit.navigationRow(this, title, description,
                () -> startActivity(new Intent(this, target))), UiKit.GAP_CONTROL);
    }
}
