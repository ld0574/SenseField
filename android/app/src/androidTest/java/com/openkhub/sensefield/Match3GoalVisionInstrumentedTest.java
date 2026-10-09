package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;

/** Explicitly supplied development stills; labels are independent of the ranking code. */
@RunWith(AndroidJUnit4.class)
public final class Match3GoalVisionInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static Bitmap load(String name) throws Exception {
        InputStream in;
        try { in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name); }
        catch(java.io.IOException absent) { Assume.assumeNoException(absent); return null; }
        try(in) { return BitmapFactory.decodeStream(in); }
    }
    private static BoardGeometry header(Bitmap bitmap) {
        return new BoardGeometry(bitmap.getWidth(),bitmap.getHeight(),0,bitmap.getHeight()/3,
                bitmap.getWidth(),bitmap.getHeight()*9/10,9,9);
    }
    @Test public void labelledHudFieldsHaveTheirExactCountsStepsAndCompletionStates() throws Exception {
        String[] names={"screen-73-2252754942.jpg","screen-86-2252765459.jpg","screen-99-2252775972.jpg",
                "screen-125-2252797001.jpg","screen-138-2252807512.jpg","screen-151-2252818024.jpg",
                "screen-164-2252828540.jpg","screen-203-2252860078.jpg","screen-229-2252881089.jpg",
                "screen-255-2252902109.jpg","screen-268-2252912623.jpg"};
        int[][] labels={{16,46,66,32},{8,30,66,31},{5,23,66,30},{3,12,66,28},{3,10,66,26},{3,9,66,25},
                {2,8,66,24},{0,2,66,23},{0,0,66,22},{0,0,24,19},{0,0,24,18}};
        Match3HudReader reader=new Match3HudReader(context());
        try {
            assertTrue(Match3VisualCatalog.get(context()).available);
            for(int i=0;i<names.length;i++) {
                Bitmap bitmap=load(names[i]);
                try {
                    Match3Goals goals=reader.read(bitmap,header(bitmap),100+i*800);
                    Log.i("Match3GoalReplay",names[i]+" status="+reader.status()+" goals="+goals.key()+" level="+goals.level);
                    assertTrue(names[i],goals.hudVerified);
                    assertEquals(names[i],42,goals.level);
                    assertEquals(names[i],labels[i][0],goals.remaining(Match3Goals.Kind.COIN));
                    assertEquals(names[i],labels[i][1],goals.remaining(Match3Goals.Kind.SNOW));
                    assertEquals(names[i],labels[i][2],goals.remaining(Match3Goals.Kind.CHICK));
                    assertEquals(names[i],labels[i][3],goals.steps);
                } finally { bitmap.recycle(); }
            }
        } finally { reader.close(); }
    }
    @Test public void mergedAnimatedCardsAndNonGameScreensNeverBecomeFinishedMissions() throws Exception {
        Match3HudReader reader=new Match3HudReader(context());
        try {
            for(String name:new String[]{"screen-112-2252786486.jpg","screen-242-2252891599.jpg","screen-1-2252692384.jpg"}) {
                Bitmap bitmap=load(name);
                try { Match3Goals goal=reader.read(bitmap,header(bitmap),100);
                    assertFalse(name,goal.finished());assertFalse(name,goal.hudVerified);
                } finally { bitmap.recycle(); }
            }
        } finally { reader.close(); }
    }
    @Test public void captureScaleAndFreshIdenticalFramesPreserveValuesWithoutInventingSamples() throws Exception {
        Bitmap original=load("screen-73-2252754942.jpg");
        Bitmap scaled=Bitmap.createScaledBitmap(original,1220,2712,true);
        Match3HudReader reader=new Match3HudReader(context());
        try {
            Match3Goals a=reader.read(original,header(original),100);
            Match3Goals b=reader.read(scaled,header(scaled),900);
            Log.i("Match3GoalReplay","capture_hud="+b.key()+" status="+reader.status());
            assertTrue(b.hudVerified);assertEquals(16,b.remaining(Match3Goals.Kind.COIN));
            assertEquals(46,b.remaining(Match3Goals.Kind.SNOW));assertEquals(66,b.remaining(Match3Goals.Kind.CHICK));assertEquals(32,b.steps);
            assertEquals(900,reader.read(scaled,header(scaled),900).observedAtMs);
            assertEquals(1700,reader.read(scaled,header(scaled),1700).observedAtMs);
        } finally { reader.close();scaled.recycle();original.recycle(); }
    }
    @Test public void coinsSnowEggsAndOpaqueLargeObstaclesRemainDifferentNonAnimalStates() throws Exception {
        Bitmap bitmap=load("screen-73-2252754942.jpg");
        try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,12,323,420,733,9,9))) {
            Match3Position p=sampler.samplePosition(bitmap);
            Log.i("Match3GoalReplay","tile_coin="+p.cell(0,4).kind+" tile_snow="+p.cell(0,1).kind+" tile_egg="+p.cell(7,1).kind);
            assertEquals(Match3Position.Kind.COIN,p.cell(0,4).kind);
            assertEquals(Match3Position.Kind.SNOW,p.cell(0,1).kind);assertEquals(1,p.cell(0,1).layers);
            assertEquals(Match3Position.Kind.EGG,p.cell(7,1).kind);
            assertEquals(Match3Position.Kind.ANIMAL,p.cell(1,1).kind);
            assertEquals('B',p.cell(1,1).color);
            assertEquals(Match3Position.Kind.ANIMAL,p.cell(1,2).kind);assertEquals('R',p.cell(1,2).color);
            assertFalse(p.cell(0,4).swappable);assertEquals(-1,p.cell(7,1).layers);
        } finally { bitmap.recycle(); }
        for(String name:new String[]{"screen-203-2252860078.jpg","screen-255-2252902109.jpg"}) {
            bitmap=load(name);
            try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,12,322,420,730,9,9))) {
                Match3Position p=sampler.samplePosition(bitmap);
                assertEquals(name,Match3Position.Kind.EGG,p.cell(7,1).kind);
                assertEquals(name,Match3Position.Kind.EGG,p.cell(7,7).kind);
            } finally { bitmap.recycle(); }
        }
        bitmap=load("screen-47-2258502432.jpg");
        try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,12,322,420,730,9,9))) {
            Match3Position p=sampler.samplePosition(bitmap);
            assertEquals(Match3Position.Kind.SURFACE,p.cell(5,5).kind);assertFalse(p.cell(5,5).swappable);
            Match3Goals goal=new Match3HudReader(context()).read(bitmap,header(bitmap),100);
            assertEquals(43,goal.level);
            assertEquals(Match3Goals.Kind.UNKNOWN,goal.targets.get(0).kind);
            assertFalse(goal.fullyKnown());assertFalse(goal.finished());
        } finally { bitmap.recycle(); }
    }
    @Test public void aRealLabelledBoardPrefersTheMoveThatTouchesSnowAndAnEggOverAnUnrelatedTopTriple() throws Exception {
        Bitmap bitmap=load("screen-203-2252860078.jpg");Match3HudReader reader=new Match3HudReader(context());
        try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,12,322,420,730,9,9))) {
            Match3Position p=sampler.samplePosition(bitmap);
            Match3Goals goals=reader.read(bitmap,header(bitmap),100);
            java.util.List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(p,goals);
            assertFalse(moves.isEmpty());Match3MoveValue best=moves.get(0);
            Log.i("Match3GoalReplay","labelled_best="+best.swap.fromRow+","+best.swap.fromCol+"->"+best.swap.toRow+","+best.swap.toCol+" "+best.evidence()+" reason="+best.reason);
            // Independently read from the still: (6,7) green / (7,7) brown
            // becomes the row-7 green triple at columns 6..8, beside snow (8,6)
            // and the right egg (8,8). Numbering here is one-based.
            assertEquals(5,best.swap.fromRow);assertEquals(6,best.swap.fromCol);
            assertEquals(6,best.swap.toRow);assertEquals(6,best.swap.toCol);
            assertEquals(1,best.collected(Match3Goals.Kind.SNOW));assertEquals(1,best.hit(Match3Goals.Kind.EGG));
            assertEquals(0,best.collected(Match3Goals.Kind.CHICK));assertEquals("清除障碍",best.reason);
        } finally { reader.close();bitmap.recycle(); }
    }
}
