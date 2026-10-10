package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** New player failure stills; not independent recognition/clearance evidence. */
@RunWith(AndroidJUnit4.class)
public final class Match3SparseTaskInstrumentedTest {
    private static Context context() {return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    @Test public void brightEmptyIceAndLargeObjectsDoNotShrinkTheWholeBoardToSixBySix() throws Exception {
        for(int width:new int[]{432,1080,1220}) {
            Bitmap frame=Match3TaskFeedbackInstrumentedTest.load("screen-47-2334671249.jpg",width);
            try {
                BoardGeometry g=Match3Sampler.autoDetectGeometry(frame);assertNotNull("Current whole board at "+width,g);
                assertEquals(g.toString(),8,g.rows);assertEquals(g.toString(),8,g.cols);
                assertTrue(g.toString(),Math.abs(g.left-width*35f/432)<width/100f);
                assertTrue(g.toString(),Math.abs(g.top-width*344f/432)<width/100f);
                assertTrue(g.toString(),Math.abs(g.bottom-width*707f/432)<width/100f);
            } finally {frame.recycle();}
        }
    }
    @Test public void theLastCoinAndLastIceKeepTheirLocationsInPreparationInsteadOfGenericFourRuns() throws Exception {
        String[] names={"screen-202-2334797160.jpg","screen-331-2334901990.jpg","screen-500-2335039006.jpg"};
        Match3Goals.Kind[] kinds={Match3Goals.Kind.COIN,Match3Goals.Kind.ICE,Match3Goals.Kind.ICE};
        JSONArray reports=new JSONArray();
        for(int index=0;index<names.length;index++) {
            Bitmap frame=Match3TaskFeedbackInstrumentedTest.load(names[index],1220);
            Match3HudReader reader=new Match3HudReader(context());
            try {
                BoardGeometry g=Match3Sampler.autoDetectGeometry(frame);assertNotNull(names[index],g);
                assertEquals(9,g.rows);assertEquals(index==0?8:9,g.cols);
                Match3Goals goals=reader.read(frame,g,100);
                assertTrue(reader.status(),goals.hudVerified);assertEquals(1,goals.remaining(kinds[index]));
                try(Match3Sampler sampler=new Match3Sampler(context(),g)) {
                    Match3Position p=sampler.samplePosition(frame);
                    List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(p,goals);assertFalse(moves.isEmpty());
                    Match3MoveValue best=moves.get(0);
                    // No certain one-move removal on these manually reviewed states.
                    assertEquals(names[index],0,best.directUnits);
                    assertFalse(best.allTargetsFinish);
                    assertTrue(names[index]+" A located target must not fall back to unqualified basic guidance: "+best.evidence(),
                            best.targetDistance<Match3TargetFrontier.UNAVAILABLE);
                    assertFalse(best.reason.isEmpty());
                    for(Match3MoveValue value:moves)if(value.directUnits==0 && value.relevantHits==best.relevantHits)
                        assertTrue("Prefer the nearest admitted spatial preparation",best.targetDistance<=value.targetDistance);
                    reports.put(new JSONObject().put("fixture",names[index]).put("geometry",g.toString())
                            .put("goals",goals.key()).put("from_row",best.swap.fromRow+1).put("from_col",best.swap.fromCol+1)
                            .put("to_row",best.swap.toRow+1).put("to_col",best.swap.toCol+1)
                            .put("reason",best.reason).put("evidence",best.evidence()).put("direct_units",best.directUnits));
                }
            } finally {reader.close();frame.recycle();}
        }
        File dir=context().getExternalFilesDir("match3-release-capture");assertNotNull(dir);dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,"sparse-task-replay.json"))) {
            out.write(new JSONObject().put("scope","fault_regression_spatial_preparation_only")
                    .put("player_benefit_verified",false).put("checkpoints",reports).toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    @Test public void actualProjectionKeepsPreparationHintsVisibleAndDispatchesTheSameRevision() throws Exception {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        android.content.SharedPreferences prefs=GameProfile.settings(context());java.util.Map<String,?> original=prefs.getAll();
        String[] keys={"match3_overlay_permission_explained","match3_hint_highlight_enabled","cue_channel_speech","cue_category_system"};
        android.content.SharedPreferences.Editor edit=prefs.edit();for(String key:keys)edit.putBoolean(key,true);edit.commit();
        String[] names={"screen-202-2334797160.jpg","screen-331-2334901990.jpg","screen-500-2335039006.jpg"};
        String[] reasons={"银币","冰","冰"};
        Bitmap[] frames=new Bitmap[names.length];JSONArray checkpoints=new JSONArray();
        File dir=context().getExternalFilesDir("match3-release-capture");assertNotNull(dir);dir.mkdirs();
        try(ActivityScenario<Match3AssistActivity> scenario=ActivityScenario.launch(Match3AssistActivity.class)) {
            for(int i=0;i<frames.length;i++)frames[i]=Match3TaskFeedbackInstrumentedTest.load(names[i],432);
            scenario.onActivity(a->Match3DiagnosticReplayInstrumentedTest.clickStart(a.getWindow().getDecorView()));
            Match3TaskFeedbackInstrumentedTest.await("projection starts",10000,()->{
                Match3DiagnosticReplayInstrumentedTest.consent(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                return Match3LiveService.isRunning();
            });
            for(int i=0;i<frames.length;i++) {
                final int index=i;
                scenario.onActivity(a->Match3TaskFeedbackInstrumentedTest.show(a,frames[index]));
                Match3TaskFeedbackInstrumentedTest.await("current located target and visible preparation",25000,()->{
                    JSONObject s=Match3TaskFeedbackInstrumentedTest.state(),g=s.optJSONObject("goal_state"),h=s.optJSONObject("hint");
                    return g!=null && g.optBoolean("hud_verified") && g.optInt("steps")== (index==2?16:11)
                            && h!=null && h.optString("reason").contains(reasons[index]) && s.optBoolean("highlight_visible")
                            && "ready".equals(s.optString("recommendation_gate"));
                });
                JSONObject s=Match3TaskFeedbackInstrumentedTest.state(),h=s.getJSONObject("hint");
                long revision=h.getLong("revision");
                assertEquals(9,s.getInt("rows"));assertEquals(index==0?8:9,s.getInt("cols"));
                assertTrue(h.getString("ranking_scope").contains("direct_units=0"));
                assertTrue(h.getString("ranking_scope").contains("target_distance_scope=spatial_only"));
                // Hold the unchanged frame through several real sampling cycles.
                // UI attachment alone is not evidence the recommendation survives recapture.
                SystemClock.sleep(3000);
                s=Match3TaskFeedbackInstrumentedTest.state();
                assertTrue(names[index],s.getBoolean("highlight_visible"));
                assertEquals(revision,s.getJSONObject("hint").getLong("revision"));
                // A replacement can be drawn before the existing six-second
                // speech interval expires. Wait for dispatch, not just drawing.
                Match3TaskFeedbackInstrumentedTest.await("visible revision reaches speech dispatch",10000,()->{
                    DiagnosticRecorder recorder=DiagnosticRecorder.current;assertNotNull(recorder);
                    DiagnosticRecorder.IO.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
                    String trace=new String(java.nio.file.Files.readAllBytes(new File(recorder.directory,"events.jsonl").toPath()),StandardCharsets.UTF_8);
                    for(String line:trace.split("\n")) {
                        JSONObject event=new JSONObject(line);
                        if("CueDispatch".equals(event.optString("type")) && event.getJSONObject("data")
                                .getString("cue_id").contains(":hint:"+revision+":"))return true;
                    }
                    return false;
                });
                s=Match3TaskFeedbackInstrumentedTest.state();
                assertTrue(s.getBoolean("highlight_visible"));
                assertEquals(revision,s.getJSONObject("hint").getLong("revision"));checkpoints.put(s);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);
                try(FileOutputStream out=new FileOutputStream(new File(dir,"sparse-task-"+index+".png"))) {
                    screenshot.compress(Bitmap.CompressFormat.PNG,100,out);
                } finally {screenshot.recycle();}
            }
            DiagnosticRecorder recorder=DiagnosticRecorder.current;assertNotNull(recorder);
            DiagnosticRecorder.IO.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            String trace=new String(java.nio.file.Files.readAllBytes(new File(recorder.directory,"events.jsonl").toPath()),StandardCharsets.UTF_8);
            for(int i=0;i<checkpoints.length();i++) {
                long revision=checkpoints.getJSONObject(i).getJSONObject("hint").getLong("revision");boolean dispatched=false;
                for(String line:trace.split("\n")) {
                    JSONObject event=new JSONObject(line);
                    if("CueDispatch".equals(event.optString("type")) && event.getJSONObject("data")
                            .getString("cue_id").contains(":hint:"+revision+":"))dispatched=true;
                }
                assertTrue("The visible preparation reaches the dispatcher, revision="+revision,dispatched);
            }
            try(FileOutputStream out=new FileOutputStream(new File(dir,"sparse-task-projection.json"))) {
                out.write(new JSONObject().put("source","fault_stills_actual_projection")
                        .put("player_benefit_verified",false).put("acoustic_evidence",false)
                        .put("checkpoints",checkpoints).toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            context().startService(new Intent(context(),Match3LiveService.class).setAction(Match3LiveService.ACTION_STOP));
            Match3TaskFeedbackInstrumentedTest.await("projection stopped",5000,()->!Match3LiveService.isRunning());
            for(Bitmap frame:frames)if(frame!=null)frame.recycle();
            android.content.SharedPreferences.Editor restore=prefs.edit();
            for(String key:keys)if(original.containsKey(key))restore.putBoolean(key,(Boolean)original.get(key));else restore.remove(key);
            restore.commit();
        }
    }
}
