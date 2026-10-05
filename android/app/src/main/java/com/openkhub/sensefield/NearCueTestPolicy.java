package com.openkhub.sensefield;

/** Pure channel gates for the near-cue test action. */
final class NearCueTestPolicy {
    static final int AUDIO_CHANNELS = CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH;
    private static final int TEST_CHANNELS = AUDIO_CHANNELS | CueRequest.CHANNEL_HAPTIC;

    private NearCueTestPolicy() {}

    static boolean audioAvailable(int mediaVolume, boolean mediaMuted, int appVolume) {
        return mediaVolume > 0 && !mediaMuted && appVolume > 0;
    }

    /** Keep configured haptics when muted audio makes the audio channels unusable. */
    static int channelsForTest(int configuredNearChannels, boolean audioAvailable) {
        int channels = configuredNearChannels & TEST_CHANNELS;
        return audioAvailable ? channels : channels & CueRequest.CHANNEL_HAPTIC;
    }
}
