package com.openkhub.sensefield;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.util.TypedValue;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;

/** Small platform-only visual system shared by the app's screens. */
final class UiKit {
    static final int PAGE = Color.rgb(251, 247, 241);
    static final int SURFACE = Color.WHITE;
    static final int INK = Color.rgb(25, 49, 57);
    static final int MUTED = Color.rgb(84, 107, 114);
    static final int PRIMARY = Color.rgb(35, 104, 116);
    static final int PRIMARY_SOFT = Color.rgb(231, 242, 242);
    /** Decorative card edge only; interactive controls use {@link #OUTLINE}. */
    static final int BORDER = Color.rgb(218, 229, 228);
    /** Edge of an interactive control: at least 3:1 against both the page and white cards. */
    static final int OUTLINE = Color.rgb(90, 124, 131);
    static final int DANGER = Color.rgb(170, 45, 35);
    static final int LOGO_BG = Color.rgb(240, 198, 178);
    static final int DISABLED_BG = Color.rgb(240, 244, 243);
    static final int DISABLED_OUTLINE = Color.rgb(130, 150, 154);
    static final int PENDING_BG = Color.rgb(255, 243, 222);
    static final int PENDING_TEXT = Color.rgb(111, 75, 25);

    /** Space between neighbouring touch targets, whether stacked or side by side. */
    static final float GAP_CONTROL = 12;
    /** Space between cards and other page-level groups. */
    static final float GAP_SECTION = 24;
    static final float TEXT_PAGE = 24;
    static final float TEXT_HEADING = 20;
    static final float TEXT_BODY = 18;
    static final float TEXT_HINT = 16;

    /** One filled action per group; outlined for the rest; danger only for irreversible actions. */
    enum ButtonStyle { FILLED, OUTLINED, TONAL, DANGER, NAVIGATION }

    private UiKit() {}

    static void configureWindow(Activity activity) {
        Window window = activity.getWindow();
        window.setStatusBarColor(PAGE);
        window.setNavigationBarColor(PAGE);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (android.os.Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
            int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            window.getInsetsController().setSystemBarsAppearance(appearance, appearance);
        }
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
        card.setElevation(0);
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
        return shape(context, fill, stroke, radiusDp, 1);
    }

