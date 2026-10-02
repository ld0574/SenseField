package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class DiagnosticImageBudgetTest {
    @Test public void accountsForQueuedAndEncodingJobsByFrameAndByteLimits() {
        DiagnosticImageBudget budget = new DiagnosticImageBudget(3, 20);

        assertTrue(budget.tryAcquire(4));
        assertTrue(budget.tryAcquire(7));
        assertTrue(budget.tryAcquire(9));
        assertFalse(budget.tryAcquire(1)); // Byte limit is full.
        assertFalse(budget.tryAcquire(0));
        assertFalse(budget.tryAcquire(-1));

        assertEquals(3, budget.pendingFrames());
        assertEquals(20L, budget.pendingBytes());
        assertEquals(3, budget.peakFrames());
        assertEquals(20L, budget.peakBytes());

        budget.release(7);
        assertEquals(2, budget.pendingFrames());
        assertEquals(13L, budget.pendingBytes());
        assertFalse(budget.tryAcquire(8));
        assertTrue(budget.tryAcquire(7));
        assertEquals(3, budget.pendingFrames());
        assertEquals(20L, budget.pendingBytes());

        budget.release(4);
        budget.release(7);
        budget.release(9);
        assertEquals(0, budget.pendingFrames());
        assertEquals(0L, budget.pendingBytes());
    }

    @Test public void ignoresInvalidUnregisteredAndDuplicateReleases() {
        DiagnosticImageBudget budget = new DiagnosticImageBudget(4, 100);
        assertTrue(budget.tryAcquire(30));
        assertTrue(budget.tryAcquire(45));

        budget.release(0);
        budget.release(-1);
        budget.release(31); // No outstanding job has this weight.
        assertEquals(2, budget.pendingFrames());
        assertEquals(75L, budget.pendingBytes());

        budget.release(30);
        budget.release(30); // The already released weight is no longer registered.
        assertEquals(1, budget.pendingFrames());
        assertEquals(45L, budget.pendingBytes());

        budget.release(45);
        budget.release(45);
        assertEquals(0, budget.pendingFrames());
        assertEquals(0L, budget.pendingBytes());
    }

    @Test public void simultaneousAcquisitionsNeverExceedEitherLimit() throws Exception {
        final int workers = 24;
        final DiagnosticImageBudget budget = new DiagnosticImageBudget(8, 16);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return budget.tryAcquire(3);
                }));
            }

            assertTrue("workers did not reach the start gate",
                    ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(5, TimeUnit.SECONDS)) accepted++;
            }

            // Five 3-byte reservations fit in 16 bytes; the sixth must be refused.
            assertEquals(5, accepted);
            assertEquals(5, budget.pendingFrames());
            assertEquals(15L, budget.pendingBytes());
            assertTrue(budget.peakFrames() <= 8);
            assertTrue(budget.peakBytes() <= 16L);
            for (int i = 0; i < accepted; i++) budget.release(3);
            assertEquals(0, budget.pendingFrames());
            assertEquals(0L, budget.pendingBytes());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test public void rejectsNonpositiveBudgetLimits() {
        assertIllegalLimits(0, 1);
        assertIllegalLimits(1, 0);
        assertIllegalLimits(-1, 1);
        assertIllegalLimits(1, -1);
    }

    private static void assertIllegalLimits(int frames, long bytes) {
        try {
            new DiagnosticImageBudget(frames, bytes);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("nonpositive budget should be rejected");
    }
}
