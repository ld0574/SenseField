package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

/** 播报节流：红立即＋冷却、黄低频、静默档、时钟注入、fail-silent。 */
public class ElderRiskAnnouncementPolicyTest {

    /** 文档纪律的毫秒值（docs/design/养老场景可信标签MVP.md §六：红 10 分钟、黄 30 分钟）。
     *  这里用字面量而不用生产常量：改常量必须同时改这里——锁住纪律本身。 */
    private static final long RED_COOLDOWN_DOC = 600_000L;
    private static final long YELLOW_COOLDOWN_DOC = 1_800_000L;

    private static ElderRiskLabel red(String evidence) {
        return new ElderRiskLabel(ElderRiskLabel.Tier.RED, "RED-01", evidence, "解释", false);
    }

    private static ElderRiskLabel yellow() {
        return new ElderRiskLabel(ElderRiskLabel.Tier.YELLOW, "YEL-02", "限时抢购", "解释", false);
    }

    @Test
    public void cooldownConstantsMatchDocumentedDiscipline() {
        assertEquals(RED_COOLDOWN_DOC, ElderRiskAnnouncementPolicy.RED_REPEAT_COOLDOWN_MS);
        assertEquals(YELLOW_COOLDOWN_DOC, ElderRiskAnnouncementPolicy.YELLOW_COOLDOWN_MS);
    }

    @Test
    public void firstRedAnnouncesImmediately() {
        AtomicLong clock = new AtomicLong(1_000);
        ElderRiskAnnouncementPolicy policy = new ElderRiskAnnouncementPolicy(clock::get);
        ElderRiskAnnouncementPolicy.Decision d = policy.decide(red("证据A"));
        assertTrue(d.announce);
        assertEquals("first-seen", d.reason);
    }

    @Test
    public void sameRedInsideCooldownHolds() {
        AtomicLong clock = new AtomicLong(1_000);
        ElderRiskAnnouncementPolicy policy = new ElderRiskAnnouncementPolicy(clock::get);
        assertTrue(policy.decide(red("证据A")).announce);
        clock.addAndGet(RED_COOLDOWN_DOC - 1);
        ElderRiskAnnouncementPolicy.Decision d = policy.decide(red("证据A"));
        assertFalse(d.announce);
        assertEquals("cooldown-hold", d.reason);
    }

    @Test
    public void sameRedRepeatsOnceAfterCooldownElapsed() {
        AtomicLong clock = new AtomicLong(1_000);
        ElderRiskAnnouncementPolicy policy = new ElderRiskAnnouncementPolicy(clock::get);
        assertTrue(policy.decide(red("证据A")).announce);
        clock.addAndGet(RED_COOLDOWN_DOC);
        ElderRiskAnnouncementPolicy.Decision d = policy.decide(red("证据A"));
        assertTrue(d.announce);
        assertEquals("cooldown-elapsed", d.reason);
    }

    @Test
    public void differentEvidenceIsDifferentFingerprintSoBothAnnounce() {
        ElderRiskAnnouncementPolicy policy = new ElderRiskAnnouncementPolicy(() -> 1_000);
        assertTrue(policy.decide(red("证据A")).announce);
        assertTrue(policy.decide(red("证据B")).announce);
        assertEquals(2, policy.trackedFingerprints());
    }

    @Test
    public void yellowUsesItsOwnLongerCooldown() {
        AtomicLong clock = new AtomicLong(1_000);
        ElderRiskAnnouncementPolicy policy = new ElderRiskAnnouncementPolicy(clock::get);
        assertTrue(policy.decide(yellow()).announce);
        clock.addAndGet(RED_COOLDOWN_DOC + 1);
        // 超过红的冷却但没到黄的冷却：黄仍被压住。
        assertFalse(policy.decide(yellow()).announce);
        clock.addAndGet(YELLOW_COOLDOWN_DOC);
        assertTrue(policy.decide(yellow()).announce);
    }

    @Test
    public void silentTiersNeverAnnounce() {
        ElderRiskAnnouncementPolicy policy = new ElderRiskAnnouncementPolicy(() -> 1_000);
        for (ElderRiskLabel.Tier tier : ElderRiskLabel.Tier.values()) {
            if (tier == ElderRiskLabel.Tier.RED || tier == ElderRiskLabel.Tier.YELLOW) continue;
            ElderRiskLabel label = new ElderRiskLabel(tier, "R", "E", "X", false);
            ElderRiskAnnouncementPolicy.Decision d = policy.decide(label);
            assertFalse(tier.name(), d.announce);
            assertEquals("tier-silent", d.reason);
        }
        assertEquals(0, policy.trackedFingerprints());
    }

    @Test
    public void nullLabelIsFailSilent() {
        ElderRiskAnnouncementPolicy policy = new ElderRiskAnnouncementPolicy(() -> 1_000);
        assertFalse(policy.decide(null).announce);
    }
}
