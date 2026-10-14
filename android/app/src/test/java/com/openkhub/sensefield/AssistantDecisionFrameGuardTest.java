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
}
