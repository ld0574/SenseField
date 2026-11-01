package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * 任务书 42：玩家自陈视野轴的纯 JVM 锁。不碰任何既有测试的期望值；这里只新增断言。
 *
 * <p>编号对照完成条件①②：默认档逐字段不变（test 1）、周边档边缘加宽（test 2/8）、
 * 周边档对非边缘不动（test 3）、中心档冻结锁（test 4）、NaN 几何（test 5/9）、
 * 关闭类别不复活（test 6/7）。
 */
public final class FieldLossPolicyTest {
    private static final float[] GEOMETRY = {
            -1f, -0.2f, 0f, 0.7f, 1f, 0.4f, 2f, Float.NaN,
    };
    private static final float[] URGENCIES = {0f, 1f, 2f, Float.NaN};
    private static final int[] MASKS = {0, 1, 2, 4, 8, 3, 5, 9, 10, 15};
    private static final int ALL_EXISTING_BITS = CueRequest.CHANNEL_TONE
            | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC
            | CueRequest.CHANNEL_VISUAL;

    /** 记录 outputAvailable 收到的掩码，仿 CueArbiterTest 的 FakeDirectOutput，不复用它的文件。 */
    private static final class RecordingOutput implements CueArbiter.DirectCueOutput {
        int lastRequestedChannels = -1;
        CueRequest.Category lastCategory;
        int outputAvailableCalls;
        int dispatches;

        @Override public boolean categoryEnabled(CueRequest.Category category) {
            return true;
        }

        @Override public boolean outputAvailable(CueRequest.Category category,
                                                  int requestedChannels) {
            lastCategory = category;
            lastRequestedChannels = requestedChannels;
            outputAvailableCalls++;
            return true;
        }

        @Override public boolean dispatch(NativeFrameResult frame, long observedAtMs) {
            dispatches++;
            return true;
        }
    }

    private static NativeFrameResult frame(int cueKind, int direction, int trackId,
                                           int transition) {
        int[] packet = new int[NativeFrameResult.LEGACY_HEADER_SIZE
                + NativeFrameResult.LEGACY_MARKER_STRIDE];
        packet[0] = cueKind;
        packet[1] = direction;
        packet[2] = 40;
        packet[7] = 100_000;
        packet[8] = 100_000;
        packet[9] = 800_000;
        packet[10] = 800_000;
        packet[11] = 1;
        packet[12] = TrackedEntity.STATE_VISIBLE;
        packet[14] = 200_000;
        packet[15] = 200_000;
        packet[16] = 100_000;
        packet[17] = 100_000;
        packet[19] = transition;
        packet[20] = trackId;
        return NativeFrameResult.parse(packet, 1000);
    }

    /** 完成条件①：默认档（未自陈）下输出逐字段等于入参，行为与没有本轴时相同。 */
    @Test public void defaultTierKeepsEveryOutputFieldUnchanged() {
        for (int mask : MASKS) {
            for (boolean edge : new boolean[] {true, false}) {
                for (float urgency : URGENCIES) {
                    for (float pan : GEOMETRY) {
                        for (float distance : GEOMETRY) {
                            for (boolean category : new boolean[] {true, false}) {
                                FieldLossPolicy.Outcome outcome = FieldLossPolicy.decide(
                                        FieldLossPolicy.TIER_NONE, edge, mask,
                                        pan, distance, urgency, category);
                                assertEquals(mask, outcome.channels);
                                assertFalse(outcome.elevatedPriority);
                                assertFalse(outcome.keepPersistentDisplay);
                            }
                        }
                    }
                }
            }
        }
    }

    /** 完成条件②的对照组：周边档下 edge==true 的线索通道集合严格比默认档宽，且只加既成位。 */
    @Test public void peripheralTierWidensEdgeChannelSetBeyondDefault() {
        int toneOnly = CueRouting.directCueRequestedChannels(
                TrackedEntity.KIND_MAIN_ENEMY, 1);
        FieldLossPolicy.Outcome baseline = FieldLossPolicy.decide(FieldLossPolicy.TIER_NONE,
                true, toneOnly, 0f, 0f, 0f, true);
        FieldLossPolicy.Outcome widened = FieldLossPolicy.decide(
                FieldLossPolicy.TIER_PERIPHERAL_LOSS, true, toneOnly, 0f, 0f, 0f, true);
        assertEquals(toneOnly, baseline.channels);
        assertNotEquals(baseline, widened);
        assertEquals(baseline.channels, widened.channels & baseline.channels);
        assertEquals(toneOnly | FieldLossPolicy.PROMOTION_CHANNEL, widened.channels);
        assertTrue(widened.elevatedPriority);
        assertEquals(CueRequest.CHANNEL_HAPTIC, FieldLossPolicy.PROMOTION_CHANNEL);
    }

