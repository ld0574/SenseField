package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;

/** Player fault stills with independently read ice locations; not a held-out accuracy claim. */
@RunWith(AndroidJUnit4.class)
public final class Match3IceTaskInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static final String FIRST="screen-49-2331120934.jpg", SECOND="screen-100-2331163518.jpg";
    private static BoardGeometry geometry(Bitmap f,boolean second) {
        float scale=f.getWidth()/432f;
        return second?new BoardGeometry(f.getWidth(),f.getHeight(),Math.round(35*scale),Math.round(345*scale),
                Math.round(397*scale),Math.round(707*scale),8,8)
                :new BoardGeometry(f.getWidth(),f.getHeight(),Math.round(58*scale),Math.round(368*scale),
                Math.round(374*scale),Math.round(685*scale),7,7);
    }
    @Test public void cyanIceMissionIsDistinctFromWhiteBlocksOnBothActualBoards() throws Exception {
        for(int width:new int[]{432,1080,1220})for(boolean second:new boolean[]{false,true}) {
            Bitmap f=Match3TaskFeedbackInstrumentedTest.load(second?SECOND:FIRST,width);
            Match3HudReader reader=new Match3HudReader(context());
            try {
                Match3Goals goals=reader.read(f,geometry(f,second),100);
                assertTrue(reader.status(),goals.hudVerified);
                assertEquals(second?26:20,goals.steps);assertEquals(1,goals.targets.size());
                assertEquals(Match3Goals.Kind.ICE,goals.targets.get(0).kind);
                int count=goals.remaining(Match3Goals.Kind.ICE),expected=second?40:25;
                assertTrue("Whole count or abstention, never truncated: "+count,count==expected || count==-1);
                assertEquals(-1,goals.remaining(Match3Goals.Kind.SNOW));
            } finally {reader.close();f.recycle();}
        }
    }
    @Test public void confirmedStationaryIceChangesTheMovePriorityWithoutInventingFlowerRules() throws Exception {
        for(int width:new int[]{432,1080,1220}) {
            Bitmap f=Match3TaskFeedbackInstrumentedTest.load(FIRST,width);
            Match3HudReader reader=new Match3HudReader(context());
            try(Match3Sampler sampler=new Match3Sampler(context(),geometry(f,false))) {
                Match3Position p=sampler.samplePosition(f);int observed=0;
                for(int r=0;r<7;r++)for(int c=0;c<7;c++) {
                    boolean ice=r>=1 && r<=5 && c>=1 && c<=5;
                    if(p.cell(r,c).iceLayers>0) {
                        assertTrue("Ice must not spill into the outside ring: "+r+","+c,ice);observed++;
                    }
                }
                // The glowing idle outline may abstain; it is not another ice layer.
                assertTrue("The visible 5x5 ice field must be observed, not treated as a plain board: "+observed,observed>=20);
                assertEquals(1,p.cell(2,2).iceLayers);assertTrue(p.cell(2,2).swappable);
                assertFalse("Rainbow special is not an ordinary swap endpoint",p.cell(6,6).swappable);
                Match3Goals goals=reader.read(f,geometry(f,false),100);
                List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(p,goals);
                assertFalse(moves.isEmpty());Match3MoveValue best=moves.get(0);
                assertTrue("Choose a directly useful ice exchange",best.collected(Match3Goals.Kind.ICE)>0);
                for(Match3MoveValue candidate:moves)
                    assertTrue("Maximize the admitted direct ice lower bound",best.collected(Match3Goals.Kind.ICE)>=candidate.collected(Match3Goals.Kind.ICE));
                assertTrue("The short reason identifies the actual task",best.reason.contains("冰"));
            } finally {reader.close();f.recycle();}
            f=Match3TaskFeedbackInstrumentedTest.load(SECOND,width);
            try(Match3Sampler sampler=new Match3Sampler(context(),geometry(f,true))) {
                Match3Position p=sampler.samplePosition(f);
                for(int c=0;c<8;c++)assertFalse("Unverified frozen flower cannot be an endpoint",p.cell(3,c).swappable);
                // A gradient may remain SURFACE under the unchanged vacancy
                // threshold. Known ice is independent of that conclusion.
                assertFalse(p.cell(5,3).swappable);
                assertNotEquals(Match3Position.Kind.ANIMAL,p.cell(5,3).kind);
                assertEquals(1,p.cell(5,3).iceLayers);
            } finally {f.recycle();}
        }
    }
    @Test public void knownIceCannotHideAnUnfamiliarForegroundCoverOrSeedPlainAnimalReferences() throws Exception {
        Bitmap f=Match3TaskFeedbackInstrumentedTest.load(FIRST,432);
        BoardGeometry g=geometry(f,false);
        try(Match3Sampler sampler=new Match3Sampler(context(),g)) {
            Match3Position p=sampler.samplePosition(f);assertTrue(p.cell(2,2).swappable);
            Match3VisualCatalog catalog=Match3VisualCatalog.get(context());
            Match3VisualCatalog.CellCache cache=new Match3VisualCatalog.CellCache();
            cache.patch=sampler.elementPatch(2,2);cache.envelope=sampler.elementEnvelope(2,2);
            assertEquals(1,catalog.animal(cache).iceLayers);
            assertNull("Ice observations cannot teach the ordinary uncovered family",catalog.trustedReference(cache));
            Bitmap covered=f.copy(Bitmap.Config.ARGB_8888,true);
            try {
                int left=(int)g.cellLeft(2),top=(int)g.cellTop(2),right=(int)g.cellLeft(3),bottom=(int)g.cellTop(3);
                // Preserve the layer's corners; replace real sprite pixels
                // with an unfamiliar opaque foreground.
                for(int y=top+(bottom-top)/4;y<bottom-(bottom-top)/4;y++)
                    for(int x=left+(right-left)/4;x<right-(right-left)/4;x++)covered.setPixel(x,y,0xfffafaff);
                assertFalse("The layer cannot confer a foreground rule",sampler.samplePosition(covered).cell(2,2).swappable);
            } finally {covered.recycle();}
        } finally {f.recycle();}
    }
    @Test public void actualProjectionRanksVisibleIceExchangesAndKeepsVoiceAndHighlightOnThatHint() throws Exception {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        android.content.SharedPreferences prefs=GameProfile.settings(context());java.util.Map<String,?> original=prefs.getAll();
        String[] keys={"match3_overlay_permission_explained","match3_hint_highlight_enabled","cue_channel_speech","cue_category_system"};
        android.content.SharedPreferences.Editor edit=prefs.edit();for(String key:keys)edit.putBoolean(key,true);edit.commit();
        Bitmap[] frames={Match3TaskFeedbackInstrumentedTest.load(FIRST,432),
                Match3TaskFeedbackInstrumentedTest.load("screen-113-2331174037.jpg",432),
                Match3TaskFeedbackInstrumentedTest.load("screen-139-2331195074.jpg",432)};
        JSONArray checkpoints=new JSONArray();
        try(ActivityScenario<Match3AssistActivity> scenario=ActivityScenario.launch(Match3AssistActivity.class)) {
            scenario.onActivity(a->Match3DiagnosticReplayInstrumentedTest.clickStart(a.getWindow().getDecorView()));
            Match3TaskFeedbackInstrumentedTest.await("projection starts",10000,()->{
                Match3DiagnosticReplayInstrumentedTest.consent(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                return Match3LiveService.isRunning();
            });
            for(int i=0;i<frames.length;i++) {
                final int index=i;
                scenario.onActivity(a->Match3TaskFeedbackInstrumentedTest.show(a,frames[index]));
                if(index==1) {
                    // This still has falling sprites and a white explosion,
                    // not a stable board. Freezing it must not manufacture a
                    // valid exchange from the cropped inner component.
                    Match3TaskFeedbackInstrumentedTest.await("animation cannot keep a stale hint",10000,()->{
                        JSONObject s=Match3TaskFeedbackInstrumentedTest.state();
                        return s.optJSONObject("hint")==null && !s.optBoolean("highlight_visible")
                                && !"ready".equals(s.optString("recommendation_gate"));
                    });
                    checkpoints.put(Match3TaskFeedbackInstrumentedTest.state());continue;
                }
                Match3TaskFeedbackInstrumentedTest.await("fresh ice task and consistent visible hint after animation",25000,()->{
                    JSONObject s=Match3TaskFeedbackInstrumentedTest.state(),g=s.optJSONObject("goal_state"),h=s.optJSONObject("hint");
                    return g!=null && g.optBoolean("hud_verified") && g.optInt("steps")== (index==0?20:18)
                            && g.getJSONArray("targets").getJSONObject(0).getString("kind").equals("ICE")
                            && h!=null && (index!=0 || h.getString("reason").contains("冰")) && s.optBoolean("highlight_visible")
                            && s.optInt("stationary_ice_swappable_cells")>0 && "ready".equals(s.optString("recommendation_gate"));
                });
                JSONObject s=Match3TaskFeedbackInstrumentedTest.state();checkpoints.put(s);
                int count=s.getJSONObject("goal_state").getJSONArray("targets").getJSONObject(0).getInt("remaining"),expected=i==0?25:6;
                assertTrue("Projection cannot truncate an ice count: "+count,count==expected || count==-1);
                assertEquals(i==0?7:8,s.getInt("rows"));assertEquals(i==0?7:8,s.getInt("cols"));
                assertTrue(s.getJSONObject("hint").getString("ranking_scope").contains("observed_goal"));
                File dir=context().getExternalFilesDir("match3-release-capture");assertNotNull(dir);dir.mkdirs();
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(250);
                Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);
                try(FileOutputStream out=new FileOutputStream(new File(dir,"ice-task-"+i+".png"))) {screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}
                finally{screenshot.recycle();}
            }
            File dir=context().getExternalFilesDir("match3-release-capture");
            try(FileOutputStream out=new FileOutputStream(new File(dir,"ice-task-replay.json"))) {
                out.write(new JSONObject().put("source","player_fault_stills_actual_projection").put("patient_accuracy_evidence",false)
                        .put("acoustic_evidence",false).put("checkpoints",checkpoints).toString(2).getBytes(StandardCharsets.UTF_8));
            }
            DiagnosticRecorder recorder=DiagnosticRecorder.current;assertNotNull(recorder);
            DiagnosticRecorder.IO.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            String trace=new String(java.nio.file.Files.readAllBytes(new File(recorder.directory,"events.jsonl").toPath()),StandardCharsets.UTF_8);
            assertTrue(trace.contains("\"type\":\"CueDispatch\""));
            assertTrue(trace.contains("\"stationary_ice\""));
            assertTrue(trace.contains("\"physical_lower_bounds\":{\"ICE\":"));
            for(int index:new int[]{0,2}) {
                long revision=checkpoints.getJSONObject(index).getJSONObject("hint").getLong("revision");
                boolean dispatched=false;
                for(String line:trace.split("\n")) {
                    JSONObject event=new JSONObject(line);
                    if("CueDispatch".equals(event.optString("type")) && event.getJSONObject("data")
                            .getString("cue_id").contains(":hint:"+revision+":"))dispatched=true;
                }
                assertTrue("The drawn hint must reach the dispatcher, revision="+revision,dispatched);
            }
        } finally {
            context().startService(new Intent(context(),Match3LiveService.class).setAction(Match3LiveService.ACTION_STOP));
            Match3TaskFeedbackInstrumentedTest.await("projection stopped",5000,()->!Match3LiveService.isRunning());
            for(Bitmap f:frames)f.recycle();
            android.content.SharedPreferences.Editor restore=prefs.edit();
            for(String key:keys)if(original.containsKey(key))restore.putBoolean(key,(Boolean)original.get(key));else restore.remove(key);
            restore.commit();
        }
    }
}
