package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Match3ProjectionSessionTest {
    @Test public void duplicateStartPreservesProjectionAndUsesNewestStopId() {
        Match3ProjectionSession sessions = new Match3ProjectionSession();
        Object projection = new Object();
        long generation = sessions.beginStart(1);
        assertTrue(sessions.attach(generation, projection));
        assertTrue(sessions.ignoreDuplicateStart(2));
        assertTrue(sessions.isCurrent(generation, projection));
        assertEquals(2, sessions.stopIfCurrent(generation, projection));
    }

    @Test public void stoppedProjectionAllowsFreshAuthorization() {
        Match3ProjectionSession sessions = new Match3ProjectionSession();
        assertFalse(sessions.ignoreDuplicateStart(1));
        Object old = new Object();
        long oldGeneration = sessions.beginStart(1);
        assertTrue(sessions.attach(oldGeneration, old));
        assertEquals(1, sessions.stopIfCurrent(oldGeneration, old));
        assertFalse(sessions.ignoreDuplicateStart(2));
        Object replacement = new Object();
        long generation = sessions.beginStart(2);
        assertTrue(sessions.attach(generation, replacement));
        assertEquals(0, sessions.stopIfCurrent(oldGeneration, old));
        assertTrue(sessions.isCurrent(generation, replacement));
    }

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
    @Test public void controlCommandsKeepProjectionAndAdvanceStopId() {
        Match3ProjectionSession sessions = new Match3ProjectionSession();
        Object projection = new Object();
        long generation = sessions.beginStart(30);
        assertTrue(sessions.attach(generation, projection));
        sessions.noteCommand(31); // Notification marker.
        sessions.noteCommand(32); // Explore toggle.
        sessions.noteCommand(31); // Delayed command cannot move the stop ID backwards.
        assertTrue(sessions.isCurrent(generation, projection));
        assertEquals(32, sessions.stopIfCurrent(generation, projection));
    }

    @Test public void oldFrameCannotPersistOrAnnounceAfterReplacement() {
        Match3ProjectionSession sessions = new Match3ProjectionSession();
        Object old = new Object();
        long generation = sessions.beginStart(40);
        assertTrue(sessions.attach(generation, old));
        sessions.beginStart(41);
        final boolean[] changed = {false};
        assertFalse(sessions.runIfCurrent(generation, old, () -> { changed[0] = true; return true; }));
        assertFalse(changed[0]);
    }
}
