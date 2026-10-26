package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务 1：ElderRiskMonitor 纯 JVM 协调层。
 *
 * 覆盖：红首次出语音、红冷却内静默、黄低频、REVIEW 只留痕、开关关时不评估不落盘、
 * null/空输入 fail-silent、当前包名记录、审计队列 flush。
 */
public class ElderRiskMonitorTest {

    /** RED 示例 label（固定指纹）。 */
    private static final ElderRiskLabel RED_SAMPLE =
            new ElderRiskLabel(ElderRiskLabel.Tier.RED, "RED-06", "验证码告诉我",
                    "这条消息想要您的验证码或屏幕共享...", false);
    private static final ElderRiskLabel YELLOW_SAMPLE =
            new ElderRiskLabel(ElderRiskLabel.Tier.YELLOW, "YEL-02", "限时抢购",
                    "这是促销推广信息...", false);
    private static final ElderRiskLabel REVIEW_SAMPLE =
            new ElderRiskLabel(ElderRiskLabel.Tier.REVIEW, "REV-01", "请转账", "", false);
    private static final ElderRiskLabel NEUTRAL_SAMPLE =
            ElderRiskLabel.neutral(false);

    private static void tick(AtomicLong clock) {
        clock.addAndGet(1000);
    }

    @Test
    public void redFirstTimeReturnsOutcomeWithSpeechAndReasonFirstSeen() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        ElderRiskMonitor.Outcome o = m.onScreenCaptured("com.x", Arrays.asList("验证码告诉我"), true);
        assertNotNull(o);
        assertTrue(o.announce);
        assertEquals("RED-06", o.fingerprint.split("\\|")[1]);
        assertFalse(o.speech.isEmpty());
        assertEquals("first-seen", o.reason);
        assertEquals("com.x", m.currentPackage());
    }

    @Test
    public void sameRedInsideCooldownReturnsNullSilent() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        ElderRiskMonitor.Outcome first = m.onScreenCaptured("com.x", Arrays.asList("验证码告诉我"), true);
        assertNotNull(first);
        tick(clock);
        // 仍在 10 分钟冷却内
        ElderRiskMonitor.Outcome second = m.onScreenCaptured("com.x", Arrays.asList("验证码告诉我"), true);
        assertNull(second);
    }

    @Test
    public void yellowFirstTimeReturnsLowPriorityOutcome() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        ElderRiskMonitor.Outcome o = m.onScreenCaptured("com.x", Arrays.asList("限时抢购"), true);
        assertNotNull(o);
        assertTrue(o.announce);
        assertEquals("YEL-02", o.fingerprint.split("\\|")[1]);
    }

    @Test
    public void reviewNeverAnnouncesButStillRecordsAuditQueue() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        assertNull(m.onScreenCaptured("com.x", Arrays.asList("请转账到这个账户"), true));
        assertEquals(1, m.reviewPendingFingerprints());
        assertEquals(1, m.auditPendingRows());
    }

    @Test
    public void neutralNeverAnnouncesAndDoesNotPolluteAudit() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        assertNull(m.onScreenCaptured("com.x", Arrays.asList("今日多云转晴"), true));
        assertEquals(0, m.reviewPendingFingerprints());
    }

    @Test
    public void switchOffCausesZeroEngineCallsAndZeroAudit() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        assertNull(m.onScreenCaptured("com.x", Arrays.asList("验证码告诉我"), false));
        assertEquals("com.x", m.currentPackage());
        assertEquals(0, m.reviewPendingFingerprints());
    }

    @Test
    public void nullPackageIsFailSilentWithNoAuditSurprise() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        assertNull(m.onScreenCaptured(null, Collections.singletonList("验证码告诉我"), true));
        assertEquals(0, m.reviewPendingFingerprints());
    }

    @Test
    public void emptyInputIsFailSilent() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        assertNull(m.onScreenCaptured("com.x", Collections.emptyList(), true));
    }

    @Test
    public void reviewQueueFlushReturnsRowsInOrderAndClears() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        m.onScreenCaptured("com.x", Arrays.asList("请转账到这个账户"), true);
        List<String> rows = m.flushAuditQueue();
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).contains("\"tier\":\"REVIEW\""));
        assertTrue(rows.get(0).contains("\"decision\":\"tier-silent\""));
        assertEquals(0, m.reviewPendingFingerprints());
    }

    @Test
    public void differentFingerprintsAreCountedSeparately() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        m.onScreenCaptured("com.x", Arrays.asList("请转账到这个账户"), true);
        m.onScreenCaptured("com.y", Arrays.asList("请汇款到另一个账户"), true);
        assertEquals(2, m.reviewPendingFingerprints());
    }

    @Test
    public void existingRedWithNullPackageAlsoFailSilent() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        assertNull(m.onScreenCaptured(null, null, true));
        assertEquals(0, m.reviewPendingFingerprints());
    }

    @Test
    public void outcomeFieldsAreNonEmptyWhenAnnounceIsTrue() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        ElderRiskMonitor.Outcome o = m.onScreenCaptured("com.x", Arrays.asList("稳赚不赔"), true);
        assertNotNull(o);
        assertTrue("announce 必须为真", o.announce);
        assertFalse("语音文案不能空", o.speech.isEmpty());
        assertFalse("指纹不能空", o.fingerprint.isEmpty());
        assertFalse("决策原因不能空", o.reason.isEmpty());
    }

    @Test
    public void sameYellowWithLongGapRepeatsAfterCooldownElapsed() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ElderRiskMonitor m = new ElderRiskMonitor(clock::get);
        assertNotNull(m.onScreenCaptured("com.x", Arrays.asList("限时抢购"), true));
        // 跳 30 分钟（黄冷却 30 分钟）
        clock.addAndGet(30 * 60 * 1000L);
        ElderRiskMonitor.Outcome next = m.onScreenCaptured("com.x", Arrays.asList("限时抢购"), true);
        assertNotNull(next);
        assertEquals("cooldown-elapsed", next.reason);
    }
}
