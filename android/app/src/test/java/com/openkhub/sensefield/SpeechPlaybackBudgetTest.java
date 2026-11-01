package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the two separate speech deadlines in CuePlayer.
 *
 * <p>The player-side allowance ({@link CuePlayer#playbackBudgetMs(String)}) exists because the
 * original fixed four-second cut kept truncating a long broadcast mid-sentence: once playback has
 * started, a sentence gets room proportional to its length, clamped to the four-second floor and
 * the thirty-second ceiling. The synthesis-side deadline ({@link CuePlayer#speechTimeoutMs}) is a
 * different line and is asserted separately below - it never grows with the sentence.
 *
 * <p>These are hand-written numeric assertions on the real production methods; nothing is mocked.
 */
public final class SpeechPlaybackBudgetTest {

    /** The floor that used to be the only value, and the bug the player-side deadline removed. */
    private static final long FIXED_FOUR_SECOND_CUT = 4000L;
    private static final long PLAYBACK_CEILING_MS = 30_000L;
    /** CuePlayer.SPEECH_TIMEOUT_MS is private; the synthesis deadline it stands for is 4000 ms. */
    private static final long SYNTHESIS_DEADLINE_MS = 4000L;

    @Test public void startedEightCharCueGetsMoreRoomThanTheFixedFourSecondCut() {
        long budget = CuePlayer.playbackBudgetMs(sentence(8));
        assertTrue("an 8-character sentence must not be cut by the four-second cap, got " + budget,
                budget > FIXED_FOUR_SECOND_CUT);
        assertEquals("8 characters leave 700 ms each plus the 3000 ms base", 8600L, budget);
    }

    @Test public void budgetNeverShrinksWhenSentencesGrow() {
        long previous = 0L;
        int[] lengths = {0, 1, 2, 4, 8, 16, 24, 32, 38, 40, 200};
        for (int length : lengths) {
            long budget = CuePlayer.playbackBudgetMs(sentence(length));
            assertTrue("budget must be non-decreasing at " + length + " characters: "
                    + previous + " -> " + budget, budget >= previous);
            previous = budget;
        }
    }

    @Test public void longerSentenceReachesAtLeastTheRoomOfTheShorterOneBelowTheCeiling() {
        long shortSentence = CuePlayer.playbackBudgetMs(sentence(6));
        long longSentence = CuePlayer.playbackBudgetMs(sentence(20));
        assertTrue("20 characters must not be given less room than 6 characters",
                longSentence > shortSentence);
        assertEquals(7200L, shortSentence);
        assertEquals(17000L, longSentence);
    }

    @Test public void twoHundredCharSentenceIsPinnedToTheThirtySecondCeiling() {
        assertEquals(PLAYBACK_CEILING_MS, CuePlayer.playbackBudgetMs(sentence(200)));
    }

    @Test public void ceilingStartsBitingRightAfterTheLastSentenceThatStillFitsUnderIt() {
        assertEquals("38 characters still fit under the ceiling", 29_600L,
                CuePlayer.playbackBudgetMs(sentence(38)));
        assertEquals("39 characters would ask for 30300 ms and are clamped", PLAYBACK_CEILING_MS,
                CuePlayer.playbackBudgetMs(sentence(39)));
    }

    @Test public void emptyAndOneCharSentencesFallToTheFourSecondFloor() {
        assertEquals("an empty sentence asks for 3000 ms and keeps the floor",
                FIXED_FOUR_SECOND_CUT, CuePlayer.playbackBudgetMs(""));
        assertEquals("one character asks for 3700 ms and keeps the floor",
                FIXED_FOUR_SECOND_CUT, CuePlayer.playbackBudgetMs(sentence(1)));
    }

    @Test public void missingSentenceUsesTheCeilingFallbackTheCallSiteAlreadyHad() {
        assertEquals("no sentence to measure means the full playback window",
                PLAYBACK_CEILING_MS, CuePlayer.playbackBudgetMs(null));
    }

    @Test public void reminderNarrationWaitsOnTheNarrationSynthesisDeadline() {
        CueRequest narration = new CueRequest("live", "guide:1", "guide:1",
                ReminderGuide.NARRATION_KIND, CueRequest.Category.SYSTEM, 80, 0, 1200,
                CueRequest.CHANNEL_SPEECH, 7, 0, 0, sentence(40));
        assertEquals(ReminderGuide.NARRATION_TIMEOUT_MS, CuePlayer.speechTimeoutMs(narration));
        assertEquals("the narration deadline is a constant, not a function of length",
                CuePlayer.speechTimeoutMs(narration),
                CuePlayer.speechTimeoutMs(new CueRequest("live", "guide:2", "guide:2",
                        ReminderGuide.NARRATION_KIND, CueRequest.Category.SYSTEM, 80, 0, 1200,
                        CueRequest.CHANNEL_SPEECH, 7, 0, 0, sentence(2))));
    }

    @Test public void ordinaryAndMislabelledCuesStayOnTheFourSecondSynthesisDeadline() {
        CueRequest near = new CueRequest("live", "live:1", "near:1", "NEAR_ENTER",
                CueRequest.Category.NEAR_ZONE, 80, 0, 1200,
                CueRequest.CHANNEL_SPEECH, 7, 0, 0, sentence(40));
        assertEquals(SYNTHESIS_DEADLINE_MS, CuePlayer.speechTimeoutMs(near));
        CueRequest mislabelled = new CueRequest("live", "live:2", "near:2",
                ReminderGuide.NARRATION_KIND, CueRequest.Category.NEAR_ZONE, 80, 0, 1200,
                CueRequest.CHANNEL_SPEECH, 7, 0, 0, sentence(40));
        assertEquals("the narration deadline needs the SYSTEM category too",
                SYNTHESIS_DEADLINE_MS, CuePlayer.speechTimeoutMs(mislabelled));
        assertEquals("synthesis does not grow with the sentence", 4000L,
                CuePlayer.speechTimeoutMs(near));
    }

    private static String sentence(int characters) {
        StringBuilder text = new StringBuilder(characters);
        for (int i = 0; i < characters; i++) text.append('字');
        return text.toString();
    }
}
