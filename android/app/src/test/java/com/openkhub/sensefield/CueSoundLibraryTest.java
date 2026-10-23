package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class CueSoundLibraryTest {
    @Test public void missingOrInvalidStoredSoundUsesTheOriginalTone() {
        assertEquals("classic", CueSoundLibrary.valid(null));
        assertEquals("classic", CueSoundLibrary.valid("deleted-file"));
        assertEquals("原版电子音", CueSoundLibrary.label("deleted-file"));
        assertArrayEquals(CueSoundLibrary.render(7, "classic", 48000),
                CueSoundLibrary.render(7, "deleted-file", 48000));
    }

    @Test public void builtinSoundsAreDistinctAndKeepDoublePulseNearAlerts() {
        for (int i = 0; i < CueSoundLibrary.IDS.length; i++) {
            String style = CueSoundLibrary.IDS[i];
            short[] near = CueSoundLibrary.render(7, style, 48000);
            assertEquals(48 * CueSoundLibrary.durationMs(7, style), near.length);
            int gap = 48 * (style.equals("tap") ? 45 : 65);
            int pulse = (near.length - gap) / 2;
            for (int sample = pulse; sample < pulse + gap; sample++) assertEquals(0, near[sample]);
            assertTrue(energy(near, 0, pulse) > 0);
            assertTrue(energy(near, pulse + gap, near.length) > 0);
            for (int j = 0; j < i; j++) assertFalse(Arrays.equals(near,
                    CueSoundLibrary.render(7, CueSoundLibrary.IDS[j], 48000)));
        }
    }

    @Test public void onlyLiveToneEventsAreOfferedAndEachHasAnIndependentKey() {
        assertArrayEquals(new int[]{7, 2, 1, 3}, CueSoundLibrary.KINDS);
        assertEquals(CueSoundLibrary.KINDS.length, CueSoundLibrary.EVENTS.length);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (int kind : CueSoundLibrary.KINDS) {
            assertTrue(keys.add(CueSoundLibrary.preferenceKey(kind)));
            for (String style : CueSoundLibrary.IDS)
                assertTrue(energy(CueSoundLibrary.render(kind, style, 16000), 0,
                        16 * CueSoundLibrary.durationMs(kind, style)) > 0);
        }
    }

    private static long energy(short[] pcm, int start, int end) {
        long energy = 0;
        for (int i = start; i < end; i++) energy += Math.abs((int) pcm[i]);
        return energy;
    }
}