    /** 周边档只宽边缘：edge==false 的线索输出与默认档逐字段相同。 */
    @Test public void peripheralTierLeavesNonEdgeCuesIdenticalToDefault() {
        for (int mask : MASKS) {
            for (float urgency : URGENCIES) {
                for (float pan : GEOMETRY) {
                    FieldLossPolicy.Outcome none = FieldLossPolicy.decide(
                            FieldLossPolicy.TIER_NONE, false, mask,
                            pan, Float.NaN, urgency, true);
                    FieldLossPolicy.Outcome peripheral = FieldLossPolicy.decide(
                            FieldLossPolicy.TIER_PERIPHERAL_LOSS, false, mask,
                            pan, Float.NaN, urgency, true);
                    assertEquals(none, peripheral);
                    assertEquals(mask, peripheral.channels);
                    assertFalse(peripheral.elevatedPriority);
                }
            }
        }
    }

    /**
     * 中心档冻结锁（完成条件②）：中心档的所有输出与默认档逐字段相同——包括 pan/distance
     * 为各种有限值或 NaN 的全矩阵，防止将来有人拿 pan 当中心／周边映射。
     */
    @Test public void centralTierFreezeLockOutputsMatchDefaultFieldByField() {
        for (int mask : MASKS) {
            for (boolean edge : new boolean[] {true, false}) {
                for (boolean category : new boolean[] {true, false}) {
                    for (float urgency : URGENCIES) {
                        for (float pan : GEOMETRY) {
                            for (float distance : GEOMETRY) {
                                assertEquals(
                                        FieldLossPolicy.decide(FieldLossPolicy.TIER_NONE,
                                                edge, mask, pan, distance, urgency, category),
                                        FieldLossPolicy.decide(FieldLossPolicy.TIER_CENTRAL_LOSS,
                                                edge, mask, pan, distance, urgency, category));
                            }
                        }
                    }
                }
            }
        }
    }

