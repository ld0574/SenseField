package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.junit.Test;

public final class Match3GeometryInstrumentedTest {
    @Test public void backgroundPredicateMatchesAndroidHsvForEveryRgbColor() {
        float[] hsv = new float[3];
        for (int red = 0; red < 256; red++) for (int green = 0; green < 256; green++)
            for (int blue = 0; blue < 256; blue++) {
                int rgb = Color.rgb(red, green, blue);
                boolean expected = false;
                if (Math.max(red, Math.max(green, blue)) < .45f * 255f) {
                    Color.colorToHSV(rgb, hsv);
                    expected = hsv[0] >= 170f && hsv[0] <= 300f;
                }
                if (expected != Match3Sampler.boardBackground(rgb))
                    fail("Android HSV disagrees at RGB " + red + "," + green + "," + blue
                            + " hue=" + hsv[0]);
            }
    }

    private static void island(Bitmap frame, int left, int top, int rows, int cols, int pitch) {
        for (int y = top; y < top + rows * pitch; y++) for (int x = left; x < left + cols * pitch; x++) {
            int xx = (x - left) % pitch, yy = (y - top) % pitch;
            frame.setPixel(x, y, xx < 3 || yy < 3 || xx >= pitch - 3 || yy >= pitch - 3
                    ? 0xff203050 : 0xffdd9944);
        }
    }

