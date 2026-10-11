package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Actual recorder/window/IO integration on an isolated emulator, never an acoustic or touch claim. */
public final class Match3HintContextInstrumentedTest {
    private static void drain() throws Exception { DiagnosticRecorder.IO.submit(()->{}).get(10,TimeUnit.SECONDS); }
    @Test public void hintContextUsesTheExistingGapBudgetAndKeepsHumanActionUnknown() throws Exception {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        assertFalse(Match3LiveService.isRunning());assertFalse(CaptureService.isRunning());drain();
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();long start=SystemClock.elapsedRealtime()-10000;
        DiagnosticRecorder recorder=DiagnosticRecorder.start(context,UUID.randomUUID().toString(),start,DiagnosticGame.MATCH3);
        recorder.setImagesEnabled(true);
        ByteBuffer rgba=ByteBuffer.allocateDirect(256*576*4);while(rgba.hasRemaining())rgba.putInt(0xff335577);rgba.rewind();
        try {
            for(int i=0;i<7;i++) {
                if(i==4)recorder.match3HintContext(7,false);
                recorder.frame(NativeFrameResult.empty(),DiagnosticSnapshot.parse(null),rgba,256,576,256*4,
                        start+i*800,start+i*800+10,800);drain();
            }
            recorder.match3HintContext(7,true);
            recorder.frame(NativeFrameResult.empty(),DiagnosticSnapshot.parse(null),rgba,256,576,256*4,start+8800,start+8810,800);
            recorder.finish("match3_hint_context_test");drain();
            String text=new String(Files.readAllBytes(new File(recorder.directory,"events.jsonl").toPath()),StandardCharsets.UTF_8);
            int links=0;boolean accepted=false,limited=false,pre=false,post=false;
            for(String line:text.split("\n")) {
                if(line.isEmpty())continue;JSONObject e=new JSONObject(line);
                assertNotEquals("automatic hint capture must not mark a human issue","user_marker",e.getString("type"));
                if("Match3HintImageContext".equals(e.getString("type"))) {
                    JSONObject data=e.getJSONObject("data");assertEquals(7,data.getInt("hint_revision"));
                    assertEquals("not_observed",data.getString("action_evidence"));links++;
                    if("ACCEPTED".equals(data.getString("status")))accepted=true;
                    if("RATE_LIMITED".equals(data.getString("status")))limited=true;
                }
                if("image".equals(e.getString("type"))) {
                    if("pre_context".equals(e.getString("reason")))pre=true;
                    if("post_context".equals(e.getString("reason")))post=true;
                }
            }
            assertEquals(2,links);assertTrue(accepted);assertTrue(limited);assertTrue(pre);assertTrue(post);
            assertTrue(recorder.failure,recorder.failure.isEmpty());
        } finally { if(!recorder.finished)recorder.finish("test_cleanup");drain();DiagnosticArchive.delete(recorder.directory); }
    }
}
