package com.openkhub.sensefield;

/**
 * HyperOS／Android 16 系统级「静默停止」的读数层：把「投影被系统释放」「无障碍授权被撤销」
 * 「后台弹出被拒」三件事收敛成一行结构化读数＋一个引导动作。
 *
 * <p>纯函数：入参全部是基本类型与本包枚举，出参是不可变对象，零 {@code android.*} import，
 * 因此整条判定规则可以在 JVM 单元测试里逐条钉死。真正的读数采集（屏幕状态、授权列表、
 * AppOps 现值）在 {@code HyperOsSystemProbe} 那一侧，本类不碰任何系统 API。
 *
 * <p>三条动作里只出一个，优先级固定为 投影 &gt; 无障碍 &gt; 后台弹出：玩家一次只会收到一个
 * 明确的下一步，而不是三个弹窗。
 */
final class HyperOsHealthSnapshot {

    /** 投影链路的现状；与帧饥饿检测的横竖屏语义无关，只作读输入用。 */
    enum ProjectionStatus { IDLE, ACTIVE, REVOKED, FAILED }

    /** 本条读数应当向玩家发出的唯一引导动作。 */
    enum Guidance { NONE, PROMPT_REAUTH, PROMPT_ACCESSIBILITY, PROMPT_BACKGROUND_START }

    /* AppOpsManager 的模式取值在本类里镜像成普通 int，避免把 android.* 引进纯函数。
     * 镜像表见 android.app.AppOpsManager；读不到时用负值，绝不与「已允许」混同。 */
    static final int OP_MODE_ALLOWED = 0;
    static final int OP_MODE_DEFAULT = 1;
    static final int OP_MODE_ERRORED = 2;
    /** 系统把这个操作判给了别的策略：对本应用即为「后台弹出被拒」。 */
    static final int OP_MODE_IGNORED = 3;
    /** 反射读不到现值；按「不确定」处理，不许据此弹引导。 */
    static final int OP_MODE_UNREADABLE = -1;

    /** 读数写进既有 {@code device} 事件时用的两个新键；既有键一字不动。 */
    static final String READING_KEY = "hyperos_system_reading";
    static final String GUIDANCE_KEY = "hyperos_guidance";

    /** 投影被系统释放后，第一次允许重走授权，之后的每一次只落这条终态读数。 */
    static final String REASK_READING = "capture_revoked_reask";
    static final String REVOKED_FINAL_READING = "capture_revoked_final";
    /** 没有恢复动作可走时的空读数。 */
    static final String NO_RECOVERY_READING = "";

    /* ---------- 前台抬升：类型闸门读数 + 被拒降级读数 ----------
     * 两行读数各说各话：静态行只说「这一级 API 要不要类型、清单声明了什么、本机有没有依据」，
     * 运行时行只说「这一跳真的被拒了、按异常名归到哪一类」。静态行不写运行时后果，
     * 运行时行不写归因——本机无 adb、无该品牌设备，任何一侧都不许宣称真机效果。 */

    /** 既有 {@code device} 事件再追加的两个键；既有九字段与既有两键一字不动。 */
    static final String FGS_GATE_KEY = "fgs_type_gate";
    static final String FGS_DEGRADATION_KEY = "fgs_degradation";

    /** 从这一级 API 起，前台服务必须声明至少一种类型；纯常量比较，不碰任何系统 API。 */
    static final int FGS_TYPE_REQUIRED_FROM_API = 34;

    /** 没观察到被拒时的空读数：空串只表示「未观测」，绝不表示抬升成功。 */
    static final String NO_FGS_DEGRADATION_READING = "";

    /** 清单没为本服务声明任何类型时用的字面量；这条清单事实由用例逐字锁清单原文。 */
    static final String FGS_UNDECLARED_TYPES = "none";

    /** 本卷结论：本机可复现的类型表里没有能为本服务辩护的类型依据。 */
    static final String FGS_NO_LOCAL_BASIS = "no_defensible_local_type";

    private static final String UNKNOWN = "unknown";

    private final long sampledAtMs;
    private final ProjectionStatus projection;
    private final boolean screenOn;
    private final boolean projectionPreviouslyGranted;
    private final boolean accessibilityEnabled;
    private final int backgroundStartMode;
    private final Guidance action;
    private final String line;