    @Test public void separatedIslandsShareOneOriginOnlyWithCurrentAlignedGridEvidence() {
        Bitmap frame = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888);
        try {
            frame.eraseColor(0xff00ccee);
            island(frame, 24, 280, 8, 4, 40); island(frame, 224, 280, 9, 4, 40);
            BoardGeometry g = Match3Sampler.autoDetectGeometry(frame);
            assertNotNull(g); assertEquals(9, g.rows); assertEquals(9, g.cols);
            assertEquals(24, g.left); assertEquals(280, g.top); assertEquals(384, g.right); assertEquals(640, g.bottom);
            assertEquals(244, g.centerX(5)); // retain the empty column in screen coordinates
            frame.eraseColor(0xff00ccee);
            island(frame, 24, 280, 8, 4, 40); island(frame, 224, 300, 9, 4, 40);
            assertNull("Half-cell shifted panels are different grids", Match3Sampler.autoDetectGeometry(frame));
            frame.eraseColor(0xff00ccee);
            island(frame, 24, 280, 8, 4, 40); island(frame, 224, 280, 9, 4, 34);
            assertNull("Islands must share the current cell pitch", Match3Sampler.autoDetectGeometry(frame));
            frame.eraseColor(0xff00ccee);
            island(frame, 24, 280, 8, 4, 40);
            for (int y = 280; y < 640; y++) for (int x = 224; x < 384; x++) frame.setPixel(x, y, 0xff203050);
            assertNull("A flat second panel cannot borrow the first island's grid", Match3Sampler.autoDetectGeometry(frame));
            frame.eraseColor(0xff00ccee);
            island(frame, 40, 280, 8, 4, 30); island(frame, 190, 280, 9, 4, 30);
            assertNull("A small task preview cannot enter the new island path", Match3Sampler.autoDetectGeometry(frame));
        } finally { frame.recycle(); }
    }

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

    @Test public void aDetachedShortRowNeedsItsOwnPitchAndPreservesTheMissingRows() {
        Bitmap frame=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888);
        try {
            for(boolean transpose:new boolean[]{false,true}) {
                frame.eraseColor(0xff00ccee);
                if(!transpose) {island(frame,24,400,5,9,40);island(frame,144,280,1,3,40);}
                else {island(frame,144,280,9,5,40);island(frame,24,400,3,1,40);}
                BoardGeometry g=Match3Sampler.autoDetectGeometry(frame);
                assertNotNull("Detached short component transpose="+transpose,g);
                assertEquals(transpose?9:8,g.rows);assertEquals(transpose?8:9,g.cols);
                assertEquals(24,g.left);assertEquals(280,g.top);
                assertEquals(transpose?344:384,g.right);assertEquals(transpose?640:600,g.bottom);
            }
            for(int invalid=0;invalid<4;invalid++) {
                frame.eraseColor(0xff00ccee);
                island(frame,24,400,5,9,40);
                if(invalid==0)island(frame,164,280,1,3,40); // half-cell shifted
                if(invalid==1)island(frame,144,280,1,3,34); // different pitch
                if(invalid==2)for(int y=280;y<320;y++)for(int x=144;x<264;x++)frame.setPixel(x,y,0xff203050);
                if(invalid==3) {frame.eraseColor(0xff00ccee);island(frame,40,400,5,9,30);island(frame,130,310,1,3,30);}
                assertNull("Shifted, flat, different-pitch or preview components cannot form a board: "+invalid,
                        Match3Sampler.autoDetectGeometry(frame));
            }
        } finally {frame.recycle();}
    }

    @Test public void disconnectedPartialBottomRowExtendsTheCurrentGridWithoutBorrowingDecor() {
        Bitmap frame = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888);
        try {
            frame.eraseColor(0xff00ccee);
            island(frame, 24, 280, 8, 9, 40);
            island(frame, 224, 602, 1, 4, 40);
            BoardGeometry g = Match3Sampler.autoDetectGeometry(frame);
            assertNotNull(g); assertEquals(9, g.rows); assertEquals(9, g.cols);
            assertEquals(24, g.left); assertEquals(280, g.top); assertEquals(384, g.right);
            assertTrue(Math.abs(640 - g.bottom) <= 2);
            assertArrayEquals(new int[]{8, 7}, g.cellAt(g.centerX(7), g.centerY(8)));
            g = Match3Sampler.autoDetectGeometry(frame, new int[]{24, 280, 384, 600});
            assertNotNull(g); assertEquals("Stored eight-row bounds cannot truncate the current tail", 9, g.rows);

            frame.eraseColor(0xff00ccee);
            island(frame, 24, 280, 8, 9, 40);
            island(frame, 224, 622, 1, 4, 40);
            g = Match3Sampler.autoDetectGeometry(frame);
            assertNotNull(g); assertEquals("A half-cell shifted tail cannot add a row", 8, g.rows);

            frame.eraseColor(0xff00ccee);
            island(frame, 24, 280, 8, 9, 40);
            for (int y = 602; y < 640; y++) for (int x = 224; x < 384; x++) frame.setPixel(x, y, 0xff203050);
            g = Match3Sampler.autoDetectGeometry(frame);
            assertNotNull(g); assertEquals("A flat aligned panel lacks its own cell boundaries", 8, g.rows);
        } finally { frame.recycle(); }
    }

    @Test public void disconnectedPartialRightColumnUsesTheSameEvidenceAsABottomRow() {
        Bitmap frame = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888);
        try {
            frame.eraseColor(0xff00ccee);
            island(frame, 24, 280, 9, 8, 40);
            island(frame, 346, 480, 4, 1, 40);
            BoardGeometry g = Match3Sampler.autoDetectGeometry(frame);
            assertNotNull(g); assertEquals(9, g.rows); assertEquals(9, g.cols);
            assertEquals(24, g.left); assertEquals(280, g.top);
            assertTrue(Math.abs(384 - g.right) <= 2); assertEquals(640, g.bottom);
        } finally { frame.recycle(); }
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

    @Test public void twoSupportedCoveredEdgePhasesCannotChooseATouchOrigin() {
        Bitmap frame=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888);frame.eraseColor(0xff00ccee);
        try {
            for(int y=280;y<664;y++)for(int x=24;x<384;x++) {
                int xx=(x-24)%40,yy=(y-280)%40;
                frame.setPixel(x,y,xx<3 || yy<3 || yy>=24 && yy<27?0xff203050:0xffdd9944);
            }
            assertNull("Both top and bottom extensions have current dark boundaries",
                    Match3Sampler.verifiedGeometry(frame,new int[]{24,304,384,640}));
        } finally {frame.recycle();}
    }

    @Test public void flatDarkPanelsAndBrightPeriodicDecorAreNotAutomaticBoards() {
        Bitmap frame=Bitmap.createBitmap(1220,2712,Bitmap.Config.ARGB_8888);frame.eraseColor(0xff00ccee);
        try {
            for(int y=900;y<1980;y++)for(int x=70;x<1150;x++)frame.setPixel(x,y,0xff203050);
            assertNull("Dark support without a period is insufficient",Match3Sampler.autoDetectGeometry(frame));
            for(int y=900;y<1980;y++)for(int x=70;x<1150;x++)
                frame.setPixel(x,y,(x/120+y/120)%2==0?0xffdd9944:0xffffeeee);
            assertNull("Periodic decoration without the board colour domain is insufficient",Match3Sampler.autoDetectGeometry(frame));
        } finally {frame.recycle();}
    }
}
