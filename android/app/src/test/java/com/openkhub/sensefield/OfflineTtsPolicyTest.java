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

    @Test public void aSingleSynthesisFailureRebindsTheActualEngineBeforeFallback() {
        OfflineTtsPolicy.BoardRecovery recovery = new OfflineTtsPolicy.BoardRecovery();
        assertEquals(OfflineTtsPolicy.BoardRecovery.Action.RECONNECT,
                recovery.onFailure("org.nobody.multitts", "m3live:hint:1", "ENGINE_ERROR:-3"));
        assertEquals(OfflineTtsPolicy.BoardRecovery.Action.NEXT_ENGINE,
                recovery.onFailure("org.nobody.multitts", "m3live:hint:2", "ENGINE_ERROR:-3"));
        assertEquals(OfflineTtsPolicy.BoardRecovery.Action.NEXT_ENGINE,
                recovery.onFailure("org.nobody.multitts", "m3live:hint:3", "ENGINE_ERROR:-3"));
    }

    @Test public void staleAndUnrelatedFailuresCannotSpendTheReconnection() {
        OfflineTtsPolicy.BoardRecovery recovery = new OfflineTtsPolicy.BoardRecovery();
        for (String reason : new String[]{"COMPLETED", "CANCELLED_BOARD_MOTION", "EXPIRED", null})
            assertEquals(OfflineTtsPolicy.BoardRecovery.Action.NONE,
                    recovery.onFailure("selected", "m3live:hint:1", reason));
        assertEquals(OfflineTtsPolicy.BoardRecovery.Action.NONE,
                recovery.onFailure("selected", "assistant:reply", "ENGINE_ERROR:-3"));
        assertEquals(OfflineTtsPolicy.BoardRecovery.Action.RECONNECT,
                recovery.onFailure("selected", "m3live:hint:2", "ENGINE_ERROR:-3"));
    }

    @Test public void aBrokenOrUnidentifiedEngineStillAdvancesWithoutAnExtraWait() {
        for (String reason : new String[]{"START_TIMEOUT", "PLAYBACK_TIMEOUT", "SUBMIT_REJECTED",
                "ENGINE_ERROR:-4", "ENGINE_ERROR:-5"}) {
            assertEquals(OfflineTtsPolicy.BoardRecovery.Action.NEXT_ENGINE,
                    new OfflineTtsPolicy.BoardRecovery().onFailure("selected", "m3:shot", reason));
        }
        for (String engine : new String[]{null, ""})
            assertEquals(OfflineTtsPolicy.BoardRecovery.Action.NEXT_ENGINE,
                    new OfflineTtsPolicy.BoardRecovery().onFailure(engine, "m3:shot", "ENGINE_ERROR:-3"));
    }

    @Test public void reconnectionIsBoundedForEachEngineAndEachPlayer() {
        OfflineTtsPolicy.BoardRecovery recovery = new OfflineTtsPolicy.BoardRecovery();
        assertEquals(OfflineTtsPolicy.BoardRecovery.Action.RECONNECT,
                recovery.onFailure("first", "m3live:hint:1", "ENGINE_ERROR:-3"));
        assertEquals(OfflineTtsPolicy.BoardRecovery.Action.RECONNECT,
                recovery.onFailure("backup", "m3live:hint:2", "ENGINE_ERROR:-3"));
        assertEquals(OfflineTtsPolicy.BoardRecovery.Action.NEXT_ENGINE,
                recovery.onFailure("first", "m3live:hint:3", "ENGINE_ERROR:-3"));
        assertEquals(OfflineTtsPolicy.BoardRecovery.Action.RECONNECT,
                new OfflineTtsPolicy.BoardRecovery().onFailure("first", "m3live:hint:1", "ENGINE_ERROR:-3"));
    }
}
