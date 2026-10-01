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

    @Test public void presetsKeepVisionMemoryQuietByDefault() {
        assertEquals(CueRequest.CHANNEL_VISUAL,
                CueSettings.channelsForCategory(15, CueSettings.PRESET_COMPACT,
                        CueRequest.Category.VISION_MEMORY));
        assertEquals(CueRequest.CHANNEL_VISUAL | CueRequest.CHANNEL_TONE,
                CueSettings.channelsForCategory(15, CueSettings.PRESET_STANDARD,
                        CueRequest.Category.VISION_MEMORY));
        assertEquals(CueRequest.CHANNEL_VISUAL | CueRequest.CHANNEL_TONE
                        | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC,
                CueSettings.channelsForCategory(15, CueSettings.PRESET_DETAILED,
                        CueRequest.Category.VISION_MEMORY));
        assertEquals(CueRequest.CHANNEL_TONE,
                CueSettings.channelsForCategory(15, CueSettings.PRESET_STANDARD,
                        CueRequest.Category.PERIPHERAL_THREAT));
        assertEquals(CueRequest.CHANNEL_TONE,
                CueSettings.channelsForCategory(15, CueSettings.PRESET_DETAILED,
                        CueRequest.Category.PERIPHERAL_THREAT));
        int compactGlobalChannels = CueRequest.CHANNEL_SPEECH
                | CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL;
        assertEquals(compactGlobalChannels,
                CueSettings.channelsForCategory(compactGlobalChannels, CueSettings.PRESET_COMPACT,
                        CueRequest.Category.PLAYER_STATE));
    }

    @Test public void newInstallVisualMemoryDefaultIsOn() {
        assertTrue(GameProfile.DEFAULT_VISION_MEMORY);
    }

    @Test public void nearZoneChannelsFollowPresetAndSurviveCompactToneSwitch() {
        int compactGlobalChannels = CueRequest.CHANNEL_SPEECH
                | CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL;
        assertEquals(CueRequest.CHANNEL_TONE,
                CueSettings.channelsForCategory(compactGlobalChannels,
                        CueSettings.PRESET_COMPACT, CueRequest.Category.NEAR_ZONE));
        int voiced = CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH
                | CueRequest.CHANNEL_HAPTIC;
        assertEquals(voiced, CueSettings.channelsForCategory(15,
                CueSettings.PRESET_STANDARD, CueRequest.Category.NEAR_ZONE));
        assertEquals(voiced, CueSettings.channelsForCategory(15,
                CueSettings.PRESET_DETAILED, CueRequest.Category.NEAR_ZONE));
        // Global switches still apply outside the compact special case, and
        // the overlay is never requested by near-zone cues.
        assertEquals(CueRequest.CHANNEL_SPEECH, CueSettings.channelsForCategory(
                CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_VISUAL,
                CueSettings.PRESET_STANDARD, CueRequest.Category.NEAR_ZONE));
    }

}