    private HyperOsHealthSnapshot(long sampledAtMs, ProjectionStatus projection, boolean screenOn,
                                  boolean projectionPreviouslyGranted, boolean accessibilityEnabled,
                                  int backgroundStartMode, Guidance action, String line) {
        this.sampledAtMs = sampledAtMs;
        this.projection = projection;
        this.screenOn = screenOn;
        this.projectionPreviouslyGranted = projectionPreviouslyGranted;
        this.accessibilityEnabled = accessibilityEnabled;
        this.backgroundStartMode = backgroundStartMode;
        this.action = action;
        this.line = line;
    }

    /**
     * 三条限制同时成立时只出一个动作：投影 &gt; 无障碍 &gt; 后台弹出。
     *
     * <p>投影分支同时要求亮屏且此前授权过：息屏时重走授权没有可读性，因此投影分支不成立，
     * 级联继续往下判（无障碍与后台弹出各自的读数与引导本来就不依赖投影）。
     */
    private static Guidance guidanceFor(ProjectionStatus projection, boolean screenOn,
                                        boolean projectionPreviouslyGranted,
                                        boolean accessibilityEnabled, int backgroundStartMode,
                                        boolean backgroundStartPromptNeeded) {
        if (projection == ProjectionStatus.REVOKED && screenOn && projectionPreviouslyGranted)
            return Guidance.PROMPT_REAUTH;
        if (!accessibilityEnabled) return Guidance.PROMPT_ACCESSIBILITY;
        if (backgroundStartPromptNeeded && backgroundStartMode == OP_MODE_IGNORED)
            return Guidance.PROMPT_BACKGROUND_START;
        return Guidance.NONE;
    }

    /** 后台弹出这一条只在「本读数确实要把引导页送到玩家面前」时才需要判。 */
    static boolean needsBackgroundStartPrompt(ProjectionStatus projection,
                                              boolean accessibilityEnabled) {
        return projection == ProjectionStatus.REVOKED || !accessibilityEnabled;
    }

    /** 全量入口：七个入参都是已采集好的现值，本方法不做任何系统调用。 */
    static HyperOsHealthSnapshot evaluate(long sampledAtMs, ProjectionStatus projection,
                                          boolean screenOn, boolean projectionPreviouslyGranted,
                                          boolean accessibilityEnabled, int backgroundStartMode,
                                          boolean backgroundStartPromptNeeded) {
        ProjectionStatus safeProjection =
                projection == null ? ProjectionStatus.IDLE : projection;
        Guidance action = guidanceFor(safeProjection, screenOn, projectionPreviouslyGranted,
                accessibilityEnabled, backgroundStartMode, backgroundStartPromptNeeded);
        return new HyperOsHealthSnapshot(sampledAtMs, safeProjection, screenOn,
                projectionPreviouslyGranted, accessibilityEnabled, backgroundStartMode, action,
                renderLine(sampledAtMs, safeProjection, screenOn, projectionPreviouslyGranted,
                        accessibilityEnabled, backgroundStartMode, action));
    }

    private static String renderLine(long sampledAtMs, ProjectionStatus projection, boolean screenOn,
                                     boolean projectionPreviouslyGranted, boolean accessibilityEnabled,
                                     int backgroundStartMode, Guidance action) {
        StringBuilder out = new StringBuilder("hyperos");
        out.append(" at=").append(sampledAtMs);
        out.append(" proj=").append(projection == ProjectionStatus.REVOKED ? "revoked"
                : projection == ProjectionStatus.FAILED ? "failed"
                        : projection == ProjectionStatus.ACTIVE ? "active" : "idle");
        out.append(" screen=").append(screenOn ? "on" : "off");
        out.append(" prior_grant=").append(projectionPreviouslyGranted ? "granted" : "none");
        out.append(" a11y=").append(accessibilityEnabled ? "enabled" : "revoked");
        out.append(" bg_start=").append(backgroundStartModeName(backgroundStartMode));
        out.append(" guidance=").append(action == null ? UNKNOWN : action.name());
        return out.toString();
    }

