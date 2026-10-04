package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class FrameHistoryTest {
    private static FrameSnapshot frame(long id, long atMs) {
        return new FrameSnapshot(id, atMs, 1, 0, null);
    }

    @Test public void samplesAtNormalAndWarmCadenceAndHotClearsTheCache() {
        FrameHistory history = new FrameHistory();
        assertTrue(history.due(1_000, FrameProcessingPolicy.Mode.NORMAL));
        assertTrue(history.add(frame(1, 1_000)));
        assertFalse(history.due(2_999, FrameProcessingPolicy.Mode.NORMAL));
        assertTrue(history.due(3_000, FrameProcessingPolicy.Mode.NORMAL));
        assertFalse(history.due(4_999, FrameProcessingPolicy.Mode.WARM));
        assertTrue(history.due(5_000, FrameProcessingPolicy.Mode.WARM));
        assertFalse(history.due(5_001, FrameProcessingPolicy.Mode.HOT));
        assertEquals(0, history.size());
        assertTrue(history.due(5_002, FrameProcessingPolicy.Mode.NORMAL));
    }

    @Test public void keepsAtMostTwoDistinctOlderFramesInChronologicalOrder() {
        FrameHistory history = new FrameHistory();
        assertTrue(history.add(frame(1, 1_000)));
        assertTrue(history.add(frame(2, 2_000)));
        assertTrue(history.add(frame(3, 3_000)));
        assertEquals(FrameHistory.CAPACITY, history.size());

        List<FrameSnapshot> context = history.context(5_000, frame(4, 5_000));
        assertEquals(2, context.size());
        assertEquals(2, context.get(0).frameId);
        assertEquals(3, context.get(1).frameId);
        assertTrue(FrameHistory.isEligibleContext(2, 2_000, 4, 5_000, 5_000));
        assertFalse(FrameHistory.isEligibleContext(4, 5_000, 4, 5_000, 5_000));
        assertFalse(FrameHistory.isEligibleContext(5, 6_000, 4, 5_000, 6_000));
    }

    @Test public void olderCaptureGenerationRemainsUsableUntilItsCaptureEpochIsCleared() {
        FrameHistory history = new FrameHistory();
        assertTrue(history.add(new FrameSnapshot(1, 1_000, 1, 0, null)));
        assertTrue(history.add(new FrameSnapshot(2, 2_000, 1, 0, null)));
        FrameSnapshot primaryFromNextTurn = new FrameSnapshot(3, 3_000, 2, 0, null);
        assertEquals("Turn generation changes do not invalidate same-capture history",
                2, history.context(3_000, primaryFromNextTurn).size());

        long oldEpoch = history.epoch();
        history.clear();
        assertTrue(history.context(3_000, primaryFromNextTurn).isEmpty());
        assertFalse("A frame copy completed after capture invalidation cannot cross epochs",
                history.add(frame(4, 3_500), oldEpoch));
    }

    @Test public void expiresHistoryAndRejectsContextOlderThanSixSeconds() {
        FrameHistory history = new FrameHistory();
        assertTrue(history.add(frame(1, 1_000)));
        assertTrue(history.context(7_001, frame(2, 7_000)).isEmpty());
        assertEquals(0, history.size());
        assertFalse(FrameHistory.isEligibleContext(1, 1_000, 2, 7_000, 7_001));
    }

    @Test public void gatewayContextRequiresAUniqueOlderFrameWithinAgeAndSizeBounds() {
        byte[] jpeg = new byte[]{1};
        assertTrue(AssistantGatewayClient.validContextImage(
                new AssistantGatewayClient.ContextImage("42", 2_000, jpeg), "43", 100));
        assertFalse(AssistantGatewayClient.validContextImage(
                new AssistantGatewayClient.ContextImage("43", 2_000, jpeg), "43", 100));
        assertFalse(AssistantGatewayClient.validContextImage(
                new AssistantGatewayClient.ContextImage("42", 100, jpeg), "43", 100));
        assertFalse(AssistantGatewayClient.validContextImage(
                new AssistantGatewayClient.ContextImage("42", 6_001, jpeg), "43", 100));
        assertFalse(AssistantGatewayClient.validContextImage(
                new AssistantGatewayClient.ContextImage("bad id", 2_000, jpeg), "43", 100));
    }
}
