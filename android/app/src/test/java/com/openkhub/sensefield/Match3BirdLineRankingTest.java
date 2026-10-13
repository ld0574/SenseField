package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Known color lower bounds must not silently become a full-screen combination mask. */
public final class Match3BirdLineRankingTest {
    private static Match3Position board() {
        Match3Position.Cell[][] cells={
                {Match3Position.Cell.animal('R'),Match3Position.Cell.bird(),
                        Match3Position.Cell.animal('R').withEffect(Match3Position.Effect.HORIZONTAL)},
                {Match3Position.Cell.animalOnIce('R'),Match3Position.Cell.animalOnIce('B'),Match3Position.Cell.animal('G')},
                {Match3Position.Cell.animal('R').withCover(Match3Position.Cover.FROZEN_ICE,2),
                        Match3Position.Cell.animalIdentity('R'),Match3Position.Cell.obstacle(Match3Position.Kind.SNOW,1)}};
        return new Match3Position(cells);
    }
    private static Match3Action combo(Match3Position board) {
        for(Match3Action action:Match3Action.enumerate(board))if(action.type==Match3Action.Type.BIRD_LINE)return action;
        throw new AssertionError("Known bird and line exchange absent");
    }
    private static Match3Goals goals(Match3Goals.Kind kind,int count) {
        return new Match3Goals(109,8,Collections.singletonList(new Match3Goals.Target(0,kind,count,false)),true,100);
    }
    @Test public void combinationRemovesOnlyKnownUnblockedPartnerColorInItsLowerBound() {
        Match3Position p=board();Match3RuleEngine.Result r=Match3RuleEngine.resolve(p,combo(p));
        assertEquals(3,r.collected(Match3Goals.Kind.RED,Match3Goals.Mode.CLEAR));
        assertEquals(1,r.collected(Match3Goals.Kind.ICE,Match3Goals.Mode.CLEAR));
        assertEquals(0,r.collected(Match3Goals.Kind.HIPPO,Match3Goals.Mode.CLEAR));
        assertEquals(Match3Position.Kind.ANIMAL,r.after.cell(1,1).kind);
        assertEquals(Match3Position.Cover.FROZEN_ICE,r.after.cell(2,0).cover);
        assertEquals(Match3Position.Kind.SNOW,r.after.cell(2,2).kind);
        assertFalse(r.successorKnown);
        assertTrue(r.unknowns.contains("bird_line_conversion_and_extra_hit_mask_unknown"));
    }
    @Test public void equalKnownTaskGainPrefersTheObservedCombinationOverAnOrdinaryBirdExchange() {
        List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(board(),goals(Match3Goals.Kind.RED,8));
        Match3MoveValue first=moves.get(0);assertEquals(Match3Action.Type.BIRD_LINE,first.action.type);
        assertTrue(first.reason.startsWith("魔力鸟组合"));
        assertTrue(moves.stream().anyMatch(m->m.action.type==Match3Action.Type.BIRD_COLOR && m.directUnits==first.directUnits));
        assertEquals(1,first.search.depth);assertFalse(first.allTargetsFinish);
    }
    @Test public void aProvenFinishOutranksACombinationOpportunity() {
        Match3Position.Cell[][] cells=board().copyCells();cells[0][0]=Match3Position.Cell.animal('B');
        Match3MoveValue first=Match3MoveRanker.rankedMoves(new Match3Position(cells),goals(Match3Goals.Kind.HIPPO,2)).get(0);
        assertEquals(Match3Action.Type.BIRD_COLOR,first.action.type);assertTrue(first.allTargetsFinish);
    }
    @Test public void aUsefulCombinationIsNotRankedAsIfItsLowerBoundWereItsEntireValue() {
        Match3Position.Cell[][] cells=board().copyCells();cells[0][0]=Match3Position.Cell.animal('B');
        cells[1][2]=Match3Position.Cell.animalOnIce('B');
        List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(new Match3Position(cells),goals(Match3Goals.Kind.ICE,10));
        Match3MoveValue first=moves.get(0);assertEquals(Match3Action.Type.BIRD_LINE,first.action.type);
        assertEquals(1,first.directUnits);assertFalse(first.allTargetsFinish);
        assertTrue(moves.stream().anyMatch(m->m.action.type==Match3Action.Type.BIRD_COLOR && m.directUnits==2));
        assertFalse(first.result.successorKnown);assertEquals(1,first.search.depth);
    }
    @Test public void theFinalStepUsesKnownGainBeforeAnUnquantifiedCombinationOpportunity() {
        Match3Position.Cell[][] cells=board().copyCells();cells[0][0]=Match3Position.Cell.animal('B');
        cells[1][2]=Match3Position.Cell.animalOnIce('B');
        Match3Goals last=new Match3Goals(109,1,Collections.singletonList(
                new Match3Goals.Target(0,Match3Goals.Kind.ICE,10,false)),true,100);
        Match3MoveValue first=Match3MoveRanker.rankedMoves(new Match3Position(cells),last).get(0);
        assertNotEquals(Match3Action.Type.BIRD_LINE,first.action.type);assertTrue(first.directUnits>1);
    }
    @Test public void eitherObservedLineDirectionSupportsTheSameConservativeCombination() {
        Match3Position.Cell[][] cells=board().copyCells();cells[0][2]=cells[0][2].withEffect(Match3Position.Effect.VERTICAL);
        assertEquals("combo_bird_line",combo(new Match3Position(cells)).ruleId);
    }
    @Test public void unknownFrozenLockedOrClosedPartnersDoNotGainACombinationPermission() {
        Match3Position.Cell line=board().cell(0,2);
        for(Match3Position.Cell invalid:new Match3Position.Cell[]{line.withEffect(Match3Position.Effect.UNKNOWN),
                line.withEffect(Match3Position.Effect.BLAST),line.withIce(-1),
                line.withCover(Match3Position.Cover.GRAY_FURBALL,1),line.withCover(Match3Position.Cover.LOCK,1),
                line.withCover(Match3Position.Cover.FROZEN_ICE,1),line.withGate(Match3Position.Gate.CLOSED)}) {
            Match3Position.Cell[][] cells=board().copyCells();cells[0][2]=invalid;
            assertFalse(Match3Action.enumerate(new Match3Position(cells)).stream().anyMatch(a->a.type==Match3Action.Type.BIRD_LINE));
        }
        Match3Position.Cell[][] cells=board().copyCells();cells[0][1]=cells[0][1].withIce(-1);
        assertFalse(Match3Action.enumerate(new Match3Position(cells)).stream().anyMatch(a->a.type==Match3Action.Type.BIRD_LINE));
    }
}
