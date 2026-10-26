package com.openkhub.sensefield;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 养老场景可信标签监控器（方案一 MVP，纯 JVM 零 Android import，旁挂不接播放路径）。
 *
 * 职责：把来自读屏服务的包名＋节点文本列表喂给 {@link ElderRiskLabelEngine}，经
 * {@link ElderRiskAnnouncementPolicy} 节流后产出两类输出：
 * - 需要语音播报的内容（RED/YELLOW）：语音文案、指纹、决策原因；
 * - 仅留审计的事件（REVIEW/GREEN/NEUTRAL/null/关闭）：只打审计日志不发音。
 *
 * 开关默认关（SharedPreferences 键 elder_risk_labels_enabled，默认 false）——游戏玩家不
 * 会被养老播报打扰。关闭时引擎根本不评估，审计也不落盘（隐私优先）。
 *
 * 工程红线（继承 haptics/AAC/ElderRisk 旁挂先例）：
 * - 不接 CuePlayer/CueDispatcher，不读系统服务，不抛异常出去——任何异常都归入 NEUTRAL；
 * - 开关关时不调引擎、不落审计——这是本类的唯一对外可见行为（测试可断言）；
 * - 时钟可注入，方便测试。
 */
public final class ElderRiskMonitor {

    /** 审计事件一行。 */
    public final class AuditEntry {
        public final String tier;
        public final String ruleId;
        public final String evidence;
        public final String pkg;
        public final long tsMs;
        public final String decision;
        public final String speech;

        AuditEntry(ElderRiskLabel label, String reason) {
            this.tier = label == null ? "SILENT" : label.tier.name();
            this.ruleId = label == null ? "" : label.ruleId;
            this.evidence = label == null ? "" : label.evidence;
            this.pkg = package_ == null ? "" : package_;
            this.tsMs = nowMs();
            this.decision = reason;
            this.speech = label == null || label.announceKind() == ElderRiskLabel.Announce.NONE
                    ? "" : label.explanation;
        }

        public String toJson() {
            return String.format(Locale.ROOT,
                    "{\"tier\":\"%s\",\"ruleId\":\"%s\",\"evidence\":\"%s\","
                            + "\"pkg\":\"%s\",\"tsMs\":%d,\"decision\":\"%s\",\"speech\":\"%s\"}",
                    tier, ruleId, esc(evidence), pkg, tsMs,
                    esc(decision), esc(speech));
        }

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
    }

    /** 需要语音播报的决策。 */
    public static final class Outcome {
        public final boolean announce;
        public final String speech;
        public final String fingerprint;
        public final String reason;

        Outcome(boolean announce, String speech, String fingerprint, String reason) {
            this.announce = announce;
            this.speech = speech == null ? "" : speech;
            this.fingerprint = fingerprint;
            this.reason = reason;
        }
    }

    /** 阈值：REVIEW/GREEN/NEUTRAL 事件也落审计的窗口大小。 */
    static final int EVIDENCE_WINDOW_RADIUS = 18;

    private final LongSupplier clock;
    private final ElderRiskLabelEngine engine = new ElderRiskLabelEngine();
    private final ElderRiskAnnouncementPolicy policy;
    private final Map<String, AuditEntry> reviewEntries = new HashMap<>();
    private String package_ = "";
    private final long REVIEW_FLUSH_MS = 5 * 60 * 1000L;

    public ElderRiskMonitor(LongSupplier clock) {
        this.clock = clock;
        this.policy = new ElderRiskAnnouncementPolicy(clock);
    }

    /** 入口：从 SenseFieldReaderService 调用。开关关时直接返回 null，不调引擎。 */
    public Outcome onScreenCaptured(String pkg, List<String> screenTexts) {
        return onScreenCaptured(pkg, screenTexts, false);
    }

    /** 供测试注入开关。 */
    public Outcome onScreenCaptured(String pkg, List<String> screenTexts, boolean enabled) {
        if (pkg == null || pkg.isEmpty()) {
            package_ = "";
            return null;
        }
        this.package_ = pkg;
        if (!enabled) return null;
        try {
            ElderRiskLabel label = engine.evaluate(pkg, screenTexts);
            ElderRiskAnnouncementPolicy.Decision d = policy.decide(label);
            recordAudit(label, d.reason);
            if (!d.announce) return null;
            return new Outcome(true, label.explanation, label.fingerprint(), d.reason);
        } catch (Exception e) {
            recordAudit(null, "exception:" + e.getClass().getSimpleName());
            return null;
        }
    }

    /** 当前包名（测试用）。 */
    public String currentPackage() {
        return package_;
    }

    /** 审计队列行数（未冲洗）。 */
    public int auditPendingRows() {
        return reviewEntries.size();
    }

    /** 冲洗审计队列并返回所有行（调用方负责写入 JSONL），清空内部状态。 */
    /** 冲洗审计队列并返回所有 JSON 行（调用方负责写入 JSONL），清空内部状态。 */
    public List<String> flushAuditQueue() {
        if (reviewEntries.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<String> out = new java.util.ArrayList<>(reviewEntries.size());
        reviewEntries.values().forEach(entry -> out.add(entry.toJson()));
        reviewEntries.clear();
        return out;
    }

    /** 当前待审指纹数。 */
    public int reviewPendingFingerprints() {
        return reviewEntries.size();
    }

    /** 注册一条非 RED/YELLOW 的审计行；不发音但留痕。 */
    private void recordAudit(ElderRiskLabel label, String reason) {
        if (label == null && reason.startsWith("tier-silent")) {
            addToReview(label, reason);
            return;
        }
        if (label != null && (label.tier == ElderRiskLabel.Tier.REVIEW)) {
            addToReview(label, reason);
        }
        // RED/YELLOW 由 policy 决定是否播，AUDIT 只接 REVIEW 档（GREEN/NEUTRAL 不进审计避免污染）
    }

    private void addToReview(ElderRiskLabel label, String reason) {
        String fp = label == null ? "SIL+" + reason : label.fingerprint();
        if (fp.isEmpty()) return;
        reviewEntries.put(fp, new AuditEntry(label, reason));
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }
}
