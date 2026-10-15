package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

public class ManualVoiceWindowTest {
    @Test public void speakerWindowDoesNotGrantContinuousVoiceOrMicOwnership() {
        assertEquals(VoiceInputSafetyPolicy.InputState.ROUTE_UNVERIFIED,
                VoiceInputSafetyPolicy.inputState(false, false, false, false));
        assertTrue(VoiceInputSafetyPolicy.manualAllowed(VoiceInputSafetyPolicy.InputState.ROUTE_UNVERIFIED));
        assertTrue(VoiceInputSafetyPolicy.manualAllowed(VoiceInputSafetyPolicy.InputState.READY));
        for (VoiceInputSafetyPolicy.InputState blocked : new VoiceInputSafetyPolicy.InputState[]{
                VoiceInputSafetyPolicy.InputState.PAUSED, VoiceInputSafetyPolicy.InputState.CLIENT_SILENCED,
                VoiceInputSafetyPolicy.InputState.OTHER_RECORDING, VoiceInputSafetyPolicy.InputState.UNAVAILABLE})
            assertFalse(blocked.name(), VoiceInputSafetyPolicy.manualAllowed(blocked));
        assertFalse(VoiceInputSafetyPolicy.manualAllowed(null));
    }

    @Test public void silenceAndLongSpeechHaveSeparateBoundedDeadlines() {
        ManualVoiceWindow window = new ManualVoiceWindow();
        assertTrue(window.begin(1, 1_000));
        assertEquals("", window.dueReason(1, 5_999));
        assertEquals("no_speech", window.dueReason(1, 6_000));
        assertFalse(window.end(1).hadSpeech);

        assertTrue(window.begin(2, 8_000));
        window.speechStarted();
        assertEquals("", window.dueReason(2, 13_000));
        assertEquals("", window.dueReason(2, 22_999));
        assertEquals("time_limit", window.dueReason(2, 23_000));
        assertTrue(window.end(2).hadSpeech);
    }

    @Test public void delayedTimeoutAndDuplicateStopCannotCloseANewerQuestion() {
        ManualVoiceWindow window = new ManualVoiceWindow();
        assertTrue(window.begin(1, 0));
        window.speechStarted();
        assertFalse(window.begin(2, 100));
        assertTrue(window.end(1).hadSpeech);
        assertNull(window.end(1));
        assertTrue(window.begin(2, 1_000));
        assertEquals("", window.dueReason(1, 15_000));
        assertNull(window.end(1));
        assertTrue(window.active());
        assertFalse("a new window must not inherit prior speech", window.end(2).hadSpeech);
        assertFalse(window.active());
    }
}
