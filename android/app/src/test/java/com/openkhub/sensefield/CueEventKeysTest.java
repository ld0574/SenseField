package com.openkhub.sensefield;

import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class CueEventKeysTest {
    @Test public void resetGenerationSeparatesReusedVisionTrack() {
        assertNotEquals(CueEventKeys.visionMemory(0, 7, 1),
                CueEventKeys.visionMemory(1, 7, 1));
    }

    @Test public void resetGenerationSeparatesNativeAndPlayerCues() {
        assertNotEquals(CueEventKeys.nativeCue(0, 1, 2),
                CueEventKeys.nativeCue(1, 1, 2));
        assertNotEquals(CueEventKeys.nativeCue(0, 4, 0),
                CueEventKeys.nativeCue(1, 4, 0));
    }
}
