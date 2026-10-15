package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.Locale;
import org.junit.Test;

public final class OfflineTtsPolicyTest {
    @org.junit.Test public void onlyBoardEngineFailuresAdvanceTheOfflineCandidateList() {
        for (String reason : new String[]{"START_TIMEOUT", "PLAYBACK_TIMEOUT", "SUBMIT_REJECTED", "ENGINE_ERROR:-5"}) {
            org.junit.Assert.assertTrue(OfflineTtsPolicy.recoverBoardFailure("m3live:hint:5", reason));
            org.junit.Assert.assertTrue(OfflineTtsPolicy.recoverBoardFailure("m3:shot", reason));
            org.junit.Assert.assertFalse(OfflineTtsPolicy.recoverBoardFailure("assistant:reply", reason));
        }
        for (String reason : new String[]{"CANCELLED_BOARD_MOTION", "CANCELLED_GOAL_CHANGED", "EXPIRED", "COMPLETED", null})
            org.junit.Assert.assertFalse(OfflineTtsPolicy.recoverBoardFailure("m3live:hint:5", reason));
        org.junit.Assert.assertFalse(OfflineTtsPolicy.recoverBoardFailure(null, "START_TIMEOUT"));
    }
    @Test public void networkAndNotInstalledVoicesCannotBecomeReady() {
        assertFalse(OfflineTtsPolicy.allows(Locale.SIMPLIFIED_CHINESE, true, false));
        assertFalse(OfflineTtsPolicy.allows(Locale.SIMPLIFIED_CHINESE, false, true));
        assertFalse(OfflineTtsPolicy.allows(Locale.ENGLISH, false, false));
        assertFalse(OfflineTtsPolicy.allows(null, false, false));
        assertTrue(OfflineTtsPolicy.allows(Locale.SIMPLIFIED_CHINESE, false, false));
        assertTrue(OfflineTtsPolicy.allows(Locale.TRADITIONAL_CHINESE, false, false));
    }

    @Test public void mandarinMainlandLocaleIsPreferredWithinAllowedVoices() {
        assertTrue(OfflineTtsPolicy.localeOrder(Locale.SIMPLIFIED_CHINESE)
                < OfflineTtsPolicy.localeOrder(Locale.CHINESE));
        assertTrue(OfflineTtsPolicy.localeOrder(Locale.CHINESE)
                < OfflineTtsPolicy.localeOrder(Locale.TRADITIONAL_CHINESE));
    }
}