    /** NaN 几何不抛异常且不升级（升级条件里根本没有几何量，NaN 不可能触发它）。 */
    @Test public void nanGeometryOnNonEdgeCueDoesNotThrowNorPromote() {
        FieldLossPolicy.Outcome outcome = FieldLossPolicy.decide(
                FieldLossPolicy.TIER_PERIPHERAL_LOSS, false,
                CueRequest.CHANNEL_TONE, Float.NaN, Float.NaN, Float.NaN, true);
        assertEquals(CueRequest.CHANNEL_TONE, outcome.channels);
        assertFalse(outcome.elevatedPriority);
        assertFalse(outcome.keepPersistentDisplay);
        // 边缘线索的连续 pan/distance 在本仓就是 NaN（直出 cue 未填），
        // 升级靠的是离散 direction 折成的 edge 判定，NaN 同样不得抛异常。
        FieldLossPolicy.Outcome edgeNan = FieldLossPolicy.decide(
                FieldLossPolicy.TIER_PERIPHERAL_LOSS, true,
                CueRequest.CHANNEL_TONE, Float.NaN, Float.NaN, Float.NaN, true);
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC, edgeNan.channels);
    }

    /** urgency 拉满也不许让已关闭的类别复活：类别开关为假时整组输出原样返回。 */
    @Test public void extremeUrgencyNeverRevivesDisabledCategory() {
        for (float urgency : new float[] {2f, Float.MAX_VALUE, Float.NaN}) {
            FieldLossPolicy.Outcome outcome = FieldLossPolicy.decide(
                    FieldLossPolicy.TIER_PERIPHERAL_LOSS, true, CueRequest.CHANNEL_TONE,
                    0f, 0f, urgency, false);
            assertEquals(CueRequest.CHANNEL_TONE, outcome.channels);
            assertFalse(outcome.elevatedPriority);
            assertFalse(outcome.keepPersistentDisplay);
        }
    }

    /** 既成类别闸门（!edge || peripheralThreatEnabled）语义不变：关时输出可用性判断都不发生。 */
    @Test public void disabledPeripheralGateKeepsArbiterMaskAndDispatchUnchanged() {
        RecordingOutput output = new RecordingOutput();
        CueArbiter arbiter = new CueArbiter();
        arbiter.setPeripheralThreatEnabled(false);
        arbiter.setFieldLossSelfReport(FieldLossPolicy.TIER_PERIPHERAL_LOSS);
        CueArbiter.Decision decision = arbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 5, TrackedEntity.TRANSITION_NONE),
                100, 15_000, output);
        assertFalse(decision.directCuePresent);
        assertEquals(-1, output.lastRequestedChannels);
        assertEquals(0, output.outputAvailableCalls);
        assertEquals(0, output.dispatches);
    }

    /** 接线证明：arbiter 把放宽后的掩码实际送进输出可用性判断（不是只接一个返回布尔的中间层）。 */
    @Test public void arbiterFeedsWidenedMaskToOutputAvailability() {
        RecordingOutput baseline = new RecordingOutput();
        CueArbiter defaultArbiter = new CueArbiter();
        CueArbiter.Decision defaultDecision = defaultArbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 5, TrackedEntity.TRANSITION_NONE),
                100, 15_000, baseline);
        assertEquals(CueRequest.Category.PERIPHERAL_THREAT, baseline.lastCategory);
        assertEquals(CueRouting.directCueRequestedChannels(TrackedEntity.KIND_MAIN_ENEMY, 1),
                baseline.lastRequestedChannels);

        RecordingOutput widened = new RecordingOutput();
        CueArbiter peripheralArbiter = new CueArbiter();
        peripheralArbiter.setFieldLossSelfReport(FieldLossPolicy.TIER_PERIPHERAL_LOSS);
        CueArbiter.Decision widenedDecision = peripheralArbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 5, TrackedEntity.TRANSITION_NONE),
                100, 15_000, widened);
        assertEquals(CueRequest.Category.PERIPHERAL_THREAT, widened.lastCategory);
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC,
                widened.lastRequestedChannels);
        // 放宽不改变任何一条既成的可派发性判定。
        assertEquals(defaultDecision.directCuePresent, widenedDecision.directCuePresent);
        assertEquals(defaultDecision.directCueShouldDispatch,
                widenedDecision.directCueShouldDispatch);

        // 左侧边缘同样加宽；非边缘的 danger 直出线索在周边档下掩码原样。
        RecordingOutput left = new RecordingOutput();
        CueArbiter leftArbiter = new CueArbiter();
        leftArbiter.setFieldLossSelfReport(FieldLossPolicy.TIER_PERIPHERAL_LOSS);
        leftArbiter.arbitrate(frame(TrackedEntity.KIND_MAIN_ENEMY, 2, 6,
                TrackedEntity.TRANSITION_NONE), 100, 15_000, left);
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC,
                left.lastRequestedChannels);

        RecordingOutput danger = new RecordingOutput();
        CueArbiter dangerArbiter = new CueArbiter();
        dangerArbiter.setFieldLossSelfReport(FieldLossPolicy.TIER_PERIPHERAL_LOSS);
        dangerArbiter.arbitrate(frame(TrackedEntity.KIND_DANGER_PING, 0, 7,
                TrackedEntity.TRANSITION_NONE), 100, 15_000, danger);
        assertEquals(CueRouting.directCueRequestedChannels(TrackedEntity.KIND_DANGER_PING, 0),
                danger.lastRequestedChannels);
    }

    /** 中心档在 arbiter 出口上的冻结锁：送进输出可用性判断的掩码与默认档逐字节相同。 */
    @Test public void arbiterCentralTierMaskEqualsDefaultForEveryDirectShape() {
        List<int[]> directShapes = new ArrayList<>();
        directShapes.add(new int[] {TrackedEntity.KIND_MAIN_ENEMY, 1});
        directShapes.add(new int[] {TrackedEntity.KIND_MAIN_ENEMY, 2});
        directShapes.add(new int[] {TrackedEntity.KIND_MAIN_ENEMY, 3});
        directShapes.add(new int[] {TrackedEntity.KIND_DANGER_PING, 1});
        directShapes.add(new int[] {TrackedEntity.KIND_PLAYER_STATE, 0});
        for (int[] shape : directShapes) {
            RecordingOutput central = new RecordingOutput();
            CueArbiter centralArbiter = new CueArbiter();
            centralArbiter.setFieldLossSelfReport(FieldLossPolicy.TIER_CENTRAL_LOSS);
            centralArbiter.arbitrate(frame(shape[0], shape[1], 8,
                    TrackedEntity.TRANSITION_NONE), 100, 15_000, central);

            RecordingOutput none = new RecordingOutput();
            CueArbiter noneArbiter = new CueArbiter();
            noneArbiter.arbitrate(frame(shape[0], shape[1], 8,
                    TrackedEntity.TRANSITION_NONE), 100, 15_000, none);
            assertEquals("中心档冻结：形状 kind=" + shape[0] + " dir=" + shape[1],
                    none.lastRequestedChannels, central.lastRequestedChannels);
        }
    }

    /** 只加既成位、不凭空造通道：零掩码不升级，任何加宽结果仍落在四个既成位之内。 */
    @Test public void promotionNeverFabricatesChannelsOrInventsBits() {
        FieldLossPolicy.Outcome zero = FieldLossPolicy.decide(
                FieldLossPolicy.TIER_PERIPHERAL_LOSS, true, 0, 0f, 0f, 0f, true);
        assertEquals(0, zero.channels);
        assertFalse(zero.elevatedPriority);
        for (int mask : MASKS) {
            if (mask == 0) continue;
            FieldLossPolicy.Outcome widened = FieldLossPolicy.decide(
                    FieldLossPolicy.TIER_PERIPHERAL_LOSS, true, mask, 0f, 0f, 0f, true);
            assertEquals(0, widened.channels & ~ALL_EXISTING_BITS);
            assertEquals(mask, widened.channels & mask);
        }
    }

    /** 常驻显示这一半本卷没有任何配置能置真（边缘线索没有可靠的屏幕覆盖坐标，中心轴冻结）。 */
    @Test public void keepPersistentDisplayStaysFalseAcrossEveryTier() {
        for (String tier : new String[] {FieldLossPolicy.TIER_NONE,
                FieldLossPolicy.TIER_PERIPHERAL_LOSS, FieldLossPolicy.TIER_CENTRAL_LOSS}) {
            for (int mask : MASKS) {
                for (boolean edge : new boolean[] {true, false}) {
                    for (boolean category : new boolean[] {true, false}) {
                        assertFalse(tier, FieldLossPolicy.decide(tier, edge, mask,
                                0f, 0f, 2f, category).keepPersistentDisplay);
                    }
                }
            }
        }
    }

    /** 脏偏好值（未知字符串、大小写、null）一律按默认档处理。 */
    @Test public void unknownSelfReportStringsBehaveLikeDefault() {
        for (String junk : new String[] {"", " ", "peripheral", "PERIPHERAL_LOSS",
                "central", "外围受损", null}) {
            FieldLossPolicy.Outcome outcome = FieldLossPolicy.decide(junk, true,
                    CueRequest.CHANNEL_TONE, 0f, 0f, 2f, true);
            assertEquals(CueRequest.CHANNEL_TONE, outcome.channels);
            assertFalse(outcome.elevatedPriority);
            assertFalse(outcome.keepPersistentDisplay);
        }
        RecordingOutput output = new RecordingOutput();
        CueArbiter arbiter = new CueArbiter();
        arbiter.setFieldLossSelfReport(null);
        assertEquals(FieldLossPolicy.TIER_NONE, arbiter.fieldLossSelfReport());
        arbiter.arbitrate(frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 9,
                TrackedEntity.TRANSITION_NONE), 100, 15_000, output);
        assertEquals(CueRouting.directCueRequestedChannels(TrackedEntity.KIND_MAIN_ENEMY, 1),
                output.lastRequestedChannels);
    }

    /** 放宽不扰动帧内仲裁：周边档的边缘线索仍以与默认档相同的方式赢下同帧小地图出现。 */
    @Test public void peripheralEdgeCueStillWinsFrameLikeDefault() {
        RecordingOutput peripheralOutput = new RecordingOutput();
        CueArbiter peripheralArbiter = new CueArbiter();
        peripheralArbiter.setFieldLossSelfReport(FieldLossPolicy.TIER_PERIPHERAL_LOSS);
        CueArbiter.Decision peripheral = peripheralArbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 7, TrackedEntity.TRANSITION_APPEAR),
                100, 15_000, peripheralOutput);
        RecordingOutput baselineOutput = new RecordingOutput();
        CueArbiter baselineArbiter = new CueArbiter();
        CueArbiter.Decision baseline = baselineArbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 7, TrackedEntity.TRANSITION_APPEAR),
                100, 15_000, baselineOutput);
        assertEquals(baseline.directCueWins, peripheral.directCueWins);
        assertEquals(baseline.directCueShouldDispatch, peripheral.directCueShouldDispatch);
        assertEquals(baseline.minimapAppearances.size(), peripheral.minimapAppearances.size());
        assertEquals(CueArbiter.SUPPRESSED_DIRECT,
                peripheral.suppressedMinimap.get(0).reason);
        assertNotEquals(baselineOutput.lastRequestedChannels,
                peripheralOutput.lastRequestedChannels);
    }
}
