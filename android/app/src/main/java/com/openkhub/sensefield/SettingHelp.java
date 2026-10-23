package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

/** Adds contextual help entries beside setting groups and controls. */
final class SettingHelp {
    static final String EXTRA_KEY = "setting_help_key";

    private SettingHelp() {}

    /** Adds a self-contained group heading, compact help action, and short summary. */
    static Button addGroup(Activity activity, LinearLayout parent, String key, float bottomDp) {
        LinearLayout group = UiKit.vertical(activity);
        LinearLayout titleRow = UiKit.horizontal(activity);
        android.widget.TextView title = UiKit.heading(activity, SettingHelpContent.title(key));
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button help = new Button(activity);
        help.setText("");
        help.setAllCaps(false);
        // Touch must click immediately; keyboard and accessibility can still focus it.
        help.setFocusable(true);
        help.setFocusableInTouchMode(false);
        help.setGravity(android.view.Gravity.CENTER);
        help.setPadding(0, 0, 0, 0);
        help.setMinWidth(UiKit.dp(activity, 56));
        help.setMinimumWidth(UiKit.dp(activity, 56));
        help.setMinHeight(UiKit.dp(activity, 56));
        help.setMinimumHeight(UiKit.dp(activity, 56));
        GradientDrawable touchMask = new GradientDrawable();
        touchMask.setShape(GradientDrawable.OVAL);
        touchMask.setColor(Color.WHITE);
        Drawable icon = new InsetDrawable(new QuestionDrawable(activity), UiKit.dp(activity, 16));
        help.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000),
                focusableIcon(activity, icon), touchMask));
        help.setContentDescription("了解更多：" + SettingHelpContent.title(key));
        help.setTag(SettingHelpPanel.GROUP_BUTTON_TAG_PREFIX + key);
        help.setOnClickListener(view -> show(activity, key, view));
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(
                UiKit.dp(activity, 56), UiKit.dp(activity, 56));
        helpParams.setMarginStart(UiKit.dp(activity, 8));
        titleRow.addView(help, helpParams);
        UiKit.add(group, titleRow, 4);

        android.widget.TextView summary = UiKit.text(activity,
                SettingHelpContent.summary(key), 16, UiKit.MUTED, false);
        summary.setTag(SettingHelpPanel.GROUP_SUMMARY_TAG_PREFIX + key);
        UiKit.add(group, summary, 4);
        UiKit.add(parent, group, bottomDp);
        return help;
    }

    /** Opens or replaces the currently visible contextual panel. */
    static void show(Activity activity, String key) {
        show(activity, key, null);
    }

    static boolean close(Activity activity) {
        return SettingHelpPanel.close(activity);
    }

    private static void show(Activity activity, String key, View trigger) {
        SettingHelpPanel.show(activity, key, trigger);
    }

    /** Icon-only control: a dark ring appears around the icon when it has keyboard focus. */
    static Drawable focusableIcon(android.content.Context activity, Drawable icon) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(UiKit.dp(activity, 3), UiKit.INK);
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused},
                new android.graphics.drawable.LayerDrawable(new Drawable[] {icon, ring}));
        states.addState(new int[] {}, icon);
        return states;
    }

    private static final class QuestionDrawable extends Drawable {
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float stroke;
        private final float textSize;
        private final float baselineShift;

        QuestionDrawable(Activity activity) {
            stroke = UiKit.dp(activity, 2);
            textSize = UiKit.dp(activity, 15);
            circle.setColor(UiKit.INK);
            circle.setStyle(Paint.Style.STROKE);
            circle.setStrokeWidth(stroke);
            glyph.setColor(UiKit.INK);
            glyph.setTextAlign(Paint.Align.CENTER);
            glyph.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            glyph.setTextSize(textSize);
            Paint.FontMetrics metrics = glyph.getFontMetrics();
            baselineShift = -(metrics.ascent + metrics.descent) / 2f;
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            float radius = Math.max(0f, Math.min(bounds.width(), bounds.height()) / 2f - stroke);
            canvas.drawCircle(cx, cy, radius, circle);
            canvas.drawText("?", cx, cy + baselineShift, glyph);
        }

        @Override public void setAlpha(int alpha) {
            circle.setAlpha(alpha);
            glyph.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            circle.setColorFilter(filter);
            glyph.setColorFilter(filter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Legacy full-page explanation entry. Kept for individual controls and callers that have
     * not migrated to group help.
     */
    static Button add(Activity activity, LinearLayout parent, String key, float bottomDp) {
        Button button = UiKit.button(activity, SettingHelpContent.title(key) + "：说明", false);
        button.setTextSize(UiKit.TEXT_BODY);
        button.setMinHeight(UiKit.dp(activity, 56));
        button.setMinimumHeight(UiKit.dp(activity, 56));
        button.setContentDescription(SettingHelpContent.title(key) + "的说明");
        button.setOnClickListener(view -> activity.startActivity(new Intent(activity,
                SettingHelpActivity.class).putExtra(EXTRA_KEY, key)));
        View previous = parent.getChildCount() == 0 ? null : parent.getChildAt(parent.getChildCount() - 1);
        if (previous instanceof CompoundButton || (previous != null && previous.isAccessibilityHeading())) {
            // Keep the legacy explanation beside its setting, including after scrolling
            // or large-font reflow. Reparenting does not change listeners/state.
            parent.removeView(previous);
            LinearLayout row = UiKit.horizontal(activity);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.setMarginEnd(UiKit.dp(activity, 12));
            row.addView(previous, labelParams);
            button.setText("说明");
            row.addView(button, new LinearLayout.LayoutParams(UiKit.dp(activity, 120),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            UiKit.add(parent, row, bottomDp);
        } else UiKit.add(parent, button, bottomDp);
        return button;
    }
}
