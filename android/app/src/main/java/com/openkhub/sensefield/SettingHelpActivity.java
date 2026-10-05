package com.openkhub.sensefield;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Scrollable, setting-specific help page. Opening it never reads or writes preferences. */
public final class SettingHelpActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.configureWindow(this);

        String key = getIntent().getStringExtra(SettingHelp.EXTRA_KEY);
        if (key == null) key = "";
        String titleText = SettingHelpContent.title(key);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        scroll.setBackgroundColor(UiKit.PAGE);

        LinearLayout page = UiKit.page(this);
        scroll.addView(page);
        UiKit.addBrandHeader(page, "设置说明");
        TextView title = UiKit.heading(this, titleText + "的说明");
        title.setAccessibilityHeading(true);
        UiKit.add(page, title, 16);

        LinearLayout card = UiKit.card(this);
        TextView explanation = UiKit.readingBody(this, SettingHelpContent.text(key), 22);
        UiKit.add(card, explanation, 0);
        UiKit.add(page, card, 20);

        Button back = UiKit.button(this, "返回", false);
        back.setTextSize(22);
        back.setMinHeight(UiKit.dp(this, 72));
        back.setMinimumHeight(UiKit.dp(this, 72));
        back.setOnClickListener(view -> finish());
        UiKit.add(page, back, 0);
        setContentView(scroll);
    }
}
