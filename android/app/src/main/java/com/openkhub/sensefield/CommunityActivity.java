package com.openkhub.sensefield;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** 社区入口：列出七个板块，选中哪个就到哪个板块发帖。 */
public final class CommunityActivity extends UiActivity {

    /** 传给发帖页的板块 key；只认 {@link CommunitySection#fromKey(String)} 认得出的值。 */
    public static final String EXTRA_SECTION = "community_section";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page);

        UiKit.pageHeader(this, page, "社区", "听野");

        LinearLayout sections = UiKit.card(this);
        UiKit.add(sections, UiKit.heading(this, "选择板块"), 8);
        for (CommunitySection section : CommunitySection.values()) {
            // 先读板块名再读用途，读屏滑动时不用回头确认自己停在第几个。
            Button entry = UiKit.button(this, section.label(), UiKit.ButtonStyle.OUTLINED);
            entry.setContentDescription(section.label() + "，进入并发帖");
            entry.setOnClickListener(view -> openComposer(section));
            UiKit.add(sections, entry, UiKit.GAP_CONTROL);
        }
        ((LinearLayout.LayoutParams) sections.getChildAt(sections.getChildCount() - 1)
                .getLayoutParams()).bottomMargin = 0;
        UiKit.add(page, sections, UiKit.GAP_SECTION);

        UiKit.add(page, UiKit.hint(this,
                "帖子发往本地社区服务，未配置地址时发往手机本机（127.0.0.1:8765）。"), 0);
        setContentView(scroll);
    }

    private void openComposer(CommunitySection section) {
        Intent intent = new Intent(this, ComposePostActivity.class);
        intent.putExtra(EXTRA_SECTION, section.key());
        startActivity(intent);
    }
}
