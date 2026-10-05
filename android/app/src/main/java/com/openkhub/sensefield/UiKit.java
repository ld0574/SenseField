package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;

/** Small platform-only visual system shared by the app's screens. */
final class UiKit {
    static final int PAGE = Color.rgb(251, 247, 241);
    static final int SURFACE = Color.WHITE;
    static final int INK = Color.rgb(25, 49, 57);
    static final int MUTED = Color.rgb(84, 107, 114);
    static final int PRIMARY = Color.rgb(35, 104, 116);
    static final int PRIMARY_SOFT = Color.rgb(231, 242, 242);
    static final int BORDER = Color.rgb(218, 229, 228);
    static final int LOGO_BG = Color.rgb(240, 198, 178);
    static final int DISABLED_BG = Color.rgb(240, 244, 243);
    static final int PENDING_BG = Color.rgb(255, 243, 222);
    static final int PENDING_TEXT = Color.rgb(111, 75, 25);

    private UiKit() {}

    static void configureWindow(Activity activity) {
        Window window = activity.getWindow();
        window.setStatusBarColor(PAGE);
        window.setNavigationBarColor(PAGE);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout vertical(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout horizontal(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static LinearLayout page(Context context) {
        LinearLayout page = new LinearLayout(context) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int mode = MeasureSpec.getMode(widthMeasureSpec);
                if (mode != MeasureSpec.UNSPECIFIED) {
                    int width = PageGeometry.readingWidthPx(MeasureSpec.getSize(widthMeasureSpec),
                            getResources().getDisplayMetrics().density);
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(width, mode);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                if (getLayoutParams() instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) getLayoutParams();
                    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    setLayoutParams(params);
                }
            }
        };
        page.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(context, 20);
        page.setPadding(inset, dp(context, 20), inset, dp(context, 28));
        return page;
    }

    static LinearLayout card(Context context) {
        LinearLayout card = vertical(context);
        card.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        card.setBackground(shape(context, SURFACE, BORDER, 22));
        card.setElevation(dp(context, 1));
        card.setClipToOutline(true);
        return card;
    }

    static LinearLayout accentCard(Context context) {
        LinearLayout card = vertical(context);
        card.setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20));
        card.setBackground(shape(context, PRIMARY_SOFT, Color.TRANSPARENT, 22));
        return card;
    }

    static GradientDrawable shape(Context context, int fill, int stroke, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    static RippleDrawable ripple(Context context, int fill, int stroke, float radiusDp,
                                 int rippleColor) {
        GradientDrawable content = shape(context, fill, stroke, radiusDp);
        GradientDrawable mask = shape(context, Color.WHITE, Color.TRANSPARENT, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static TextView text(Context context, CharSequence value, float sp, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setLineSpacing(dp(context, 2), 1f);
        return text;
    }

    static TextView heading(Context context, CharSequence value) {
        TextView title = text(context, value, 22, INK, true);
        title.setAccessibilityHeading(true);
        return title;
    }

    static TextView body(Context context, CharSequence value) {
        return text(context, value, 20, MUTED, false);
    }

    /** Continuous reading only: controls and live status retain the user's full font scale. */
    static TextView readingBody(Context context, CharSequence value, float sp) {
        TextView text = text(context, value, sp, INK, false);
        Configuration configuration = context.getResources().getConfiguration();
        if (configuration.fontScale > 1.5f) {
            Configuration reading = new Configuration(configuration);
            reading.fontScale = 1.5f;
            Context readingContext = context.createConfigurationContext(reading);
            // Use Android's SP conversion, including its nonlinear large-font scaling.
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, sp,
                    readingContext.getResources().getDisplayMetrics()));
        }
        return text;
    }

    static void add(LinearLayout parent, View child, float bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(parent.getContext(), bottomDp);
        parent.addView(child, params);
    }

    static void addWeighted(LinearLayout parent, View child, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        parent.addView(child, params);
    }

    static void gap(LinearLayout parent, float heightDp) {
        View gap = new View(parent.getContext());
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(parent.getContext(), heightDp)));
    }

    static Button button(Context context, CharSequence label, boolean primary) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(22);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 68));
        button.setMinimumHeight(dp(context, 68));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        button.setBackground(ripple(context, primary ? PRIMARY : SURFACE,
                primary ? Color.TRANSPARENT : BORDER, 15,
                primary ? 0x33FFFFFF : 0x18000000));
        button.setTextColor(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {primary ? Color.rgb(207, 220, 222) : MUTED,
                        primary ? Color.WHITE : INK}));
        button.setStateListAnimator(null);
        button.setElevation(dp(context, 0));
        return button;
    }

    static void styleCheckable(View view, Context context) {
        view.setMinimumHeight(dp(context, 64));
        if (view instanceof android.widget.CompoundButton) {
            ((android.widget.CompoundButton) view).setButtonTintList(
                    ColorStateList.valueOf(PRIMARY));
        }
    }

    static void styleSeekBar(android.widget.SeekBar seekBar, Context context) {
        seekBar.setMinimumHeight(dp(context, 48));
        seekBar.setProgressTintList(ColorStateList.valueOf(PRIMARY));
        seekBar.setThumbTintList(ColorStateList.valueOf(PRIMARY));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
    }

    static void addBrandHeader(LinearLayout parent, String contextLabel) {
        Context context = parent.getContext();
        LinearLayout row = horizontal(context);
        LinearLayout markTile = new LinearLayout(context);
        markTile.setGravity(Gravity.CENTER);
        markTile.setBackground(shape(context, LOGO_BG, Color.TRANSPARENT, 13));
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.sensefield_mark_compact);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setContentDescription("听野品牌标记");
        int markInset = dp(context, 3);
        mark.setPadding(markInset, markInset, markInset, markInset);
        markTile.addView(mark, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        row.addView(markTile, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));

        LinearLayout labels = vertical(context);
        labels.setPadding(dp(context, 12), 0, 0, 0);
        labels.addView(text(context, "听野", 20, INK, true));
        labels.addView(text(context, contextLabel, 20, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        add(parent, row, 24);
    }

    static void addSectionTitle(LinearLayout parent, String title, String subtitle) {
        add(parent, heading(parent.getContext(), title), subtitle.isEmpty() ? 12 : 4);
        if (!subtitle.isEmpty()) add(parent, body(parent.getContext(), subtitle), 14);
    }
}
