package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

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
        entry(content, "测试记录与反馈", "标记问题，导出本地诊断记录", DiagnosticsActivity.class);
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
