package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class NearCueTestPolicyTest {
    private static final int NEAR_AUDIO_AND_HAPTIC = CueRequest.CHANNEL_TONE
            | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC;

    @Test public void usableAudioKeepsCurrentNearAudioAndHapticChannelsTogether() {
        assertEquals(NEAR_AUDIO_AND_HAPTIC,
                NearCueTestPolicy.channelsForTest(NEAR_AUDIO_AND_HAPTIC, true));
    }

    @Test public void mutedOrZeroVolumeAudioLeavesEnabledHapticAvailable() {
        assertFalse(NearCueTestPolicy.audioAvailable(5, true, 45));
        assertFalse(NearCueTestPolicy.audioAvailable(5, false, 0));
        assertFalse(NearCueTestPolicy.audioAvailable(0, false, 45));
        assertEquals(CueRequest.CHANNEL_HAPTIC,
                NearCueTestPolicy.channelsForTest(NEAR_AUDIO_AND_HAPTIC, false));
    }

    @Test public void hapticOnlyTestDoesNotNeedAudioVolume() {
        assertEquals(CueRequest.CHANNEL_HAPTIC,
                NearCueTestPolicy.channelsForTest(CueRequest.CHANNEL_HAPTIC, false));
    }

    @Test public void mutedAudioOnlyTestHasNoUsableChannel() {
        assertEquals(0, NearCueTestPolicy.channelsForTest(
                CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH, false));
    }
}
