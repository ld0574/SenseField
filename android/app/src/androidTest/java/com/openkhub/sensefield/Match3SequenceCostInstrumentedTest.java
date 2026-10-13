package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/** Sampled JPEG sequences, with original times and gaps. No capture, playback or heat claim. */
public final class Match3SequenceCostInstrumentedTest {
    private static final class Step {
        BoardGeometry geometry;
        Match3Position raw, confirmed;
        Match3Goals observed, goals;
        String gate="geometry_confirming", reset="", hud="not_read";
        boolean motion, quiet;
        int unconfirmed=-1;
        List<Match3MoveValue> moves=Collections.emptyList();
    }

    // Exercise the production stage classes and admission order. This is a
    // processing benchmark, not Match3LiveService's UI/lifecycle/audio replay.
    private static final class Pipeline implements AutoCloseable {
        final Context app;
        final Match3GeometryConfirmation geometryConfirmation=new Match3GeometryConfirmation();
        final Match3CellConfirmation cells=new Match3CellConfirmation();
        final Match3GoalConfirmation goals=new Match3GoalConfirmation();
        final Match3HudReader hud;
        BoardGeometry grid;
        Match3Sampler sampler;
        Match3Position lastStable;
        String rankedGoal="";
        List<Match3MoveValue> ranked;
        Pipeline(Context app) { this.app=app;hud=new Match3HudReader(app); }
        void reset() {
            if(sampler!=null)sampler.close();sampler=null;grid=null;
            geometryConfirmation.reset();cells.reset();goals.clear();hud.clear();
            lastStable=null;ranked=null;rankedGoal="";
        }
        Step process(Bitmap image,long at,boolean reset,long[] stage) {
            Step step=new Step();long before=Debug.threadCpuTimeNanos();
            if(reset){reset();step.reset="window_start";}
            BoardGeometry next=Match3Sampler.autoDetectGeometry(image);
            if(grid!=null && !grid.sameGrid(next)){reset();step.reset="geometry_changed_or_unavailable";}
            if(sampler==null) {
                grid=geometryConfirmation.accept(next);
                if(grid!=null)sampler=new Match3Sampler(app,grid);
            }
            step.geometry=grid;long afterGeometry=Debug.threadCpuTimeNanos();stage[0]=afterGeometry-before;
            if(sampler==null){step.gate=next==null?"geometry_unavailable":"geometry_confirming";return step;}
            step.observed=hud.read(image,grid,at);step.goals=goals.accept(step.observed,at);step.hud=hud.status();
            long afterHud=Debug.threadCpuTimeNanos();
            step.raw=sampler.samplePosition(image,at);step.motion=sampler.frameMotion();
            long afterSample=Debug.threadCpuTimeNanos();stage[1]=afterSample-afterHud;
            if(!Match3Board.findRuns(step.raw.matrix()).isEmpty()) {
                cells.reset();step.gate="cascade";
            } else if(step.motion) {
                cells.reset();step.gate="visual_motion";
            } else {
                Match3CellConfirmation.Snapshot snapshot=cells.accept(step.raw,at);
                step.confirmed=snapshot.position;step.quiet=snapshot.quiet;step.unconfirmed=snapshot.unconfirmed;
                if(!snapshot.quiet)step.gate="board_changing";
                else if(!Match3Board.findRuns(step.confirmed.matrix()).isEmpty()) {cells.reset();step.gate="cascade";}
                else step.gate="admitted";
            }
            long afterConfirm=Debug.threadCpuTimeNanos();
            stage[2]=afterHud-afterGeometry+afterConfirm-afterSample;
            if(step.gate.equals("admitted")) {
                if(!step.confirmed.sameCells(lastStable) || !rankedGoal.equals(step.goals.key()) || ranked==null) {
                    lastStable=step.confirmed;rankedGoal=step.goals.key();
                    ranked=Match3MoveRanker.rankedMoves(step.confirmed,step.goals);
                }
                step.moves=ranked;
                // Gate creation of a new hint; an already shown hint and its
                // retries/retirement are outside this isolated-stage scope.
                step.gate=step.observed.hudVerified && !step.goals.hudVerified?"goal_confirming"
                        :ranked.isEmpty()?"no_swap":"ready";
            }
            stage[3]=Debug.threadCpuTimeNanos()-afterConfirm;
            return step;
        }
        @Override public void close(){reset();hud.close();}
    }

