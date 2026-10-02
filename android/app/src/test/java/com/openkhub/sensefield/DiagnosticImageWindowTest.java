package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class DiagnosticImageWindowTest {
    @Test public void samplesAtHalfSecondIntervalsWithoutCatchUpBursts() {
        DiagnosticImageWindow<String> window = new DiagnosticImageWindow<>();

        assertTrue(window.shouldSample(1000));
        assertFalse(window.shouldSample(1499));
        assertTrue(window.shouldSample(1500));
        assertTrue(window.shouldSample(2700));
        assertFalse(window.shouldSample(3199));
        assertTrue(window.shouldSample(3200));
    }

    @Test public void triggerReturnsOnlyRecentContextAndCacheNeverExceedsSevenEntries() {
        DiagnosticImageWindow<String> window = new DiagnosticImageWindow<>();
        for (int i = 0; i < 9; i++) window.add(i, i * 500L, "frame-" + i);

        DiagnosticImageWindow.TriggerResult<String> result = window.trigger(4000, true);

        assertEquals(DiagnosticImageWindow.TriggerStatus.ACCEPTED, result.status);
        assertEquals(1, result.id);
        assertEquals(Arrays.asList(2L, 3L, 4L, 5L, 6L, 7L, 8L), frameIndexes(result.entries));
        assertEquals(6000, result.endMs);
        assertEquals(result.id, window.activeWindowId(5999));
        assertEquals(0, window.activeWindowId(6000));
    }

    @Test public void coalescedTriggersExtendPostWindowButRespectFiveSecondHardLimit() {
        DiagnosticImageWindow<String> window = new DiagnosticImageWindow<>();
        DiagnosticImageWindow.TriggerResult<String> first = window.trigger(10000, true);
        window.add(1, 11000, "during-window");

        DiagnosticImageWindow.TriggerResult<String> coalesced = window.trigger(11500, false);
        assertEquals(DiagnosticImageWindow.TriggerStatus.COALESCED, coalesced.status);
        assertEquals(first.id, coalesced.id);
        assertEquals(13500, coalesced.endMs);
        assertEquals(Arrays.asList(1L), frameIndexes(coalesced.entries));

        DiagnosticImageWindow.TriggerResult<String> bounded = window.trigger(13000, false);
        assertEquals(DiagnosticImageWindow.TriggerStatus.COALESCED, bounded.status);
        assertEquals(first.id, bounded.id);
        assertEquals(15000, bounded.endMs);
        assertEquals(first.id, window.activeWindowId(14999));
        assertEquals(0, window.activeWindowId(15000));
    }

    @Test public void automaticWindowsAreRateLimitedButManualWindowsBypassTheGap() {
        DiagnosticImageWindow<String> window = new DiagnosticImageWindow<>();
        DiagnosticImageWindow.TriggerResult<String> first = window.trigger(0, false);
        assertEquals(DiagnosticImageWindow.TriggerStatus.ACCEPTED, first.status);

        // This overlaps the first window, so it coalesces before the gap is considered.
        assertEquals(DiagnosticImageWindow.TriggerStatus.COALESCED,
                window.trigger(1000, false).status);
        assertEquals(DiagnosticImageWindow.TriggerStatus.RATE_LIMITED,
                window.trigger(3000, false).status);

        DiagnosticImageWindow.TriggerResult<String> manual = window.trigger(3000, true);
        assertEquals(DiagnosticImageWindow.TriggerStatus.ACCEPTED, manual.status);
        assertTrue(first.id != manual.id);
        assertEquals(DiagnosticImageWindow.TriggerStatus.RATE_LIMITED,
                window.trigger(12999, false).status);
        assertEquals(DiagnosticImageWindow.TriggerStatus.ACCEPTED,
                window.trigger(13000, false).status);
    }

    @Test public void clockRollbackClearsPayloadsAndSchedulingWithoutReusingWindowIds() {
        DiagnosticImageWindow<String> window = new DiagnosticImageWindow<>();
        window.add(1, 5000, "stale");
        DiagnosticImageWindow.TriggerResult<String> beforeRollback = window.trigger(5000, true);
        assertEquals(1, beforeRollback.id);

        window.add(2, 1000, "after-rollback");
        assertEquals(0, window.activeWindowId(1000));
        DiagnosticImageWindow.TriggerResult<String> afterRollback = window.trigger(1001, false);

        assertEquals(DiagnosticImageWindow.TriggerStatus.ACCEPTED, afterRollback.status);
        assertEquals(2, afterRollback.id);
        assertEquals(Arrays.asList(2L), frameIndexes(afterRollback.entries));
        assertTrue(window.shouldSample(1001));
    }

    @Test public void clearDropsStateAndPayloadsButKeepsWindowIdsMonotonic() {
        DiagnosticImageWindow<String> window = new DiagnosticImageWindow<>();
        DiagnosticImageWindow.TriggerResult<String> beforeClear = window.trigger(1000, true);
        window.add(1, 1100, "discard-me");

        window.clear();

        assertEquals(0, window.activeWindowId(1200));
        assertTrue(window.shouldSample(1200));
        DiagnosticImageWindow.TriggerResult<String> afterClear = window.trigger(1200, false);
        assertEquals(DiagnosticImageWindow.TriggerStatus.ACCEPTED, afterClear.status);
        assertEquals(beforeClear.id + 1, afterClear.id);
        assertTrue(afterClear.entries.isEmpty());
    }

    @Test public void configurableLimitsMakeSmallWindowBehaviorDeterministic() {
        DiagnosticImageWindow<String> window = new DiagnosticImageWindow<>(10, 20, 30,
                50, 2, 100);
        window.add(1, 0, "old");
        window.add(2, 10, "middle");
        window.add(3, 20, "new");

        DiagnosticImageWindow.TriggerResult<String> result = window.trigger(20, true);
        assertEquals(Arrays.asList(2L, 3L), frameIndexes(result.entries));
        assertEquals(50, result.endMs);
        assertEquals(result.id, window.activeWindowId(49));
        assertEquals(0, window.activeWindowId(50));
    }

    private static List<Long> frameIndexes(List<? extends DiagnosticImageWindow.Entry<?>> entries) {
        java.util.ArrayList<Long> indexes = new java.util.ArrayList<>();
        for (DiagnosticImageWindow.Entry<?> entry : entries) indexes.add(entry.frameIndex);
        return indexes;
    }
}
