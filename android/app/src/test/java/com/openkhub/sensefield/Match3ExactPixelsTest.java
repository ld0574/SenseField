package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

public final class Match3ExactPixelsTest {
    @Test public void absentNativeLibraryRetainsExactJavaFallback() {
        assertFalse(Match3NativeMath.AVAILABLE);
        assertTrue(Match3NativeMath.samePixels(null,null));
        assertTrue(Match3NativeMath.samePixels(new int[0],new int[0]));
        assertFalse(Match3NativeMath.samePixels(null,new int[0]));
        assertFalse(Match3NativeMath.samePixels(new int[1],new int[2]));
        int[] pixels={0,0xffffffff,Integer.MIN_VALUE,0x12abcdef};
        assertTrue(Match3NativeMath.samePixels(pixels,pixels.clone()));
        int[] changed=pixels.clone();changed[3]^=0x01000000;
        assertFalse(Match3NativeMath.samePixels(pixels,changed));
    }
}
