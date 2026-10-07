package com.openkhub.sensefield;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 读屏状态服务（领导方案 P0 基线）：抓取前台窗口的无障碍树并压缩成
 * 「短 ID 节点列表 + 分区」的判定状态，供 Jev 判断式模型消费。
 * 压缩规则（specs/screen-jev-spec.md §四）：只保留 可见 + (可点击|可聚焦|有文本/描述)
 * 的节点，去掉容器空壳；按屏幕纵向三区（顶栏/主内容/底栏）分区；节点 ID n1..nN。
 * 状态缓存为静态最新值，判定入口（自测页）按需取用。不消费、不注入任何事件。
 */
public class SenseFieldReaderService extends AccessibilityService {
    private static final String TAG = "ScreenReader";
    private static final int MAX_NODES = 60;
    private static final int MAX_DEPTH = 14;
    private static final int MAX_TEXT = 60;

    private static volatile String latestState = "";
    private static volatile String latestPackage = "";
    private static volatile long latestAt;
    private static volatile boolean connected;
    static boolean isConnected() { return connected; }

    /** 判定入口取用的最新压缩状态。 */
    static String latestState() {
        return latestState;
    }

    static String latestPackage() {
        return latestPackage;
    }

    static long latestAt() {
        return latestAt;
    }

    static boolean hasFreshState() {
        return connected && !latestState.isEmpty() && SystemClock.elapsedRealtime() - latestAt <= 15000;
    }

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        /* capabilities（含 canRetrieveWindowContent）来自 XML 元数据，setServiceInfo 不会清除 */

