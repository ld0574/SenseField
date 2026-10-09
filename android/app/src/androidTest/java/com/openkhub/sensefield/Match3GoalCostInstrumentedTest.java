package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

/** Same inputs on the same isolated emulator; software cost is not a phone temperature measurement. */
@RunWith(AndroidJUnit4.class)
public final class Match3GoalCostInstrumentedTest {
    private static Bitmap load(String name) throws Exception {
        InputStream in;
        try { in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name); }
        catch(java.io.IOException absent) { Assume.assumeNoException(absent);return null; }
        try(in) { Bitmap source=BitmapFactory.decodeStream(in);Bitmap scaled=Bitmap.createScaledBitmap(source,1220,2712,true);source.recycle();return scaled; }
    }
    private static double percentile(long[] values,double percentile) {
        long[] sorted=values.clone();Arrays.sort(sorted);return sorted[(int)Math.ceil(percentile*sorted.length)-1]/1000000d;
    }
    @Test public void reportTheAddedLocalGoalReadAndRankingCostAgainstTheExistingPixelPath() throws Exception {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap[] frames={load("screen-73-2252754942.jpg"),load("screen-203-2252860078.jpg"),load("screen-229-2252881089.jpg")};
        Match3Sampler[] samplers=new Match3Sampler[frames.length];Match3HudReader reader=new Match3HudReader(context);
        long[][] wall=new long[2][30],cpu=new long[2][30];
        long[][] goalStages=new long[3][30];
        try {
            for(int i=0;i<frames.length;i++) { BoardGeometry g=Match3Sampler.autoDetectGeometry(frames[i]);assertNotNull(g);samplers[i]=new Match3Sampler(context,g); }
            for(int iteration=-8;iteration<30;iteration++)for(int mode=0;mode<2;mode++) {
                // Alternate which path runs first to avoid always charging one path the cold scheduling cost.
                int variant=(mode+(iteration&1))&1,index=Math.max(0,iteration)/2%frames.length;
                long start=SystemClock.elapsedRealtimeNanos(),cpuStart=Debug.threadCpuTimeNanos();
                BoardGeometry geometry=Match3Sampler.autoDetectGeometry(frames[index]);assertNotNull(geometry);
                if(variant==0)Match3MoveRanker.rankedSwaps(Match3Sampler.sample(frames[index],geometry,Collections.emptyList()));
                else {
                    long sampledAt=SystemClock.elapsedRealtimeNanos();
                    Match3Position position=samplers[index].samplePosition(frames[index]);
                    long readAt=SystemClock.elapsedRealtimeNanos();
                    Match3Goals goals=reader.read(frames[index],geometry,SystemClock.elapsedRealtime());
                    long rankedAt=SystemClock.elapsedRealtimeNanos();
                    Match3MoveRanker.rankedMoves(position,goals);
                    if(iteration>=0) {
                        goalStages[0][iteration]=readAt-sampledAt;
                        goalStages[1][iteration]=rankedAt-readAt;
                        goalStages[2][iteration]=SystemClock.elapsedRealtimeNanos()-rankedAt;
                    }
                }
                long cost=SystemClock.elapsedRealtimeNanos()-start,cpuCost=Debug.threadCpuTimeNanos()-cpuStart;
                if(iteration>=0) { wall[variant][iteration]=cost;cpu[variant][iteration]=cpuCost; }
            }
            JSONObject report=new JSONObject().put("source","isolated_emulator_same_inputs_interleaved_paths")
                    .put("samples_per_path",30).put("input_width",1220).put("input_height",2712)
                    .put("baseline_wall_p50_ms",percentile(wall[0],.5)).put("baseline_wall_p95_ms",percentile(wall[0],.95))
                    .put("goal_wall_p50_ms",percentile(wall[1],.5)).put("goal_wall_p95_ms",percentile(wall[1],.95))
                    .put("baseline_cpu_p95_ms",percentile(cpu[0],.95)).put("goal_cpu_p95_ms",percentile(cpu[1],.95))
                    .put("goal_sampling_p95_ms",percentile(goalStages[0],.95))
                    .put("goal_hud_p95_ms",percentile(goalStages[1],.95))
                    .put("goal_ranking_p95_ms",percentile(goalStages[2],.95))
                    .put("p95_change_percent",100*(percentile(wall[1],.95)/percentile(wall[0],.95)-1))
                    .put("physical_thermal_gate_passed",false).put("patient_trial_gate_passed",false);
            File dir=context.getExternalFilesDir("match3-release-capture");assertNotNull(dir);dir.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(dir,"goal-cost.json"))) { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
            android.util.Log.i("Match3GoalCost",report.toString());
        } finally { for(Match3Sampler s:samplers)if(s!=null)s.close();reader.close();for(Bitmap f:frames)if(f!=null)f.recycle(); }
    }
}
