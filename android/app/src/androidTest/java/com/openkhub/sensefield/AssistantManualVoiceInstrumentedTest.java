package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Emulator microphone silence only: no speech model, network, audio file or host microphone. */
@RunWith(AndroidJUnit4.class)
public final class AssistantManualVoiceInstrumentedTest {
    @Test public void speakerNeedsAGestureAndSilenceClosesWithoutAQuestion() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<VoiceInputSafetyPolicy.InputState> state = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger(), samples = new AtomicInteger();
        List<String> ends = Collections.synchronizedList(new ArrayList<>());
        AssistantVoiceInput input = new AssistantVoiceInput(context, new AssistantVoiceInput.Listener() {
            public void started() { starts.incrementAndGet(); }
            public void pcm(short[] pcm) { samples.addAndGet(pcm.length); }
            public void ended() { fail("silent emulator input must not produce an ASR utterance"); }
            public void inputStateChanged(VoiceInputSafetyPolicy.InputState value) { state.set(value); }
            public void unavailable(String value) { ends.add("unavailable"); }
            public void manualFinished(long token, String reason, boolean hadSpeech) {
                ends.add(token + ":" + reason + ":" + hadSpeech);
            }
        });
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(input::start);
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (state.get() != VoiceInputSafetyPolicy.InputState.ROUTE_UNVERIFIED
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
            assertEquals("default speaker must keep continuous voice paused",
                    VoiceInputSafetyPolicy.InputState.ROUTE_UNVERIFIED, state.get());
            assertEquals(0, starts.get());
            assertEquals(0, samples.get());
            assertTrue("the deliberate window must be admitted on speaker", input.beginManual(1));
            deadline = SystemClock.elapsedRealtime() + 6_000;
            while (ends.isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
            assertEquals(Collections.singletonList("1:no_speech:false"), ends);
            assertEquals(0, starts.get());
            assertEquals(0, samples.get());

            assertTrue(input.beginManual(2));
            input.finishManual(2);
            assertEquals("2:button:false", ends.get(1));
            assertTrue(input.beginManual(3));
            input.pause(true);
            assertEquals("3:paused:false", ends.get(2));
            assertFalse("user pause applies to the manual entry too", input.beginManual(4));
        } finally { input.close(); }
    }
}
