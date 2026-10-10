package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class Match3FlywheelInstrumentedTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private void isolated() {
        String hardware=android.os.Build.HARDWARE;assertTrue(hardware.contains("ranchu") || hardware.contains("goldfish"));
        assertFalse(Match3LiveService.isRunning());assertFalse(CaptureService.isRunning());
    }
    private void drain() throws Exception { DiagnosticRecorder.IO.submit(()->{}).get(5,TimeUnit.SECONDS); }
    @Test public void descriptorsRespectDisabledImagesBusyBudgetAndGenerationCancellation() throws Exception {
        isolated();DiagnosticRecorder recorder=DiagnosticRecorder.start(context(),UUID.randomUUID().toString(),SystemClock.elapsedRealtime(),DiagnosticGame.MATCH3);
        CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
        try {
            drain();recorder.setImagesEnabled(false);
            assertFalse(recorder.harvestEnabled());assertFalse(recorder.flywheelSample(new JSONObject().put("id","disabled")));
            recorder.setImagesEnabled(true);
            DiagnosticRecorder.IO.execute(()->{entered.countDown();try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            int accepted=0;
            for(int i=0;i<20;i++)if(recorder.flywheelSample(new JSONObject().put("id","cancelled-"+i)))accepted++;
            assertTrue("shared queue budget is finite",accepted>0 && accepted<20);
            recorder.setImagesEnabled(false);release.countDown();drain();
            recorder.setImagesEnabled(true);assertTrue(recorder.flywheelSample(new JSONObject().put("id","saved")));drain();
            recorder.finish("flywheel_test");drain();
            String log=new String(Files.readAllBytes(new File(recorder.directory,"events.jsonl").toPath()),StandardCharsets.UTF_8);
            assertFalse(log.contains("cancelled-"));assertFalse(log.contains("\"id\":\"disabled\""));
            assertTrue(log.contains("Match3FlywheelSample"));assertTrue(log.contains("\"id\":\"saved\""));
            assertFalse(recorder.flywheelSample(new JSONObject().put("id","after-finish")));
        } finally {
            release.countDown();recorder.finish("cleanup");drain();DiagnosticArchive.delete(recorder.directory);
            if(DiagnosticRecorder.current==recorder)DiagnosticRecorder.current=null;
        }
    }
    @Test public void nativeStableFramesHarvestWholeCookieWithoutGrantingRulesAndRetryBusyWriter() throws Exception {
        isolated();Bitmap frame;
        try(java.io.InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("task-feedback-1.png")) {
            frame=android.graphics.BitmapFactory.decodeStream(in);
        }
        assertNotNull(frame);BoardGeometry geometry=Match3Sampler.autoDetectGeometry(frame);assertNotNull(geometry);
        Match3FlywheelHarvest collector=new Match3FlywheelHarvest();List<JSONObject> saved=new ArrayList<>();
        try(Match3Sampler sampler=new Match3Sampler(context(),geometry)) {
            Match3HudReader hud=new Match3HudReader(context());
            try {
                for(int i=0;i<3;i++) {
                    long at=100+800L*i;Match3Position board=sampler.samplePosition(frame,at);hud.read(frame,geometry,at);
                    collector.collect(sampler,board,hud,at,data->false);
                }
                for(int i=3;i<12;i++) {
                    long at=100+800L*i;Match3Position board=sampler.samplePosition(frame,at);int before=saved.size();
                    collector.collect(sampler,board,hud,at,data->{saved.add(data);return true;});
                    assertTrue("no speculative serialization beyond the shared queue capacity",saved.size()-before<=DiagnosticRecorder.MAX_IMAGE_JOBS);
                }
                int objects=0;for(JSONObject data:saved)if(data.getString("role").equals("object")) {
                    objects++;assertEquals(256,data.getJSONArray("pixels").length());assertFalse(data.getBoolean("rule_supported"));
                    assertTrue(data.getInt("row")>=0);assertTrue(data.getInt("col")>=0);
                }
                assertTrue("real cookie envelopes survived rejected writes",objects>0);
            } finally { hud.close(); }
        } finally {frame.recycle();}
    }
}
