package com.openkhub.sensefield;

/** Applies the saved cue preferences to live Match3 announcements, which use speech only. */
final class Match3LiveCuePolicy implements CueDispatcher.Policy {
    static final int REQUESTED_CHANNELS = CueRequest.CHANNEL_SPEECH;

    private final CueDispatcher.Policy storedSettings;

    Match3LiveCuePolicy(CueDispatcher.Policy storedSettings) {
        this.storedSettings = storedSettings;
    }

    @Override public boolean categoryEnabled(CueRequest.Category category) {
        return storedSettings.categoryEnabled(category);
    }

    @Override public int enabledChannels() {
        return storedSettings.enabledChannels() & CueRequest.CHANNEL_SPEECH;
    }

    @Override public int enabledChannels(CueRequest.Category category) {
        return storedSettings.enabledChannels(category) & CueRequest.CHANNEL_SPEECH;
    }

    @Override public long dedupeWindowMs(CueRequest.Category category) {
        return storedSettings.dedupeWindowMs(category);
    }
}
