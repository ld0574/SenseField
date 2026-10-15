package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Player fault stills; geometry and playable cells reviewed, not independent accuracy. */
@RunWith(AndroidJUnit4.class)
public final class Match3DetachedRowInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static Bitmap load(String name, int width) throws Exception {
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            Bitmap source = BitmapFactory.decodeStream(in); assertNotNull(name, source);
            Bitmap result = Bitmap.createScaledBitmap(source, width, Math.round(width * 960f / 432), true);
            if (result != source) source.recycle();
            return result;
        }
    }
    private static boolean playable(int row, int col) {
        return row == 0 ? col >= 3 && col <= 5 : row >= 3 && (row <= 5
                || row == 6 && col >= 1 && col <= 7 || row == 7 && col >= 2 && col <= 6);
    }
    @Test public void recordedDetachedRowUsesOneOriginAndKeepsSkyOutOfLegalMoves() throws Exception {
        List<String> failures = new ArrayList<>(); JSONArray records = new JSONArray();
        for (String name : new String[]{"screen-206-237749841.jpg",
                "screen-232-237770957.jpg", "screen-271-237802627.jpg"}) for (int width : new int[]{432,1080,1220}) {
            Bitmap frame = load(name, width); Match3HudReader reader = new Match3HudReader(context());
            JSONObject record = new JSONObject().put("fixture", name).put("width", width); records.put(record);
            try {
                BoardGeometry g = Match3Sampler.autoDetectGeometry(frame);
                record.put("geometry", g == null ? JSONObject.NULL : new JSONArray(new int[]{g.left,g.top,g.right,g.bottom,g.rows,g.cols}));
                if (g == null || g.rows != 8 || g.cols != 9 || Math.abs(g.left-width*12f/432)>width*3f/432
                        || Math.abs(g.top-width*345f/432)>width*3f/432
                        || Math.abs(g.right-width*420f/432)>width*3f/432
                        || Math.abs(g.bottom-width*707f/432)>width*3f/432) {
                    failures.add(name+" "+width+" geometry="+g); continue;
                }
                Match3Goals goals = reader.read(frame,g,100);
                record.put("goal_key",goals.key());
                if (!goals.hudVerified || !goals.hasTarget(Match3Goals.Kind.CHICK))
                    failures.add(name+" "+width+" HUD="+goals.key());
                try (Match3Sampler sampler = new Match3Sampler(context(),g)) {
                    Match3Position position = sampler.samplePosition(frame); int falseSwap=0;
                    for(int row=0;row<8;row++)for(int col=0;col<9;col++)
                        if(!playable(row,col) && position.cell(row,col).swappable)falseSwap++;
                    record.put("sky_false_swap",falseSwap);
                    if(falseSwap!=0)failures.add("Sky admitted as playable: "+name+" "+width);
                    List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(position,goals);
                    record.put("legal_moves",moves.size());
                    if(moves.isEmpty())failures.add("No recommendation on stable player board: "+name+" "+width);
                    else {
                        record.put("best_action",moves.get(0).action.key()).put("reason",moves.get(0).reason);
                        for(Match3MoveValue value:moves)if(!playable(value.swap.fromRow,value.swap.fromCol)
                                || !playable(value.swap.toRow,value.swap.toCol))failures.add("Action crosses a missing cell");
                    }
                }
            } finally {reader.close();frame.recycle();}
        }
        File directory=context().getExternalFilesDir("match3-detached-row");assertNotNull(directory);directory.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(directory,"detached-row-replay.json"))) {
            out.write(new JSONObject().put("independent",false).put("records",records)
                    .put("failures",new JSONArray(failures)).toString(2).getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(failures.toString(),failures.isEmpty());
    }

    @Test public void aCrossBoardEffectMustNotFillItsOccludedGridFromTheOtherIsland() throws Exception {
        for(int width:new int[]{432,1080,1220}) {
            Bitmap frame=load("screen-193-237739283.jpg",width);
            try {assertNull("The cross-board effect hides the current main origin",Match3Sampler.autoDetectGeometry(frame));}
            finally {frame.recycle();}
        }
    }

    @Test public void actualProjectionOfTheDetachedRowReachesAVisibleDispatchedHint() throws Exception {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        android.content.SharedPreferences prefs=GameProfile.settings(context());Map<String,?> previous=prefs.getAll();
        String[] keys={"match3_overlay_permission_explained","match3_hint_highlight_enabled","cue_channel_speech","cue_category_system"};
        android.content.SharedPreferences.Editor edit=prefs.edit();for(String key:keys)edit.putBoolean(key,true);edit.commit();
        Bitmap frame=load("screen-206-237749841.jpg",432);
        try(ActivityScenario<Match3AssistActivity> scenario=ActivityScenario.launch(Match3AssistActivity.class)) {
            scenario.onActivity(a->Match3DiagnosticReplayInstrumentedTest.clickStart(a.getWindow().getDecorView()));
            Match3TaskFeedbackInstrumentedTest.await("projection starts",10000,()->{
                Match3DiagnosticReplayInstrumentedTest.consent(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                return Match3LiveService.isRunning();
            });
            scenario.onActivity(a->Match3TaskFeedbackInstrumentedTest.show(a,frame));
            Match3TaskFeedbackInstrumentedTest.await("detached row hint visible",25000,()->{
                JSONObject s=Match3TaskFeedbackInstrumentedTest.state();
                return s.optInt("rows")==8 && s.optInt("cols")==9 && s.optBoolean("highlight_visible")
                        && "ready".equals(s.optString("recommendation_gate"));
            });
            JSONObject first=Match3TaskFeedbackInstrumentedTest.state(),hint=first.getJSONObject("hint");
            assertTrue(playable(hint.getInt("from_row")-1,hint.getInt("from_col")-1));
            assertTrue(playable(hint.getInt("to_row")-1,hint.getInt("to_col")-1));
            final long revision=first.getLong("board_revision"),processed=first.getLong("processed_frames");
            Match3TaskFeedbackInstrumentedTest.await("new frames retain the same hint",8000,()->
                    Match3TaskFeedbackInstrumentedTest.state().optLong("processed_frames")>=processed+5);
            JSONObject retained=Match3TaskFeedbackInstrumentedTest.state();
            assertEquals(revision,retained.getLong("board_revision"));assertTrue(retained.getBoolean("highlight_visible"));
            DiagnosticRecorder.IO.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            String trace=new String(java.nio.file.Files.readAllBytes(new File(DiagnosticRecorder.current.directory,"events.jsonl").toPath()),StandardCharsets.UTF_8);
            boolean accepted=false;
            for(String line:trace.split("\n")) {
                if(line.isEmpty())continue;JSONObject event=new JSONObject(line),data=event.optJSONObject("data");
                if("CueDispatch".equals(event.optString("type")) && data!=null
                        && data.optString("event_key").startsWith("m3live:hint:") && data.optInt("accepted_channels")==2)accepted=true;
            }
            assertTrue("Current hint speech must be accepted by the dispatcher",accepted);
            File directory=context().getExternalFilesDir("match3-detached-row");assertNotNull(directory);directory.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(directory,"detached-row-projection.json"))) {
                out.write(new JSONObject().put("source","player_fault_still_actual_projection").put("acoustic_evidence",false)
                        .put("first",first).put("retained",retained).toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            context().startService(new Intent(context(),Match3LiveService.class).setAction(Match3LiveService.ACTION_STOP));
            Match3TaskFeedbackInstrumentedTest.await("projection stopped",5000,()->!Match3LiveService.isRunning());frame.recycle();
            android.content.SharedPreferences.Editor restore=prefs.edit();
            for(String key:keys)if(previous.containsKey(key))restore.putBoolean(key,(Boolean)previous.get(key));else restore.remove(key);
            restore.commit();
        }
    }
}