        /* Android 14（API 34）+：只观察不消费触摸屏事件——「连续触屏点读」的坐标来源。
         * 观察模式不干扰游戏本身的触摸处理。低版本无此能力，点读降级为截图预览内点读。 */
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            info.setMotionEventSources(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            Log.i(TAG, "触摸观察已启用（API 34+，只观察不消费）");
        }
        setServiceInfo(info);
        instance = new java.lang.ref.WeakReference<>(this);
        connected = true;
        Log.i(TAG, "读屏状态服务已连接");
    }

    /* 连续触屏点读：最新一次按下屏幕的坐标（观察模式由系统投递，不消费） */
    private static volatile int touchX = -1;
    private static volatile int touchY = -1;
    private static volatile long touchAt;

    static int latestTouchX() { return touchX; }

    static int latestTouchY() { return touchY; }

    static long latestTouchAt() { return touchAt; }

    @Override
    public void onMotionEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            touchX = (int) event.getX();
            touchY = (int) event.getY();
            touchAt = SystemClock.elapsedRealtime();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = String.valueOf(event.getPackageName());
        if (pkg.equals(getPackageName())) return;   // 不读自己
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || SystemClock.elapsedRealtime() - latestAt > 2000) {
            captureNow(pkg, event.getClassName());
        }
    }

    /** 操作结果确认（阶段二）：标记当前状态为「操作前」，判定时带 before/after。 */
    private static volatile String beforeState;

    static void markBefore() {
        beforeState = latestState;
    }

    static String beforeState() {
        return beforeState;
    }

    /** 压缩当前窗口树为判定状态（状态头含 app/activity/深色模式，节点行含 EC 状态位）。 */
    private void captureNow(String pkg, CharSequence activityHint) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("app=").append(pkg);
        if (activityHint != null && activityHint.length() > 0) {
            sb.append(" activity=").append(shorten(activityHint));
        }
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        sb.append(" dark=").append(dark);
        sb.append('\n');
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        List<String> lines = new ArrayList<>();
        walk(root, 0, screenHeight, lines);
        int kept = Math.min(lines.size(), MAX_NODES);
        for (int i = 0; i < kept; i++) sb.append(lines.get(i)).append('\n');
        latestState = sb.toString();
        latestPackage = pkg;
        latestAt = SystemClock.elapsedRealtime();
    }

    private void walk(AccessibilityNodeInfo node, int depth, int screenHeight, List<String> out) {
        if (node == null || depth > MAX_DEPTH || out.size() >= MAX_NODES) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        boolean hasText = text != null && text.length() > 0;
        boolean hasDesc = desc != null && desc.length() > 0;
        if (node.isVisibleToUser() && (node.isClickable() || node.isFocusable() || hasText || hasDesc)) {
            android.graphics.Rect b = new android.graphics.Rect();
            node.getBoundsInScreen(b);
            String zone = b.centerY() < screenHeight / 3 ? "顶栏"
                    : b.centerY() < screenHeight * 2 / 3 ? "主内容" : "底栏";
            String label = hasText ? text.toString() : (hasDesc ? desc.toString() : "");
            if (label.length() > MAX_TEXT) label = label.substring(0, MAX_TEXT) + "…";
            String state2 = (node.isEnabled() ? "E" : "-") + (node.isCheckable() ? (node.isChecked() ? "1" : "0") : "-");
            out.add("n" + out.size() + " [" + zone + "] "
                    + shorten(node.getClassName()) + (node.isClickable() ? " 可点" : "")
                    + " 状态=" + state2
                    + (hasText || hasDesc ? " \"" + label + "\"" : " \"\"")
                    + " @" + b.toShortString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), depth + 1, screenHeight, out);
        }
    }

    private static String shorten(CharSequence className) {
        String s = String.valueOf(className);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "读屏服务被中断");
    }

    /* ---------- 行号标尺：棋盘左侧竖排 1..N，随标定自动对齐 ----------
     * 棋盘格子位置固定（动的只是棋子），标尺一次对齐永久有效；
     * 播报用「第几行」报数，用户按标尺即可在屏上定位。 */

    private android.view.WindowManager rowWindowManager;
    private android.view.View rowOverlay;

    /** 请求显示/刷新行号标尺。无障碍服务未连接时返回 false；实际挂载在主线程执行。 */
    public static boolean showRowNumbers(android.content.Context ctx,
                                         int l, int t, int r, int b, int rows) {
        SenseFieldReaderService self = instance.get();
        if (self == null || !connected || rows <= 0) return false;
        self.postRowNumbers(l, t, r, b, rows);
        return true;
    }

    /** 隐藏行号标尺（停止辅助/投影结束时调用）。 */
    public static void hideRowNumbers() {
        SenseFieldReaderService self = instance.get();
        if (self == null) return;
        self.handler().post(self::removeRowOverlay);
    }

    private static volatile java.lang.ref.WeakReference<SenseFieldReaderService> instance =
            new java.lang.ref.WeakReference<>(null);

    @Override
    public void onDestroy() {
        if (uiHandler != null) uiHandler.removeCallbacksAndMessages(null);
        removeRowOverlay();
        if (instance.get() == this) instance.clear();
        connected = false;
        latestState = latestPackage = "";
        beforeState = null;
        latestAt = touchAt = 0;
        touchX = touchY = -1;
        super.onDestroy();
    }

    private android.os.Handler uiHandler;

    private android.os.Handler handler() {
        if (uiHandler == null) uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        return uiHandler;
    }

    private void postRowNumbers(int l, int t, int r, int b, int rows) {
        handler().post(() -> addOrUpdateRowOverlay(l, t, r, b, rows));
    }

    private void addOrUpdateRowOverlay(int lPct, int tPct, int rPct, int bPct, int rows) {
        try {
            if (rowOverlay != null) {
                rowWindowManager.removeView(rowOverlay);
                rowOverlay = null;
            }
            android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            android.graphics.Point size = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(size);
            int sw = size.x, sh = size.y;
            final int boardL = sw * lPct / 100, boardT = sh * tPct / 100;
            final int boardH = sh * (bPct - tPct) / 100;
            final int cellH = boardH / rows;
            final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFFFFFFFF);
            fill.setStyle(android.graphics.Paint.Style.FILL);
            fill.setTextAlign(android.graphics.Paint.Align.CENTER);
            final android.graphics.Paint stroke = new android.graphics.Paint(fill);
            stroke.setColor(0xFF1A2A44);
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeWidth(6f);
            android.view.View overlay = new android.view.View(this) {
                @Override protected void onDraw(android.graphics.Canvas canvas) {
                    super.onDraw(canvas);
                    float textSize = Math.max(30f, cellH * 0.5f);
                    fill.setTextSize(textSize);
                    stroke.setTextSize(textSize);
                    float x = Math.max(textSize * 0.8f, boardL - textSize * 1.1f);
                    for (int i = 0; i < rows; i++) {
                        float y = boardT + cellH * (i + 0.5f) + textSize * 0.35f;
                        String n = String.valueOf(i + 1);
                        canvas.drawText(n, x, y, stroke);
                        canvas.drawText(n, x, y, fill);
                    }
                }
            };
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            wm.addView(overlay, lp);
            rowOverlay = overlay;
            rowWindowManager = wm;
            Log.i(TAG, "行号标尺已显示 " + rows + " 行");
        } catch (Exception e) {
            Log.w(TAG, "行号标尺显示失败: " + e.getMessage());
        }
    }

    private void removeRowOverlay() {
        try {
            if (rowOverlay != null && rowWindowManager != null) {
                rowWindowManager.removeView(rowOverlay);
            }
        } catch (Exception e) {
            Log.w(TAG, "行号标尺移除失败: " + e.getMessage());
        } finally {
            rowOverlay = null;
        }
    }
}
