package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Actual projection on the tested app; fixture actions are not player or acoustic evidence. */
@RunWith(AndroidJUnit4.class)
public final class Match3GoalReplayInstrumentedTest {
    private static JSONObject state() throws Exception {
        return DiagnosticRecorder.current==null?new JSONObject():new JSONObject(DiagnosticRecorder.current.stateForDiagnostics());
    }
    private static Bitmap load(String name) throws Exception {
        InputStream in;
        try { in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name); }
        catch(java.io.IOException absent) { Assume.assumeNoException(absent);return null; }
        try(in) { return BitmapFactory.decodeStream(in); }
    }
    private interface Condition { boolean ready() throws Exception; }
    private static void await(String message,long budget,Condition condition) throws Exception {
        long until=SystemClock.elapsedRealtime()+budget;
        while(!condition.ready() && SystemClock.elapsedRealtime()<until) {
            Match3DiagnosticReplayInstrumentedTest.dismissFullscreenTutorial(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
            SystemClock.sleep(100);
        }
        assertTrue(message+" actual="+state(),condition.ready());
    }
    private static void show(Match3AssistActivity a,Bitmap bitmap) {
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        a.setContentView(new Match3DiagnosticReplayInstrumentedTest.Replay(a,bitmap));
    }
    @Test public void actualCaptureRanksTheObservedTaskAndClearsItForAnUnsupportedNextMission() throws Exception {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences prefs=GameProfile.settings(context);
        Map<String,?> original=prefs.getAll();String[] keys={"match3_overlay_permission_explained","match3_hint_highlight_enabled","cue_channel_speech","cue_category_system"};
        android.content.SharedPreferences.Editor edit=prefs.edit();for(String key:keys)edit.putBoolean(key,true);edit.commit();
        Bitmap known=load("screen-203-2252860078.jpg"),unknown=load("screen-47-2258502432.jpg");
        Bitmap completedHud=load("screen-229-2252881089.jpg");
        Bitmap hudChanged=known.copy(Bitmap.Config.ARGB_8888,true);
        // Keep the identical board and change only the observed HUD. This is a
        // state-transition regression, not a claim about an actual player move.
        new Canvas(hudChanged).drawBitmap(completedHud,new Rect(0,0,completedHud.getWidth(),300),
                new Rect(0,0,hudChanged.getWidth(),300),null);
        completedHud.recycle();
        JSONArray checkpoints=new JSONArray();
        try(ActivityScenario<Match3AssistActivity> scenario=ActivityScenario.launch(Match3AssistActivity.class)) {
            scenario.onActivity(a->Match3DiagnosticReplayInstrumentedTest.clickStart(a.getWindow().getDecorView()));
            await("actual projection starts",10000,()->{
                Match3DiagnosticReplayInstrumentedTest.consent(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                return Match3LiveService.isRunning();
            });
            scenario.onActivity(a->show(a,known));
            await("confirmed task and rendered recommendation",20000,()->{
                JSONObject s=state(),goal=s.optJSONObject("goal_state");
                return goal!=null && goal.optBoolean("hud_verified") && goal.optInt("steps")==23
                        && s.optBoolean("highlight_visible") && s.optJSONObject("hint")!=null;
            });
            JSONObject first=state(),hint=first.getJSONObject("hint");checkpoints.put(first);
            assertEquals("清除障碍",hint.getString("reason"));
            assertEquals(6,hint.getInt("from_row"));assertEquals(7,hint.getInt("from_col"));
            assertEquals(7,hint.getInt("to_row"));assertEquals(7,hint.getInt("to_col"));
            assertTrue(hint.getString("ranking_scope").contains("observed_goal_one_move_lower_bound"));
            assertEquals(9,first.getInt("rows"));assertEquals(9,first.getInt("cols"));
            File directory=context.getExternalFilesDir("match3-release-capture");assertNotNull(directory);directory.mkdirs();
            Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);
            try(FileOutputStream out=new FileOutputStream(new File(directory,"goal-ranked-actual.png"))) { screenshot.compress(Bitmap.CompressFormat.PNG,100,out); }
            finally { screenshot.recycle(); }
            long revision=hint.getLong("revision");
            scenario.onActivity(a->show(a,hudChanged));
            await("changed HUD retires the old task hint during confirmation",10000,()->{
                JSONObject s=state(),g=s.optJSONObject("goal_state");
                return "goal_confirming".equals(s.optString("recommendation_gate")) && g!=null && !g.optBoolean("hud_verified");
            });
            JSONObject confirming=state();checkpoints.put(confirming);
            assertTrue(confirming.isNull("hint"));assertFalse(confirming.optBoolean("highlight_visible"));
            await("the same board gets one fresh hint after counters confirm",15000,()->{
                JSONObject s=state(),g=s.optJSONObject("goal_state"),h=s.optJSONObject("hint");
                return g!=null && g.optBoolean("hud_verified") && g.optInt("steps")==22
                        && h!=null && s.optBoolean("highlight_visible");
            });
            JSONObject changed=state();checkpoints.put(changed);
            assertEquals(first.getLong("board_revision"),changed.getLong("board_revision"));
            assertEquals(revision+1,changed.getJSONObject("hint").getLong("revision"));
            assertEquals(0,changed.getJSONObject("goal_state").getJSONArray("targets").getJSONObject(1).getInt("remaining"));
            final long latestRevision=changed.getJSONObject("hint").getLong("revision");
            scenario.onActivity(a->show(a,unknown));
            await("next task retires the old snow claim and has fresh guidance",20000,()->{
                JSONObject s=state(),h=s.optJSONObject("hint"),g=s.optJSONObject("goal_state");
                return s.optBoolean("highlight_visible") && h!=null && h.optLong("revision")>latestRevision
                        && g!=null && g.optBoolean("hud_verified") && g.optJSONArray("targets")!=null
                        && g.getJSONArray("targets").length()==1
                        && "COOKIE".equals(g.getJSONArray("targets").getJSONObject(0).getString("kind"));
            });
            JSONObject next=state();checkpoints.put(next);
            assertEquals("COOKIE",next.getJSONObject("goal_state").getJSONArray("targets").getJSONObject(0).getString("kind"));
            String nextReason=next.getJSONObject("hint").getString("reason");
            assertTrue(nextReason.isEmpty() || "靠近饼干".equals(nextReason));
            assertTrue(next.getJSONObject("hint").getString("ranking_scope").contains("direct_units=0"));
            assertFalse(next.getJSONObject("goal_state").getJSONArray("targets").getJSONObject(0).getBoolean("rule_supported"));
            try(FileOutputStream out=new FileOutputStream(new File(directory,"goal-replay.json"))) {
                out.write(new JSONObject().put("source","diagnostic_stills_actual_projection").put("player_action_evidence",false)
                        .put("acoustic_evidence",false).put("checkpoints",checkpoints).toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            context.startService(new Intent(context,Match3LiveService.class).setAction(Match3LiveService.ACTION_STOP));
            await("projection stopped",5000,()->!Match3LiveService.isRunning());
            known.recycle();unknown.recycle();hudChanged.recycle();android.content.SharedPreferences.Editor restore=prefs.edit();
            for(String key:keys)if(original.containsKey(key))restore.putBoolean(key,(Boolean)original.get(key));else restore.remove(key);
            restore.commit();
        }
    }
}
