package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AssistantTtsPipelineTest {
    @Test public void shortSentencesStayWholeAndDecimalNumbersStayTogether() {
        String first = "左侧出现3.5米的敌人。";
        AssistantTtsPipeline.Plan plan = AssistantTtsPipeline.split(first + "保持距离。");

        assertEquals(2, plan.segments.size());
        assertEquals(first, plan.segments.get(0).text);
        assertEquals("保持距离。", plan.segments.get(1).text);
        assertEquals(0, plan.droppedChars);
    }

    @Test public void longSentenceUsesClauseBoundariesAndKeepsEmojiSequencesWhole() {
        String firstClause = "前方有😀👩‍🚀" + repeat("注意", 62) + "，";
        String secondClause = "请保持距离。";
        String reply = firstClause + secondClause + "撤离后再观察。";

        AssistantTtsPipeline.Plan plan = AssistantTtsPipeline.split(reply);

        assertTrue(plan.segments.size() >= 2);
        assertEquals(firstClause, plan.segments.get(0).text);
        assertTrue(plan.segments.get(0).text.endsWith("，"));
        assertFalse(containsUnpairedSurrogate(plan.segments.get(0).text));
        assertEquals("撤离后再观察。", plan.segments.get(plan.segments.size() - 1).text);
    }

    @Test public void boundedPlanNeverExceedsFourSegmentsOrFourHundredCodePoints() {
        String sentence = repeat("敌人靠近", 24) + "。"; // 97 code points
        AssistantTtsPipeline.Plan plan = AssistantTtsPipeline.split(
                sentence + sentence + sentence + sentence + sentence);

        assertEquals(4, plan.segments.size());
        assertTrue(plan.chars <= AssistantTtsPipeline.MAX_CHARS);
        assertTrue(plan.droppedChars > 0);
        assertTrue(plan.segments.get(0).text.endsWith("。"));
        assertTrue(plan.segments.get(3).text.endsWith("。"));
    }

    @Test public void cancelAtomicallyBlocksLateReadyAndCannotRestartTheGroup() {
        AssistantTtsPipeline.Group group = group("先报方位。再说明距离。最后给建议。");
        AssistantTtsPipeline.Segment first = group.reserveNext();
        assertNotNull(first);
        assertTrue(group.synthesisReady(first.index));
        assertTrue(group.markFirstWrite(first.index));
        assertFalse(group.markFirstWrite(first.index));
        assertEquals(AssistantTtsPipeline.Action.NEXT,
                group.playbackCompleted(first.index).action);

        AssistantTtsPipeline.Segment second = group.reserveNext();
        assertNotNull(second);
        AssistantTtsPipeline.DropSummary dropped = group.cancel();

        assertEquals(second.index, dropped.index);
        assertEquals(2, dropped.segments);
        assertEquals(group.plan.segments.get(1).chars + group.plan.segments.get(2).chars,
                dropped.chars);
        assertFalse(group.synthesisReady(second.index));
        assertNull(group.reserveNext());
        assertEquals(AssistantTtsPipeline.Action.IGNORED,
                group.playbackCompleted(second.index).action);
        assertFalse(group.claimFinishCallback());
    }

    @Test public void cancelInTheBetweenSegmentsGapPreventsTheNextReservation() {
        AssistantTtsPipeline.Group group = group("先报方位。再说明距离。最后给建议。");
        AssistantTtsPipeline.Segment first = group.reserveNext();
        assertTrue(group.synthesisReady(first.index));
        assertTrue(group.markFirstWrite(first.index));
        assertEquals(AssistantTtsPipeline.Action.NEXT,
                group.playbackCompleted(first.index).action);

        // Cancellation wins the window after one segment completes and before the next is
        // reserved; the next TTS callback cannot reopen the reply group.
        AssistantTtsPipeline.DropSummary dropped = group.cancel();

        assertEquals(1, dropped.index);
        assertEquals(2, dropped.segments);
        assertNull(group.reserveNext());
        assertFalse(group.synthesisReady(first.index));
        assertTrue(group.isCancelled());
    }

    @Test public void lateDoneFromAnEarlierSegmentCannotMakeTheCurrentSegmentReady() {
        AssistantTtsPipeline.Group group = group("先报方位。再说明距离。");
        AssistantTtsPipeline.Segment first = group.reserveNext();
        assertTrue(group.synthesisReady(first.index));
        assertTrue(group.markFirstWrite(first.index));
        group.playbackCompleted(first.index);
        AssistantTtsPipeline.Segment second = group.reserveNext();

        assertNotNull(second);
        assertFalse(group.synthesisReady(first.index));
        assertTrue(group.isSynthesizing(second.index));
        assertTrue(group.synthesisReady(second.index));
        assertTrue(group.isReadyToPlay(second.index));
    }

    @Test public void expiryBeforeAnyFirstWriteFailsOnceAndDropsTheWholeReply() {
        AssistantTtsPipeline.Group group = group("先报方位。再说明距离。最后给建议。");
        AssistantTtsPipeline.Segment first = group.reserveNext();
        assertTrue(group.synthesisReady(first.index));

        AssistantTtsPipeline.Transition expired = group.expiredBeforePlayback(first.index);

        assertEquals(AssistantTtsPipeline.Action.FINISH, expired.action);
        assertFalse(expired.success);
        assertFalse(group.started());
        assertEquals(group.plan.segments.size(), expired.dropped.segments);
        assertTrue(group.claimFinishCallback());
        assertFalse(group.claimFinishCallback());
        assertNull(group.reserveNext());
    }

    @Test public void failedSegmentCannotDeliverASecondFinishOrAdvance() {
        AssistantTtsPipeline.Group group = group("先报方位。再说明距离。");
        AssistantTtsPipeline.Segment first = group.reserveNext();

        AssistantTtsPipeline.Transition failed = group.failed(first.index);
        AssistantTtsPipeline.Transition lateFailure = group.failed(first.index);

        assertEquals(AssistantTtsPipeline.Action.FINISH, failed.action);
        assertFalse(failed.success);
        assertEquals(AssistantTtsPipeline.Action.IGNORED, lateFailure.action);
        assertTrue(group.claimFinishCallback());
        assertFalse(group.claimFinishCallback());
        assertNull(group.reserveNext());
    }

    @Test public void boundedPcmDrainFailureAfterFirstWriteEndsWithoutStartingTheTail() {
        AssistantTtsPipeline.Group group = group("先报方位。再说明距离。最后给建议。");
        AssistantTtsPipeline.Segment first = group.reserveNext();
        assertTrue(group.synthesisReady(first.index));
        assertTrue(group.markFirstWrite(first.index));

        // CuePlayer routes its bounded AudioTrack drain timeout through the same terminal gate as
        // any PCM playback failure; this test verifies group semantics without mocking audio.
        AssistantTtsPipeline.Transition failed = group.failed(first.index);

        assertEquals(AssistantTtsPipeline.Action.FINISH, failed.action);
        assertFalse(failed.success);
        assertTrue(group.started());
        assertEquals(2, failed.dropped.segments);
        assertTrue(group.claimFinishCallback());
        assertFalse(group.claimFinishCallback());
        assertNull(group.reserveNext());
    }

    @Test public void expiryAfterFirstSegmentDropsTailAndClaimsFinishedOnlyOnce() {
        AssistantTtsPipeline.Group group = group("先报方位。再说明距离。最后给建议。");
        AssistantTtsPipeline.Segment first = group.reserveNext();
        assertTrue(group.synthesisReady(first.index));
        assertTrue(group.markFirstWrite(first.index));
        assertEquals(AssistantTtsPipeline.Action.NEXT,
                group.playbackCompleted(first.index).action);

        AssistantTtsPipeline.Segment second = group.reserveNext();
        assertTrue(group.synthesisReady(second.index));
        AssistantTtsPipeline.Transition expired = group.expiredBeforePlayback(second.index);

        assertEquals(AssistantTtsPipeline.Action.FINISH, expired.action);
        assertTrue(expired.success);
        assertEquals(2, expired.dropped.segments);
        assertEquals(1, expired.dropped.index);
        assertTrue(group.claimFinishCallback());
        assertFalse(group.claimFinishCallback());
    }

    private static AssistantTtsPipeline.Group group(String speech) {
        AssistantTtsPipeline.Plan plan = AssistantTtsPipeline.split(speech);
        return new AssistantTtsPipeline.Group("assistant:test", "cue", plan);
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static boolean containsUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i)))
                    return true;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
