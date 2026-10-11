package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import org.junit.Test;

public final class Match3GeometryInstrumentedTest {
    @Test public void actualDisplayStretchKeepsItsObservedOriginAndBothGridPhases() {
        Bitmap frame=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888);frame.eraseColor(0xff00ccee);
        try {
            for(int y=280;y<622;y++)for(int x=24;x<384;x++) {
                int xx=(x-24)%40,yy=(y-280)%38;
                frame.setPixel(x,y,xx<3 || yy<3 || xx>=37 || yy>=35?0xff203050:0xffdd9944);
            }
            BoardGeometry g=Match3Sampler.verifiedGeometry(frame,new int[]{24,280,384,622});
            assertNotNull(g);assertEquals(9,g.rows);assertEquals(9,g.cols);assertEquals(280,g.top);assertEquals(622,g.bottom);
        } finally {frame.recycle();}
    }
    @Test public void coveredOuterEdgesNeedRepeatedBoundariesBeforeRecoveringSquareCells() {
        for(int count=6;count<=9;count++)for(boolean vertical:new boolean[]{true,false}) {
            Bitmap frame=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888);
            frame.eraseColor(0xff00ccee);
            int left=24,top=280,pitch=40,right=left+count*pitch,bottom=top+count*pitch;
            for(int y=top;y<bottom;y++)for(int x=left;x<right;x++) {
                int xx=(x-left)%pitch,yy=(y-top)%pitch;
                frame.setPixel(x,y,xx<3 || yy<3 || xx>=37 || yy>=37?0xff203050:0xffdd9944);
            }
            int[] clipped={left,top,right,bottom};
            if(vertical)clipped[1]+=24;else clipped[0]+=24;
            try {
                BoardGeometry geometry=Match3Sampler.verifiedGeometry(frame,clipped);
                assertNotNull(count+" vertical="+vertical,geometry);
                assertEquals(count,geometry.rows);assertEquals(count,geometry.cols);
                assertEquals(left,geometry.left);assertEquals(top,geometry.top);
                assertEquals(right,geometry.right);assertEquals(bottom,geometry.bottom);
                frame.eraseColor(0xffdd9944);
                assertNull("A square extrapolation without current grid evidence abstains",Match3Sampler.verifiedGeometry(frame,clipped));
            } finally { frame.recycle(); }
        }
    }
}
