package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Hand-specified tasks and gains; expected choices do not come from the scoring implementation. */
public final class Match3GoalValueTest {
    private static Match3Position board(String... rows) {
        Match3Position.Cell[][] cells = new Match3Position.Cell[rows.length][];
        for (int r = 0; r < rows.length; r++) {
            cells[r] = new Match3Position.Cell[rows[r].length()];
            for (int c = 0; c < cells[r].length; c++) {
                char ch = rows[r].charAt(c);
                cells[r][c] = Match3Sampler.isMovable(ch) ? Match3Position.Cell.animal(ch)
                        : Match3Position.Cell.obstacle(ch == 'I' || ch == '2' ? Match3Position.Kind.SNOW
                        : ch == 'C' ? Match3Position.Kind.COIN : ch == 'E' ? Match3Position.Kind.EGG
                        : ch == 'H' ? Match3Position.Kind.EMPTY : Match3Position.Kind.UNKNOWN, ch == '2' ? 2 : 1);
            }
        }
        return new Match3Position(cells);
    }
    private static Match3Goals goals(int steps, Match3Goals.Kind kind, int count) {
        return new Match3Goals(42, steps, Collections.singletonList(new Match3Goals.Target(0, kind, count, false)), true, 100);
    }
    private static Match3MoveValue at(Match3Position p, Match3Goals g, int r, int c, int rr, int cc) {
        for (Match3MoveValue value : Match3MoveRanker.rankedMoves(p, g)) if (value.swap.fromRow == r
                && value.swap.fromCol == c && value.swap.toRow == rr && value.swap.toCol == cc) return value;
        throw new AssertionError("Specified legal exchange absent");
    }

