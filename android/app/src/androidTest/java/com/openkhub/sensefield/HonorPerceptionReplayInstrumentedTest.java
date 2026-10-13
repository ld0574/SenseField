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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in sampled JPEG replay. This is neither original capture pixels nor accuracy/heat truth. */
public final class HonorPerceptionReplayInstrumentedTest {
    @Test public void frozenJpegDetectorOutputAndCost() throws Exception {
        Assume.assumeTrue("isolated emulator",android.os.Build.HARDWARE.contains("ranchu")
                || android.os.Build.HARDWARE.contains("goldfish"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=context.getExternalFilesDir("honor-development-replay");assertNotNull(dir);
        File manifestFile=new File(dir,"manifest.json");
        Assume.assumeTrue("explicit frozen private inputs",manifestFile.isFile());
        byte[] manifestBytes=Files.readAllBytes(manifestFile.toPath());
        JSONObject manifest=new JSONObject(new String(manifestBytes,StandardCharsets.UTF_8));
        assertTrue(manifest.getBoolean("frozen"));assertFalse(manifest.getBoolean("independent"));
        // This is the profile entry point retained across the separate Release
        // test APK. R8 may merge the internal Loaded wrapper with other types.
        GameProfile p=GameProfile.load(context);
        assertTrue(p.minimapYolox);assertEquals(512,p.yoloxInputSize);
        JSONObject frozen=manifest.getJSONObject("profile_record");
        assertEquals(frozen.getString("name"),p.name);
        assertEquals(frozen.getString("version"),p.version);
        assertEquals(frozen.getDouble("confidence"),p.yoloxConfidence,0.000001);
        assertEquals(frozen.getInt("observation_age_budget_ms"),p.eventInts[0]);
        for(int i=0;i<p.rois.length;i++)assertEquals(frozen.getJSONArray("rois_xywh").getDouble(i),p.rois[i],0.000001);
        for(int i=0;i<p.yoloxClassThresholds.length;i++)assertEquals(frozen.getJSONArray("class_thresholds").getDouble(i),p.yoloxClassThresholds[i],0.000001);
        JSONArray entries=manifest.getJSONArray("frames");assertTrue(entries.length()>0);
        ByteBuffer[] pixels=new ByteBuffer[entries.length()];int[] widths=new int[pixels.length],heights=new int[pixels.length];
        for(int i=0;i<pixels.length;i++) {
            JSONObject entry=entries.getJSONObject(i);File file=new File(dir,entry.getString("file"));
            assertTrue(file.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator));
            byte[] bytes=Files.readAllBytes(file.toPath());assertEquals(entry.getString("sha256"),Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));
            Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(image);
            try {
                widths[i]=image.getWidth();heights[i]=image.getHeight();
                assertEquals(widths[i]*4,image.getRowBytes());
                pixels[i]=ByteBuffer.allocateDirect(image.getByteCount());image.copyPixelsToBuffer(pixels[i]);pixels[i].rewind();
            } finally {image.recycle();}
        }
        GameProfile.TemplateData enemy=p.enemyTemplate,ping=p.pingTemplate;GameProfile.PlayerLifeData life=p.playerLife;
        long session=NativeBridge.nativeCreate(context.getAssets(),p.rois,p.flags,p.tuning,p.eventInts,p.minConfidence,
                enemy==null?null:enemy.rgba,enemy==null?0:enemy.width,enemy==null?0:enemy.height,
                ping==null?null:ping.rgba,ping==null?0:ping.width,ping==null?0:ping.height,
                p.minimapYolox,p.yoloxInputSize,p.yoloxParamAsset,p.yoloxBinAsset,p.yoloxConfidence,p.yoloxNms,
                p.yoloxClassKinds,p.yoloxClassThresholds,p.minimapLocatorEnabled,p.minimapLocatorFloats,p.minimapLocatorInts,p.minimapLocatorDescriptor,
                life!=null,life==null?null:life.roiAndThresholds,life==null?0:life.maxDhashDistance,
                life==null?null:life.hashes,life==null?null:life.states,life==null?null:life.luma,life==null?null:life.chroma);
        assertTrue(session!=0);
        JSONArray outputs=new JSONArray(),wall=new JSONArray(),worker=new JSONArray(),process=new JSONArray(),batches=new JSONArray();
        long[] stages=new long[4];long memoryStart=Debug.getNativeHeapAllocatedSize();
        long inferences=0,reuses=0;
        try {
            if(p.relation!=null)assertTrue(NativeBridge.nativeConfigureRelation(session,p.relation.floats,p.relation.ints));
            for(int round=-6;round<15;round++) {
                NativeBridge.nativeReset(session);
                long[] before=NativeBridge.nativeReadWorkStats(session);assertEquals(2,before[0]);
                long processAt=Process.getElapsedCpuTime();
                long batchWallAt=SystemClock.elapsedRealtimeNanos();
                for(int i=0;i<pixels.length;i++) {
                    long at=1000000L+entries.getJSONObject(i).getLong("observed_at_ms");
                    long wallAt=SystemClock.elapsedRealtimeNanos(),cpuAt=Debug.threadCpuTimeNanos();
                    int[] packed=NativeBridge.nativeProcess(session,pixels[i],widths[i],heights[i],widths[i]*4,at,at);
                    assertNotNull(packed);
                    DiagnosticSnapshot raw=DiagnosticSnapshot.parse(NativeBridge.nativeReadDiagnosticSnapshot(session));
                    NativeFrameResult frame=NativeFrameResult.parse(packed,at);
                    if(round>=0) {wall.put(SystemClock.elapsedRealtimeNanos()-wallAt);worker.put(Debug.threadCpuTimeNanos()-cpuAt);}
                    if(round==0) {
                        JSONArray observations=new JSONArray();for(long[] row:raw.observations)observations.put(new JSONArray(row));
                        outputs.put(new JSONObject().put("file",entries.getJSONObject(i).getString("file"))
                                .put("observed_at_ms",at).put("raw_observations",observations)
                                .put("native_packet",new JSONArray(packed))
                                .put("engine_age_ms",raw.engineAtMs-at).put("locator_state",frame.locatorState)
                                .put("locator_score_milli",frame.locatorScoreMilli)
                                .put("minimap_roi_xywh",new JSONArray(new float[]{frame.minimapRoi.left,frame.minimapRoi.top,frame.minimapRoi.width(),frame.minimapRoi.height()}))
                                .put("entity_count",frame.entities.size()).put("relation_state",frame.relation.state)
                                .put("relation_event",frame.relation.event).put("relation_reliable",frame.relation.reliable));
                    }
                }
                if(round>=0) {
                    long elapsedCpu=Process.getElapsedCpuTime()-processAt;
                    process.put(elapsedCpu);
                    long[] after=NativeBridge.nativeReadWorkStats(session);
                    inferences+=after[1]-before[1];reuses+=after[2]-before[2];
                    batches.put(new JSONObject().put("round",round).put("process_cpu_ms",elapsedCpu)
                            .put("wall_ns",SystemClock.elapsedRealtimeNanos()-batchWallAt)
                            .put("inferences",after[1]-before[1]).put("cache_reuses",after[2]-before[2]));
                    stages[0]+=after[7]-before[7];stages[1]+=after[5]-before[5];stages[2]+=after[8]-before[8];
                    stages[3]+=after[3]-before[3]+after[4]-before[4];
                }
            }
            JSONObject result=new JSONObject().put("scope",manifest.getString("scope"))
                    .put("cost_scope","emulator native processing and snapshot parse; excludes capture, JPEG reconstruction, recorder, audio and game heat")
                    .put("manifest_sha256",Match3HoldoutAccuracyInstrumentedTest.sha256(manifestBytes))
                    .put("source_sha256",InstrumentationRegistry.getArguments().getString("match3SourceSha256",""))
                    .put("model_bin_sha256",p.minimapYoloxBinSha256).put("profile_version",p.version)
                    .put("locator_enabled",p.minimapLocatorEnabled).put("input_size",p.yoloxInputSize)
                    .put("independent",false).put("frames",pixels.length).put("observations",outputs)
                    .put("workload","honor-sequence-v2").put("profiling",false)
                    .put("warmup_rounds",6).put("measured_rounds",15)
                    .put("inferences",inferences).put("cache_reuses",reuses)
                    .put("repeats",pixels.length*15).put("batches",batches)
                    .put("wall_ns",wall).put("worker_cpu_ns",worker).put("process_cpu_batches_ms",process)
                    .put("stage_wall_total_us",new JSONArray(stages)).put("stage_order",new JSONArray(new String[]{"prepare","forward","decode","cache_compare_and_store"}))
                    .put("native_heap_start_bytes",memoryStart).put("native_heap_end_bytes",Debug.getNativeHeapAllocatedSize());
            Files.write(new File(dir,"perception.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
        } finally {NativeBridge.nativeDestroy(session);}
    }
}
