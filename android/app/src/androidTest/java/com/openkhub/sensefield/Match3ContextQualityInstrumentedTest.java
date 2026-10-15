package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Real recorder JPEGs from frozen development pixels, not an independent/live accuracy claim. */
public final class Match3ContextQualityInstrumentedTest {
    private static Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static void drain() throws Exception { DiagnosticRecorder.IO.submit(()->{}).get(15,TimeUnit.SECONDS); }
    private static byte[] asset(String name) throws Exception {
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) { return in.readAllBytes(); }
    }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
    private static void report(JSONObject result) {
        Bundle status=new Bundle();status.putString("stream","\nMATCH3_CONTEXT_QUALITY "+result+"\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    private static ByteBuffer rgba(Bitmap bitmap) {
        int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];
        bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());
        ByteBuffer out=ByteBuffer.allocateDirect(pixels.length*4);
        for(int p:pixels)out.put((byte)(p>>16)).put((byte)(p>>8)).put((byte)p).put((byte)255);
        out.rewind();return out;
    }
    private static Bitmap oldContext(Bitmap source) throws Exception {
        int w=source.getWidth(),h=source.getHeight();double scale=Math.min(1,480.0/Math.max(w,h));
        int ow=Math.max(1,(int)Math.round(w*scale)),oh=Math.max(1,(int)Math.round(h*scale));
        int[] input=new int[w*h],out=new int[ow*oh];source.getPixels(input,0,w,0,0,w,h);
        for(int y=0;y<oh;y++)for(int x=0;x<ow;x++)out[y*ow+x]=input[(int)((long)y*h/oh)*w+(int)((long)x*w/ow)];
        Bitmap image=Bitmap.createBitmap(out,ow,oh,Bitmap.Config.ARGB_8888);
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            assertTrue(image.compress(Bitmap.CompressFormat.JPEG,75,bytes));byte[] encoded=bytes.toByteArray();
            return BitmapFactory.decodeByteArray(encoded,0,encoded.length);
        } finally {image.recycle();}
    }
    private static int locks(Bitmap bitmap,JSONArray labels) throws Exception {
        BoardGeometry grid=Match3Sampler.autoDetectGeometry(bitmap);assertNotNull(grid);
        boolean[][] expected=new boolean[grid.rows][grid.cols];
        for(int k=0;k<labels.length();k++) {JSONObject l=labels.getJSONObject(k);expected[l.getInt("row")][l.getInt("col")]=true;}
        try(Match3Sampler sampler=new Match3Sampler(app(),grid)) {
            Match3Position board=sampler.samplePosition(bitmap);int count=0;
            for(int r=0;r<grid.rows;r++)for(int c=0;c<grid.cols;c++) {
                Match3Position.Cell cell=board.cell(r,c);
                if(cell.cover==Match3Position.Cover.LOCK) {assertTrue("no extra LOCK",expected[r][c]);count++;}
                if(expected[r][c])assertNotEquals("vine cannot be swappable",Match3Position.SwapPermission.YES,cell.swapPermission);
            }
            return count;
        }
    }
    @Test public void savedPreAndPostJpegsPreserveReviewedVinesWithinExistingBudget() throws Exception {
        assertFalse(Match3LiveService.isRunning());assertFalse(CaptureService.isRunning());drain();
        JSONObject cases=new JSONObject(new String(asset("red-vine-cases.json"),StandardCharsets.UTF_8));
        assertFalse(cases.getBoolean("independent"));JSONArray frames=cases.getJSONArray("frames");int checked=0;
        for(int k=0;k<frames.length();k++) {
            JSONObject entry=frames.getJSONObject(k);if(entry.getBoolean("low_resolution"))continue;
            byte[] bytes=asset(entry.getString("file"));assertEquals(entry.getString("sha256"),Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));
            Bitmap source=BitmapFactory.decodeByteArray(bytes,0,bytes.length),before=oldContext(source);
            JSONArray labels=entry.getJSONArray("vines");int oldLocks=locks(before,labels);before.recycle();
            long start=SystemClock.elapsedRealtime()-10000;
            DiagnosticRecorder recorder=DiagnosticRecorder.start(app(),UUID.randomUUID().toString(),start,DiagnosticGame.MATCH3);
            recorder.setImagesEnabled(true);ByteBuffer pixels=rgba(source);
            try {
                for(int i=0;i<7;i++) {
                    if(i==4)recorder.match3HintContext(1,false);
                    recorder.frame(NativeFrameResult.empty(),DiagnosticSnapshot.parse(null),pixels,source.getWidth(),source.getHeight(),source.getWidth()*4,
                            start+i*800,start+i*800+10,800);drain();
                }
                recorder.finish("context_quality_fixed_input");drain();
                JSONArray images=new JSONArray();boolean pre=false,post=false;int improved=0;
                for(String line:read(new File(recorder.directory,"events.jsonl")).split("\n")) {
                    JSONObject event=new JSONObject(line);if(!"image".equals(event.getString("type")))continue;
                    String reason=event.getString("reason");if(!"pre_context".equals(reason)&&!"post_context".equals(reason))continue;
                    File file=new File(recorder.directory,event.getString("file"));Bitmap saved=BitmapFactory.decodeFile(file.getAbsolutePath());
                    int afterLocks;
                    try {assertEquals(source.getWidth(),saved.getWidth());assertEquals(source.getHeight(),saved.getHeight());afterLocks=locks(saved,labels);}
                    finally {saved.recycle();}
                    images.put(new JSONObject().put("reason",reason).put("width",event.getInt("output_width"))
                            .put("height",event.getInt("output_height")).put("locks",afterLocks).put("jpeg_bytes",file.length()));
                    if(afterLocks>oldLocks)improved++;
                    if("pre_context".equals(reason))pre=true;else post=true;
                }
                JSONObject summary=new JSONObject(read(new File(recorder.directory,"summary.json")));
                JSONObject stats=summary.getJSONObject("image_context_stats");
                report(new JSONObject().put("file",entry.getString("file")).put("independent",false)
                        .put("source_scope","decoded_frozen_clear_jpeg_not_raw_capture_or_old_context_recovery")
                        .put("old_480_locks",oldLocks).put("expected",labels.length()).put("images",images).put("stats",stats));
                assertTrue(pre);assertTrue(post);assertTrue("improves the frozen low-detail counterexample",improved>0);
                for(int i=0;i<images.length();i++)assertEquals(labels.length(),images.getJSONObject(i).getInt("locks"));
                assertTrue(stats.getLong("max_context_frame_bytes")<=2L*1024*1024);
                assertTrue(stats.getLong("peak_image_frames")<=8);assertTrue(stats.getLong("peak_image_bytes")<=16L*1024*1024);
                assertEquals(0,stats.getLong("pending_image_bytes"));assertEquals(0,stats.getLong("pending_image_frames"));
                assertEquals("",recorder.failure);checked++;
            } finally {recorder.finish("test_cleanup");drain();DiagnosticArchive.delete(recorder.directory);source.recycle();}
        }
        assertEquals(2,checked);
    }
    @Test public void squareMatch3ContextShrinksBeforeAllocationWhileHonorRetainsItsResolution() throws Exception {
        assertFalse(Match3LiveService.isRunning());assertFalse(CaptureService.isRunning());drain();
        for(DiagnosticGame game:new DiagnosticGame[]{DiagnosticGame.MATCH3,DiagnosticGame.HONOR}) {
            int width=game==DiagnosticGame.MATCH3?1024:1600,height=game==DiagnosticGame.MATCH3?1024:900;
            ByteBuffer pixels=ByteBuffer.allocateDirect(width*height*4);
            for(int i=0;i<width*height;i++)pixels.putInt(0x336699ff);pixels.rewind();
            long start=SystemClock.elapsedRealtime()-10000;
            DiagnosticRecorder recorder=DiagnosticRecorder.start(app(),UUID.randomUUID().toString(),start,game);recorder.setImagesEnabled(true);
            try {
                recorder.frame(NativeFrameResult.empty(),DiagnosticSnapshot.parse(null),pixels,width,height,width*4,start,start+10,800);drain();
                // The first frame already has its periodic JPEG, and the trigger gets a clear
                // JPEG. Cache a separate middle frame so deduplication leaves a pre image.
                recorder.frame(NativeFrameResult.empty(),DiagnosticSnapshot.parse(null),pixels,width,height,width*4,start+800,start+810,800);drain();
                if(game==DiagnosticGame.MATCH3)recorder.match3HintContext(1,false);
                // Honor needs no automatic trigger: a manual issue exercises its cached screen.
                else recorder.markIssue();
                recorder.frame(NativeFrameResult.empty(),DiagnosticSnapshot.parse(null),pixels,width,height,width*4,start+1600,start+1610,800);drain();
                recorder.finish("context_shape_test");drain();int pre=0;
                for(String line:read(new File(recorder.directory,"events.jsonl")).split("\n")) {
                    JSONObject event=new JSONObject(line);if(!"image".equals(event.getString("type"))||!"pre_context".equals(event.getString("reason")))continue;
                    assertEquals(game==DiagnosticGame.MATCH3?724:480,event.getInt("output_width"));
                    assertEquals(game==DiagnosticGame.MATCH3?724:270,event.getInt("output_height"));pre++;
                }
                assertTrue(pre>0);JSONObject stats=new JSONObject(read(new File(recorder.directory,"summary.json"))).getJSONObject("image_context_stats");
                assertTrue(stats.getLong("max_context_frame_bytes")<=2L*1024*1024);assertEquals("",recorder.failure);
            } finally {recorder.finish("test_cleanup");drain();DiagnosticArchive.delete(recorder.directory);}
        }
    }
}
