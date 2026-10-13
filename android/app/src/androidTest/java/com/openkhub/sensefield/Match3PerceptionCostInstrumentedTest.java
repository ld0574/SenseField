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
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Assume;

/** Opt-in frozen development frames; software processing, not foreground heat. */
public final class Match3PerceptionCostInstrumentedTest {
    @Test public void frozenFrameStagesAndProcessCpu() throws Exception {
        Assume.assumeTrue("isolated emulator", android.os.Build.HARDWARE.contains("ranchu")
                || android.os.Build.HARDWARE.contains("goldfish"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=app.getExternalFilesDir("match3-holdout");
        File manifestFile=new File(dir,"manifest.json");
        Assume.assumeTrue("explicit frozen cost inputs",manifestFile.isFile());
        byte[] manifestBytes=Files.readAllBytes(manifestFile.toPath());
        JSONObject manifest=new JSONObject(new String(manifestBytes,StandardCharsets.UTF_8));
        JSONArray entries=manifest.getJSONArray("frames");assertTrue(entries.length()>0);
        Bitmap[] frames=new Bitmap[entries.length()];
        Match3Sampler[] samplers=new Match3Sampler[frames.length];
        BoardGeometry[] grids=new BoardGeometry[frames.length];
        Match3HudReader hud=new Match3HudReader(app);
        JSONArray wall=new JSONArray(),worker=new JSONArray(),process=new JSONArray();
        long[] stages=new long[3];int repeats=0;
        try {
            for(int i=0;i<frames.length;i++) {
                JSONObject entry=entries.getJSONObject(i);File input=new File(dir,entry.getString("file"));
                assertTrue(input.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator));
                byte[] bytes=Files.readAllBytes(input.toPath());
                assertEquals(entry.getString("sha256"),Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));
                frames[i]=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(frames[i]);
                grids[i]=Match3Sampler.autoDetectGeometry(frames[i]);assertNotNull(grids[i]);
                samplers[i]=new Match3Sampler(app,grids[i]);
            }
            // Includes fresh and unchanged pixel paths in each batch. Six
            // warmups; 15 batches amortize millisecond process CPU resolution.
            for(int round=-6;round<15;round++) {
                long processAt=Process.getElapsedCpuTime();
                for(int i=0;i<frames.length;i++) {
                    Match3CellConfirmation confirmation=new Match3CellConfirmation();hud.clear();
                    for(int repeat=0;repeat<3;repeat++) {
                        long at=(round+10L)*100000+i*4000L+repeat*800;
                        long wallAt=SystemClock.elapsedRealtimeNanos(),cpuAt=Debug.threadCpuTimeNanos();
                        Match3Position position=samplers[i].samplePosition(frames[i],at);
                        long afterSample=Debug.threadCpuTimeNanos();
                        Match3Position confirmed=confirmation.accept(position,at).position;
                        Match3Goals goals=hud.read(frames[i],grids[i],at);
                        long afterHud=Debug.threadCpuTimeNanos();
                        Match3MoveRanker.rankedMoves(confirmed,goals);
                        long afterRank=Debug.threadCpuTimeNanos();
                        if(round>=0) {
                            wall.put(SystemClock.elapsedRealtimeNanos()-wallAt);worker.put(afterRank-cpuAt);
                            stages[0]+=afterSample-cpuAt;stages[1]+=afterHud-afterSample;stages[2]+=afterRank-afterHud;repeats++;
                        }
                    }
                }
                if(round>=0)process.put(Process.getElapsedCpuTime()-processAt);
            }
            JSONObject result=new JSONObject().put("scope","isolated_emulator_fixed_input_processing; no_capture_audio_io_or_game_heat")
                    .put("manifest_sha256",Match3HoldoutAccuracyInstrumentedTest.sha256(manifestBytes))
                    .put("source_sha256",InstrumentationRegistry.getArguments().getString("match3SourceSha256",""))
                    .put("frames",frames.length).put("repeats",repeats).put("wall_ns",wall).put("worker_cpu_ns",worker)
                    .put("process_cpu_batches_ms",process).put("stage_worker_cpu_total_ns",new JSONArray(stages));
            Files.write(new File(dir,"perception-cost.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
        } finally {
            hud.close();for(Match3Sampler sampler:samplers)if(sampler!=null)sampler.close();
            for(Bitmap frame:frames)if(frame!=null)frame.recycle();
        }
    }
}