    @Test public void aChickTaskBeatsUnrelatedSnowAndKeepsItsShortReason() {
        Match3Position p = board("BYBHHH", "IBIHHH", "HHHHHH", "YRYHHH", "HYHHHH", "HHHHHH");
        Match3MoveValue best = Match3MoveRanker.rankedMoves(p, goals(1, Match3Goals.Kind.CHICK, 3)).get(0);
        assertEquals(3, best.swap.fromRow); assertEquals(1, best.swap.fromCol);
        assertEquals(4, best.swap.toRow); assertEquals(1, best.swap.toCol);
        assertEquals(3, best.directUnits); assertTrue(best.allTargetsFinish);
        assertEquals("收集小鸡", best.reason);
    }
    @Test public void completedSnowDoesNotOverruleTheUnfinishedAnimalGoal() {
        Match3Position p = board("BYBHHH", "IBIHHH", "HHHHHH", "YRYHHH", "HYHHHH", "HHHHHH");
        Match3Goals g = new Match3Goals(42, 8, Arrays.asList(new Match3Goals.Target(0, Match3Goals.Kind.SNOW, 0, true),
                new Match3Goals.Target(1, Match3Goals.Kind.CHICK, 6, false)), true, 100);
        assertEquals(3, Match3MoveRanker.rankedMoves(p, g).get(0).swap.fromRow);
    }
    @Test public void taskContributionIsCappedByTheActualRemainingCount() {
        Match3Position p = board("YRYHH", "HYHHH");
        Match3MoveValue value = at(p, goals(5, Match3Goals.Kind.CHICK, 1), 0, 1, 1, 1);
        assertEquals(1, value.directUnits); assertEquals(1000, value.progressMilli); assertEquals(1, value.completedTargets);
    }
    @Test public void snowAndSilverCoinsHaveSeparateTaskGains() {
        Match3Position p = board("CICHHH", "YR YHHH".replace(" ", ""), "HYHHHH");
        Match3Goals g = new Match3Goals(42, 6, Arrays.asList(new Match3Goals.Target(0, Match3Goals.Kind.SNOW, 1, false),
                new Match3Goals.Target(1, Match3Goals.Kind.COIN, 2, false)), true, 100);
        Match3MoveValue value = at(p, g, 1, 1, 2, 1);
        assertEquals(1, value.collected(Match3Goals.Kind.SNOW)); assertEquals(2, value.collected(Match3Goals.Kind.COIN));
        assertEquals(2, value.completedTargets); assertEquals("兼顾任务", value.reason);
    }
    @Test public void twoLayerSnowGetsADamageOpportunityButNoInventedCompletedTarget() {
        Match3Position p = board("H2HHHH", "YRYHHH", "HYHHHH");
        Match3MoveValue value = at(p, goals(1, Match3Goals.Kind.SNOW, 1), 1, 1, 2, 1);
        assertEquals(0, value.directUnits); assertEquals(1, value.relevantHits);
        assertFalse(value.allTargetsFinish); assertEquals("清理障碍", value.reason);
    }
    @Test public void eggHitsNeverPretendToCollectRandomChicks() {
        Match3Position p = board("HERHHH", "RGRHHH", "HRHHHH");
        Match3MoveValue value = at(p, goals(3, Match3Goals.Kind.CHICK, 2), 1, 1, 2, 1);
        assertEquals(0, value.collected(Match3Goals.Kind.CHICK)); assertEquals(0, value.directUnits);
        assertEquals(1, value.hit(Match3Goals.Kind.EGG)); assertEquals("靠近鸡蛋", value.reason);
    }
    @Test public void unknownGoalKindsDoNotProduceATaskClaim() {
        Match3Position p = board("YRYHHH", "HYHHHH");
        for (Match3Goals g : Arrays.asList(goals(2, Match3Goals.Kind.UNKNOWN, 3), Match3Goals.unknown(100))) {
            Match3MoveValue value = Match3MoveRanker.rankedMoves(p, g).get(0);
            assertEquals(0, value.directUnits); assertFalse(value.allTargetsFinish); assertEquals("", value.reason);
        }
    }
    @Test public void unreadCountRetainsTaskPriorityWithoutInventingUnitsOrCompletion() {
        Match3Position p=board("BYBHHH","IBIHHH","HHHHHH","YRYHHH","HYHHHH","HHHHHH");
        Match3Goals g=goals(5,Match3Goals.Kind.CHICK,-1);
        Match3MoveValue best=Match3MoveRanker.rankedMoves(p,g).get(0);
        assertEquals(3,best.swap.fromRow);assertEquals(1,best.swap.fromCol);
        assertEquals(0,best.directUnits);assertEquals(0,best.progressMilli);assertEquals(0,best.completedTargets);
        assertFalse(best.allTargetsFinish);assertEquals(-1,g.remaining(Match3Goals.Kind.CHICK));
        assertEquals("优先小鸡",best.reason);
    }
    @Test public void aLargeCookieIsOneRelatedObjectAndNeverAPredictedRemoval() {
        String[] grid={"HHHKKH","YRYKKH","HYHHHH","HHHHHH","BBGBHH","HHBHHH"};
        Match3Position p=board(grid);Match3Position.Cell[][] cells=new Match3Position.Cell[p.rows][p.cols];
        for(int r=0;r<p.rows;r++)for(int c=0;c<p.cols;c++)
            cells[r][c]=grid[r].charAt(c)=='K'?Match3Position.Cell.cookie(3):p.cell(r,c);
        p=new Match3Position(cells);
        Match3MoveValue best=Match3MoveRanker.rankedMoves(p,goals(5,Match3Goals.Kind.COOKIE,8)).get(0);
        assertEquals(1,best.swap.fromRow);assertEquals(1,best.swap.fromCol);
        assertEquals("靠近饼干",best.reason);assertEquals(1,best.hit(Match3Goals.Kind.COOKIE));
        assertEquals(0,best.collected(Match3Goals.Kind.COOKIE));assertEquals(0,best.directUnits);
        assertFalse(best.allTargetsFinish);assertEquals(-1,p.cell(0,3).layers);assertFalse(p.cell(0,3).swappable);
    }
    @Test public void twoHitQuadrantsDoNotTurnOneCookieIntoTwoTaskOpportunities() {
        String[] grid={"HKKHHH","HKKHHH","YYRYHH","HHYHHH"};
        Match3Position p=board(grid);Match3Position.Cell[][] cells=new Match3Position.Cell[p.rows][p.cols];
        for(int r=0;r<p.rows;r++)for(int c=0;c<p.cols;c++)cells[r][c]=grid[r].charAt(c)=='K'?Match3Position.Cell.cookie(1):p.cell(r,c);
        Match3MoveValue v=at(new Match3Position(cells),goals(5,Match3Goals.Kind.COOKIE,8),2,2,3,2);
        assertEquals(1,v.hit(Match3Goals.Kind.COOKIE));assertEquals(0,v.directUnits);
    }
    @Test public void duplicateTaskFieldsCountOnlyOnceAndConflictingCopiesAreUnknown() {
        Match3Position p = board("YRYHHH", "HYHHHH");
        Match3Goals g = new Match3Goals(42, 2, Arrays.asList(new Match3Goals.Target(0, Match3Goals.Kind.CHICK, 3, false),
                new Match3Goals.Target(1, Match3Goals.Kind.CHICK, 3, false)), true, 100);
        Match3MoveValue value = Match3MoveRanker.rankedMoves(p, g).get(0);
        assertEquals(3, value.directUnits); assertEquals(1, value.completedTargets);
        Match3Goals conflict = new Match3Goals(42, 2, Arrays.asList(new Match3Goals.Target(0, Match3Goals.Kind.CHICK, 3, false),
                new Match3Goals.Target(1, Match3Goals.Kind.CHICK, 4, false)), true, 100);
        assertFalse(conflict.fullyKnown()); assertEquals(-1, conflict.remaining(Match3Goals.Kind.CHICK));
        assertEquals(0, Match3MoveRanker.rankedMoves(p, conflict).get(0).directUnits);
    }
    @Test public void aPotentialSpecialRetainsOneAnimalAndCannotGuaranteeAOneNeighbourClear() {
        Match3Position p = board("HHIHHH", "YRY YHH".replace(" ", ""), "HYHHHH");
        Match3MoveValue value = at(p, goals(1, Match3Goals.Kind.CHICK, 4), 1, 1, 2, 1);
        assertEquals(4, value.swap.matchedCells); assertEquals(3, value.collected(Match3Goals.Kind.CHICK));
        assertEquals(0, value.collected(Match3Goals.Kind.SNOW)); assertFalse(value.allTargetsFinish);
    }
    @Test public void noMovesAreSuggestedWithConfirmedZeroStepsOrCompletedGoals() {
        Match3Position p = board("YRYHHH", "HYHHHH");
        assertTrue(Match3MoveRanker.rankedMoves(p, goals(0, Match3Goals.Kind.CHICK, 5)).isEmpty());
        assertTrue(Match3MoveRanker.rankedMoves(p, goals(4, Match3Goals.Kind.CHICK, 0)).isEmpty());
    }
    @Test public void lockedAnimalsCanMatchButCannotBeExchangeEndpoints() {
        Match3Position p = board("YRYHHH", "HYHHHH");
        Match3Position.Cell[][] cells = new Match3Position.Cell[p.rows][p.cols];
        for (int r = 0; r < p.rows; r++) for (int c = 0; c < p.cols; c++) cells[r][c] = p.cell(r, c);
        cells[0][1] = new Match3Position.Cell(Match3Position.Kind.ANIMAL, 'R', false, 0, 0);
        assertTrue(Match3Board.findSwaps(new Match3Position(cells)).isEmpty());
    }
    @Test public void matchedCellMasksAndTileArraysCannotBeMutatedByAConsumer() {
        Match3Position p = board("YRYHHH", "HYHHHH");
        Match3Board.Swap swap = Match3Board.findSwaps(p).get(0); swap.matchedPositions().clear();
        assertEquals(3, swap.matchedPositions().cardinality());
        char[][] matrix = p.matrix(); matrix[0][0] = '.'; assertEquals('Y', p.cell(0,0).color);
    }
}
