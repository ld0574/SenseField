package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CueRoutingTest {
    @Test public void minimapDetectionsNeverBypassTrackedTransitions() {
        assertFalse(CueRouting.shouldDispatchDirectNativeCue(0));
        assertFalse(CueRouting.shouldDispatchDirectNativeCue(2));

        assertTrue(CueRouting.shouldDispatchDirectNativeCue(1));
        assertTrue(CueRouting.shouldDispatchDirectNativeCue(3));
        assertTrue(CueRouting.shouldDispatchDirectNativeCue(4));
        assertTrue(CueRouting.shouldDispatchDirectNativeCue(5));
    }

    @Test public void onlyNewAppearancesAreAudibleAndDoNotClaimPlayerRelativeDirection() {
        assertTrue(CueRouting.shouldCueMinimapAppearance(
                1, 100, CueRouting.NO_MINIMAP_CUE, 15000));
        assertFalse(CueRouting.shouldCueMinimapAppearance(
                0, 100, CueRouting.NO_MINIMAP_CUE, 15000));
        assertFalse(CueRouting.shouldCueMinimapAppearance(
                2, 100, CueRouting.NO_MINIMAP_CUE, 15000));
        assertEquals(0, CueRouting.unlocatedMinimapEnemyDirection());
        assertEquals("小地图发现新敌方头像", CueRouting.unlocatedMinimapEnemySpeech());
    }

    @Test public void minimapAppearanceGapAllowsFirstExactBoundaryAndClockRollback() {
        long gapMs = 15000;
        assertTrue(CueRouting.shouldCueMinimapAppearance(
                1, 1000, CueRouting.NO_MINIMAP_CUE, gapMs));
        assertTrue(CueRouting.shouldCueMinimapAppearance(1, 16000, 1000, gapMs));
        assertFalse(CueRouting.shouldCueMinimapAppearance(1, 15999, 1000, gapMs));
        assertTrue(CueRouting.shouldCueMinimapAppearance(1, 999, 1000, gapMs));
        assertFalse(CueRouting.shouldCueMinimapAppearance(1, 1000, 1000, 0));
    }

    @Test public void legacyProfilesCannotRestoreFiveSecondMinimapChatter() {
        assertEquals(15000, CueRouting.effectiveMinimapAppearanceGap(5000));
        assertEquals(15000, CueRouting.effectiveMinimapAppearanceGap(15000));
        assertEquals(30000, CueRouting.effectiveMinimapAppearanceGap(30000));
    }

    @Test public void cueAccountingMatchesSessionSummaryOutcome() {
        assertEquals(CueRouting.CueAccounting.QUEUED,
                CueRouting.cueAccounting("none", true));
        assertEquals(CueRouting.CueAccounting.FAILED,
                CueRouting.cueAccounting("tone_unavailable", false));
        assertEquals(CueRouting.CueAccounting.STALE,
                CueRouting.cueAccounting("expired", false));
        assertEquals(CueRouting.CueAccounting.QUEUED,
                CueRouting.cueAccounting("expired", true));
    }
}
