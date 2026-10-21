package com.openkhub.sensefield;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * HyperOS 读数的采集侧：只读系统现值，喂给纯函数 {@link HyperOsHealthSnapshot}，
 * 并把引导动作送到既有引导页出口 {@link CapturePermissionsActivity}。
 *
 * <p>三条边界，逐条对应本卷的板：
 * <ul>
 *   <li>后台弹出权限只用反射<b>读</b> {@code AppOpsManager.checkOpNoThrow} 的现值，
 *       不反射写、不改设置、不自动跳转系统页。</li>
 *   <li>无障碍自检只读 {@code getEnabledAccessibilityServiceList}，发现被撤销时只弹引导页，
 *       <b>不自动重开</b>无障碍服务（那属模拟用户操作）。</li>
 *   <li>引导不新增弹窗类型：只复用既有授权／引导页，且按边沿触发，一次状态变化最多送一次。</li>
 * </ul>
 *
 * <p>本类不启线程、不起周期任务：所有采样都挂在既有 {@code device} 事件的周期采样链上。
 */
final class HyperOsSystemProbe {
    private static final String TAG = "HyperOsHealth";
    private static final String PREF_PROJECTION_GRANTED = "hyperos_projection_granted";

    /** 「后台弹出界面」这条厂商限制对应的隐藏 AppOps 操作名；只能按名字反射读，读不到即视为不确定。 */
    static final String BACKGROUND_START_OP = "OP_BACKGROUND_START_ACTIVITY";

    private static volatile HyperOsHealthSnapshot.Guidance lastDelivered = HyperOsHealthSnapshot.Guidance.NONE;

    /** 网络那一行的最后一条读数：没采过就是 {@code unknown}，不是一条编出来的「有网」。 */
    private static volatile String lastNetworkReading =
            NetworkPathology.reading(-1L, null, null, null);
    static final String NETWORK_READING_KEY = NetworkPathology.READING_KEY;

    private HyperOsSystemProbe() { /* 采集侧只暴露静态只读方法。 */ }

    /** 取一次现场读数：屏幕、无障碍授权列表、后台弹出操作现值、网络现值，全部只读。 */
    static HyperOsHealthSnapshot sample(Context context,
                                        HyperOsHealthSnapshot.ProjectionStatus projection) {
        if (context == null) return idleReading();
        boolean screenOn = isScreenOn(context);
        boolean previouslyGranted = wasProjectionGranted(context);
        boolean accessibilityEnabled = isAccessibilityServiceEnabled(context);
        int backgroundStartMode = backgroundStartMode(context);
        // 网络这一跳挂在既有 device 采样链上：读数由采集侧渲染并发布，不新增周期任务、线程、事件名，
        // 也不在这里做任何请求——它只读系统现值。
        networkReading(context);
        return HyperOsHealthSnapshot.evaluate(SystemClock.elapsedRealtime(), projection, screenOn,
                previouslyGranted, accessibilityEnabled, backgroundStartMode,
                HyperOsHealthSnapshot.needsBackgroundStartPrompt(projection, accessibilityEnabled));
    }

    private static HyperOsHealthSnapshot idleReading() {
        return HyperOsHealthSnapshot.evaluate(-1L, HyperOsHealthSnapshot.ProjectionStatus.IDLE,
                false, false, true, HyperOsHealthSnapshot.OP_MODE_UNREADABLE, false);
    }

