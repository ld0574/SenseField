package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CueSettingsTest {
    @Test public void visionMemoryRequiresExplicitOptInAndEnabledCategory() {
        assertFalse(CueSettings.visionMemoryDispatchEnabled(false, true));
        assertFalse(CueSettings.visionMemoryDispatchEnabled(true, false));
        assertTrue(CueSettings.visionMemoryDispatchEnabled(true, true));
    }

    @Test public void compactPresetRestrictsVisionMemoryToVisualChannel() {
        assertEquals(CueRequest.CHANNEL_VISUAL,
                CueSettings.channelsForCategory(15, CueSettings.PRESET_COMPACT,
                        CueRequest.Category.VISION_MEMORY));
        int compactGlobalChannels = CueRequest.CHANNEL_SPEECH
                | CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL;
        assertEquals(compactGlobalChannels,
                CueSettings.channelsForCategory(compactGlobalChannels, CueSettings.PRESET_COMPACT,
                        CueRequest.Category.PLAYER_STATE));
        assertEquals(15,
                CueSettings.channelsForCategory(15, CueSettings.PRESET_STANDARD,
                        CueRequest.Category.VISION_MEMORY));
    }
}
