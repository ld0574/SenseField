package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Player-supplied level 49–53 fault regression, not independent accuracy or acoustic evidence. */
@RunWith(AndroidJUnit4.class)
public final class Match3LiveFeedbackInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static Bitmap load(String name) throws Exception {
        InputStream in;
        try { in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name); }
        catch(java.io.IOException absent) { Assume.assumeNoException(absent); return null; }
        try(in) { return BitmapFactory.decodeStream(in); }
    }
    @Test public void theSameRealBoardKeepsItsIdentityUnderOurDrawingAtPhoneDensity() throws Exception {
        Bitmap original=load("screen-284-2285557292.jpg");
        Bitmap game=Bitmap.createScaledBitmap(original,1220,2712,true);
        BoardGeometry g=new BoardGeometry(1220,2712,35,909,1185,2060,9,9);
        Configuration config=new Configuration(context().getResources().getConfiguration()); config.densityDpi=480;
        Context phone=context().createConfigurationContext(config);
        try(Match3Sampler sampler=new Match3Sampler(phone,g)) {
            Match3Position baseline=sampler.samplePosition(game);
            for(Match3Board.Swap swap:new Match3Board.Swap[]{new Match3Board.Swap(4,3,4,4,3),new Match3Board.Swap(6,1,7,1,3)}) {
                Bitmap captured=game.copy(Bitmap.Config.ARGB_8888,true);
                try {
                    Match3Hint hint=new Match3Hint("fault",1,1,g,swap);
                    Canvas canvas=new Canvas(captured);
                    Match3HintOverlay.HintView drawing=new Match3HintOverlay.HintView(phone);drawing.hint=hint;
                    int layer=canvas.saveLayerAlpha(g.left,g.top,g.right,g.bottom,191);
                    canvas.translate(g.left,g.top);drawing.drawHint(canvas);canvas.restoreToCount(layer);
                    assertTrue(new Match3OverlayCaptureFilter(phone).clean(captured,hint,.75f));
                    Match3Position after=sampler.samplePosition(captured);
                    List<String> changed=new ArrayList<>();
                    for(int r=0;r<9;r++)for(int c=0;c<9;c++)if(!baseline.cell(r,c).equals(after.cell(r,c)))
                        changed.add((r+1)+","+(c+1)+":"+baseline.cell(r,c).code()+"->"+after.cell(r,c).code());
                    assertTrue("Our highlight must not cancel a valid utterance: "+changed,changed.isEmpty());
                } finally {captured.recycle();}
            }
        } finally {game.recycle();original.recycle();}
    }
    @Test public void newJarsAndFrozenFlowersAreNotExchangeableAnimals() throws Exception {
        Bitmap frame=load("screen-50-2285367442.jpg");
        try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,12,322,420,730,9,9))) {
            Match3Position p=sampler.samplePosition(frame);
            Log.i("Match3Feedback","level49="+matrix(p));
            for(int c=1;c<8;c++)assertFalse("Golden jar is not a bear/chick endpoint: "+c,p.cell(0,c).swappable);
            assertEquals(Match3Position.Kind.COIN,p.cell(1,1).kind);
        } finally {frame.recycle();}
        frame=load("screen-284-2285557292.jpg");
        try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,12,322,420,730,9,9))) {
            Match3Position p=sampler.samplePosition(frame);
            Log.i("Match3Feedback","level50="+matrix(p));
            assertFalse("Frozen flower cannot become a blue animal",p.cell(0,8).swappable);
            assertFalse("Frozen flower cannot become a blue animal",p.cell(6,0).swappable);
            assertEquals(Match3Position.Kind.SNOW,p.cell(0,7).kind);
            for(int c:new int[]{0,1,2,6,7,8})assertNotEquals("Sky outside the irregular board is not a task block",Match3Position.Kind.SNOW,p.cell(8,c).kind);
        } finally {frame.recycle();}
    }
    @Test public void visibleAnimalColoursAgreeWithAnIndependentManualGrid() throws Exception {
        Bitmap frame=load("screen-284-2285557292.jpg");
        String[] expected={"OPGGROPI#","OPRGPRGOI","GRPRGORG I".replace(" ",""),"PGGOORPRP",
                "RPOROPRGR","ROROROGOP","#POGRRPRI","PRGRPGORI","HHHRGOHHH"};
        try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,12,322,420,730,9,9))) {
            Match3Position p=sampler.samplePosition(frame);List<String> wrong=new ArrayList<>();
            for(int r=0;r<9;r++)for(int c=0;c<9;c++) {
                char wanted=expected[r].charAt(c);
                if(Match3Sampler.isMovable(wanted) && p.cell(r,c).color!=wanted)
                    wrong.add((r+1)+","+(c+1)+":"+wanted+"->"+p.cell(r,c).code());
            }
            Log.i("Match3Feedback","manual="+matrix(p)+" wrong="+wrong);
            assertTrue("Animal labels are independently read from the screenshot: "+wrong,wrong.isEmpty());
        } finally {frame.recycle();}
    }
    @Test public void fragmentedStepBadgesKeepAllThreeGoalsAndDoNotInventJarRules() throws Exception {
        Bitmap frame=load("screen-50-2285367442.jpg");Match3HudReader reader=new Match3HudReader(context());
        try {
            Match3Goals goals=reader.read(frame,new BoardGeometry(432,960,12,322,420,730,9,9),100);
            Log.i("Match3Feedback","fragmented_hud="+reader.status()+" "+goals.key());
            assertTrue("22 splits the badge colour, not the actual mission",goals.hudVerified);
            assertEquals(22,goals.steps);assertEquals(3,goals.targets.size());
            assertEquals(14,goals.remaining(Match3Goals.Kind.COIN));assertEquals(70,goals.remaining(Match3Goals.Kind.CHICK));
            assertEquals(Match3Goals.Kind.UNKNOWN,goals.targets.get(1).kind);assertEquals(22,goals.targets.get(1).remaining);
            assertFalse(goals.fullyKnown());
        } finally {reader.close();frame.recycle();}
    }
    @Test public void anUnchangedBoardDoesNotRetireItsHintOnIdleSpriteAnimation() throws Exception {
        for(int width:new int[]{432,1080,1220}) {
        Match3Position first=null;BoardGeometry firstGeometry=null;Match3MoveValue retained=null;
        for(String name:new String[]{"screen-284-2285557292.jpg","screen-297-2285567809.jpg","screen-310-2285578323.jpg"}) {
            Bitmap original=load(name),frame=Bitmap.createScaledBitmap(original,width,width==1220?2712:Math.round(width*960f/432),true);
            if(frame!=original)original.recycle();
            BoardGeometry geometry=Match3Sampler.autoDetectGeometry(frame);assertNotNull(geometry);
            assertEquals(9,geometry.rows);assertEquals(9,geometry.cols);
            try(Match3Sampler sampler=new Match3Sampler(context(),geometry)) {
                Match3Position p=sampler.samplePosition(frame);
                Log.i("Match3Feedback",name+" width="+width+" "+geometry+"="+matrix(p));
                if(first!=null) {
                    assertTrue("Idle animation preserves confirmed geometry at "+width,firstGeometry.sameGrid(geometry));
                    List<String> changes=new ArrayList<>();
                    for(int r=0;r<p.rows;r++)for(int c=0;c<p.cols;c++)if(!first.cell(r,c).equals(p.cell(r,c)))
                        changes.add((r+1)+","+(c+1)+":"+first.cell(r,c).kind+"/"+first.cell(r,c).color+"/"+first.cell(r,c).swapPermission
                                +"->"+p.cell(r,c).kind+"/"+p.cell(r,c).color+"/"+p.cell(r,c).swapPermission);
                    for(int r=0;r<p.rows;r++)for(int c=0;c<p.cols;c++)if(first.cell(r,c).kind==Match3Position.Kind.ANIMAL) {
                        assertEquals("Idle animation must preserve identity at "+r+","+c,Match3Position.Kind.ANIMAL,p.cell(r,c).kind);
                        assertEquals("Idle animation must preserve colour at "+r+","+c,first.cell(r,c).color,p.cell(r,c).color);
                    }
                    assertTrue("A localized cover abstention must not retire an unaffected exchange at "+width+" changes="+changes,
                            Match3HintValidity.valid(first,p,retained,Match3MoveRanker.rankedMoves(p,Match3Goals.unknown(0))));
                } else {
                    first=p;firstGeometry=geometry;
                    List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(p,Match3Goals.unknown(0));assertFalse(moves.isEmpty());retained=moves.get(0);
                }
            } finally {frame.recycle();}
        }
        }
    }
    @Test public void newLevelsPreserveRectangularGridsAndExcludeTheirUnknownObjects() throws Exception {
        String[] names={"screen-24-2290398043.jpg","screen-128-2290482080.jpg","screen-258-2290586993.jpg"};
        int[][] dimensions={{8,9},{9,8},{9,9}};
        List<String> wrong=new ArrayList<>();
        for(int width:new int[]{432,1080,1220}) {
        for(int i=0;i<names.length;i++) {
            Bitmap original=load(names[i]),frame=Bitmap.createScaledBitmap(original,width,width==1220?2712:Math.round(width*960f/432),true);
            if(frame!=original)original.recycle();
            BoardGeometry g=Match3Sampler.autoDetectGeometry(frame);
            try {
                assertNotNull(names[i],g);assertEquals(names[i],dimensions[i][0],g.rows);assertEquals(names[i],dimensions[i][1],g.cols);
                try(Match3Sampler sampler=new Match3Sampler(context(),g)) {
                    Match3Position p=sampler.samplePosition(frame);Log.i("Match3Feedback",names[i]+" "+g+" "+matrix(p));
                    if(i==0) {
                        for(int c=0;c<9;c++)assertFalse("Level51 top row contains jars, not chicks: "+c,p.cell(0,c).swappable);
                        assertEquals(Match3Position.Kind.SNOW,p.cell(1,0).kind);
                        if(p.cell(7,8).kind!=Match3Position.Kind.COIN)wrong.add("51 coin at 8,9 width="+width+"="+p.cell(7,8).kind);
                    } else if(i==1) {
                        for(int r=4;r<=5;r++)for(int c=0;c<8;c++)
                            assertFalse("Large cookie quadrant is not an animal: "+r+","+c,p.cell(r,c).swappable);
                        for(int r=0;r<4;r++)for(int c=0;c<8;c++)
                            if(p.cell(r,c).kind!=Match3Position.Kind.COIN)
                                wrong.add("52 coin at "+(r+1)+","+(c+1)+" width="+width+"="+p.cell(r,c).kind);
                    } else {
                        assertFalse("Jar endpoint",p.cell(1,4).swappable);
                        assertFalse("Covered flower endpoint",p.cell(0,3).swappable);
                        for(int c=0;c<3;c++)assertNotEquals("Sky cannot count as a target",Match3Position.Kind.SNOW,p.cell(0,c).kind);
                    }
                }
            } finally {frame.recycle();}
        }
        }
        assertTrue("Known coin endpoints: "+wrong,wrong.isEmpty());
    }
    @Test public void jarsAndCookiesInTheNewMissionCardsCannotBecomeChickGoals() throws Exception {
        String[] names={"screen-24-2290398043.jpg","screen-128-2290482080.jpg","screen-258-2290586993.jpg"};
        Match3Goals.Kind[][] kinds={{Match3Goals.Kind.SNOW,Match3Goals.Kind.UNKNOWN,Match3Goals.Kind.COIN},
                {Match3Goals.Kind.COOKIE,Match3Goals.Kind.COIN},{Match3Goals.Kind.UNKNOWN,Match3Goals.Kind.SNOW}};
        int[][] counts={{20,12,16},{18,32},{27,20}};int[] steps={26,20,27};
        for(int i=0;i<names.length;i++) {
            Bitmap frame=load(names[i]);Match3HudReader reader=new Match3HudReader(context());
            try {
                Match3Goals goals=reader.read(frame,Match3Sampler.autoDetectGeometry(frame),100);
                Log.i("Match3Feedback",names[i]+" HUD="+reader.status()+" "+goals.key());
                assertTrue(names[i],goals.hudVerified);assertEquals(steps[i],goals.steps);assertEquals(kinds[i].length,goals.targets.size());
                for(int t=0;t<kinds[i].length;t++) {
                    assertEquals("Known artwork is distinct from an unverified jar/cookie rule",kinds[i][t],goals.targets.get(t).kind);
                    assertEquals(counts[i][t],goals.targets.get(t).remaining);
                }
                assertEquals(-1,goals.remaining(Match3Goals.Kind.CHICK));
                // Levels 51 and 53 contain jars, not cookies. Their rules and
                // identities remain unverified; only the reviewed cookie is known.
                if(i==1)assertTrue("All HUD identities/counts, not all mechanics, are known",goals.fullyKnown());
                else assertFalse("Unverified jars must remain unknown",goals.fullyKnown());
            } finally {reader.close();frame.recycle();}
        }
    }
    @Test public void theLaterLevel53BoardKeepsItsIdentityAcrossIdleOutlineFrames() throws Exception {
        for(int width:new int[]{432,1080,1220}) {
            Match3Position first=null;
            for(String name:new String[]{"screen-310-2290628768.jpg","screen-323-2290639206.jpg"}) {
                Bitmap original=load(name),frame=Bitmap.createScaledBitmap(original,width,width==1220?2712:Math.round(width*960f/432),true);
                if(frame!=original)original.recycle();
                BoardGeometry g=Match3Sampler.autoDetectGeometry(frame);assertNotNull(g);
                try(Match3Sampler sampler=new Match3Sampler(context(),g)) {
                    Match3Position p=sampler.samplePosition(frame);Log.i("Match3Feedback",name+" width="+width+" "+matrix(p));
                    assertEquals("Visible purple owl remains an ordinary animal despite its idle glow",'P',p.cell(8,2).color);
                    if(first!=null)assertTrue("The same 16-step board must preserve its hint at "+width,first.sameCells(p));
                    else first=p;
                } finally {frame.recycle();}
            }
        }
    }
    private static String matrix(Object value) {
        // A private helper descriptor is reflected by JUnit across the test APK
        // boundary; keep it independent of the target APK's obfuscated names.
        Match3Position p=(Match3Position)value;
        StringBuilder s=new StringBuilder();for(char[] row:p.matrix())s.append(new String(row)).append('/');return s.toString();
    }
}
