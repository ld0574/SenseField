package com.openkhub.sensefield;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 风险标签的播报节流策略（纯逻辑，时钟可注入）。
 *
 * 设计动机（docs/design/养老场景可信标签MVP.md §六）：标签引擎挂在
 * TYPE_WINDOW_CONTENT_CHANGED 上，同一屏会被反复判出同一条红/黄。不节流就是
 * 「后面一直重复」的翻版；不节流＋重复红又是狼来了——老人第二次就不听了。
 *
 * - RED：新指纹立即播；同指纹 10 分钟内不重播，超过后最多再播一次；
 * - YELLOW：同指纹 30 分钟只播一次；
 * - REVIEW/GREEN/NEUTRAL：永不语音播报（REVIEW 进审核记录，不惊扰）；
 * - 异常输入一律不播（fail-silent，降级优先）。
 */
public final class ElderRiskAnnouncementPolicy {

    public static final long RED_REPEAT_COOLDOWN_MS = 10 * 60 * 1000L;
    public static final long YELLOW_COOLDOWN_MS = 30 * 60 * 1000L;

    /** 判定输出：是否播＋原因（原因进诊断留痕，不进播报文案）。 */
    public static final class Decision {
        public final boolean announce;
        public final String reason;

        Decision(boolean announce, String reason) {
            this.announce = announce;
            this.reason = reason;
        }
    }

    private final LongSupplier clock;
    private final Map<String, Long> lastAnnouncedAt = new HashMap<>();

    public ElderRiskAnnouncementPolicy(LongSupplier clock) {
        this.clock = clock;
    }

    public Decision decide(ElderRiskLabel label) {
        if (label == null || label.announceKind() == ElderRiskLabel.Announce.NONE) {
            return new Decision(false, "tier-silent");
        }
        long now = clock.getAsLong();
        String fingerprint = label.fingerprint();
        Long last = lastAnnouncedAt.get(fingerprint);
        long cooldown = label.tier == ElderRiskLabel.Tier.RED
                ? RED_REPEAT_COOLDOWN_MS : YELLOW_COOLDOWN_MS;
        if (last == null) {
            lastAnnouncedAt.put(fingerprint, now);
            return new Decision(true, "first-seen");
        }
        if (now - last >= cooldown) {
            lastAnnouncedAt.put(fingerprint, now);
            return new Decision(true, "cooldown-elapsed");
        }
        return new Decision(false, "cooldown-hold");
    }

    /** 状态行数（审核留痕用）：当前记忆的不同指纹数。 */
    public int trackedFingerprints() {
        return lastAnnouncedAt.size();
    }
}