    private static JSONObject position(Match3Position board) throws Exception {
        JSONObject result=new JSONObject();if(board==null)return result;
        JSONArray cells=new JSONArray();
        for(int r=0;r<board.rows;r++)for(int c=0;c<board.cols;c++) {
            Match3Position.Cell cell=board.cell(r,c);
            cells.put(new JSONObject().put("row",r).put("col",c).put("kind",cell.kind.name())
                    .put("color",String.valueOf(cell.color)).put("effect",cell.effect.name()).put("cover",cell.cover.name())
                    .put("layers",cell.layers).put("ice_layers",cell.iceLayers).put("cover_layers",cell.coverLayers)
                    .put("object_id",cell.objectId).put("cover_rule",cell.coverRuleId)
                    .put("swap",cell.swapPermission.name()).put("match",cell.matchPermission.name())
                    .put("activation",cell.activationPermission.name()).put("adjacent_damage",cell.adjacentDamagePermission.name())
                    .put("special_damage",cell.specialDamagePermission.name()).put("gate",cell.gate.name()).put("timed_gate",cell.timedGate)
                    .put("rule_id",cell.ruleId).put("floor_rule_id",cell.floorRuleId).put("rule_version",cell.ruleVersion)
                    .put("absent_turns",cell.absentTurns).put("charge",cell.charge).put("capacity",cell.capacity).put("direction",cell.direction));
        }
        return result.put("rows",board.rows).put("cols",board.cols).put("cells",cells);
    }
    private static JSONObject export(Step step,JSONObject entry) throws Exception {
        JSONObject result=new JSONObject().put("file",entry.getString("file"))
                .put("sequence",entry.getString("sequence")).put("observed_at_ms",entry.getLong("observed_at_ms"))
                .put("reset",step.reset).put("gate",step.gate).put("motion",step.motion).put("quiet",step.quiet)
                .put("unconfirmed",step.unconfirmed).put("hud_status",step.hud)
                .put("observed_goal",step.observed==null?"not_read":step.observed.key())
                .put("confirmed_goal",step.goals==null?"not_read":step.goals.key())
                .put("raw",position(step.raw)).put("confirmed",position(step.confirmed));
        BoardGeometry g=step.geometry;
        if(g!=null)result.put("geometry",new JSONArray(new int[]{g.frameWidth,g.frameHeight,g.left,g.top,g.right,g.bottom,g.rows,g.cols}));
        JSONArray moves=new JSONArray();for(Match3MoveValue move:step.moves)moves.put(Match3RuleDiagnostics.action(move));
        result.put("ranked_moves",moves);
        JSONArray probes=new JSONArray();
        if(step.gate.equals("ready")) {
            Match3Hint hint=new Match3Hint(entry.getString("sequence"),1,entry.getLong("observed_at_ms"),g,step.moves.get(0).swap);
            for(float[] probe:Match3HintOverlay.probes(hint))probes.put(new JSONArray(probe));
        }
        return result.put("new_hint_probes",probes);
    }

