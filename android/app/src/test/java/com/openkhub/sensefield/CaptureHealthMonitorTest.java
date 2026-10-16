package com.openkhub.sensefield;

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

    @Test public void portraitSetupWaitsWithoutExhaustingRecoveryAndLandscapeIsMonitored() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(0);
        monitor.frameArrived(100);
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(60000, false));
        assertEquals(0, monitor.attempts());

        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(61000, true));
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(62000, true));
        assertEquals(500, monitor.beginRecovery());

        // Returning to setup cancels this recovery budget. Opening the game
        // again still detects a genuinely stalled landscape stream.
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(62200, false));
        assertEquals(0, monitor.attempts());
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(64200, true));
        assertEquals(500, monitor.beginRecovery());
    }

    @Test public void portraitWaitCannotUndoRevocationFailureOrUserPause() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(0);
        monitor.pause(true, 100);
        assertEquals(CaptureHealthMonitor.State.PAUSED, monitor.check(60000, false));

        monitor.start(0);
        monitor.revoke();
        assertEquals(CaptureHealthMonitor.State.REVOKED, monitor.check(60000, false));

        monitor.start(0);
        monitor.check(2000);
        monitor.beginRecovery();
        monitor.recoveryFailed();
        monitor.beginRecovery();
        monitor.recoveryFailed();
        monitor.beginRecovery();
        monitor.recoveryFailed();
        assertEquals(CaptureHealthMonitor.State.FAILED, monitor.check(60000, false));
    }

    @Test public void revokedProjectionNeverRecovers() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(0);
        monitor.revoke();
        assertEquals(CaptureHealthMonitor.State.REVOKED, monitor.check(10000));
        assertEquals(-1, monitor.beginRecovery());
    }

    /**
     * 竖屏识别会话（开心消消乐）也必须能判出饥饿。
     * 旧实现在 !expectFrames 时每 tick 调 start()，时钟一直往前滑，
     * 于是永远到不了 STARVATION_MS，帧停摆既不恢复也不上报。
     */
    @Test public void portraitSessionStillDetectsStarvation() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(1000);
        // 识别进行中：expectFrames=true。前 2 秒启动宽限内仍算健康，
        // 超过 STARTUP_GRACE_MS 且再无帧就该判 STARVED。
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(1500, true));
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(2500, true));
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(3000, true));
        assertEquals(500, monitor.beginRecovery());
    }

    /**
     * 空闲期不得每个 tick 都重置启动窗口。
     * 旧实现每 tick 调 start()，时钟一直往前滑；恢复期待帧后永远差 2 秒到不了
     * STARVATION_MS，帧停摆既不恢复也不上报。
     */
    @Test public void idleTicksDoNotSlideTheStartupWindowForever() {
        CaptureHealthMonitor monitor = new CaptureHealthMonitor();
        monitor.start(1000);
        monitor.frameArrived(1000);
        // 转入空闲：这一次重置给出新的启动窗口。
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(1500, false));
        // 之后每 tick 都不再重置——这一点由下面的时间跨度证明。
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(2000, false));
        assertEquals(CaptureHealthMonitor.State.HEALTHY, monitor.check(60000, false));
        // 若仍在每 tick 重置，这里会因窗口被推到 60000 而变成 HEALTHY；
        // 实际窗口停在 1500，因此 3500ms 后（>2000 宽限 + >1000 饥饿）必须判 STARVED。
        assertEquals(CaptureHealthMonitor.State.STARVED, monitor.check(5000, true));
    }

    /** 空闲期不能把真实失败、吊销或用户暂停冲掉。 */
    @Test public void idleTicksPreserveFailureRevocationAndPause() {
        CaptureHealthMonitor failed = new CaptureHealthMonitor();
        failed.start(1000);
        for (int i = 0; i < 5; i++) failed.check(1000 + i * 500, true);
        for (int i = 0; i < 6; i++) {
            failed.beginRecovery();
            failed.recoveryFailed();
        }
        assertEquals(CaptureHealthMonitor.State.FAILED, failed.state());
        assertEquals(CaptureHealthMonitor.State.FAILED, failed.check(9000, false));

        CaptureHealthMonitor revoked = new CaptureHealthMonitor();
        revoked.start(1000);
        revoked.revoke();
        assertEquals(CaptureHealthMonitor.State.REVOKED, revoked.check(9000, false));
    }
}