    private static String backgroundStartModeName(int mode) {
        switch (mode) {
            case OP_MODE_ALLOWED: return "allowed";
            case OP_MODE_DEFAULT: return "default";
            case OP_MODE_ERRORED: return "errored";
            case OP_MODE_IGNORED: return "ignored";
            default: return "unreadable";
        }
    }

    /**
     * 类型闸门那一行读数（静态结论，不需要真机就能成立）：这一级 API 要不要类型、
     * 清单给本服务声明了什么、本机拿不拿得出依据。
     */
    static String fgsTypeGateReading(int apiLevel, String declaredTypes) {
        StringBuilder out = new StringBuilder("api=").append(apiLevel);
        out.append(" requires_from=").append(FGS_TYPE_REQUIRED_FROM_API);
        out.append(" type_required=").append(apiLevel >= FGS_TYPE_REQUIRED_FROM_API);
        out.append(" declared=").append(declaredTypes == null || declaredTypes.isEmpty()
                ? FGS_UNDECLARED_TYPES : declaredTypes);
        out.append(" basis=").append(FGS_NO_LOCAL_BASIS);
        return out.toString();
    }

    /**
     * 被拒原因归类：只按异常<b>类名字符串</b>归，不 import、不假设任何类型名在这台机器上存在；
     * 认不出来的统统落 {@code other_runtime}，不猜。
     */
    static String fgsCauseCategory(String causeName) {
        if (causeName == null || causeName.isEmpty()) return "unreadable_cause";
        if (causeName.contains("MissingForegroundServiceType"))
            return "missing_foreground_service_type";
        if (causeName.contains("ForegroundServiceStartNotAllowed")) return "start_not_allowed";
        if (causeName.contains("SecurityException")) return "security";
        return "other_runtime";
    }

    /**
     * 降级那一行读数（运行时观测）：{@code result=call_threw} 说的是「这一跳抛异常了」，
     * 不是「设备一定把它杀了」；{@code kind=} 只是异常名分类，不宣称被拒的因果。
     */
    static String fgsDegradationReading(long atMs, String causeName, int apiLevel,
                                        String declaredTypes) {
        StringBuilder out = new StringBuilder("at=").append(atMs);
        out.append(" result=call_threw");
        out.append(" cause=").append(causeName == null || causeName.isEmpty()
                ? UNKNOWN : causeName);
        out.append(" kind=").append(fgsCauseCategory(causeName));
        out.append(" sdk=").append(apiLevel);
        out.append(" declared=").append(declaredTypes == null || declaredTypes.isEmpty()
                ? FGS_UNDECLARED_TYPES : declaredTypes);
        return out.toString();
    }

    /** 一行结构化读数，直接写进既有 {@code device} 事件。 */
    String line() { return line; }

    /** 本条读数对应的唯一引导动作。 */
    Guidance action() { return action; }

    long sampledAtMs() { return sampledAtMs; }

    ProjectionStatus projection() { return projection; }

    boolean screenOn() { return screenOn; }

    boolean accessibilityEnabled() { return accessibilityEnabled; }

    /**
     * 一次性恢复额度。投影被系统释放后只允许自动重走一次授权；额度用完后不再循环重试，
     * 只落 {@link #REVOKED_FINAL_READING}。放在纯函数侧是为了让「只触发一次」可被单元测试钉死。
     */
    static final class ReauthLatch {
        private boolean claimed;

        /** 取一次额度；已被取过则返回 false，且永远不再返回 true（直到 {@link #reset()}）。 */
        synchronized boolean claim() {
            if (claimed) return false;
            claimed = true;
            return true;
        }

        synchronized boolean spent() { return claimed; }

        /** 新的授权成功之后重新给一次额度，供下一次系统撤销时使用。 */
        synchronized void reset() { claimed = false; }
    }

    /**
     * 由读数决定恢复读数：只有 {@link Guidance#PROMPT_REAUTH} 才消耗额度，
     * 额度用完之后每次返回 {@link #REVOKED_FINAL_READING}，不产生第二次弹窗。
     */
    static String recoveryReadingFor(HyperOsHealthSnapshot snapshot, ReauthLatch latch) {
        if (snapshot == null || snapshot.action() != Guidance.PROMPT_REAUTH)
            return NO_RECOVERY_READING;
        return latch.claim() ? REASK_READING : REVOKED_FINAL_READING;
    }
}
