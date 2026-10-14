package com.openkhub.sensefield;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AssistantDecisionFrameGuardTest {
    @Test public void tenSecondDecisionNeedsRecentFramesAndKeepsItsOriginalExpiry() {
        AssistantDecisionFrameGuard guard = new AssistantDecisionFrameGuard(7, 1000);
        AssistantReply reply = new AssistantReply(1, "t", 1, 1000, "hud", "补对抗路", false,
                false, "hero_selection", guard);
        assertFalse(reply.freshAt(11000));
        guard.observe(7, 11000);
        assertTrue(reply.freshAt(11000));
        guard.observe(7, 21000);
        assertTrue(reply.freshAt(21000));
        guard.observe(7, 21001);
        assertFalse(reply.freshAt(21001));
    }
    @Test public void confirmedPageChangePermanentlyRevokesTheCapturedDecision() {
        AssistantDecisionFrameGuard guard = new AssistantDecisionFrameGuard(0, 1000);
        guard.observe(-1L, 1500);
        assertTrue(guard.validAt(1500));
        guard.observe(-1L, 1600);
        assertFalse(guard.validAt(1600));
        guard.observe(0, 1700);
        assertFalse(guard.validAt(1700));
    }
    @Test public void generalGameDecisionUsesTheSameCaptureExpiryAndPageGuard() {
        AssistantDecisionFrameGuard guard = new AssistantDecisionFrameGuard(0, 1000);
        AssistantReply reply = new AssistantReply(1, "t", 1, 1000, "hud", "先升级二技能", false,
                false, "game_decision", guard);
        assertFalse(reply.freshAt(11000));
        guard.observe(0, 11000);
        assertTrue(reply.freshAt(11000));
        assertEquals(21000, reply.expiresAtMs());
        guard.observe(-1L, 11100);
        guard.observe(-1L, 11200);
        assertFalse(reply.freshAt(11200));
    }
    @Test public void oneTransientChangeAndMinorChangesDoNotRevokeDecision() {
        AssistantDecisionFrameGuard guard = new AssistantDecisionFrameGuard(0, 1000);
        guard.observe(-1L, 1500);
        guard.observe(3, 1600);
        guard.observe(7, 1700);
        assertTrue(guard.validAt(1700));
    }
    @Test public void duplicateOldFramesAndTimeReversalCannotExtendValidity() {
        AssistantDecisionFrameGuard guard = new AssistantDecisionFrameGuard(0, 1000);
        guard.observe(0, 999);
        assertFalse(guard.validAt(999));
        guard.observe(0, 1000);
        assertFalse(guard.validAt(2001));
    }
    @Test public void UnknownSceneAndUnmarkedCombatKeepTheFiveSecondBudget() {
        AssistantReply reply = new AssistantReply(1, "t", 1, 1000, "hud", "战斗信息", false,
                false, "combat", new AssistantDecisionFrameGuard(0, 1000));
        assertTrue(reply.freshAt(6000));
        assertFalse(reply.freshAt(6001));
        AssistantReply unguarded = new AssistantReply(1, "t", 1, 1000, "hud", "选择", false,
                false, "equipment", null);
        assertFalse(unguarded.freshAt(2000));
    }
    @Test public void historyIsOnlyRequestedForAnExplicitComparison() {
        assertFalse(AssistantController.needsVisualHistory("接下来如何出装"));
        assertFalse(AssistantController.needsVisualHistory("我擅长辅助，选哪个英雄？"));
        assertFalse(AssistantController.needsVisualHistory(AssistantController.SCREEN_QUESTION));
        assertTrue(AssistantController.needsVisualHistory("和刚才的阵容相比有什么变化？"));
        assertTrue(AssistantController.needsVisualHistory("Compare with the previous screen"));
    }
    @Test public void intentionalSnapshotSurvivesPageChangeButExpiresFromOriginalCapture() {
        AssistantDecisionFrameGuard guard = new AssistantDecisionFrameGuard(0, 1000);
        guard.observe(-1L, 1500);
        guard.observe(-1L, 1600);
        assertFalse(guard.validAt(11000));
        AssistantReply reply = new AssistantReply(1, "t", 1, 1000, "hud", "截图右侧有敌方标记。", false,
                false, "game_decision", guard, true);
        assertTrue(reply.snapshotRead);
        assertFalse(reply.freshAt(999));
        assertTrue(reply.freshAt(11000));
        assertTrue(reply.freshAt(21000));
        assertFalse(reply.freshAt(21001));
        assertEquals("约10秒前截图显示，截图右侧有敌方标记。", reply.spokenAnswerAt(11000));
    }
    @Test public void snapshotFlagDoesNotExtendAutomaticUncertainOrStaticAnswers() {
        AssistantReply automatic = new AssistantReply(1, "t", 1, 1000, "hud", "敌方标记。", false,
                true, "", null, true);
        AssistantReply uncertain = new AssistantReply(1, "t", 1, 1000, "hud", "看不清。", true,
                false, "", null, true);
        AssistantReply menu = new AssistantReply(1, "t", 1, 1000, "ui_text", "开始。", false,
                false, "", null, true);
        assertFalse(automatic.snapshotRead);
        assertFalse(uncertain.snapshotRead);
        assertFalse(menu.snapshotRead);
        assertFalse(automatic.freshAt(6001));
        assertFalse(uncertain.freshAt(6001));
        assertFalse(menu.freshAt(16001));
    }
    @Test public void repeatingSnapshotRefreshesItsCaptionWithoutExtendingItsLifetime() {
        AssistantReply original = new AssistantReply(1, "t", 1, 1000, "hud", "截图里的标记。", false,
                false, "", null, true);
        assertEquals("约6秒前截图显示，截图里的标记。", original.spokenAnswerAt(7000));
        AssistantReply repeated = new AssistantReply(2, "repeat", original.frameId, original.capturedAtMs,
                original.kind, original.answer, original.uncertain, false,
                original.decisionScene, original.decisionGuard, original.snapshotRead);
        assertEquals("约12秒前截图显示，截图里的标记。", repeated.spokenAnswerAt(13000));
        assertEquals(original.expiresAtMs(), repeated.expiresAtMs());
        assertFalse(repeated.freshAt(21001));
        assertEquals("约0秒前截图显示，截图里的标记。", original.spokenAnswerAt(1500));
    }
}
