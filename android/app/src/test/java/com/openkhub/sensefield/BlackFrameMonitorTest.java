package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BlackFrameMonitorTest {
    @Test public void shortLoadingBlackoutRecoversWithoutStopping() {
        BlackFrameMonitor monitor = new BlackFrameMonitor();
        assertEquals(BlackFrameMonitor.Action.WAIT, monitor.update(true, false, 100));
        assertEquals(BlackFrameMonitor.Action.WAIT, monitor.update(true, false, 15100));
        assertEquals(BlackFrameMonitor.Action.PROCESS, monitor.update(false, false, 15200));
        assertFalse(monitor.isBlack());
    }

    @Test public void removesOwnOverlayBeforeGivingUpAndAllowsRecovery() {
        BlackFrameMonitor monitor = new BlackFrameMonitor();
        assertEquals(BlackFrameMonitor.Action.WAIT, monitor.update(true, true, 100));
        assertEquals(BlackFrameMonitor.Action.DISABLE_OVERLAY,
                monitor.update(true, true, 1100));
        assertEquals(BlackFrameMonitor.Action.WAIT, monitor.update(true, false, 1110));
        assertEquals(BlackFrameMonitor.Action.PROCESS, monitor.update(false, false, 1200));
    }

    @Test public void persistentProtectedOrUnavailableContentStillStops() {
        BlackFrameMonitor monitor = new BlackFrameMonitor();
        monitor.update(true, false, 100);
        assertEquals(BlackFrameMonitor.Action.STOP, monitor.update(true, false, 30100));
        assertTrue(monitor.isBlack());
    }

    @Test public void recoveredFrameAndNewSessionResetBlackoutDeadline() {
        BlackFrameMonitor monitor = new BlackFrameMonitor();
        monitor.update(true, false, 100);
        monitor.update(false, false, 20100);
        assertEquals(BlackFrameMonitor.Action.WAIT, monitor.update(true, false, 30000));
        monitor.reset();
        assertEquals(BlackFrameMonitor.Action.WAIT, monitor.update(true, false, 70000));
    }
}
