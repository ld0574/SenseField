package com.openkhub.sensefield;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Hand-written expectations for {@link Match3RoundDigest}. Every expected value is a hard-coded
 * Chinese literal; none is produced by calling the class under test, so a wrong implementation
 * cannot define its own success.
 */
public final class Match3RoundDigestTest {

    /** Counts Chinese sentence-ending marks so the two-sentence ceiling is measurable. */
    private static int sentences(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '。' || c == '！' || c == '？') n++;
        }
        return n;
    }

    @Test public void allZeroInputTruthfullySaysNoRunSeen() {
        assertEquals("这局处理了 0 帧，盘面上没看到连线。目标状态：unread。",
                Match3RoundDigest.digest(0, 0L, 0L, "unread"));
        assertTrue(Match3RoundDigest.digest(0, 0L, 0L, "unread").contains("没看到连线"));
        assertFalse(Match3RoundDigest.digest(0, 0L, 0L, "unread").contains("表现不错"));
    }

    @Test public void onlyVisibleRunsIsPhrasedAsMaxSimultaneous() {
        assertTrue(Match3RoundDigest.digest(3, 0L, 12L, "0:2").contains("盘面上最多同时看到 3 条连线"));
    }

    @Test public void onlyCascadeFramesIsPhrasedAsFramesWaiting() {
        assertTrue(Match3RoundDigest.digest(0, 5L, 9L, "unread").contains("有 5 帧在等连锁落定"));
    }

    @Test public void bothCountersAppearWithoutCumulativeClaims() {
        String out = Match3RoundDigest.digest(2, 7L, 20L, "1:0");
        assertTrue(out.contains("盘面上最多同时看到 2 条连线"));
        assertTrue(out.contains("另有 7 帧在等连锁落定"));
    }

    @Test public void hugeFrameCountKeepsEveryDigit() {
        String out = Match3RoundDigest.digest(0, 100000000000L, 999999999999L, "unread");
        assertTrue(out.contains("100000000000"));
        assertTrue(out.contains("999999999999"));
        assertFalse(out.contains("E+"));
        assertFalse(out.contains("e+"));
    }

    @Test public void outputNeverCarriesCoordinateMarker() {
        assertFalse(Match3RoundDigest.digest(0, 0L, 0L, "unread").contains("@["));
        assertFalse(Match3RoundDigest.digest(3, 4L, 5L, "1:2").contains("@["));
    }

    /** The forbidden reading: an instantaneous run count recast as a cumulative combo. */
    @Test public void runCountIsNeverWrittenAsComboTimes() {
        String out = Match3RoundDigest.digest(4, 0L, 30L, "unread");
        assertFalse(out.contains("连击 4 次"));
        assertFalse(out.contains("连击了4次"));
        assertFalse(out.contains("连锁特效"));
        assertFalse(out.contains("次"));
    }

    @Test public void processedFramesIsStatedAsCumulativeFrameCount() {
        assertTrue(Match3RoundDigest.digest(0, 0L, 7L, "unread").contains("这局处理了 7 帧"));
    }

    @Test public void goalStateIsQuotedVerbatimNotReinterpreted() {
        assertTrue(Match3RoundDigest.digest(0, 0L, 0L, "LEVEL3:4SPEED").contains("目标状态：LEVEL3:4SPEED"));
    }

    @Test public void everyBranchStaysWithinTwoSentences() {
        assertTrue(sentences(Match3RoundDigest.digest(0, 0L, 0L, "unread")) <= 2);
        assertTrue(sentences(Match3RoundDigest.digest(3, 0L, 12L, "1:0")) <= 2);
        assertTrue(sentences(Match3RoundDigest.digest(0, 5L, 9L, "1:0")) <= 2);
        assertTrue(sentences(Match3RoundDigest.digest(2, 7L, 20L, "1:0")) <= 2);
    }

    @Test public void nullGoalStateDoesNotThrowOrAddSentences() {
        String out = Match3RoundDigest.digest(0, 0L, 0L, null);
        assertTrue(out.contains("没看到连线"));
        assertEquals(2, sentences(out));
    }

    @Test public void outputCarriesNoIdentityOrPackageTokens() {
        String out = Match3RoundDigest.digest(1, 1L, 1L, "1:0");
        assertFalse(out.contains("com.openkhub"));
        assertFalse(out.contains("sensefield"));
    }
}
