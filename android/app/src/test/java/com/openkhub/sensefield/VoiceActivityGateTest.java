package com.openkhub.sensefield;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Contract tests for the local VAD gate.
 *
 * <p>These exist because AAC plans to reuse this gate's noise-floor reading as its
 * environment classifier. Until this class had tests, that reuse would have been built on a
 * production component with no regression net.
 */
public final class VoiceActivityGateTest {
    private static final int FRAME = 160;

    /** Loud enough to clear the absolute floor of 350 and the initial noise*3.2 = 256. */
    private static short[] loud() {
        short[] samples = new short[FRAME];
        java.util.Arrays.fill(samples, (short) 2000);
        return samples;
    }

    private static short[] silent() {
        return new short[FRAME];
    }

    /** A frame loud enough to clear the floor but flagged as non-speech by the classifier. */
    private static short[] loudButNotSpeech() {
        return loud();
    }

    private static final class Recorder implements VoiceActivityGate.Output {
        final List<String> events = new ArrayList<>();
        final ArrayDeque<short[]> played = new ArrayDeque<>();
        int started;
        int ended;

        @Override public void started() {
            started++;
            events.add("started");
        }

        @Override public void pcm(short[] samples) {
            played.addLast(samples);
            events.add("pcm");
        }

        @Override public void ended() {
            ended++;
            events.add("ended");
        }
    }

    private static VoiceActivityGate gate(Recorder recorder) {
        return new VoiceActivityGate(recorder);
    }

    @Test public void rejectsFramesThatAreNotTenMilliseconds() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        try {
            gate.accept(new short[FRAME + 1], true);
            fail("a non-10ms frame must be rejected, not silently mis-sized");
        } catch (IllegalArgumentException expected) {
            // The contract is explicit: 10 ms at 16 kHz is exactly 160 samples.
        }
        assertEquals(0, recorder.started);
    }

    @Test public void silenceNeverStartsAndNeverStreamsPcm() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        for (int i = 0; i < 100; i++) gate.accept(silent(), true);

        assertFalse("silence must not open the gate", gate.active());
        assertEquals(0, recorder.started);
        assertEquals(0, recorder.ended);
        assertEquals("nothing may stream while inactive", 0, recorder.played.size());
    }

    @Test public void needsFifteenConsecutiveVoicedFramesToOpen() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        for (int i = 0; i < 14; i++) gate.accept(loud(), true);
        assertFalse("fourteen voiced frames are not enough", gate.active());
        assertEquals(0, recorder.started);

        gate.accept(loud(), true);
        assertTrue("the fifteenth voiced frame opens the gate", gate.active());
        assertEquals(1, recorder.started);
    }

    @Test public void aSingleQuietFrameResetsTheVoicedRun() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        for (int i = 0; i < 14; i++) gate.accept(loud(), true);
        gate.accept(silent(), true);
        for (int i = 0; i < 14; i++) gate.accept(loud(), true);

        assertFalse("the run was broken, so fourteen more are not enough", gate.active());
        gate.accept(loud(), true);
        assertTrue("a fresh unbroken run of fifteen opens it", gate.active());
    }

    @Test public void openingReplaysTheBoundedPreRoll() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        for (int i = 0; i < 15; i++) gate.accept(loud(), true);

        assertEquals(1, recorder.started);
        assertEquals("the pre-roll must be replayed on open", 15, recorder.played.size());
        assertEquals("started must precede every replayed frame",
                0, recorder.events.indexOf("started"));
        assertEquals("pcm", recorder.events.get(1));
    }

    @Test public void preRollNeverGrowsPastThirtyFrames() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        // Below the floor the gate stays closed, so the pre-roll keeps only its tail.
        for (int i = 0; i < 60; i++) gate.accept(silent(), true);
        for (int i = 0; i < 15; i++) gate.accept(loud(), true);

        assertEquals(1, recorder.started);
        assertEquals("the pre-roll is bounded at 30 frames", 30, recorder.played.size());
    }

    @Test public void theClassifierCanRefuseLoudNonSpeech() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        for (int i = 0; i < 50; i++) gate.accept(loudButNotSpeech(), false);

        assertFalse("loud but non-speech must not open the gate", gate.active());
        assertEquals(0, recorder.started);
    }

    @Test public void fortyFiveQuietFramesCloseTheGate() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        for (int i = 0; i < 15; i++) gate.accept(loud(), true);
        assertTrue(gate.active());

        for (int i = 0; i < 44; i++) gate.accept(silent(), true);
        assertTrue("forty-four quiet frames are not enough to close", gate.active());

        gate.accept(silent(), true);
        assertFalse("the forty-fifth quiet frame closes the gate", gate.active());
        assertEquals(1, recorder.ended);
    }

    @Test public void anActiveGateStreamsEveryFrameItReceives() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        for (int i = 0; i < 15; i++) gate.accept(loud(), true);
        int afterOpen = recorder.played.size();
        for (int i = 0; i < 10; i++) gate.accept(loud(), true);

        assertEquals("open frames stream straight through",
                afterOpen + 10, recorder.played.size());
    }

    @Test public void resetClearsAnOpenGateAndItsCounters() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        for (int i = 0; i < 15; i++) gate.accept(loud(), true);
        assertTrue(gate.active());

        gate.reset();
        assertFalse(gate.active());
        assertEquals(0, recorder.ended);

        // After a reset the gate needs a full fresh run before it opens again.
        for (int i = 0; i < 14; i++) gate.accept(loud(), true);
        assertFalse("reset must clear the voiced-frame run too", gate.active());
    }

    @Test public void aLongUtteranceIsCappedSoItCannotRunForever() {
        Recorder recorder = new Recorder();
        VoiceActivityGate gate = gate(recorder);

        for (int i = 0; i < 15; i++) gate.accept(loud(), true);
        assertTrue(gate.active());

        // The opening run of 15 frames happens before the active counter starts, so the cap
        // of 1500 active frames lands on the 1515th accept, not the 1500th.
        for (int i = 0; i < 1499; i++) gate.accept(loud(), true);
        assertTrue("1499 active frames are still inside the cap", gate.active());

        gate.accept(loud(), true);
        assertFalse("the 1500th active frame closes the gate", gate.active());
        assertEquals(1, recorder.ended);
    }
}
