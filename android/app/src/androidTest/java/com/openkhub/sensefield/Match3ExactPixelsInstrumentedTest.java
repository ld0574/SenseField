package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

/** Native comparison must retain every bit, including alpha, at all live buffer sizes. */
public final class Match3ExactPixelsInstrumentedTest {
    @Test public void nullIdentityLengthsAndEveryPixelBitStayExact() {
        assertTrue(Match3NativeMath.equalPixels(null,null));
        assertFalse(Match3NativeMath.equalPixels(null,new int[0]));
        assertFalse(Match3NativeMath.equalPixels(new int[0],null));
        assertTrue(Match3NativeMath.equalPixels(new int[0],new int[0]));
        assertFalse(Match3NativeMath.equalPixels(new int[1],new int[2]));
        Random random=new Random(0x047);
        for(int size:new int[]{1,15,16,255,256,257,432*80,432*200}) {
            int[] first=new int[size];for(int i=0;i<size;i++)first[i]=random.nextInt();
            int[] second=first.clone();
            assertTrue(Match3NativeMath.equalPixels(first,first));
            assertTrue(Match3NativeMath.equalPixels(first,second));
            for(int index:new int[]{0,size/2,size-1})for(int bit=0;bit<32;bit++) {
                second[index]^=1<<bit;
                assertEquals("size="+size+" index="+index+" bit="+bit,
                        Arrays.equals(first,second),Match3NativeMath.equalPixels(first,second));
                second[index]^=1<<bit;
            }
        }
    }
}
