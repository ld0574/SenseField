package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class Match3GoalOutcomeTest {
    private static Match3Position board(boolean exchanged) {
        String[] rows=exchanged?new String[]{"YYYHHH","HRHHHH"}:new String[]{"YRYHHH","HYHHHH"};
        Match3Position.Cell[][] cells=new Match3Position.Cell[2][6];
        for(int r=0;r<2;r++)for(int c=0;c<6;c++) {
            char color=rows[r].charAt(c);cells[r][c]=color=='H'
                    ?Match3Position.Cell.obstacle(Match3Position.Kind.EMPTY,0):Match3Position.Cell.animal(color);
        }
        return new Match3Position(cells);
    }
    private static Match3Goals goals(int count,int steps,long at) {
        return new Match3Goals(42,steps,Collections.singletonList(new Match3Goals.Target(0,Match3Goals.Kind.CHICK,count,false)),true,at);
    }
    private static Match3GoalOutcome tracker(List<Match3GoalOutcome.Result> rows) {
        Match3GoalOutcome tracker=new Match3GoalOutcome(rows::add);Match3Goals before=goals(6,8,100);
        tracker.begin(7,board(false),before,Match3MoveRanker.rankedMoves(board(false),before).get(0),100);return tracker;
    }
    @Test public void aSingleObservedExchangeAndCounterDeltaAreRecordedWithVisualOnlyAttribution() {
        List<Match3GoalOutcome.Result> rows=new ArrayList<>();Match3GoalOutcome t=tracker(rows);
        t.observeFrame(board(true),goals(6,8,200),200);t.confirmed(goals(3,7,900),900);
        assertEquals(1,rows.size());Match3GoalOutcome.Result r=rows.get(0);
        assertEquals("visual_exchange_progress_observed",r.outcome);assertEquals("exact_visual_exchange_only",r.actionEvidence);
        assertEquals(Integer.valueOf(3),r.deltas.get(Match3Goals.Kind.CHICK));assertEquals(Integer.valueOf(3),r.expected.get(Match3Goals.Kind.CHICK));
        assertEquals(7,r.hintRevision);
    }
    @Test public void unseenExchangesOrMultipleMovesNeverClaimThatTheRecommendationWasFollowed() {
        for(int afterSteps:new int[]{7,6}) {
            List<Match3GoalOutcome.Result> rows=new ArrayList<>();Match3GoalOutcome t=tracker(rows);
            t.confirmed(goals(3,afterSteps,900),900);
            assertEquals(afterSteps==7?"progress_unattributed":"multiple_actions_unattributed",rows.get(0).outcome);
            assertEquals("not_observed",rows.get(0).actionEvidence);assertTrue(t.abstainedRules().isEmpty());
        }
    }
    @Test public void observedGainsBelowTheConservativePredictionAbstainForThisSessionOnly() {
        List<Match3GoalOutcome.Result> rows=new ArrayList<>();Match3GoalOutcome t=tracker(rows);
        t.observeFrame(board(true),goals(6,8,200),200);t.confirmed(goals(5,7,900),900);
        assertEquals("below_lower_bound_rule_abstained",rows.get(0).outcome);
        assertTrue(t.abstainedRules().contains(Match3Goals.Kind.CHICK));
        Match3MoveValue fallback=Match3MoveRanker.rankedMoves(board(false),goals(6,8,100),t.abstainedRules()).get(0);
        assertEquals(0,fallback.directUnits);assertEquals("",fallback.reason);
        assertFalse(new Match3GoalOutcome(null).abstainedRules().contains(Match3Goals.Kind.CHICK));
    }
    @Test public void resetsUnknownNumbersAndStaleResultsDoNotBecomeFailedRules() {
        for(int count:new int[]{-1,9}) {
            List<Match3GoalOutcome.Result> rows=new ArrayList<>();Match3GoalOutcome t=tracker(rows);
            t.observeFrame(board(true),goals(6,8,200),200);t.confirmed(goals(count,7,900),900);
            assertEquals(count==-1?"counter_coverage_incomplete":"counter_reset_or_added_steps",rows.get(0).outcome);
            assertTrue(t.abstainedRules().isEmpty());
        }
        List<Match3GoalOutcome.Result> rows=new ArrayList<>();Match3GoalOutcome t=tracker(rows);
        t.confirmed(goals(3,7,90),900);assertTrue(rows.isEmpty());
        t.cancel("FRAME_STALE");t.confirmed(goals(3,7,1000),1000);
        assertEquals(1,rows.size());assertEquals("FRAME_STALE",rows.get(0).outcome);
    }
    @Test public void noKnownTargetOrUnchangedStepsDoNotCreateAProgressMeasurement() {
        List<Match3GoalOutcome.Result> rows=new ArrayList<>();Match3GoalOutcome t=tracker(rows);
        t.confirmed(goals(3,8,900),900);assertTrue(rows.isEmpty());
        Match3Goals unknown=new Match3Goals(43,22,Collections.singletonList(new Match3Goals.Target(0,Match3Goals.Kind.UNKNOWN,24,false)),true,1000);
        Match3GoalOutcome noKnownTarget=new Match3GoalOutcome(rows::add);
        noKnownTarget.begin(9,board(false),unknown,Match3MoveRanker.rankedMoves(board(false),unknown).get(0),1000);
        noKnownTarget.confirmed(new Match3Goals(43,21,unknown.targets,true,1700),1700);assertTrue(rows.isEmpty());
    }
    @Test public void lateObservationsAreCancelledAndCannotAttachToANewHint() {
        List<Match3GoalOutcome.Result> rows=new ArrayList<>();Match3GoalOutcome t=tracker(rows);
        t.observeFrame(board(true),goals(3,7,13000),13000);assertEquals("outcome_timeout",rows.get(0).outcome);
        t.confirmed(goals(3,7,14000),14000);assertEquals(1,rows.size());
    }
    @Test public void temporaryBasicGuidanceCannotEraseARecommendedExchangeBeforeDelayedCountersConfirm() {
        List<Match3GoalOutcome.Result> rows=new ArrayList<>();Match3GoalOutcome t=tracker(rows);
        t.observeFrame(board(true),Match3Goals.unknown(200),200);
        Match3Goals unknown=Match3Goals.unknown(900);
        t.begin(8,board(false),unknown,Match3MoveRanker.rankedMoves(board(false),unknown).get(0),900);
        assertTrue(rows.isEmpty());
        t.confirmed(goals(3,7,2500),2500);
        assertEquals(1,rows.size());assertEquals(7,rows.get(0).hintRevision);
        assertEquals("visual_exchange_progress_observed",rows.get(0).outcome);
        assertEquals(Integer.valueOf(3),rows.get(0).deltas.get(Match3Goals.Kind.CHICK));
    }
}
