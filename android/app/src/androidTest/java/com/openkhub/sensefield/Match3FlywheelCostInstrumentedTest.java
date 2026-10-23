package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Paired same-engine benchmark. Reports every sample before asserting the predeclared gate. */
@RunWith(AndroidJUnit4.class)
public final class Match3FlywheelCostInstrumentedTest {
    private static final int ROUNDS=30;
    private static double p95(long[] samples) {
        long[] sorted=samples.clone();Arrays.sort(sorted);return sorted[(int)Math.ceil(.95*sorted.length)-1]/1e6;
    }
    @Test public void compareFixedCatalogWithGalleryAndStableHarvestOnTheSameFrames() throws Exception {
        String hardware=android.os.Build.HARDWARE;
        assertTrue("isolated emulator",hardware.contains("ranchu") || hardware.contains("goldfish"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=context.getExternalFilesDir("match3-holdout");assertNotNull(dir);
        byte[] manifestBytes=Files.readAllBytes(new File(dir,"manifest.json").toPath());
        JSONObject manifest=new JSONObject(new String(manifestBytes,StandardCharsets.UTF_8));
        double gate=manifest.getJSONObject("gates").getDouble("max_cost_increase_percent");
        assertTrue("finite predeclared gate",Double.isFinite(gate) && gate>=0);
        JSONArray entries=manifest.getJSONArray("frames");
        Bitmap[] frames=new Bitmap[entries.length()];BoardGeometry[] geometries=new BoardGeometry[frames.length];
        JSONObject hashes=new JSONObject();int boards=0;
        for(int i=0;i<frames.length;i++) {
            JSONObject entry=entries.getJSONObject(i);String name=entry.getString("file");
            File input=new File(dir,name);assertTrue(input.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator));
            byte[] bytes=Files.readAllBytes(input.toPath());hashes.put(name,Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));
            frames[i]=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(frames[i]);
            if(entry.has("bounds")) {
                JSONArray b=entry.getJSONArray("bounds");geometries[i]=BoardGeometry.fromPercent(frames[i].getWidth(),frames[i].getHeight(),entry.getInt("rows"),entry.getInt("cols"),
                        new int[]{b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3)});
            } else geometries[i]=Match3Sampler.autoDetectGeometry(frames[i]);
            assertNotNull(geometries[i]);boards++;
        }
        byte[] baseBytes;JSONObject base;try(InputStream in=context.getAssets().open("match3-fixed-ui-v1.json")) {
            baseBytes=in.readAllBytes();base=new JSONObject(new String(baseBytes,StandardCharsets.UTF_8));
        }
        Match3VisualCatalog[] catalogs={Match3VisualCatalog.withGallery(base,null),Match3VisualCatalog.get(context)};
        assertEquals("loaded",catalogs[1].galleryStatus);
        JSONObject report=new JSONObject().put("scope","emulator_processing_thread_including_descriptor_serialization; excludes_async_disk_io_and_phone_thermal")
                .put("rounds",ROUNDS).put("boards",boards).put("manifest_sha256",Match3HoldoutAccuracyInstrumentedTest.sha256(manifestBytes))
                .put("frame_sha256",hashes).put("gallery_sha256",catalogs[1].gallerySha256)
                .put("fixed_catalog_sha256",Match3HoldoutAccuracyInstrumentedTest.sha256(baseBytes))
                .put("hardware",hardware).put("sdk",android.os.Build.VERSION.SDK_INT).put("max_cost_increase_percent",gate);
        boolean passed=true;int[] serialized={0};
        try {
            for(boolean cold:new boolean[]{true,false}) {
                long[][] wall=new long[2][ROUNDS],cpu=new long[2][ROUNDS];
                long[][][] stages=new long[2][4][ROUNDS];
                Match3Sampler[][] samplers=new Match3Sampler[2][frames.length];
                Match3HudReader[] hud={new Match3HudReader(catalogs[0]),new Match3HudReader(catalogs[1])};
                Match3FlywheelHarvest collector=new Match3FlywheelHarvest();
                try {
                    for(int iteration=-6;iteration<ROUNDS;iteration++) {
                        if(cold)collector=new Match3FlywheelHarvest();
                        for(int order=0;order<2;order++) {
                            int mode=(order+(iteration&1))&1;
                            long cpuStart=Debug.threadCpuTimeNanos(),start=SystemClock.elapsedRealtimeNanos();
                            for(int i=0;i<frames.length;i++) {
                                if(cold || samplers[mode][i]==null) {
                                    if(samplers[mode][i]!=null)samplers[mode][i].close();
                                    samplers[mode][i]=new Match3Sampler(context,geometries[i],catalogs[mode]);
                                }
                                hud[mode].clear(); // each board is a different real observation
                                Match3UnknownElements unknown=new Match3UnknownElements();
                                Match3CellConfirmation confirmation=new Match3CellConfirmation();
                                for(int repeat=0;repeat<3;repeat++) {
                                    long at=(iteration+10L)*100000+i*4000L+repeat*800;
                                    long stageAt=Debug.threadCpuTimeNanos();
                                    Match3Position position=samplers[mode][i].samplePosition(frames[i],at);
                                    long afterSample=Debug.threadCpuTimeNanos();
                                    unknown.observe(samplers[mode][i].reviewElements(position),at);
                                    confirmation.accept(position,at);
                                    Match3Goals goals=hud[mode].read(frames[i],geometries[i],at);
                                    long afterHud=Debug.threadCpuTimeNanos();
                                    Match3MoveRanker.rankedMoves(position,goals);
                                    long afterRank=Debug.threadCpuTimeNanos();
                                    if(mode==1)collector.collect(samplers[mode][i],position,hud[mode],at,data->{
                                        // Include producer's synchronous JSON work. Disk IO has its own bounded worker.
                                        serialized[0]+=data.toString().length();return true;
                                    });
                                    if(iteration>=0) {
                                        stages[mode][0][iteration]+=afterSample-stageAt;stages[mode][1][iteration]+=afterHud-afterSample;
                                        stages[mode][2][iteration]+=afterRank-afterHud;stages[mode][3][iteration]+=Debug.threadCpuTimeNanos()-afterRank;
                                    }
                                }
                                if(mode==1)collector.resetStability();
                            }
                            long elapsed=SystemClock.elapsedRealtimeNanos()-start,cpuElapsed=Debug.threadCpuTimeNanos()-cpuStart;
                            if(iteration>=0) { wall[mode][iteration]=elapsed;cpu[mode][iteration]=cpuElapsed; }
                        }
                    }
                    double wallChange=100*(p95(wall[1])/p95(wall[0])-1),cpuChange=100*(p95(cpu[1])/p95(cpu[0])-1);
                    report.put(cold?"cold":"cached",new JSONObject()
                            .put("baseline_wall_p95_ms",p95(wall[0])).put("candidate_wall_p95_ms",p95(wall[1]))
                            .put("baseline_cpu_p95_ms",p95(cpu[0])).put("candidate_cpu_p95_ms",p95(cpu[1]))
                            .put("wall_change_percent",wallChange).put("cpu_change_percent",cpuChange)
                            .put("baseline_wall_ns",new JSONArray(wall[0])).put("candidate_wall_ns",new JSONArray(wall[1]))
                            .put("baseline_cpu_ns",new JSONArray(cpu[0])).put("candidate_cpu_ns",new JSONArray(cpu[1])));
                    JSONObject stageReport=new JSONObject();String[] names={"sampling","confirmation_and_hud","ranking","harvest"};
                    for(int i=0;i<names.length;i++)stageReport.put(names[i],new JSONObject()
                            .put("baseline_cpu_p95_ms",p95(stages[0][i])).put("candidate_cpu_p95_ms",p95(stages[1][i])));
                    report.getJSONObject(cold?"cold":"cached").put("stages",stageReport);
                    passed &= wallChange<=gate && cpuChange<=gate;
                } finally {
                    for(Match3Sampler[] row:samplers)for(Match3Sampler sampler:row)if(sampler!=null)sampler.close();
                    for(Match3HudReader reader:hud)reader.close();
                }
            }
        } finally { for(Bitmap frame:frames)if(frame!=null)frame.recycle(); }
        report.put("serialized_characters",serialized[0]).put("passed",passed);
        Files.write(new File(dir,"flywheel-cost.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
        assertTrue("collector actually ran",serialized[0]>0);
        assertTrue("same-engine P95 exceeded predeclared gate; keep flywheel-cost.json",passed);
    }
}
