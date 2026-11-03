package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ProjectionStartGateTest {
    @Test public void grantedResultWaitsForBothResumeAndFocusAndIsUsedOnce() {
        ProjectionStartGate<Object> gate = new ProjectionStartGate<>();
        Object token = new Object();
        assertTrue(gate.begin());
        assertTrue(gate.complete(token));
        assertNull(gate.take(false, false));
        assertNull(gate.take(false, true));
        assertNull(gate.take(true, false));
        assertSame(token, gate.take(true, true));
        assertNull(gate.take(true, true));
        assertFalse(gate.complete(token));
        assertFalse(gate.pending());
    }

    @Test public void cancellationAllowsAFreshRequestWithoutStartingAService() {
        ProjectionStartGate<Object> gate = new ProjectionStartGate<>();
        assertTrue(gate.begin());
        assertFalse(gate.begin());
        assertFalse(gate.complete(null));
        assertFalse(gate.pending());
        assertNull(gate.take(true, true));
        assertTrue(gate.begin());
        Object fresh = new Object();
        assertTrue(gate.complete(fresh));
        assertFalse(gate.begin());
        assertSame(fresh, gate.take(true, true));
    }

    @Test public void rotationRetainsAWaitingResultWithoutLaunchingBeforeForeground() {
        Object token = new Object();
        ProjectionStartGate<Object> before = new ProjectionStartGate<>();
        before.begin(); before.complete(token);
        ProjectionStartGate<Object> after = new ProjectionStartGate<>();
        after.restore(before.requested(), before.waitingResult());
        assertFalse(after.complete(new Object()));
        assertNull(after.take(false, false));
        assertSame(token, after.take(true, true));
        assertNull(after.take(true, true));
    }

    @Test public void rotationDuringConsentStillAcceptsOneResult() {
        ProjectionStartGate<Object> gate = new ProjectionStartGate<>();
        gate.restore(true, null);
        assertFalse(gate.begin());
        Object token = new Object();
        assertTrue(gate.complete(token));
        assertFalse(gate.complete(new Object()));
        assertSame(token, gate.take(true, true));
    }

    @Test public void stoppingDropsAnUnconsumedResultAndIgnoresLateCallbacks() {
        ProjectionStartGate<Object> gate = new ProjectionStartGate<>();
        gate.begin(); gate.complete(new Object()); gate.cancel();
        assertNull(gate.take(true, true));
        assertFalse(gate.complete(new Object()));
        assertTrue(gate.begin());
    }
}
