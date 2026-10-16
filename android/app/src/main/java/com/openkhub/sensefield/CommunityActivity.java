package com.openkhub.sensefield;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 社区入口：列出七个板块，选中哪个就到哪个板块发帖。 */
public final class CommunityActivity extends UiActivity {

    /** 传给发帖页的板块 key；只认 {@link CommunitySection#fromKey(String)} 认得出的值。 */
    public static final String EXTRA_SECTION = "community_section";

    private static final String PREF_ENDPOINT = "community_endpoint";
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8765";

    private EditText endpointInput;
    private TextView endpointStatus;

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

        LinearLayout service = UiKit.card(this);
        UiKit.add(service, UiKit.heading(this, "社区服务地址"), 8);
        endpointInput = new EditText(this);
        UiKit.styleInput(endpointInput);
        endpointInput.setSingleLine(true);
        endpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpointInput.setHint(DEFAULT_ENDPOINT);
        endpointInput.setText(GameProfile.settings(this).getString(PREF_ENDPOINT, ""));
        UiKit.add(service, UiKit.field(this, "服务地址", endpointInput), UiKit.GAP_CONTROL);
        UiKit.add(service, UiKit.hint(this,
                "留空则发往手机本机 " + DEFAULT_ENDPOINT + "；填电脑地址时手机要和电脑在同一网络。"),
                UiKit.GAP_CONTROL);

        endpointStatus = UiKit.body(this, "");
        endpointStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(service, endpointStatus, UiKit.GAP_CONTROL);

        Button save = UiKit.button(this, "保存地址", UiKit.ButtonStyle.FILLED);
        ((LinearLayout.LayoutParams) save.getLayoutParams()).bottomMargin = 0;
        save.setOnClickListener(view -> saveEndpoint());
        UiKit.add(service, save, 0);
        UiKit.add(page, service, UiKit.GAP_SECTION);

        setContentView(scroll);
    }

    /** 只在地址真的能用时改设置；写坏的值留在输入框里让用户改，不静默丢弃。 */
    private void saveEndpoint() {
        String value = endpointInput.getText() == null
                ? "" : endpointInput.getText().toString().trim();
        SharedPreferences preferences = GameProfile.settings(this);
        if (value.isEmpty()) {
            preferences.edit().remove(PREF_ENDPOINT).apply();
            endpointStatus.setTextColor(UiKit.MUTED);
            endpointStatus.setText("已恢复默认：" + DEFAULT_ENDPOINT);
            return;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            endpointStatus.setTextColor(UiKit.DANGER);
            endpointStatus.setText("地址要以 http:// 或 https:// 开头。已填的内容保留着，改完再保存。");
            return;
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        preferences.edit().putString(PREF_ENDPOINT, value).apply();
        endpointStatus.setTextColor(UiKit.MUTED);
        endpointStatus.setText("已保存。帖子会发往 " + value);
    }

    private void openComposer(CommunitySection section) {
        Intent intent = new Intent(this, ComposePostActivity.class);
        intent.putExtra(EXTRA_SECTION, section.key());
        startActivity(intent);
    }
}