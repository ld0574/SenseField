package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Large, directly visible settings entries for low-vision users. */
public final class AppSettingsActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);
        UiKit.addBrandHeader(content, "王者荣耀");
        UiKit.add(content, UiKit.text(this, "设置", 32, UiKit.INK, true), 24);

        button(content, "授权与运行设置").setOnClickListener(view ->
                startActivity(new Intent(this, CapturePermissionsActivity.class)));
        button(content, "配置与调参").setOnClickListener(view ->
                startActivity(new Intent(this, GameTuningActivity.class)));
        button(content, "测试记录与反馈").setOnClickListener(view ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        android.view.View spacer = new android.view.View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 28), 1f));
        button(content, "返回辅助主页").setOnClickListener(view -> finish());
        setContentView(scroll);
    }

    private Button button(LinearLayout parent, String label) {
        Button button = UiKit.button(this, label, false);
        button.setTextSize(24);
        button.setMinHeight(UiKit.dp(this, 80));
        button.setMinimumHeight(UiKit.dp(this, 80));
        UiKit.add(parent, button, 18);
        return button;
    }
}
