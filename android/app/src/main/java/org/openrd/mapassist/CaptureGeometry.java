package org.openrd.mapassist;

/** Pure display-geometry checks used by capture before processing a frame. */
final class CaptureGeometry {
    private CaptureGeometry() {}

    static boolean rotationChanged(int previousRotation, int currentRotation) {
        return previousRotation >= 0 && currentRotation >= 0 &&
                previousRotation != currentRotation;
    }
}
