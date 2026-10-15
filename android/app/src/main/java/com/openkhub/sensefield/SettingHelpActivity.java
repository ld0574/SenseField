package com.openkhub.sensefield;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Full group help, secondary to the panel. Opening it never changes preferences. */
public final class SettingHelpActivity extends UiActivity {
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
        UiKit.pageHeader(this, page, titleText + "的说明", "设置说明");

        LinearLayout card = UiKit.card(this);
        UiKit.add(card, UiKit.readingSections(this, SettingHelpContent.text(key)), 0);
        for (String itemKey : SettingHelpContent.itemKeys(key)) {
            UiKit.gap(card, 18);
            UiKit.add(card, UiKit.heading(this, SettingHelpContent.title(itemKey)), 6);
            UiKit.add(card, UiKit.readingSections(this, SettingHelpContent.text(itemKey)), 0);
        }
        UiKit.add(page, card, 20);

        setContentView(scroll);
    }
}
