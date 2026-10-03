package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
    private float downX, downY;
    private int originalX, originalY;
    private boolean dragged;
    AssistantOverlay(Context context, Listener listener) { this.context = context; this.listener = listener; }
    void show() {
        if (!Settings.canDrawOverlays(context)) return;
        try {
            manager = context.getSystemService(WindowManager.class);
            layout = new WindowManager.LayoutParams(dp(48), dp(48), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            layout.gravity = Gravity.TOP | Gravity.LEFT; layout.x = 0; layout.y = dp(112); layout.alpha = .8f;
            layout.setTitle("听野语音助手");
            root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
            manager.addView(root, layout); render();
        } catch (RuntimeException ignored) { root = null; manager = null; bounds = new Rect(); }
    }
    private void render() {
        if (root == null) return;
        root.removeAllViews();
        GradientDrawable background = new GradientDrawable(); background.setColor(expanded ? Color.rgb(18, 35, 50) : Color.rgb(0, 68, 103));
        background.setCornerRadius(dp(expanded ? 14 : 24)); root.setBackground(background);
        if (!expanded) {
            root.setPadding(0, 0, 0, 0);
            TextView dot = label("听", 24); dot.setGravity(Gravity.CENTER);
            dot.setContentDescription("听野助手，点击展开；可以拖动位置"); dot.setClickable(true);
            dot.setOnClickListener(view -> setExpanded(true));
            dot.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); originalX = layout.x; originalY = layout.y; dragged = false;
                } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) dragged = true;
                    if (dragged) {
                        layout.x = Math.max(0, Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(48), originalX + (int) dx));
                        layout.y = Math.max(0, Math.min(context.getResources().getDisplayMetrics().heightPixels - dp(48), originalY + (int) dy)); update();
                    }
                } else if (event.getAction() == MotionEvent.ACTION_UP && !dragged) view.performClick();
                return true;
            });
            root.addView(dot, new LinearLayout.LayoutParams(dp(48), dp(48)));
        } else {
            root.setPadding(dp(12), dp(8), dp(12), dp(8));
            status = label(lastStatus, 18); root.addView(status);
            ScrollView scroll = new ScrollView(context); history = label(lastHistory, 18); scroll.addView(history);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout first = row(); addButton(first, "读画面", listener::readScreen); addButton(first, "重说", listener::repeat); root.addView(first);
            LinearLayout second = row(); addButton(second, "标记问题", listener::mark);
            voiceControl = addButton(second, voicePaused ? "恢复语音" : "暂停语音", listener::pauseVoice); root.addView(second);
            addButton(root, "收起", () -> setExpanded(false));
        }
        layout.width = expanded ? Math.min(dp(310), context.getResources().getDisplayMetrics().widthPixels - dp(16)) : dp(48);
        layout.height = expanded ? Math.min(dp(340), context.getResources().getDisplayMetrics().heightPixels - dp(24)) : dp(48);
        layout.x = Math.max(0, Math.min(layout.x, context.getResources().getDisplayMetrics().widthPixels - layout.width));
        layout.y = Math.max(0, Math.min(layout.y, context.getResources().getDisplayMetrics().heightPixels - layout.height)); update();
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
