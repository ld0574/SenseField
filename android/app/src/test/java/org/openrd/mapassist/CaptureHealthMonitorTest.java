package org.openrd.mapassist;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CaptureHealthMonitorTest {
    @Test public void starvationUsesGraceAndBoundedBackoff() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(1000);
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(2999));
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(3000));
        assertEquals(500, monitor.beginRecovery());
        monitor.recoveryRebuilt(3500);
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.check(5000));
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(5500));
        monitor.recoveryFailed();
        assertEquals(1000, monitor.beginRecovery());
        monitor.recoveryFailed();
        assertEquals(2000, monitor.beginRecovery());
        monitor.recoveryFailed();
        assertEquals(CaptureHealthMonitor.State.FAILED, monitor.state());
        assertEquals(-1, monitor.beginRecovery());
    }

    @Test public void recoveryWaitIsStableAndMissingFramesCanRetryOnlyWithinLimit() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(0);

        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(2000));
        assertEquals(500, monitor.beginRecovery());
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.state());

        // Watchdog checks during the scheduled rebuild must not start duplicates.
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.check(2200));
        assertEquals(-1, monitor.beginRecovery());
        assertEquals(1, monitor.attempts());
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.check(2400));
        assertEquals(-1, monitor.beginRecovery());
        assertEquals(1, monitor.attempts());

        // A rebuilt reader gets a fresh startup grace period. If no frame
        // arrives during it, the next bounded attempt becomes available.
        monitor.recoveryRebuilt(2500);
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.state());
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.check(4499));
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(4500));
        assertEquals(1000, monitor.beginRecovery());
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.check(5000));
        assertEquals(-1, monitor.beginRecovery());
        assertEquals(2, monitor.attempts());

        monitor.recoveryRebuilt(5500);
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.check(7499));
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(7500));
        assertEquals(2000, monitor.beginRecovery());
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.check(9000));
        assertEquals(-1, monitor.beginRecovery());
        assertEquals(3, monitor.attempts());

        monitor.recoveryRebuilt(9500);
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.check(11499));
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(11500));
        assertEquals(-1, monitor.beginRecovery());
        assertEquals(CaptureHealthMonitor.State.FAILED, monitor.state());
    }

    @Test public void frameAfterReaderRebuildReturnsHealthToHealthy() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(0);
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(2000));
        assertEquals(500, monitor.beginRecovery());
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.check(2200));
        monitor.recoveryRebuilt(2500);

        monitor.frameArrived(2600);
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.state());
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(3100));
        assertEquals(1, monitor.attempts());
    }

    @Test public void resizeDuringRecoveryStartsFreshReaderGracePeriod() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(0);
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(2000));
        assertEquals(500, monitor.beginRecovery());

        // A display resize replaces the reader before the delayed recovery task runs.
        monitor.recoveryRebuilt(2200);
        assertEquals(CaptureHealthMonitor.State.RECOVERING, monitor.check(4000));
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(4200));
        assertEquals(1000, monitor.beginRecovery());
        assertEquals(2, monitor.attempts());
    }

    @Test public void frameArrivalAndPausePreventFalseRecovery() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(0);
        monitor.frameArrived(2100);
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(2500));
        monitor.pause(true, 2600);
        assertEquals(CaptureHealthMonitor.State.PAUSED, monitor.check(10000));
        monitor.pause(false, 10000);
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(11000));
    }

    @Test public void receivedAndProcessedFrameTimesRemainIndependent() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(100);
        monitor.frameArrived(250);
        monitor.frameProcessed(275);
        monitor.frameArrived(300);

        assertEquals(300, monitor.lastFrameAtMs());
        assertEquals(275, monitor.lastProcessedAtMs());
    }

    @Test public void revokedProjectionNeverRecovers() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(0);
        monitor.revoke();
        assertEquals(CaptureHealthMonitor.State.REVOKED, monitor.check(10000));
        assertEquals(-1, monitor.beginRecovery());
    }
}
