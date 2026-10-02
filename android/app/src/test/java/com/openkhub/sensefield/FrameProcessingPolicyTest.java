package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FrameProcessingPolicyTest {
    @Test public void firstRunIsImmediateAndCooldownUsesCompletionAndStartBounds() {
        FrameProcessingPolicy policy = new FrameProcessingPolicy();
        assertTrue(policy.canProcess(10));

        // 281 ms processing cost gives 70 ms normal rest, but the 83 ms
        // minimum start-to-start interval is measured from the run start.
        policy.recordProcessed(100, 381);
        assertEquals(70, policy.lastRestMs());
        assertEquals(451, policy.nextAllowedAtMs());
        assertFalse(policy.canProcess(450));
        assertTrue(policy.canProcess(451));
    }

    @Test public void restFloorsAndCapsBoundEveryThermalMode() {
        FrameProcessingPolicy normal = new FrameProcessingPolicy();
        normal.recordProcessed(0, 10);
        assertEquals(40, normal.lastRestMs());
        assertEquals(83, normal.nextAllowedAtMs());

        FrameProcessingPolicy warm = new FrameProcessingPolicy();
        warm.updateDevice(430, -1);
        warm.recordProcessed(0, 10);
        assertEquals(FrameProcessingPolicy.Mode.WARM, warm.mode());
        assertEquals(80, warm.lastRestMs());

        FrameProcessingPolicy hot = new FrameProcessingPolicy();
        hot.updateDevice(450, -1);
        hot.recordProcessed(0, 10);
        assertEquals(FrameProcessingPolicy.Mode.HOT, hot.mode());
        assertEquals(100, hot.lastRestMs());

        FrameProcessingPolicy normalCap = new FrameProcessingPolicy();
        normalCap.recordProcessed(0, 2000);
        assertEquals(150, normalCap.lastRestMs());

        FrameProcessingPolicy warmCap = new FrameProcessingPolicy();
        warmCap.updateDevice(430, -1);
        warmCap.recordProcessed(0, 2000);
        assertEquals(250, warmCap.lastRestMs());

        FrameProcessingPolicy hotCap = new FrameProcessingPolicy();
        hotCap.updateDevice(450, -1);
        hotCap.recordProcessed(0, 2000);
        assertEquals(350, hotCap.lastRestMs());
    }

    @Test public void twoHundredEightyOneMillisecondRunUsesCostAwareRestAtEachMode() {
        FrameProcessingPolicy normal = new FrameProcessingPolicy();
        normal.recordProcessed(0, 281);
        assertEquals(70, normal.lastRestMs());
        assertEquals(351, normal.nextAllowedAtMs());

        FrameProcessingPolicy warm = new FrameProcessingPolicy();
        warm.updateDevice(430, -1);
        warm.recordProcessed(0, 281);
        assertEquals(140, warm.lastRestMs());

        FrameProcessingPolicy hot = new FrameProcessingPolicy();
        hot.updateDevice(450, -1);
        hot.recordProcessed(0, 281);
        assertEquals(210, hot.lastRestMs());
    }

    @Test public void batteryThresholdsHaveOneDegreeHysteresis() {
        FrameProcessingPolicy policy = new FrameProcessingPolicy();
        policy.updateDevice(429, -1);
        assertEquals(FrameProcessingPolicy.Mode.NORMAL, policy.mode());
        policy.updateDevice(430, -1);
        assertEquals(FrameProcessingPolicy.Mode.WARM, policy.mode());
        policy.updateDevice(429, -1);
        assertEquals(FrameProcessingPolicy.Mode.WARM, policy.mode());
        policy.updateDevice(420, -1);
        assertEquals(FrameProcessingPolicy.Mode.NORMAL, policy.mode());

        policy.updateDevice(450, -1);
        assertEquals(FrameProcessingPolicy.Mode.HOT, policy.mode());
        policy.updateDevice(441, -1);
        assertEquals(FrameProcessingPolicy.Mode.HOT, policy.mode());
        policy.updateDevice(440, -1);
        assertEquals(FrameProcessingPolicy.Mode.WARM, policy.mode());
        policy.updateDevice(421, -1);
        assertEquals(FrameProcessingPolicy.Mode.WARM, policy.mode());
        policy.updateDevice(420, -1);
        assertEquals(FrameProcessingPolicy.Mode.NORMAL, policy.mode());
    }

    @Test public void thermalStatusEscalatesAndUnknownDoesNotCool() {
        FrameProcessingPolicy policy = new FrameProcessingPolicy();
        policy.updateDevice(-1, FrameProcessingPolicy.THERMAL_STATUS_LIGHT);
        assertEquals(FrameProcessingPolicy.Mode.WARM, policy.mode());
        policy.updateDevice(-1, -1);
        assertEquals(FrameProcessingPolicy.Mode.WARM, policy.mode());

        policy.updateDevice(-1, FrameProcessingPolicy.THERMAL_STATUS_SEVERE);
        assertEquals(FrameProcessingPolicy.Mode.HOT, policy.mode());
        policy.updateDevice(-1, 2);
        assertEquals(FrameProcessingPolicy.Mode.WARM, policy.mode());
        policy.updateDevice(-1, FrameProcessingPolicy.THERMAL_STATUS_NONE);
        assertEquals(FrameProcessingPolicy.Mode.NORMAL, policy.mode());
    }

    @Test public void independentSignalsUseTheHotterKnownMode() {
        FrameProcessingPolicy policy = new FrameProcessingPolicy();
        policy.updateDevice(430, FrameProcessingPolicy.THERMAL_STATUS_SEVERE);
        assertEquals(FrameProcessingPolicy.Mode.HOT, policy.mode());
        policy.updateDevice(430, 2);
        assertEquals(FrameProcessingPolicy.Mode.WARM, policy.mode());
        policy.updateDevice(-1, FrameProcessingPolicy.THERMAL_STATUS_NONE);
        // The known warm battery signal remains when that sensor is missing,
        // even if the other sensor now reports thermal NONE.
        assertEquals(FrameProcessingPolicy.Mode.WARM, policy.mode());
        policy.updateDevice(420, FrameProcessingPolicy.THERMAL_STATUS_NONE);
        assertEquals(FrameProcessingPolicy.Mode.NORMAL, policy.mode());
        policy.updateDevice(450, FrameProcessingPolicy.THERMAL_STATUS_NONE);
        policy.updateDevice(-1, FrameProcessingPolicy.THERMAL_STATUS_NONE);
        assertEquals(FrameProcessingPolicy.Mode.HOT, policy.mode());
    }

    @Test public void thermalModeChangeRecomputesAnActiveCooldown() {
        FrameProcessingPolicy policy = new FrameProcessingPolicy();
        policy.recordProcessed(100, 381);
        assertEquals(451, policy.nextAllowedAtMs());

        policy.updateDevice(450, -1);
        assertEquals(FrameProcessingPolicy.Mode.HOT, policy.mode());
        assertEquals(210, policy.lastRestMs());
        assertEquals(591, policy.nextAllowedAtMs());
        assertFalse(policy.canProcess(590));
        assertTrue(policy.canProcess(591));
    }

    @Test public void resetAllowsANewSessionImmediatelyAndRetainsHotDeviceState() {
        FrameProcessingPolicy policy = new FrameProcessingPolicy();
        policy.updateDevice(450, -1);
        policy.recordProcessed(100, 381);
        policy.reset();

        assertEquals(FrameProcessingPolicy.Mode.HOT, policy.mode());
        assertEquals(-1, policy.nextAllowedAtMs());
        assertEquals(0, policy.lastRestMs());
        assertTrue(policy.canProcess(200));
    }

    @Test public void invalidOrOutOfOrderRunDoesNotChangeTheSchedule() {
        FrameProcessingPolicy policy = new FrameProcessingPolicy();
        policy.recordProcessed(100, 381);
        policy.recordProcessed(200, 199);
        policy.recordProcessed(99, 500);

        assertEquals(70, policy.lastRestMs());
        assertEquals(451, policy.nextAllowedAtMs());
    }

    @Test public void captureHealthStillTracksArrivalsDuringPolicySkips() {
        FrameProcessingPolicy policy = new FrameProcessingPolicy();
        CaptureHealthMonitor health = new CaptureHealthMonitor();
        health.start(0);

        for (long now = 0; now <= 3000; now += 100) {
            health.frameArrived(now);
            if (policy.canProcess(now)) policy.recordProcessed(now, now + 281);
            assertEquals(CaptureHealthMonitor.State.HEALTHY, health.check(now));
        }
        assertTrue(policy.lastRestMs() > 0);

        CaptureHealthMonitor starved = new CaptureHealthMonitor();
        starved.start(0);
        starved.frameArrived(2000);
        assertEquals(CaptureHealthMonitor.State.STARVED, starved.check(3000));
    }
}