    /** 屏幕是否亮着：息屏时不重走授权，只留读数。 */
    @SuppressWarnings("deprecation")
    static boolean isScreenOn(Context context) {
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            return power != null && power.isInteractive();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * 无障碍服务是否仍在系统授权列表里。HyperOS 即使关掉电池优化也可能随机撤销授权，
     * 所以这里只核对现值，不做任何恢复动作。
     */
    static boolean isAccessibilityServiceEnabled(Context context) {
        try {
            AccessibilityManager manager = (AccessibilityManager)
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null) return false;
            String serviceId = context.getPackageName() + "/" + SenseFieldReaderService.class.getName();
            List<AccessibilityServiceInfo> enabled =
                    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabled == null) return false;
            for (AccessibilityServiceInfo info : enabled) {
                if (info != null && serviceId.equals(info.getId())) return true;
            }
            return false;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * 反射<b>读</b>后台弹出操作的现值。只调用 {@code checkOpNoThrow}，不寻找任何写入入口；
     * 反射失败或系统拒绝隐藏接口时返回 {@link HyperOsHealthSnapshot#OP_MODE_UNREADABLE}。
     */
    static int backgroundStartMode(Context context) {
        try {
            Object appOps = context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return HyperOsHealthSnapshot.OP_MODE_UNREADABLE;
            Object value = appOps.getClass()
                    .getMethod("checkOpNoThrow", String.class, int.class, String.class)
                    .invoke(appOps, BACKGROUND_START_OP, Process.myUid(), context.getPackageName());
            return value instanceof Integer ? (Integer) value
                    : HyperOsHealthSnapshot.OP_MODE_UNREADABLE;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            Log.i(TAG, "后台弹出操作现值不可读: " + unreadable.getClass().getSimpleName());
            return HyperOsHealthSnapshot.OP_MODE_UNREADABLE;
        }
    }

    /**
     * 网络这一跳：只<b>读</b>系统现值——活动网络的传输类型、VALIDATED 能力、下行带宽估计。
     * 不发起任何请求、不注册任何网络回调、不起线程；读不到一律交纯函数落 {@code unknown}，
     * 与「系统说没有网络」的 {@code none} 分成两档，谁也不替谁圆场。
     */
    static String networkReading(Context context) {
        long atMs = SystemClock.elapsedRealtime();
        if (context == null) return publishNetworkReading(atMs, null, null, null);
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) return publishNetworkReading(atMs, null, null, null);
            Network active = manager.getActiveNetwork();
            if (active == null)
                // 系统明说当前没有活动网络：这一档才是 none，不是 unknown。
                return publishNetworkReading(atMs, Collections.<Integer>emptyList(), null, null);
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
            if (capabilities == null) return publishNetworkReading(atMs, null, null, null);
            Collection<Integer> transports = new LinkedHashSet<>();
            for (int code : NetworkPathology.MIRRORED_TRANSPORTS)
                if (capabilities.hasTransport(code)) transports.add(Integer.valueOf(code));
            if (transports.isEmpty())
                // 有活动网络却一个传输类型也叫不出来：这是读不到，不许播成「没有网络」。
                return publishNetworkReading(atMs, null, null, null);
            Boolean validated = Boolean.valueOf(
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            // 平台取不到时返回 0：这里原样交给纯函数判 0＝unknown，不自己把它播成弱网。
            Integer downKbps = Integer.valueOf(capabilities.getLinkDownstreamBandwidthKbps());
            return publishNetworkReading(atMs, transports, validated, downKbps);
        } catch (RuntimeException unreadable) {
            Log.i(TAG, "网络现值不可读: " + unreadable.getClass().getSimpleName());
            return publishNetworkReading(atMs, null, null, null);
        }
    }

    /** 渲染并发布那一行读数；既有 device 面要取的就是这一格。 */
    private static String publishNetworkReading(long atMs, Collection<Integer> transports,
                                                Boolean validated, Integer downKbps) {
        lastNetworkReading = NetworkPathology.reading(atMs, transports, validated, downKbps);
        return lastNetworkReading;
    }

    /** 网络那一行的最后一条读数，采集侧唯一的出口；本卷不改它的取值口径。 */
    static String lastNetworkReading() { return lastNetworkReading; }

    /** 投影授权成功过一次的记忆位，供「此前已授权」判定；不新增任何系统权限。 */
    static void markProjectionGranted(Context context) {
        if (context == null) return;
        GameProfile.settings(context).edit().putBoolean(PREF_PROJECTION_GRANTED, true).apply();
    }

    static boolean wasProjectionGranted(Context context) {
        if (context == null) return false;
        return GameProfile.settings(context).getBoolean(PREF_PROJECTION_GRANTED, false);
    }

    /** 新会话授权成功后归还恢复额度与引导边沿，使下一次系统撤销仍能出一条引导。 */
    static void resetGuidanceEdge() {
        lastDelivered = HyperOsHealthSnapshot.Guidance.NONE;
    }

    /**
     * 把引导动作送到既有引导页出口；边沿触发——同一条动作连续出现只送一次，
     * 动作回到 {@link HyperOsHealthSnapshot.Guidance#NONE} 时重新武装。
     */
    static HyperOsHealthSnapshot.Guidance deliverGuidanceEdge(Context context, HyperOsHealthSnapshot.Guidance action) {
        if (context == null || action == null) return HyperOsHealthSnapshot.Guidance.NONE;
        if (action == HyperOsHealthSnapshot.Guidance.NONE) {
            lastDelivered = HyperOsHealthSnapshot.Guidance.NONE;
            return action;
        }
        if (action == lastDelivered) return action;
        lastDelivered = action;
        deliverGuidance(context, action);
        return action;
    }

    /** 唯一的引导出口：既有授权／引导页，不新增 Activity、不新增弹窗类型。 */
    static boolean deliverGuidance(Context context, HyperOsHealthSnapshot.Guidance action) {
        if (context == null || action == null
                || action == HyperOsHealthSnapshot.Guidance.NONE) return false;
        final Intent guidance = new Intent(context, CapturePermissionsActivity.class)
                .putExtra(CapturePermissionsActivity.EXTRA_START,
                        action == HyperOsHealthSnapshot.Guidance.PROMPT_REAUTH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            // 采样线程不直接开页面，回主线程投递；主线程是既有 Looper，不新增线程。
            new Handler(Looper.getMainLooper()).post(() -> {
                try { context.startActivity(guidance); }
                catch (RuntimeException blocked) {
                    Log.w(TAG, "引导页被系统拦下 guidance=" + action
                            + " " + blocked.getClass().getSimpleName());
                }
            });
            Log.i(TAG, "引导动作已送既有引导页 guidance=" + action);
            return true;
        } catch (RuntimeException blocked) {
            Log.w(TAG, "引导动作投递失败 guidance=" + action, blocked);
            return false;
        }
    }
}
