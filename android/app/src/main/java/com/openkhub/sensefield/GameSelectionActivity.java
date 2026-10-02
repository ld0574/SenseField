package com.openkhub.sensefield;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** First screen: choose the game adapter to use. */
public final class GameSelectionActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page);

        UiKit.addBrandHeader(page, "多游戏辅助");
        UiKit.add(page, UiKit.text(this, "选择辅助游戏", 28, UiKit.INK, true), 4);
        UiKit.add(page, UiKit.body(this, "选择可用游戏后开始辅助。"), 18);

        for (GameCatalog.GameEntry game : GameCatalog.GAMES) {
            addGameButton(page, game);
            UiKit.gap(page, 14);
        }

        addCommunityLogo(page);

        setContentView(scroll);
    }

    private void addCommunityLogo(LinearLayout page) {
        // Keep the community mark below the game entrances and visually secondary.
        View spacer = new View(this);
        page.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.openkhub_logo);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("K-Hub 罕见病开源社区");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                UiKit.dp(this, 210), UiKit.dp(this, 71));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoParams.topMargin = UiKit.dp(this, 32);
        logoParams.bottomMargin = UiKit.dp(this, 8);
        page.addView(logo, logoParams);
    }

    private void addGameButton(LinearLayout page, GameCatalog.GameEntry game) {
        boolean available = game.available;
        LinearLayout button = UiKit.horizontal(this);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(UiKit.dp(this, 22), UiKit.dp(this, 18),
                UiKit.dp(this, 22), UiKit.dp(this, 18));
        button.setMinimumHeight(UiKit.dp(this, 112));
        button.setBackground(UiKit.ripple(this,
                available ? UiKit.SURFACE : UiKit.DISABLED_BG,
                UiKit.BORDER, 22, 0x33236874));
        button.setElevation(available ? UiKit.dp(this, 1) : 0);
        button.setFocusable(available);
        button.setClickable(available);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        button.setContentDescription(available
                ? game.name + "，" + game.subtitle + "，当前可用，进入辅助。"
                : game.name + "，" + game.subtitle + "，即将适配。暂不可进入。");
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
                info.setClickable(available);
                info.setEnabled(available);
            }
        });

        LinearLayout labels = UiKit.vertical(this);
        TextView name = UiKit.text(this, game.name, 24, UiKit.INK, true);
        labels.addView(name);
        TextView subtitle = UiKit.body(this, game.subtitle);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = UiKit.dp(this, 2);
        labels.addView(subtitle, subtitleParams);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        labelsParams.rightMargin = UiKit.dp(this, 12);
        button.addView(labels, labelsParams);

        TextView status = UiKit.text(this, available ? "开始辅助" : "即将适配", 20,
                available ? UiKit.PRIMARY : UiKit.MUTED, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 8),
                UiKit.dp(this, 10), UiKit.dp(this, 8));
        status.setBackground(UiKit.shape(this,
                available ? UiKit.PRIMARY_SOFT : Color.WHITE, Color.TRANSPARENT, 18));
        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(status);

        if (available) {
            button.setOnClickListener(view -> game.open(this));
            button.setOnFocusChangeListener((view, focused) ->
                    view.setBackground(UiKit.ripple(this,
                            focused ? UiKit.PRIMARY_SOFT : UiKit.SURFACE,
                            UiKit.BORDER, 22, 0x33236874)));
        }
        UiKit.add(page, button, 0);
    }
}
