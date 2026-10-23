package com.openkhub.sensefield;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Inline, non-modal contextual help that keeps its setting page available. */
final class SettingHelpPanel {
    static final String PANEL_TAG = "setting_help_panel";
    static final String PANEL_VIEW_TAG = "setting_help_panel_view";
    static final String CLOSE_BUTTON_TAG = "setting_help_close_button";
    static final String GROUP_BUTTON_TAG_PREFIX = "setting_help_group_button:";
    static final String GROUP_SUMMARY_TAG_PREFIX = "setting_help_group_summary:";

    private static final float SIDE_PANEL_MIN_DP = 360f;
    private static final float SIDE_PANEL_MAX_DP = 420f;
    private static final float SIDE_MODE_MIN_WIDTH_DP = 720f;

    private SettingHelpPanel() {}

    static void show(Activity activity, String key, View trigger) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Controller controller = findController(activity);
        if (controller == null) controller = install(activity);
        if (controller != null) controller.show(key, trigger);
    }

    static boolean close(Activity activity) {
        if (activity == null || activity.isDestroyed()) return false;
        Controller controller = findController(activity);
        return controller != null && controller.close();
    }

    static void saveState(Activity activity, android.os.Bundle state) {
        Controller c = findController(activity);
        if (c != null && c.open) {
            state.putString("ui_help_key", c.activeKey);
            state.putInt("ui_help_scroll", c.explanationScroll.getScrollY());
        }
    }

    static void restoreState(Activity activity, android.os.Bundle state) {
        String key = state.getString("ui_help_key");
        if (key == null) return;
        View trigger = findTagged(activity.findViewById(android.R.id.content), GROUP_BUTTON_TAG_PREFIX + key);
        if (trigger == null) return;
        show(activity, key, trigger);
        Controller c = findController(activity);
        if (c != null) c.explanationScroll.post(() ->
                c.explanationScroll.scrollTo(0, state.getInt("ui_help_scroll")));
    }

    static void finishMotion(Activity activity) {
        Controller c = findController(activity);
        if (c == null) return;
        c.motionGeneration++;
        UiMotion.reset(c.panel);
        if (!c.open) c.finishClose();
    }

    private static View findTagged(View view, String tag) {
        if (view == null) return null;
        if (tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTagged(group.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Controller findController(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return null;
        ViewGroup contentRoot = (ViewGroup) content;
        for (int i = 0; i < contentRoot.getChildCount(); i++) {
            View child = contentRoot.getChildAt(i);
            if (child instanceof PanelHost && PANEL_TAG.equals(child.getTag())) {
                return ((PanelHost) child).controller;
            }
        }
        return null;
    }

    private static Controller install(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return null;
        ViewGroup contentRoot = (ViewGroup) content;

        ArrayList<View> originalViews = new ArrayList<>();
        IdentityHashMap<View, ViewGroup.LayoutParams> originalParams = new IdentityHashMap<>();
        IdentityHashMap<ScrollView, Integer> scrollPositions = new IdentityHashMap<>();
        for (int i = 0; i < contentRoot.getChildCount(); i++) {
            View child = contentRoot.getChildAt(i);
            originalViews.add(child);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            originalParams.put(child, copyLayoutParams(params));
            rememberScrollPositions(child, scrollPositions);
        }

        PanelHost host = new PanelHost(activity);
        host.setTag(PANEL_TAG);
        contentRoot.removeAllViews();
        addMatchParent(contentRoot, host);
        for (View child : originalViews) {
            contentRoot.removeView(child);
            host.addView(child, copyFrameParams(originalParams.get(child)));
        }

        Controller controller = new Controller(activity, contentRoot, host,
                originalViews, originalParams, scrollPositions);
        host.controller = controller;
        return controller;
    }

    private static void rememberScrollPositions(View view,
            IdentityHashMap<ScrollView, Integer> positions) {
        if (view instanceof ScrollView) positions.put((ScrollView) view, ((ScrollView) view).getScrollY());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                rememberScrollPositions(group.getChildAt(i), positions);
            }
        }
    }

    private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams params) {
        if (params == null) return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (params instanceof FrameLayout.LayoutParams) {
            return new FrameLayout.LayoutParams((FrameLayout.LayoutParams) params);
        }
        return new ViewGroup.LayoutParams(params.width, params.height);
    }

    private static FrameLayout.LayoutParams copyFrameParams(ViewGroup.LayoutParams params) {
        if (params instanceof FrameLayout.LayoutParams) {
            return new FrameLayout.LayoutParams((FrameLayout.LayoutParams) params);
        }
        if (params != null) return new FrameLayout.LayoutParams(params.width, params.height);
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static void addMatchParent(ViewGroup parent, View child) {
        if (parent instanceof FrameLayout) {
            parent.addView(child, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            parent.addView(child, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private static final class PanelHost extends FrameLayout {
        Controller controller;

        PanelHost(Context context) {
            super(context);
            // Let the original ScrollView and the panel receive window insets separately.
            setFitsSystemWindows(false);
        }
    }

    private static final class Controller implements View.OnLayoutChangeListener {
        private final Activity activity;
        private final ViewGroup contentRoot;
        private final PanelHost host;
        private final List<View> originalViews;
        private final Map<View, ViewGroup.LayoutParams> originalParams;
        private final Map<ScrollView, Integer> scrollPositions;
        private final PanelView panel;
        private final TextView heading;
        private final ScrollView explanationScroll;
        private final LinearLayout explanationContent;
        private final Button closeButton;
        private View trigger;
        private String activeKey;
        private boolean open;
        private boolean sideMode;
        private int reservedPx;
        private int measuredWidth;
        private int measuredHeight;
        private int motionGeneration;

        Controller(Activity activity, ViewGroup contentRoot, PanelHost host,
                List<View> originalViews, Map<View, ViewGroup.LayoutParams> originalParams,
                Map<ScrollView, Integer> scrollPositions) {
            this.activity = activity;
            this.contentRoot = contentRoot;
            this.host = host;
            this.originalViews = originalViews;
            this.originalParams = originalParams;
            this.scrollPositions = scrollPositions;

            panel = new PanelView(activity);
            panel.setTag(PANEL_VIEW_TAG);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(UiKit.dp(activity, 18), UiKit.dp(activity, 12),
                    UiKit.dp(activity, 18), UiKit.dp(activity, 18));
            panel.setBackground(UiKit.shape(activity, UiKit.SURFACE, UiKit.BORDER, 22));
            panel.setElevation(UiKit.dp(activity, 1));
            panel.setClipToOutline(true);
            panel.setFitsSystemWindows(true);

            LinearLayout headerRow = UiKit.horizontal(activity);
            heading = UiKit.heading(activity, "设置说明");
            heading.setTextSize(20);
            heading.setFocusable(true);
            heading.setFocusableInTouchMode(true);
            headerRow.addView(heading, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            closeButton = UiKit.button(activity, "关闭", false);
            closeButton.setText("");
            closeButton.setPadding(0, 0, 0, 0);
            // Keep the low-vision touch target large without a large outlined button.
            GradientDrawable closeMask = new GradientDrawable();
            closeMask.setShape(GradientDrawable.OVAL);
            closeMask.setColor(Color.WHITE);
            Drawable closeIcon = new InsetDrawable(new CloseDrawable(activity),
                    UiKit.dp(activity, 18));
            closeButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18000000),
                    SettingHelp.focusableIcon(activity, closeIcon), closeMask));
            closeButton.setMinWidth(UiKit.dp(activity, 56));
            closeButton.setMinimumWidth(UiKit.dp(activity, 56));
            closeButton.setMinHeight(UiKit.dp(activity, 56));
            closeButton.setMinimumHeight(UiKit.dp(activity, 56));
            closeButton.setContentDescription("关闭说明面板");
            closeButton.setTag(CLOSE_BUTTON_TAG);
            closeButton.setOnClickListener(view -> close());
            LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                    UiKit.dp(activity, 56), UiKit.dp(activity, 56));
            closeParams.setMarginStart(UiKit.dp(activity, 8));
            headerRow.addView(closeButton, closeParams);
            UiKit.add(panel, headerRow, 8);

            explanationScroll = new ScrollView(activity);
            explanationScroll.setFillViewport(false);
            explanationScroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
            explanationContent = UiKit.vertical(activity);
            explanationScroll.addView(explanationContent, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.addView(explanationScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            panel.setAccessibilityPaneTitle("设置说明");

            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.END | Gravity.TOP);
            panel.setVisibility(View.GONE);
            host.addView(panel, panelParams);
            host.addOnLayoutChangeListener(this);

            // Reparenting must not reset the setting page's current reading position.
            host.post(() -> {
                for (Map.Entry<ScrollView, Integer> entry : this.scrollPositions.entrySet()) {
                    ScrollView scroll = entry.getKey();
                    if (scroll.getParent() != null) scroll.scrollTo(scroll.getScrollX(), entry.getValue());
                }
            });
        }

        void show(String key, View newTrigger) {
            boolean wasOpen = open;
            motionGeneration++;
            UiMotion.reset(panel);
            String normalizedKey = key == null ? "" : key;
            boolean changed = !normalizedKey.equals(activeKey);
            if (newTrigger != null) trigger = newTrigger;
            if (changed) {
                activeKey = normalizedKey;
                populate(normalizedKey);
            }
            open = true;
            panel.setVisibility(View.VISIBLE);
            panel.bringToFront();
            applyGeometry();
            if (!wasOpen) UiMotion.reveal(panel, sideMode);
            panel.setAccessibilityPaneTitle(SettingHelpContent.title(normalizedKey));
            panel.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            host.requestLayout();
            ensureTriggerVisible();
            focusHeading();
        }

        private void populate(String key) {
            String titleText = SettingHelpContent.title(key);
            heading.setText(titleText);
            heading.setAccessibilityHeading(true);
            explanationContent.removeAllViews();

            UiKit.add(explanationContent, UiKit.readingSections(activity, SettingHelpContent.text(key)), 24);

            List<String> itemKeys = SettingHelpContent.itemKeys(key);
            if (itemKeys != null) {
                for (String itemKey : itemKeys) {
                    if (itemKey == null || itemKey.isEmpty()) continue;
                    TextView itemHeading = UiKit.heading(activity,
                            SettingHelpContent.title(itemKey));
                    itemHeading.setTextSize(18);
                    UiKit.add(explanationContent, itemHeading, 6);
                    UiKit.add(explanationContent,
                            UiKit.readingSections(activity, SettingHelpContent.text(itemKey)), 24);
                }
            }

            explanationScroll.scrollTo(0, 0);
            heading.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }

        private TextView explanationText(String text) {
            TextView body = UiKit.readingBody(activity, text, 18);
            body.setLineSpacing(UiKit.dp(activity, 2), 1.15f);
            return body;
        }

        boolean close() {
            if (!open) return false;
            open = false;
            int run = ++motionGeneration;
            UiMotion.dismiss(panel, sideMode, () -> {
                if (run != motionGeneration || open) return;
                finishClose();
                restoreTriggerFocus();
            });
            return true;
        }

        private void finishClose() {
            UiMotion.reset(panel);
            panel.setVisibility(View.GONE);
            restoreOriginalBounds();
            host.requestLayout();
        }

        private void restoreOriginalBounds() {
            for (View view : originalViews) {
                ViewGroup.LayoutParams params = originalParams.get(view);
                if (params != null) view.setLayoutParams(copyFrameParams(params));
            }
            sideMode = false;
            reservedPx = 0;
        }

        @SuppressLint("AccessibilityFocus")
        private void restoreTriggerFocus() {
            View target = trigger;
            if (target == null || target.getParent() == null) return;
            target.post(() -> {
                if (open || target != trigger || target.getParent() == null
                        || activity.isFinishing() || activity.isDestroyed()
                        || !hasWindowFocus()) return;
                requestTriggerRowVisible(target, false);
                target.requestFocus();
                AccessibilityManager manager = (AccessibilityManager) activity.getSystemService(
                        Context.ACCESSIBILITY_SERVICE);
                if (manager != null && manager.isTouchExplorationEnabled()) {
                    // Explicit focus return follows the user's close action and restores the
                    // local setting context for touch exploration.
                    target.performAccessibilityAction(
                            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
                }
            });
        }

        private void ensureTriggerVisible() {
            View target = trigger;
            if (target == null || target.getParent() == null) return;
            View row = target.getParent() instanceof View ? (View) target.getParent() : target;
            row.post(() -> {
                if (!open || target != trigger || target.getParent() == null
                        || activity.isFinishing() || activity.isDestroyed()
                        || !hasWindowFocus()) return;
                requestTriggerRowVisible(target, !sideMode);
            });
        }

        private void requestTriggerRowVisible(View target, boolean alignAtTop) {
            View row = target.getParent() instanceof View ? (View) target.getParent() : target;
            if (alignAtTop) {
                // Keep the active group's controls in the small remaining viewport,
                // rather than leaving only its heading above the bottom sheet.
                View ancestor = row;
                while (ancestor.getParent() instanceof View) {
                    ancestor = (View) ancestor.getParent();
                    if (ancestor instanceof ScrollView) {
                        ScrollView scroll = (ScrollView) ancestor;
                        int[] rowLocation = new int[2];
                        int[] scrollLocation = new int[2];
                        row.getLocationInWindow(rowLocation);
                        scroll.getLocationInWindow(scrollLocation);
                        int offset = rowLocation[1] - scrollLocation[1]
                                - scroll.getPaddingTop() - UiKit.dp(activity, 12);
                        scroll.scrollTo(scroll.getScrollX(), Math.max(0, scroll.getScrollY() + offset));
                        return;
                    }
                }
            }
            Rect bounds = new Rect(0, 0, row.getWidth(), row.getHeight());
            row.requestRectangleOnScreen(bounds, false);
        }

        @SuppressLint("AccessibilityFocus")
        private void focusHeading() {
            heading.post(() -> {
                if (!open || activity.isFinishing() || activity.isDestroyed()
                        || !hasWindowFocus()) return;
                heading.requestFocus();
                AccessibilityManager manager = (AccessibilityManager) activity.getSystemService(
                        Context.ACCESSIBILITY_SERVICE);
                if (manager != null && manager.isTouchExplorationEnabled()) {
                    heading.performAccessibilityAction(
                            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
                }
            });
        }

        private boolean hasWindowFocus() {
            View decor = activity.getWindow().getDecorView();
            return decor != null && decor.hasWindowFocus();
        }

        @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (!open) return;
            int width = right - left;
            int height = bottom - top;
            if (width == measuredWidth && height == measuredHeight) return;
            measuredWidth = width;
            measuredHeight = height;
            applyGeometry();
        }

        private void applyGeometry() {
            float density = activity.getResources().getDisplayMetrics().density;
            if (!Float.isFinite(density) || density <= 0f) density = 1f;
            float widthDp = host.getWidth() > 0 ? host.getWidth() / density
                    : activity.getResources().getConfiguration().screenWidthDp;
            float heightDp = host.getHeight() > 0 ? host.getHeight() / density
                    : activity.getResources().getConfiguration().screenHeightDp;
            boolean useSide = widthDp >= SIDE_MODE_MIN_WIDTH_DP;
            int reserve;
            FrameLayout.LayoutParams panelParams;
            if (useSide) {
                float panelWidthDp = Math.min(SIDE_PANEL_MAX_DP,
                        Math.max(SIDE_PANEL_MIN_DP, widthDp - SIDE_PANEL_MIN_DP));
                reserve = UiKit.dp(activity, panelWidthDp);
                panelParams = new FrameLayout.LayoutParams(reserve,
                        ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.TOP);
            } else {
                float panelHeightDp = Math.min(480f, Math.max(0f, heightDp * 0.5f));
                if (heightDp > 160f) panelHeightDp = Math.min(panelHeightDp, heightDp - 120f);
                reserve = UiKit.dp(activity, panelHeightDp);
                panelParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        reserve, Gravity.BOTTOM | Gravity.START);
            }

            if (sideMode != useSide || reservedPx != reserve) {
                sideMode = useSide;
                reservedPx = reserve;
                for (View original : originalViews) {
                    ViewGroup.LayoutParams old = originalParams.get(original);
                    FrameLayout.LayoutParams params = copyFrameParams(old);
                    params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    if (useSide) params.setMarginEnd(params.getMarginEnd() + reserve);
                    else params.bottomMargin += reserve;
                    original.setLayoutParams(params);
                }
            }
            panel.setLayoutParams(panelParams);
        }
    }

    /** Fixed-size icon independent of system font scale; its Button keeps the full touch area. */
    private static final class CloseDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CloseDrawable(Context context) {
            paint.setColor(UiKit.INK);
            paint.setStrokeWidth(UiKit.dp(context, 2.5f));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float inset = paint.getStrokeWidth() / 2f;
            canvas.drawLine(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset, paint);
            canvas.drawLine(bounds.left + inset, bounds.bottom - inset,
                    bounds.right - inset, bounds.top + inset, paint);
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static final class PanelView extends LinearLayout {
        PanelView(Context context) {
            super(context);
        }
    }
}
