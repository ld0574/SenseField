package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Hand-checked tray perimeter: yellow touches three sides, red one, green none.
 * The remote red four can create a special but provides no stronger tray contact. */
public final class Match3CookieBirdRankingTest {
    private static Match3Position board() {
        String[] rows={"RRYRHH","HHRHHH","HHYJRH","YYGGHH","KKYHHH","KKRHHH"};
        Match3Position.Cell[][] cells=new Match3Position.Cell[6][6];
        for(int r=0;r<6;r++)for(int c=0;c<6;c++) {
            char color=rows[r].charAt(c);
            cells[r][c]=color=='J'?Match3Position.Cell.bird():color=='K'?Match3Position.Cell.cookie(24)
                    :color=='H'?Match3Position.Cell.obstacle(Match3Position.Kind.EMPTY,0):Match3Position.Cell.animal(color);
        }
        return new Match3Position(cells);
    }
    private static Match3Goals goals(int steps,boolean verified) {
        return new Match3Goals(15,steps,Collections.singletonList(
                new Match3Goals.Target(0,Match3Goals.Kind.COOKIE,3,false,"",Match3Goals.Mode.UNKNOWN,"identity_only")),verified,0);
    }
    private static char birdColor(Match3MoveValue move) {
        Match3Position board=move.result.before;
        Match3Position.Cell a=board.cell(move.swap.fromRow,move.swap.fromCol),b=board.cell(move.swap.toRow,move.swap.toCol);
        return a.kind==Match3Position.Kind.ANIMAL?a.color:b.color;
    }
    @Test public void birdChoosesTrayContactColorBeforeUnrelatedFour() {
        List<Match3MoveValue> ranked=Match3MoveRanker.rankedMoves(board(),goals(8,true));
        Match3MoveValue best=ranked.get(0);
        assertEquals(Match3Action.Type.BIRD_COLOR,best.action.type);assertEquals('Y',birdColor(best));
        assertEquals(3,best.cookieContactCells);assertEquals(1,best.hit(Match3Goals.Kind.COOKIE));
        assertEquals("用魔力鸟清理饼干周边",best.reason);
        assertTrue(ranked.stream().anyMatch(v->v.potentialSpecials>0));
        for(Match3MoveValue move:ranked)if(move.action.type==Match3Action.Type.BIRD_COLOR) {
            if(birdColor(move)=='R')assertEquals(1,move.cookieContactCells);
            if(birdColor(move)=='G')assertEquals(0,move.cookieContactCells);
        }
    }
    @Test public void trayPerimeterIsNotInventedDamageOrCompletion() {
        Match3MoveValue best=Match3MoveRanker.rankedMoves(board(),goals(8,true)).get(0);
        assertEquals(0,best.collected(Match3Goals.Kind.COOKIE));assertEquals(0,best.directUnits);
        assertEquals(0,best.completedTargets);assertFalse(best.allTargetsFinish);
        assertFalse(best.result.successorKnown);assertTrue(best.result.unknowns.contains("cookie_damage_rule_unknown"));
        assertEquals(24,best.result.after.cell(4,0).objectId);
        assertTrue(best.evidence().contains("cookie_contact_scope=adjacent_clears_not_task_units"));
    }
    @Test public void confirmedAnimalTaskGainOutranksUnknownTrayContact() {
        Match3Goals g=new Match3Goals(15,8,Arrays.asList(
                new Match3Goals.Target(0,Match3Goals.Kind.COOKIE,3,false,"",Match3Goals.Mode.UNKNOWN,"identity_only"),
                new Match3Goals.Target(1,Match3Goals.Kind.RED,3,false)),true,0);
        Match3MoveValue best=Match3MoveRanker.rankedMoves(board(),g).get(0);
        assertEquals(3,best.directUnits);assertEquals(1,best.completedTargets);
        assertFalse(best.action.type==Match3Action.Type.BIRD_COLOR && birdColor(best)=='Y');
    }
    @Test public void unreadOrAbstainedTaskDoesNotAddTrayPreference() {
        for(Match3MoveValue move:Match3MoveRanker.rankedMoves(board(),goals(8,false)))assertEquals(0,move.cookieContactCells);
        for(Match3MoveValue move:Match3MoveRanker.rankedMoves(board(),goals(8,true),
                Collections.singleton(Match3Goals.Kind.COOKIE)))assertEquals(0,move.cookieContactCells);
    }
    @Test public void lastStepKeepsExistingPhysicalLowerBoundPolicy() {
        for(Match3MoveValue move:Match3MoveRanker.rankedMoves(board(),goals(1,true)))assertEquals(0,move.cookieContactCells);
    }
    @Test public void unknownBirdStateCannotAcquireAnExchangeFromRanking() {
        Match3Position p=board();Match3Position.Cell[][] cells=p.copyCells();
        cells[2][3]=Match3Position.Cell.observed(Match3Position.Kind.BIRD,"magic_bird",-1,-1);
        for(Match3MoveValue move:Match3MoveRanker.rankedMoves(new Match3Position(cells),goals(8,true)))
            assertFalse(move.action.type==Match3Action.Type.BIRD_COLOR || move.action.type==Match3Action.Type.BIRD_PAIR);
    }
}
