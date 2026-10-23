package com.openkhub.sensefield;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** First screen: choose the game adapter to use. */
public final class GameSelectionActivity extends UiActivity {
    private AppUpdateController updater;
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
        UiKit.add(page, UiKit.pageTitle(this, "选择辅助游戏"), 8);
        UiKit.add(page, UiKit.hint(this, "选择游戏，开始语音与触觉辅助。"), 24);

        for (GameCatalog.GameEntry game : GameCatalog.GAMES) {
            addGameButton(page, game);
            UiKit.gap(page, UiKit.GAP_SECTION);
        }

        Object retained = getLastNonConfigurationInstance();
        updater = retained instanceof AppUpdateController
                ? (AppUpdateController) retained : new AppUpdateController(this);
        updater.attach(this, page);
        addCommunityLogo(page);

        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        if (updater != null) updater.resume();
    }

    @Override protected void onPause() {
        if (updater != null) updater.pause();
        super.onPause();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        if (updater != null) updater.detach();
        return updater;
    }

    @Override protected void onDestroy() {
        if (updater != null) {
            if (isChangingConfigurations()) updater.detach();
            else updater.close();
        }
        super.onDestroy();
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
        LinearLayout button = UiKit.vertical(this);
        button.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 20),
                UiKit.dp(this, 20), UiKit.dp(this, 20));
        button.setMinimumHeight(UiKit.dp(this, 104));
        // The whole card is the target: give it a visible edge plus focus and unavailable states.
        button.setBackground(UiKit.controlBackground(this, UiKit.ButtonStyle.OUTLINED, 22));
        button.setEnabled(available);
        button.setElevation(0);
        button.setFocusable(available);
        button.setClickable(available);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        button.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        button.setContentDescription(available
                ? game.name + "，" + game.subtitle + "，" + game.availability + "，开始辅助。"
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
        TextView gameTitle = UiKit.heading(this, game.name);
        UiKit.add(labels, gameTitle, 6);
        UiKit.add(labels, UiKit.hint(this, game.subtitle), 6);
        UiKit.add(labels, UiKit.text(this, game.availability, 16, UiKit.PRIMARY, false), 0);
        TextView action = UiKit.text(this, available ? "开始辅助" : "即将适配", 18,
                available ? UiKit.PRIMARY : UiKit.MUTED, true);
        action.setGravity(Gravity.CENTER);
        action.setMinimumHeight(UiKit.dp(this, 56));
        action.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 8));
        action.setBackground(UiKit.shape(this, UiKit.PRIMARY_SOFT, Color.TRANSPARENT, 20));
        LinearLayout cardBody = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                float titleWidth = gameTitle.getPaint().measureText(game.name);
                float actionWidth = action.getPaint().measureText(action.getText().toString())
                        + action.getPaddingLeft() + action.getPaddingRight();
                boolean stack = titleWidth + actionWidth + UiKit.dp(getContext(), 16) > width;
                LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) labels.getLayoutParams();
                int desiredWidth = stack ? LayoutParams.MATCH_PARENT : 0;
                if (getOrientation() != (stack ? VERTICAL : HORIZONTAL) || labelParams.width != desiredWidth) {
                    setOrientation(stack ? VERTICAL : HORIZONTAL);
                    labelParams.width = desiredWidth;
                    labelParams.weight = stack ? 0 : 1;
                    labels.setLayoutParams(labelParams);
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) action.getLayoutParams();
                    params.setMargins(0, stack ? UiKit.dp(getContext(), 12) : 0, 0, 0);
                    params.setMarginStart(stack ? 0 : UiKit.dp(getContext(), 16));
                    params.gravity = stack ? Gravity.END : -1;
                    action.setLayoutParams(params);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        cardBody.setGravity(Gravity.CENTER_VERTICAL);
        cardBody.addView(labels);
        cardBody.addView(action);
        cardBody.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        UiKit.add(button, cardBody, 0);

        if (available) {
            button.setOnClickListener(view -> {
                if (updater != null) updater.beforeGame();
                game.open(this);
            });
        }
        UiKit.add(page, button, 0);
    }
}
