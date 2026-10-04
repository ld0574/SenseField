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
        return !latestState.isEmpty();
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
        setServiceInfo(info);
        Log.i(TAG, "读屏状态服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = String.valueOf(event.getPackageName());
        if (pkg.equals(getPackageName())) return;   // 不读自己
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || SystemClock.elapsedRealtime() - latestAt > 2000) {
            captureNow(pkg);
        }
    }

    /** 压缩当前窗口树为判定状态。 */
    private void captureNow(String pkg) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("app=").append(pkg).append('\n');
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
            out.add("n" + out.size() + " [" + zone + "] "
                    + shorten(node.getClassName()) + (node.isClickable() ? " 可点" : "")
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

    @Override
    public void onDestroy() {
        latestState = "";
        super.onDestroy();
    }
}
