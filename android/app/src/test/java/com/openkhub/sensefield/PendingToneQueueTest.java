package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PendingToneQueueTest {
    @Test public void unreadySampleIsAcceptedAndPlayedWhenItLoadsBeforeExpiry() {
        PendingToneQueue queue = new PendingToneQueue();

        assertTrue(queue.enqueue(12, 2, 3, 1000, 100));
        PendingToneQueue.Pending loaded = queue.completeLoad(12, 500, true);

        assertEquals(2, loaded.kind);
        assertEquals(3, loaded.direction);
    }

    @Test public void expiredPendingToneIsDiscardedAtLoadAndExpiredRequestIsRejected() {
        PendingToneQueue queue = new PendingToneQueue();

        assertTrue(queue.enqueue(12, 2, 3, 100, 50));
        assertNull(queue.completeLoad(12, 101, true));
        assertFalse(queue.enqueue(13, 1, 0, 99, 100));
        assertNull(queue.completeLoad(13, 100, true));
    }

    @Test public void newerRequestReplacesOlderPendingTone() {
        PendingToneQueue queue = new PendingToneQueue();

        assertTrue(queue.enqueue(12, 2, 3, 1000, 100));
        assertTrue(queue.enqueue(13, 3, 0, 1200, 200));

        assertNull(queue.completeLoad(12, 300, true));
        PendingToneQueue.Pending loaded = queue.completeLoad(13, 300, true);
        assertEquals(3, loaded.kind);
    }

    @Test public void invalidRequestDoesNotClearValidPendingTone() {
        PendingToneQueue queue = new PendingToneQueue();

        assertTrue(queue.enqueue(12, 2, 3, 1000, 100));
        assertFalse(queue.enqueue(0, 3, 0, 1200, 200));
        PendingToneQueue.Pending loaded = queue.completeLoad(12, 300, true);
        assertEquals(2, loaded.kind);
    }

    @Test public void clearingOneCategoryKeepsOtherCategoryInGlobalSlot() {
        PendingToneQueue queue = new PendingToneQueue();

        assertTrue(queue.enqueue(12, 2, 3, 1000, 100,
                CueRequest.Category.DANGER, null));
        assertNull(queue.clearCategory(CueRequest.Category.VISION_MEMORY));

        PendingToneQueue.Pending loaded = queue.completeLoad(12, 300, true);
        assertEquals(CueRequest.Category.DANGER, loaded.category);
    }
}
