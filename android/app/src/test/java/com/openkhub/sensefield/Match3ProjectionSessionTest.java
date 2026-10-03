package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Match3ProjectionSessionTest {
    @Test public void delayedStopFromReplacedProjectionCannotStopNewStart() {
        Match3ProjectionSession sessions = new Match3ProjectionSession();
        Object oldProjection = new Object();
        long oldGeneration = sessions.beginStart(10);
        assertTrue(sessions.attach(oldGeneration, oldProjection));

        Object currentProjection = new Object();
        long currentGeneration = sessions.beginStart(11);
        assertTrue(sessions.attach(currentGeneration, currentProjection));

        assertEquals(0, sessions.stopIfCurrent(oldGeneration, oldProjection));
        int[] staleFrameEffects = {0};
        assertFalse(sessions.runIfCurrent(oldGeneration, oldProjection, () -> {
            staleFrameEffects[0]++;
            return true;
        }));
        assertEquals(0, staleFrameEffects[0]);
        assertEquals(11, sessions.stopIfCurrent(currentGeneration, currentProjection));
        assertEquals(0, sessions.stopIfCurrent(currentGeneration, currentProjection));
        assertFalse(sessions.attach(oldGeneration, oldProjection));
    }

    @Test public void explicitStopInvalidatesPendingProjectionCallbacks() {
        Match3ProjectionSession sessions = new Match3ProjectionSession();
        Object projection = new Object();
        long generation = sessions.beginStart(20);
        assertTrue(sessions.attach(generation, projection));

        sessions.invalidate(21);

        assertEquals(0, sessions.stopIfCurrent(generation, projection));
    }
}
