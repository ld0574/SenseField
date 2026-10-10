package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;

/** Explicit player fault stills with manually read HUDs; not independent task-benefit evidence. */
@RunWith(AndroidJUnit4.class)
public final class Match3TaskFeedbackInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    static Bitmap load(String name, int width) throws Exception {
        InputStream in;
        try { in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name); }
        catch(java.io.IOException absent) { Assume.assumeNoException(absent);return null; }
        try(in) {
            Bitmap source=BitmapFactory.decodeStream(in);
            Bitmap scaled=Bitmap.createScaledBitmap(source,width,Math.round(width*960f/432),true);
            if(source!=scaled)source.recycle();return scaled;
        }
    }
    @Test public void aDifficultyLabelDoesNotHideTheCoinMissionOrChangeTheBadgeDigits() throws Exception {
        for(int width:new int[]{432,1080,1220}) {
            Bitmap frame=load("screen-60-2325628886.jpg",width);
            Match3HudReader reader=new Match3HudReader(context());
            try {
                BoardGeometry g=new BoardGeometry(width,frame.getHeight(),Math.round(width*12f/432),
                        Math.round(width*322f/432),Math.round(width*420f/432),Math.round(width*730f/432),9,9);
                Match3Goals goals=reader.read(frame,g,100);
                assertTrue("Difficulty bubble obscures only the upper badge: "+reader.status(),goals.hudVerified);
                assertEquals(24,goals.steps);assertEquals(2,goals.targets.size());
                assertEquals(47,goals.remaining(Match3Goals.Kind.COIN));
                assertEquals("COOKIE",goals.targets.get(1).kind.name());
                int cookieCount=goals.targets.get(1).remaining;
                // A twice-resampled diagnostic numeral may abstain. It must
                // not fabricate a different count or erase this task identity.
                if(width==432)assertEquals(14,cookieCount);
                else assertTrue("14 or explicit unread, never an invented count at "+width,cookieCount==14 || cookieCount==-1);
                try(Match3Sampler sampler=new Match3Sampler(context(),g)) {
                    java.util.List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(sampler.samplePosition(frame),goals);
                    assertFalse(moves.isEmpty());Match3MoveValue best=moves.get(0);
                    assertTrue("The initial coin board has direct coin contact, not just an unrelated match",best.collected(Match3Goals.Kind.COIN)>0);
                    for(Match3MoveValue candidate:moves)
                        assertTrue("One-move coin contribution must be maximal among admitted exchanges",best.collected(Match3Goals.Kind.COIN)>=candidate.collected(Match3Goals.Kind.COIN));
                    assertTrue(best.reason.contains("银币"));
                }
            } finally {reader.close();frame.recycle();}
        }
    }
    @Test public void aSparseBoardWithLargeObjectsStillHasNineIndependentRowsAndColumns() throws Exception {
        for(int width:new int[]{432,1080,1220})for(String name:new String[]{
                "screen-513-2325997784.jpg","screen-526-2326008301.jpg","screen-539-2326018814.jpg"}) {
            Bitmap frame=load(name,width);
            try {
                BoardGeometry g=Match3Sampler.autoDetectGeometry(frame);
                assertNotNull("Empty regions and double-size objects must not erase grid evidence: "+name+" "+width,g);
                assertEquals(9,g.rows);assertEquals(9,g.cols);
                assertTrue(Math.abs(g.left-width*12f/432)<width/60f);
                assertTrue(Math.abs(g.top-width*322f/432)<width/60f);
                Match3HudReader reader=new Match3HudReader(context());
                try(Match3Sampler sampler=new Match3Sampler(context(),g)) {
                    Match3Goals goals=reader.read(frame,g,100);
                    assertTrue(reader.status(),goals.hudVerified);assertEquals(28,goals.steps);
                    assertEquals(17,goals.remaining(Match3Goals.Kind.SNOW));
                    assertEquals("COOKIE",goals.targets.get(1).kind.name());assertEquals(32,goals.targets.get(1).remaining);
                    Match3Position p=sampler.samplePosition(frame);
                    int cookies=0;for(int r=0;r<9;r++)for(int c=0;c<9;c++)if("COOKIE".equals(p.cell(r,c).kind.name())) {
                        cookies++;assertFalse(p.cell(r,c).swappable);
                    }
                    assertEquals("Eight complete double-size objects cover 32 cells",32,cookies);
                    java.util.List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(p,goals);
                    assertFalse("There are visible ordinary animal exchanges at the start",moves.isEmpty());
                    assertTrue("The first exchange must touch an observed unfinished task",moves.get(0).relevantHits>0);
                    assertEquals(0,moves.get(0).collected(Match3Goals.Kind.COOKIE));
                } finally {reader.close();}
            } finally {frame.recycle();}
        }
    }
    @Test public void projectionResamplingCannotSilentlyDropALeadingOrTrailingDigit() throws Exception {
        String[] names={"task-feedback-0.png","task-feedback-1.png"};
        Match3Goals.Kind[][] kinds={{Match3Goals.Kind.COIN,Match3Goals.Kind.COOKIE},{Match3Goals.Kind.SNOW,Match3Goals.Kind.COOKIE}};
        int[][] counts={{47,14},{17,32}};
        for(int i=0;i<names.length;i++) {
            Bitmap frame=load(names[i],1080);Match3HudReader reader=new Match3HudReader(context());
            try {
                BoardGeometry g=Match3Sampler.autoDetectGeometry(frame);assertNotNull(g);
                Match3Goals goals=reader.read(frame,g,100);
                assertTrue(reader.status(),goals.hudVerified);assertEquals(i==0?24:28,goals.steps);
                for(int t=0;t<2;t++) {
                    assertEquals(kinds[i][t],goals.targets.get(t).kind);
                    int count=goals.targets.get(t).remaining;
                    assertTrue("Projection cannot turn "+counts[i][t]+" into "+count,count==counts[i][t] || count==-1);
                }
            } finally {reader.close();frame.recycle();}
        }
    }
    static JSONObject state() throws Exception {
        return DiagnosticRecorder.current==null?new JSONObject():new JSONObject(DiagnosticRecorder.current.stateForDiagnostics());
    }
    interface Condition { boolean ready() throws Exception; }
    static void await(String name,long budget,Condition condition) throws Exception {
        long until=SystemClock.elapsedRealtime()+budget;
        while(!condition.ready() && SystemClock.elapsedRealtime()<until) {
            Match3DiagnosticReplayInstrumentedTest.dismissFullscreenTutorial(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
            SystemClock.sleep(100);
        }
        assertTrue(name+" "+state(),condition.ready());
    }
    static void show(Match3AssistActivity a,Bitmap frame) {
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        a.setContentView(new Match3DiagnosticReplayInstrumentedTest.Replay(a,frame));
    }
    @Test public void actualProjectionShowsAndDispatchesFreshTaskHintsForBothFaultBoards() throws Exception {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        android.content.SharedPreferences prefs=GameProfile.settings(context());java.util.Map<String,?> original=prefs.getAll();
        String[] keys={"match3_overlay_permission_explained","match3_hint_highlight_enabled","cue_channel_speech","cue_category_system"};
        android.content.SharedPreferences.Editor edit=prefs.edit();for(String key:keys)edit.putBoolean(key,true);edit.commit();
        Bitmap coin=load("screen-60-2325628886.jpg",432),cookie=load("screen-513-2325997784.jpg",432);
        JSONArray checkpoints=new JSONArray();
        try(ActivityScenario<Match3AssistActivity> scenario=ActivityScenario.launch(Match3AssistActivity.class)) {
            scenario.onActivity(a->Match3DiagnosticReplayInstrumentedTest.clickStart(a.getWindow().getDecorView()));
            await("projection starts",10000,()->{
                Match3DiagnosticReplayInstrumentedTest.consent(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                return Match3LiveService.isRunning();
            });
            for(int i=0;i<2;i++) {
                final int index=i;final Bitmap frame=i==0?coin:cookie;
                scenario.onActivity(a->show(a,frame));
                await("fresh confirmed task and visible hint",25000,()->{
                    JSONObject s=state(),g=s.optJSONObject("goal_state"),h=s.optJSONObject("hint");
                    return g!=null && g.optBoolean("hud_verified") && g.optInt("steps")== (index==0?24:28)
                            && h!=null && s.optBoolean("highlight_visible") && "ready".equals(s.optString("recommendation_gate"));
                });
                JSONObject s=state(),hint=s.getJSONObject("hint");checkpoints.put(s);
                JSONArray targets=s.getJSONObject("goal_state").getJSONArray("targets");
                int[] labelledCounts=i==0?new int[]{47,14}:new int[]{17,32};
                for(int t=0;t<labelledCounts.length;t++) {
                    int count=targets.getJSONObject(t).getInt("remaining");
                    assertTrue("Live projection must not silently truncate a task count: "+count,
                            count==labelledCounts[t] || count==-1);
                }
                assertTrue(hint.getString("ranking_scope").contains("observed_goal"));
                assertFalse("A task-related reason must replace generic guidance",hint.getString("reason").isEmpty());
                assertEquals(9,s.getInt("rows"));assertEquals(9,s.getInt("cols"));
                File directory=context().getExternalFilesDir("match3-release-capture");assertNotNull(directory);directory.mkdirs();
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                SystemClock.sleep(250); // Let the newly attached hint window submit its first draw.
                Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);
                try(FileOutputStream out=new FileOutputStream(new File(directory,"task-feedback-"+i+".png"))) {screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}
                finally{screenshot.recycle();}
            }
            File directory=context().getExternalFilesDir("match3-release-capture");
            try(FileOutputStream out=new FileOutputStream(new File(directory,"task-feedback-replay.json"))) {
                out.write(new JSONObject().put("source","player_fault_stills_actual_projection").put("player_strategy_evidence",false)
                        .put("acoustic_evidence",false).put("checkpoints",checkpoints).toString(2).getBytes(StandardCharsets.UTF_8));
            }
            DiagnosticRecorder recorder=DiagnosticRecorder.current;assertNotNull(recorder);
            DiagnosticRecorder.IO.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            String trace=new String(java.nio.file.Files.readAllBytes(new File(recorder.directory,"events.jsonl").toPath()),StandardCharsets.UTF_8);
            assertTrue("Task ranking must leave a diagnostic comparison",trace.contains("\"type\":\"Match3Ranking\""));
            assertTrue("Projection must reach the sound dispatcher",trace.contains("\"type\":\"CueDispatch\""));
        } finally {
            context().startService(new Intent(context(),Match3LiveService.class).setAction(Match3LiveService.ACTION_STOP));
            await("projection stopped",5000,()->!Match3LiveService.isRunning());coin.recycle();cookie.recycle();
            android.content.SharedPreferences.Editor restore=prefs.edit();
            for(String key:keys)if(original.containsKey(key))restore.putBoolean(key,(Boolean)original.get(key));else restore.remove(key);
            restore.commit();
        }
    }
}
