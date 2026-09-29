package com.openkhub.sensefield;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CaptureGeometryTest {
    @Test public void sameSizeLandscapeRotationStillResetsTemporalState() {
        // Android's 90° and 270° rotations are both landscape-sized frames.
        assertTrue(CaptureGeometry.rotationChanged(1, 3));
        assertFalse(CaptureGeometry.rotationChanged(1, 1));
    }

    @Test public void unknownRotationDoesNotTriggerRepeatedResets() {
        assertFalse(CaptureGeometry.rotationChanged(-1, 3));
        assertFalse(CaptureGeometry.rotationChanged(3, -1));
    }
}
