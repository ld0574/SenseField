package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

public final class PageGeometryTest {
    @Test public void foldFromWideToNarrowUsesTheNewAvailableWidth() {
        assertEquals(720, PageGeometry.readingWidthPx(1600, 1f));
        assertEquals(320, PageGeometry.readingWidthPx(320, 1f));
        assertEquals(720, PageGeometry.readingWidthPx(1600, 1f));
    }
    @Test public void widthCapUsesDensityAndClampsInvalidMeasurements() {
        assertEquals(1440, PageGeometry.readingWidthPx(2000, 2f));
        assertEquals(0, PageGeometry.readingWidthPx(-1, 2f));
        assertEquals(720, PageGeometry.readingWidthPx(2000, Float.NaN));
    }
}