    static GradientDrawable shape(Context context, int fill, int stroke, float radiusDp,
                                  float strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(context, strokeDp), stroke);
        return drawable;
    }

    static RippleDrawable ripple(Context context, int fill, int stroke, float radiusDp,
                                 int rippleColor) {
        GradientDrawable content = shape(context, fill, stroke, radiusDp);
        GradientDrawable mask = shape(context, Color.WHITE, Color.TRANSPARENT, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    /**
     * Shared control surface. Enabled controls keep a visible edge; keyboard and switch focus
     * adds a dark ring with a white inner ring; disabled controls turn grey with a quiet edge,
     * so availability never depends on text colour alone.
     */
    static Drawable controlBackground(Context context, ButtonStyle style, float radiusDp) {
        int fill = style == ButtonStyle.FILLED ? PRIMARY
                : style == ButtonStyle.TONAL ? PRIMARY_SOFT : SURFACE;
        int edge = style == ButtonStyle.FILLED || style == ButtonStyle.NAVIGATION ? Color.TRANSPARENT
                : style == ButtonStyle.TONAL ? PRIMARY
                : style == ButtonStyle.DANGER ? DANGER : OUTLINE;
        GradientDrawable disabled = shape(context, DISABLED_BG, DISABLED_OUTLINE, radiusDp);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {-android.R.attr.state_enabled}, disabled);
        states.addState(new int[] {android.R.attr.state_focused},
                focusRing(context, fill, radiusDp));
        states.addState(new int[] {}, shape(context, fill, edge, radiusDp, 1));
        GradientDrawable mask = shape(context, Color.WHITE, Color.TRANSPARENT, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(
                style == ButtonStyle.FILLED ? 0x33FFFFFF : 0x22000000), states, mask);
    }

    private static Drawable focusRing(Context context, int fill, float radiusDp) {
        int outer = dp(context, 3);
        int inner = dp(context, 5);
        LayerDrawable ring = new LayerDrawable(new Drawable[] {
                shape(context, INK, Color.TRANSPARENT, radiusDp),
                shape(context, Color.WHITE, Color.TRANSPARENT, Math.max(0, radiusDp - 3)),
                shape(context, fill, Color.TRANSPARENT, Math.max(0, radiusDp - 5))});
        ring.setLayerInset(1, outer, outer, outer, outer);
        ring.setLayerInset(2, inner, inner, inner, inner);
        return ring;
    }

    /** Applies the shared surface and text colours, keeping the control's own padding. */
    static void styleControl(TextView control, ButtonStyle style, float radiusDp) {
        int left = control.getPaddingLeft();
        int top = control.getPaddingTop();
        int right = control.getPaddingRight();
        int bottom = control.getPaddingBottom();
        control.setBackground(controlBackground(control.getContext(), style, radiusDp));
        control.setPadding(left, top, right, bottom);
        control.setTextColor(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {MUTED, style == ButtonStyle.FILLED ? Color.WHITE
                        : style == ButtonStyle.DANGER ? DANGER : INK}));
    }

    static TextView text(Context context, CharSequence value, float sp, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setLineSpacing(0, 1.2f);
        return text;
    }

    static TextView heading(Context context, CharSequence value) {
        TextView title = text(context, value, TEXT_HEADING, INK, true);
        title.setAccessibilityHeading(true);
        return title;
    }

    static TextView body(Context context, CharSequence value) {
        return text(context, value, TEXT_BODY, INK, false);
    }

    /** Page title shared by all full-screen pages. */
    static TextView pageTitle(Context context, CharSequence value) {
        TextView title = text(context, value, TEXT_PAGE, INK, true);
        title.setLineSpacing(0, 1.1f);
        title.setAccessibilityHeading(true);
        return title;
    }

    /** Group heading with an optional one-line purpose, kept together as one block. */
    static LinearLayout titleBlock(Context context, CharSequence title, CharSequence description) {
        LinearLayout block = vertical(context);
        if (description == null || description.length() == 0) {
            block.addView(heading(context, title));
        } else {
            add(block, heading(context, title), 4);
            add(block, text(context, description, 18, MUTED, false), 0);
        }
        return block;
    }

    /** One short muted line that explains the control directly above it. */
    static TextView hint(Context context, CharSequence value) {
        return text(context, value, TEXT_HINT, MUTED, false);
    }

    /**
     * Keeps a control's condition under the control instead of inside its label, so labels stay
     * one short line. Screen readers hear the label and the hint together on the control.
     */
    static LinearLayout withHint(View control, CharSequence hintText) {
        Context context = control.getContext();
        LinearLayout group = vertical(context);
        spaceChildren(group, 6);
        group.addView(control, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView hint = hint(context, hintText);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        group.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (control instanceof TextView) {
            control.setContentDescription(((TextView) control).getText() + "，" + hintText);
        }
        return group;
    }

    /** Numbered steps: one short instruction per line instead of a paragraph. */
    static LinearLayout steps(Context context, CharSequence... lines) {
        return list(context, true, lines);
    }

    /** Short facts, one per line, in place of a dense paragraph. */
    static LinearLayout bullets(Context context, CharSequence... lines) {
        return list(context, false, lines);
    }

    private static LinearLayout list(Context context, boolean numbered, CharSequence... lines) {
        LinearLayout list = vertical(context);
        spaceChildren(list, numbered ? 10 : 6);
        for (int index = 0; index < lines.length; index++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            float size = TEXT_BODY;
            TextView marker = text(context, numbered ? (index + 1) + "." : "•", size,
                    numbered ? PRIMARY : MUTED, true);
            marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            markerParams.setMarginEnd(dp(context, 8));
            row.addView(marker, markerParams);
            row.addView(text(context, lines[index], size, INK, false),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            // Each line is one stop for a screen reader, with the step number spoken.
            row.setScreenReaderFocusable(true);
            row.setContentDescription(numbered ? "第" + (index + 1) + "步，" + lines[index]
                    : lines[index]);
            list.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return list;
    }

    /**
     * Collapsed details: a large labelled toggle with the complete text underneath, shown only
     * on request. Nothing is removed; the screen just stops leading with paragraphs.
     */
    static LinearLayout details(Context context, CharSequence label, CharSequence... paragraphs) {
        LinearLayout section = vertical(context);
        Button toggle = button(context, label, ButtonStyle.OUTLINED);
        toggle.setTextSize(TEXT_BODY);
        toggle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        toggle.setMinHeight(dp(context, 60));
        toggle.setMinimumHeight(dp(context, 60));
        ChevronDrawable chevron = new ChevronDrawable(context);
        int size = dp(context, 18);
        chevron.setBounds(0, 0, size, size);
        toggle.setCompoundDrawablesRelative(null, null, chevron, null);
        toggle.setCompoundDrawablePadding(dp(context, 12));
        LinearLayout body = vertical(context);
        spaceChildren(body, 12);
        body.setPadding(dp(context, 4), dp(context, 12), dp(context, 4), 0);
        for (CharSequence paragraph : paragraphs) {
            body.addView(readingBody(context, paragraph, TEXT_BODY), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        body.setVisibility(View.GONE);
        section.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        section.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        Runnable sync = () -> {
            boolean open = body.getVisibility() == View.VISIBLE;
            chevron.setPointingUp(open);
            ViewCompat.setStateDescription(toggle, open ? "已展开" : "已折叠");
        };
        toggle.setOnClickListener(view -> {
            body.setVisibility(body.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            sync.run();
        });
        section.setTag("ui_details:" + label);
        body.setTag("ui_details_body");
        body.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> sync.run());
        sync.run();
        return section;
    }

    /** Kinds of state shown by {@link #setStatus}. */
    enum Status { DONE, NEEDED, INFO }

    /** A compact state badge; hidden until {@link #setStatus} gives it a state. */
    static TextView statusChip(Context context) {
        TextView chip = text(context, "", TEXT_HINT, INK, true);
        chip.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        chip.setVisibility(View.GONE);
        return chip;
    }

    static void addChip(LinearLayout parent, TextView chip, float bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(parent.getContext(), bottomDp);
        parent.addView(chip, params);
    }

    /** Shows a state with words, a mark and colour together, never colour alone. */
    static void setStatus(TextView chip, Status status, CharSequence value) {
        Context context = chip.getContext();
        int fill = status == Status.DONE ? PRIMARY_SOFT
                : status == Status.NEEDED ? PENDING_BG : DISABLED_BG;
        int ink = status == Status.DONE ? PRIMARY
                : status == Status.NEEDED ? PENDING_TEXT : INK;
        String mark = status == Status.DONE ? "✓ " : status == Status.NEEDED ? "！" : "";
        setTextIfChanged(chip, mark + value);
        chip.setContentDescription(value);
        chip.setTextColor(ink);
        chip.setBackground(shape(context, fill,
                status == Status.NEEDED ? PENDING_TEXT : Color.TRANSPARENT, 12, 1));
        chip.setVisibility(View.VISIBLE);
    }

    /** All reading follows the real system font scale, including Android's nonlinear scaling. */
    static TextView readingBody(Context context, CharSequence value, float sp) {
        TextView text = text(context, value, sp, INK, false);
        text.setLineSpacing(0, 1.4f);
        return text;
    }

    /** Original copy is retained in its tag; paragraphs change presentation only. */
    static LinearLayout readingSections(Context context, String copy) {
        LinearLayout paragraphs = vertical(context);
        paragraphs.setTag("ui_reading:" + copy);
        spaceChildren(paragraphs, 12);
        StringBuilder part = new StringBuilder();
        int sentences = 0;
        for (int i = 0; i < copy.length(); i++) {
            char c = copy.charAt(i);
            part.append(c);
            if (c == '。' || c == '！' || c == '？') sentences++;
            if (c == '\n' || (sentences >= 2 && part.length() >= 48) || i == copy.length() - 1) {
                paragraphs.addView(readingBody(context, part.toString().trim(), TEXT_BODY));
                part.setLength(0);
                sentences = 0;
            }
        }
        return paragraphs;
    }

    static void setTextIfChanged(TextView view, CharSequence value) {
        if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
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

    /**
     * Separates the visible children of a stack by a fixed gap, without leading or trailing
     * space. Hidden children take no gap, so optional controls never leave double spacing.
     */
    static void spaceChildren(LinearLayout layout, float gapDp) {
        GradientDrawable gap = new GradientDrawable();
        gap.setColor(Color.TRANSPARENT);
        int size = dp(layout.getContext(), gapDp);
        gap.setSize(size, size);
        layout.setDividerDrawable(gap);
        layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
    }

    /** Equal side-by-side actions with a fixed gap; stacks them when a label would not fit. */
    static ReminderSampleGrid actionRow(Context context) {
        return new ReminderSampleGrid(context, 2).fillRow();
    }

    /**
     * Makes dialog actions look and size like the app's buttons: a visible edge, at least 56dp
     * tall, and a 12dp gap between neighbours in both the side-by-side and stacked button bar.
     * Call after {@code show()}, when the dialog has created its buttons.
     */
    static void styleDialog(AlertDialog dialog, ButtonStyle positiveStyle) {
        Context context = dialog.getContext();
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextSize(TEXT_BODY);
            message.setTextColor(INK);
            message.setLineSpacing(0, 1.35f);
        }
        int half = dp(context, GAP_CONTROL / 2);
        int[] which = {AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_POSITIVE};
        for (int id : which) {
            Button button = dialog.getButton(id);
            if (button == null || button.getVisibility() != View.VISIBLE) continue;
            button.setAllCaps(false);
            button.setTextSize(id == AlertDialog.BUTTON_POSITIVE ? 20 : 18);
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            int actionHeight = id == AlertDialog.BUTTON_POSITIVE && positiveStyle == ButtonStyle.FILLED ? 64 : 56;
            button.setMinHeight(dp(context, actionHeight));
            button.setMinimumHeight(dp(context, actionHeight));
            button.setPadding(dp(context, 18), dp(context, 8), dp(context, 18), dp(context, 8));
            styleControl(button, id == AlertDialog.BUTTON_POSITIVE ? positiveStyle
                    : ButtonStyle.OUTLINED, 15);
            ViewGroup.LayoutParams params = button.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                margins.setMargins(half, half, half, half);
                margins.setMarginStart(half);
                margins.setMarginEnd(half);
                button.setLayoutParams(margins);
            }
        }
        // Reparent the existing buttons, retaining Dialog's actual listeners and dismiss logic.
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null && positive.getParent() instanceof ViewGroup) {
            ViewGroup original = (ViewGroup) positive.getParent();
            if (!(original instanceof ReminderSampleGrid)
                    && original.getParent() instanceof ViewGroup) {
                ViewGroup host = (ViewGroup) original.getParent();
                int position = host.indexOfChild(original);
                ViewGroup.LayoutParams params = original.getLayoutParams();
                ReminderSampleGrid actions = actionRow(context).reserveDialogHeight();
                // AlertDialogLayout identifies and reserves the button panel by its ID.
                // Without it, long messages consume the window and hide all the actions.
                actions.setId(original.getId());
                actions.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 12));
                for (int id : which) {
                    Button button = dialog.getButton(id);
                    if (button == null || button.getVisibility() != View.VISIBLE) continue;
                    if (button.getParent() instanceof ViewGroup)
                        ((ViewGroup) button.getParent()).removeView(button);
                    actions.addView(button);
                }
                host.removeView(original);
                host.addView(actions, position, params);
            }
        }
    }

    static Button button(Context context, CharSequence label, boolean primary) {
        return button(context, label, primary ? ButtonStyle.FILLED : ButtonStyle.OUTLINED);
    }

    static Button button(Context context, CharSequence label, ButtonStyle style) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(style == ButtonStyle.FILLED ? 20 : TEXT_BODY);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, style == ButtonStyle.FILLED ? 64 : 56));
        button.setMinimumHeight(dp(context, style == ButtonStyle.FILLED ? 64 : 56));
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setElevation(dp(context, 0));
        styleControl(button, style, 16);
        button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        return button;
    }

    static void styleCheckable(View view, Context context) {
        view.setMinimumHeight(dp(context, 64));
        if (view instanceof android.widget.CompoundButton) {
            ((android.widget.CompoundButton) view).setTextSize(TEXT_BODY);
            ((android.widget.CompoundButton) view).setTextColor(INK);
            ((android.widget.CompoundButton) view).setCompoundDrawablePadding(dp(context, 10));
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

    private static StateListDrawable inputBackground(Context context) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {-android.R.attr.state_enabled},
                shape(context, DISABLED_BG, DISABLED_OUTLINE, 12, 2));
        states.addState(new int[] {android.R.attr.state_focused},
                shape(context, SURFACE, PRIMARY, 12, 3));
        states.addState(new int[] {}, shape(context, SURFACE, OUTLINE, 12, 2));
        return states;
    }

    /** Boxed text entry; an underline alone is too faint to find with low vision. */
    static void styleInput(EditText input) {
        Context context = input.getContext();
        input.setTextSize(TEXT_BODY);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setMinHeight(dp(context, 64));
        input.setMinimumHeight(dp(context, 64));
        input.setBackground(inputBackground(context));
        input.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
    }

    /** Boxed drop-down with a visible arrow; options keep the user's font size and wrap. */
    static void styleSpinner(Spinner spinner, String[] values) {
        Context context = spinner.getContext();
        spinner.setAdapter(new ArrayAdapter<String>(context, 0, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return option(context, convertView, getItem(position));
            }

            @Override public View getDropDownView(int position, View convertView,
                                                  ViewGroup parent) {
                return option(context, convertView, getItem(position));
            }
        });
        LayerDrawable background = new LayerDrawable(new Drawable[] {
                inputBackground(context), new ChevronDrawable(context)});
        background.setLayerGravity(1, Gravity.END | Gravity.CENTER_VERTICAL);
        background.setLayerSize(1, dp(context, 18), dp(context, 18));
        background.setLayerInsetEnd(1, dp(context, 16));
        spinner.setBackground(background);
        spinner.setMinimumHeight(dp(context, 64));
        spinner.setPadding(dp(context, 2), 0, dp(context, 44), 0);
    }

    private static TextView option(Context context, View convertView, CharSequence value) {
        TextView option = convertView instanceof TextView ? (TextView) convertView
                : text(context, "", TEXT_BODY, INK, false);
        option.setText(value);
        option.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        option.setMinHeight(dp(context, 56));
        option.setMinimumHeight(dp(context, 56));
        option.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        return option;
    }

    /** A persistent label above its control that also names the control for screen readers. */
    static LinearLayout field(Context context, CharSequence label, View control) {
        LinearLayout field = vertical(context);
        TextView name = text(context, label, 18, INK, true);
        if (control.getId() == View.NO_ID) control.setId(View.generateViewId());
        String lowerLabel = label.toString().toLowerCase(java.util.Locale.ROOT);
        if (lowerLabel.contains("key") || lowerLabel.contains("密钥") || lowerLabel.contains("秘钥"))
            control.setSaveEnabled(false);
        name.setLabelFor(control.getId());
        add(field, name, 6);
        field.addView(control, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return field;
    }

    /** A labelled field whose condition sits on one short line under the control. */
    static LinearLayout field(Context context, CharSequence label, CharSequence hint,
                              View control) {
        LinearLayout field = field(context, label, control);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 6);
        field.addView(hint(context, hint), params);
        return field;
    }

    /** Equal-width fields side by side, separated by the standard control gap. */
    static ReminderSampleGrid fieldRow(Context context, View... fields) {
        ReminderSampleGrid row = actionRow(context);
        for (View field : fields) row.addView(field);
        return row;
    }

    /** A compact return bar shared by secondary pages; its height expands with large text. */
    static TextView pageHeader(Activity activity, LinearLayout parent, String title, String context) {
        LinearLayout header = horizontal(activity);
        header.setGravity(Gravity.TOP);
        Button back = iconButton(activity, "返回", new ArrowDrawable(activity, true));
        back.setOnClickListener(v -> activity.onBackPressed());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56));
        backParams.setMarginEnd(dp(activity, 8));
        header.addView(back, backParams);
        LinearLayout labels = vertical(activity);
        labels.setPadding(0, dp(activity, 3), 0, 0);
        labels.setMinimumHeight(dp(activity, 56));
        TextView heading = pageTitle(activity, title);
        add(labels, heading, 4);
        if (context != null && !context.isEmpty()) add(labels, hint(activity, context), 0);
        header.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        add(parent, header, GAP_SECTION);
        return heading;
    }

    /** Title and its local action stay side by side only when their real labels fit. */
    static LinearLayout headingActionRow(Context context, TextView title, View action) {
        LinearLayout row = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                action.measure(MeasureSpec.makeMeasureSpec(Math.max(0, available), MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                float labelWidth = title.getPaint().measureText(title.getText().toString());
                boolean stack = labelWidth + action.getMeasuredWidth() + dp(context, 12) > available;
                int orientation = stack ? VERTICAL : HORIZONTAL;
                LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) title.getLayoutParams();
                if (getOrientation() != orientation || labelParams.weight != (stack ? 0 : 1)) {
                    setOrientation(orientation);
                    labelParams.width = stack ? LayoutParams.MATCH_PARENT : 0;
                    labelParams.weight = stack ? 0 : 1;
                    title.setLayoutParams(labelParams);
                    LinearLayout.LayoutParams actionParams = (LinearLayout.LayoutParams) action.getLayoutParams();
                    actionParams.setMargins(0, stack ? dp(context, 8) : 0, 0, 0);
                    actionParams.setMarginStart(stack ? 0 : dp(context, 12));
                    actionParams.gravity = stack ? Gravity.END : -1;
                    action.setLayoutParams(actionParams);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(context, 12));
        row.addView(action, params);
        return row;
    }

    static Button iconButton(Context context, String description, Drawable icon) {
        Button button = button(context, "", false);
        button.setMinWidth(dp(context, 56));
        button.setMinimumWidth(dp(context, 56));
        button.setPadding(0, 0, 0, 0);
        Drawable inset = new android.graphics.drawable.InsetDrawable(icon, dp(context, 18));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18236874),
                SettingHelp.focusableIcon(context, inset), shape(context, Color.WHITE, Color.TRANSPARENT, 16)));
        button.setContentDescription(description);
        button.setFocusableInTouchMode(false);
        return button;
    }

    /** Navigation is a named row rather than a competing primary button. */
    static LinearLayout navigationRow(Context context, String label, String summary, Runnable action) {
        LinearLayout row = horizontal(context);
        row.setPadding(dp(context, 16), dp(context, 14), dp(context, 14), dp(context, 14));
        row.setMinimumHeight(dp(context, 64));
        row.setBackground(controlBackground(context, ButtonStyle.NAVIGATION, 16));
        row.setTag("ui_nav:" + label);
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout labels = vertical(context);
        add(labels, text(context, label, TEXT_BODY, INK, true), summary.isEmpty() ? 0 : 4);
        if (!summary.isEmpty()) add(labels, hint(context, summary), 0);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ImageView arrow = new ImageView(context);
        arrow.setImageDrawable(new ArrowDrawable(context, false));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18));
        arrowParams.setMarginStart(dp(context, 12));
        row.addView(arrow, arrowParams);
        row.setContentDescription(label + (summary.isEmpty() ? "" : "，" + summary));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** Decorative directional arcs; static, excluded from reading and never a live radar. */
    static void addSignature(LinearLayout parent) {
        Context context = parent.getContext();
        ImageView signature = new ImageView(context);
        signature.setImageDrawable(new DirectionArcDrawable(context));
        signature.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 72), dp(context, 48));
        params.gravity = Gravity.END;
        params.bottomMargin = dp(context, 12);
        parent.addView(signature, params);
    }

    static void addSignatureLabel(LinearLayout parent, String label) {
        Context context = parent.getContext();
        LinearLayout row = horizontal(context);
        addWeighted(row, hint(context, label), 1f);
        ImageView signature = new ImageView(context);
        signature.setImageDrawable(new DirectionArcDrawable(context));
        signature.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(signature, new LinearLayout.LayoutParams(dp(context, 56), dp(context, 36)));
        add(parent, row, 8);
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
        labels.addView(text(context, contextLabel, TEXT_HINT, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if ("多游戏辅助".equals(contextLabel)) {
            ImageView signature = new ImageView(context);
            signature.setImageDrawable(new DirectionArcDrawable(context));
            signature.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(signature, new LinearLayout.LayoutParams(dp(context, 64), dp(context, 44)));
        }
        add(parent, row, 16);
    }

    private static final class ArrowDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean back;
        ArrowDrawable(Context context, boolean back) {
            this.back = back;
            paint.setColor(INK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(context, 2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float start = back ? b.right - b.width() * .25f : b.left + b.width() * .25f;
            float tip = back ? b.left + b.width() * .25f : b.right - b.width() * .25f;
            path.reset();
            path.moveTo(start, b.top + b.height() * .2f);
            path.lineTo(tip, b.exactCenterY());
            path.lineTo(start, b.bottom - b.height() * .2f);
            canvas.drawPath(path, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class DirectionArcDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        DirectionArcDrawable(Context context) {
            paint.setColor(PRIMARY);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(context, 1.5f));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float x = b.exactCenterX(), y = b.exactCenterY();
            for (int i = 1; i <= 3; i++) {
                float radius = b.height() * .14f * i;
                paint.setAlpha(210 - i * 35);
                canvas.drawArc(x - radius, y - radius, x + radius, y + radius, -45, 145, false, paint);
                canvas.drawArc(x - radius, y - radius, x + radius, y + radius, 150, 85, false, paint);
            }
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, Math.max(1, b.height() * .04f), paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static void addSectionTitle(LinearLayout parent, String title, String subtitle) {
        add(parent, heading(parent.getContext(), title), subtitle.isEmpty() ? 12 : 4);
        if (!subtitle.isEmpty()) add(parent, body(parent.getContext(), subtitle), 14);
    }

    /** Fixed-size arrow, independent of the system font scale; points up when expanded. */
    private static final class ChevronDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean pointingUp;

        ChevronDrawable(Context context) {
            paint.setColor(INK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(context, 2.5f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setPointingUp(boolean up) {
            pointingUp = up;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float inset = paint.getStrokeWidth();
            float upper = bounds.top + bounds.height() * 0.32f;
            float lower = bounds.bottom - bounds.height() * 0.32f;
            float ends = pointingUp ? lower : upper;
            path.reset();
            path.moveTo(bounds.left + inset, ends);
            path.lineTo(bounds.exactCenterX(), pointingUp ? upper : lower);
            path.lineTo(bounds.right - inset, ends);
            canvas.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