    @Test public void originalTimelineStagesAndProcessCpu() throws Exception {
        Assume.assumeTrue("isolated emulator",android.os.Build.HARDWARE.contains("ranchu")
                || android.os.Build.HARDWARE.contains("goldfish"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=app.getExternalFilesDir("match3-sequence");File file=new File(dir,"manifest.json");
        Assume.assumeTrue("explicit private sequence workload",file.isFile());
        byte[] bytes=Files.readAllBytes(file.toPath());JSONObject manifest=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        assertEquals("sensefield.match3-sequence/1",manifest.getString("schema"));
        assertEquals("sequence-v1",manifest.getString("workload"));assertTrue(manifest.getBoolean("frozen"));
        assertFalse(manifest.getBoolean("independent"));assertEquals(1,manifest.getInt("repeats_per_frame"));
        assertEquals(6,manifest.getInt("warmup_rounds"));assertEquals(15,manifest.getInt("measured_rounds"));
        JSONArray entries=manifest.getJSONArray("frames");assertTrue(entries.length()>0);
        Bitmap[] frames=new Bitmap[entries.length()];
        JSONArray wall=new JSONArray(),worker=new JSONArray(),process=new JSONArray(),batches=new JSONArray(),outputs=new JSONArray();
        long[] stages=new long[4];java.util.Map<String,String> beforeRuntime=null;
        try {
            String sequence="";long previousAt=-1;
            for(int i=0;i<frames.length;i++) {
                JSONObject entry=entries.getJSONObject(i);String current=entry.getString("sequence");
                assertEquals(!current.equals(sequence),entry.getBoolean("reset_before"));
                long at=entry.getLong("observed_at_ms");assertTrue(at>=0);
                if(current.equals(sequence))assertTrue(at>previousAt);
                sequence=current;previousAt=at;File input=new File(dir,entry.getString("file"));
                assertTrue(input.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator));
                byte[] image=Files.readAllBytes(input.toPath());assertEquals(entry.getString("sha256"),Match3HoldoutAccuracyInstrumentedTest.sha256(image));
                frames[i]=BitmapFactory.decodeByteArray(image,0,image.length);assertNotNull(frames[i]);
                assertEquals(entry.getInt("output_width"),frames[i].getWidth());assertEquals(entry.getInt("output_height"),frames[i].getHeight());
            }
            try(Pipeline pipeline=new Pipeline(app)) {
                for(int round=-6;round<15;round++) {
                    if(round==0)beforeRuntime=Debug.getRuntimeStats();
                    Step[] steps=new Step[frames.length];long[] batchStages=new long[4];
                    long batchWall=SystemClock.elapsedRealtimeNanos(),processAt=Process.getElapsedCpuTime();
                    for(int i=0;i<frames.length;i++) {
                        JSONObject entry=entries.getJSONObject(i);long[] stage=new long[4];
                        long at=SystemClock.elapsedRealtimeNanos(),cpu=Debug.threadCpuTimeNanos();
                        steps[i]=pipeline.process(frames[i],entry.getLong("observed_at_ms"),entry.getBoolean("reset_before"),stage);
                        long elapsedWall=SystemClock.elapsedRealtimeNanos()-at,elapsedWorker=Debug.threadCpuTimeNanos()-cpu;
                        if(round>=0) {
                            wall.put(elapsedWall);worker.put(elapsedWorker);
                            for(int n=0;n<4;n++){stages[n]+=stage[n];batchStages[n]+=stage[n];}
                        }
                    }
                    long elapsedCpu=Process.getElapsedCpuTime()-processAt,elapsedWall=SystemClock.elapsedRealtimeNanos()-batchWall;
                    if(round>=0){process.put(elapsedCpu);batches.put(new JSONObject().put("round",round)
                            .put("wall_ns",elapsedWall).put("process_cpu_ms",elapsedCpu).put("stage_worker_cpu_ns",new JSONArray(batchStages)));}
                    if(round==14)for(int i=0;i<steps.length;i++)outputs.put(export(steps[i],entries.getJSONObject(i)));
                }
            }
            JSONObject result=new JSONObject().put("scope","emulator_sampled_JPEG_sequence_stages; no_capture_audio_recorder_UI_or_heat")
                    .put("workload","sequence-v1").put("profiling",false).put("independent",false)
                    .put("warmup_rounds",6).put("measured_rounds",15).put("repeats_per_frame",1)
                    .put("frames",frames.length).put("repeats",frames.length*15)
                    .put("manifest_sha256",Match3HoldoutAccuracyInstrumentedTest.sha256(bytes))
                    .put("source_sha256",InstrumentationRegistry.getArguments().getString("match3SourceSha256",""))
                    .put("wall_ns",wall).put("worker_cpu_ns",worker).put("process_cpu_batches_ms",process).put("batches",batches)
                    .put("stage_worker_cpu_total_ns",new JSONArray(stages))
                    .put("stage_order",new JSONArray(new String[]{"geometry_and_reset","sample","HUD_and_confirmation","rank"}))
                    .put("runtime_before",new JSONObject(beforeRuntime)).put("runtime_after",new JSONObject(Debug.getRuntimeStats()))
                    .put("outputs",outputs);
            Files.write(new File(dir,"perception-cost.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
        } finally {for(Bitmap frame:frames)if(frame!=null)frame.recycle();}
    }
}
