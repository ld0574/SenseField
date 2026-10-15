package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.Random;
import org.junit.Test;

public final class Match3PatchSumsTest {
    @Test public void clippedRegistrationBoundsNeverExceedFullPixelError() {
        Random random=new Random(921407);
        for(int fixture=0;fixture<12;fixture++) {
            int[] a=new int[256],b=new int[256];
            for(int i=0;i<256;i++) { a[i]=random.nextInt();b[i]=fixture==0?a[i]:random.nextInt(); }
            Match3PatchSums reference=new Match3PatchSums(a),observed=new Match3PatchSums(b);
            for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++) {
                long full=0;
                for(int y=Math.max(0,-dy);y<Math.min(16,16-dy);y++)
                    for(int x=Math.max(0,-dx);x<Math.min(16,16-dx);x++)for(int k=0;k<3;k++)
                        full+=Math.abs((a[y*16+x]>>(16-8*k)&255)-(b[(y+dy)*16+x+dx]>>(16-8*k)&255));
                assertTrue(reference.lowerBound(observed,dx,dy,Double.POSITIVE_INFINITY)<=full);
                double budget=Math.nextUp(.145f)*(16-Math.abs(dx))*(16-Math.abs(dy))*765;
                if(reference.lowerBound(observed,dx,dy,budget)>budget)assertTrue(full>budget);
            }
            if(fixture==0)assertEquals(0,reference.lowerBound(observed,0,0,Double.POSITIVE_INFINITY));
        }
    }
}
