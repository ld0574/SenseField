package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small touchable window; never repurposes the full-screen marker overlay. Main-thread only. */
final class AssistantOverlay implements AutoCloseable {
    interface Listener { void readScreen(); void repeat(); void mark(); void pauseVoice(); }
    private final Context context;
    private final Listener listener;
    private WindowManager manager;
    private WindowManager.LayoutParams layout;
    private volatile LinearLayout root;
    private TextView history, status;
    private Button voiceControl;
    private boolean voicePaused;
    private volatile boolean expanded;
    private String lastStatus = "听野助手", lastHistory = "直接说话即可提问。\n";
    private volatile Rect bounds = new Rect();
    private final Rect safeInsets = new Rect();
    private boolean rightDocked;
    private int lastInsetsWidth, lastInsetsHeight;
    private float downX, downY;
    private int originalX, originalY;
    private boolean dragged;
    AssistantOverlay(Context context, Listener listener) { this.context = context; this.listener = listener; }
    void show() {
        if (!Settings.canDrawOverlays(context)) return;
        try {
            manager = context.getSystemService(WindowManager.class);
            layout = new WindowManager.LayoutParams(dp(48), dp(48), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            layout.gravity = Gravity.TOP | Gravity.LEFT; layout.x = 0; layout.y = dp(112); layout.alpha = .8f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Keep x/y in physical-display coordinates. Insets are handled below so
                // the collapsed handle can reach the edge while the panel stays safe.
                layout.setFitInsetsTypes(0);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            layout.setTitle("听野语音助手");
            root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                DisplayMetrics metrics = screenMetrics();
                boolean changed = metrics.widthPixels != lastInsetsWidth || metrics.heightPixels != lastInsetsHeight;
                lastInsetsWidth = metrics.widthPixels; lastInsetsHeight = metrics.heightPixels;
                // Per-window insets depend on this overlay's position. Using them to
                // move it creates a feedback loop when it enters/leaves a cutout.
                // Only a physical display-size change can reposition us here.
                if (changed && !dragged) placeWithinSafeArea();
                return insets;
            });
            manager.addView(root, layout); render();
            root.requestApplyInsets();
        } catch (RuntimeException ignored) { root = null; manager = null; bounds = new Rect(); }
    }
    private void render() {
        if (root == null) return;
        root.removeAllViews();
        if (expanded) {
            root.setGravity(Gravity.TOP | Gravity.LEFT);
            root.setOnClickListener(null);
            root.setClickable(false);
            GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(18, 35, 50));
            background.setCornerRadius(dp(14)); root.setBackground(background);
            root.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    setExpanded(false);
                    return true;
                }
                return false;
            });
        } else {
            root.setBackgroundColor(Color.TRANSPARENT);
            root.setGravity((rightDocked ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            root.setContentDescription("听野助手，点击展开；可以拖动位置");
            root.setClickable(true);
            root.setOnClickListener(view -> setExpanded(true));
            root.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); originalX = layout.x; originalY = layout.y; dragged = false;
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) dragged = true;
                    if (dragged) {
                        DisplayMetrics metrics = screenMetrics();
                        layout.x = Math.max(0, Math.min(metrics.widthPixels - dp(48), originalX + (int) dx));
                        int maxY = metrics.heightPixels - safeInsets.bottom - dp(48);
                        layout.y = Math.max(safeInsets.top, Math.min(maxY, originalY + (int) dy)); update();
                    }
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (dragged) {
                        dockToNearestEdge();
                        dragged = false;
                        update();
                    } else {
                        view.performClick();
                    }
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                    if (dragged) {
                        dockToNearestEdge();
                        dragged = false;
                        update();
                    }
                    return true;
                }
                return false;
            });
        }
        if (!expanded) {
            root.setPadding(0, 0, 0, 0);
            View dot = new View(context);
            GradientDrawable dotBackground = new GradientDrawable();
            dotBackground.setShape(GradientDrawable.OVAL); dotBackground.setColor(Color.WHITE);
            dot.setBackground(dotBackground);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(dot, new LinearLayout.LayoutParams(dp(36), dp(36)));
        } else {
            root.setPadding(dp(12), dp(8), dp(12), dp(8));
            status = label(lastStatus, 18); root.addView(status);
            ScrollView scroll = new ScrollView(context); history = label(lastHistory, 18); scroll.addView(history);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout first = row(); addButton(first, "读画面", listener::readScreen); addButton(first, "重说", listener::repeat); root.addView(first);
            LinearLayout second = row(); addButton(second, "标记问题", listener::mark);
            voiceControl = addButton(second, voicePaused ? "恢复语音" : "暂停语音", listener::pauseVoice); root.addView(second);
        }
        layout.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | (expanded ? WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH : 0);
        placeWithinSafeArea();
    }
    private void dockToNearestEdge() {
        int width = screenMetrics().widthPixels;
        rightDocked = layout.x + dp(24) >= width / 2;
        layout.x = rightDocked ? Math.max(0, width - dp(48)) : 0;
        root.setGravity((rightDocked ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
    }
    private DisplayMetrics screenMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        if (manager != null) manager.getDefaultDisplay().getRealMetrics(metrics);
        else metrics.setTo(context.getResources().getDisplayMetrics());
        return metrics;
    }
    private void refreshSafeInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets safe = manager.getMaximumWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            safeInsets.set(safe.left, safe.top, safe.right, safe.bottom);
        } else {
            // API29 has no WindowMetrics; use display-level cutout and stable
            // system dimensions, never position-dependent view insets.
            safeInsets.set(0, systemDimension("status_bar_height"), 0, systemDimension("navigation_bar_height"));
            android.view.DisplayCutout cutout = manager.getDefaultDisplay().getCutout();
            if (cutout != null) {
                safeInsets.left = cutout.getSafeInsetLeft();
                safeInsets.top = Math.max(safeInsets.top, cutout.getSafeInsetTop());
                safeInsets.right = cutout.getSafeInsetRight();
                safeInsets.bottom = Math.max(safeInsets.bottom, cutout.getSafeInsetBottom());
            }
        }
    }
    private int systemDimension(String name) {
        int id = context.getResources().getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : context.getResources().getDimensionPixelSize(id);
    }
    private void placeWithinSafeArea() {
        if (layout == null) return;
        refreshSafeInsets();
        DisplayMetrics metrics = screenMetrics();
        int width = Math.max(dp(48), metrics.widthPixels);
        int height = Math.max(dp(48), metrics.heightPixels);
        if (expanded) {
            int margin = dp(8);
            int safeWidth = Math.max(1, width - safeInsets.left - safeInsets.right - margin * 2);
            int safeHeight = Math.max(1, height - safeInsets.top - safeInsets.bottom - margin * 2);
            layout.width = Math.min(dp(310), safeWidth);
            layout.height = Math.min(dp(340), safeHeight);
            int minX = safeInsets.left + margin;
            int maxX = width - safeInsets.right - margin - layout.width;
            if (maxX < minX) maxX = minX;
            layout.x = rightDocked ? maxX : minX;
            int minY = safeInsets.top + margin;
            int maxY = height - safeInsets.bottom - margin - layout.height;
            if (maxY < minY) maxY = minY;
            layout.y = Math.max(minY, Math.min(layout.y, maxY));
        } else {
            layout.width = dp(48); layout.height = dp(48);
            layout.x = rightDocked ? Math.max(0, width - layout.width) : 0;
            int minY = safeInsets.top;
            int maxY = height - safeInsets.bottom - layout.height;
            if (maxY < minY) maxY = minY;
            layout.y = Math.max(minY, Math.min(layout.y, maxY));
        }
        update();
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); return row; }
    private Button addButton(LinearLayout page, String title, Runnable action) {
        Button button = new Button(context); button.setText(title); button.setTextSize(16); button.setMinHeight(dp(48));
        button.setOnClickListener(v -> action.run());
        page.addView(button, new LinearLayout.LayoutParams(page.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1, -2,
                page.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0));
        return button;
    }
    private TextView label(String text, int size) { TextView label = new TextView(context); label.setText(text); label.setTextSize(size); label.setTextColor(Color.WHITE); return label; }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void update() {
        try {
            if (manager != null && root != null) {
                manager.updateViewLayout(root, layout);
                bounds = new Rect(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height);
                root.post(() -> {
                    if (root == null) return;
                    int[] location = new int[2]; root.getLocationOnScreen(location);
                    bounds = new Rect(location[0], location[1], location[0] + root.getWidth(), location[1] + root.getHeight());
                });
            }
        } catch (RuntimeException ignored) { close(); }
    }
    void setExpanded(boolean value) { expanded = value; render(); }
    boolean isExpanded() { return root != null && expanded; }
    Rect bounds() { return new Rect(bounds); }
    void status(String value) { lastStatus = value; if (status != null) status.setText(value); }
    void history(String value) { lastHistory = value; if (history != null) history.setText(value); }
    void voicePaused(boolean value) { voicePaused = value; if (voiceControl != null) voiceControl.setText(value ? "恢复语音" : "暂停语音"); }
    @Override public void close() {
        if (manager != null && root != null) try { manager.removeViewImmediate(root); } catch (RuntimeException ignored) { }
        root = null; bounds = new Rect(); expanded = false;
    }
}
