package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Hand-built isolated exchanges, not scores used as their own expected answers. */
public final class Match3TargetFrontierTest {
    private static Match3Position board() {
        String[] rows = {"RRYRHHHH", "HHRHHHHH", "HHHHHHHH", "HHHHHHHH", "BBYGHHHH", "HHBHHCHH"};
        Match3Position.Cell[][] cells = new Match3Position.Cell[rows.length][rows[0].length()];
        for (int r=0;r<rows.length;r++) for(int c=0;c<rows[r].length();c++) {
            char ch=rows[r].charAt(c);
            cells[r][c]=ch=='C'?Match3Position.Cell.obstacle(Match3Position.Kind.COIN,1)
                    :ch=='H'?Match3Position.Cell.obstacle(Match3Position.Kind.EMPTY,0):Match3Position.Cell.animal(ch);
        }
        return new Match3Position(cells);
    }
    private static Match3Goals goal(int steps, Match3Goals.Kind kind, int count) {
        return new Match3Goals(64,steps,Collections.singletonList(new Match3Goals.Target(0,kind,count,count==0)),true,100);
    }
    @Test public void aDistantFourRunYieldsToPreparationNearTheLastCoinWithoutInventingAGain() {
        Match3Position p=board();
        assertEquals(0,Match3MoveRanker.rankedMoves(p,Match3Goals.unknown(100)).get(0).swap.fromRow);
        List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(p,goal(8,Match3Goals.Kind.COIN,1));
        Match3MoveValue best=moves.get(0);
        assertEquals(4,best.swap.fromRow);assertEquals(2,best.swap.fromCol);
        assertEquals(5,best.swap.toRow);assertEquals(2,best.swap.toCol);
        assertEquals(4,best.targetDistance);assertEquals(0,best.directUnits);assertEquals(0,best.relevantHits);
        assertFalse(best.allTargetsFinish);assertEquals(0,best.progressMilli);
        assertTrue(best.reason.contains("银币"));assertTrue(best.evidence().contains("target_distance_scope=spatial_only"));
        assertEquals(Match3Board.findSwaps(p).size(),moves.size());
    }
    @Test public void theLastStepCannotClaimPreparatoryProgress() {
        Match3MoveValue best=Match3MoveRanker.rankedMoves(board(),goal(1,Match3Goals.Kind.COIN,1)).get(0);
        assertEquals(0,best.swap.fromRow);assertEquals("",best.reason);
        assertEquals(Match3TargetFrontier.UNAVAILABLE,best.targetDistance);
    }
    @Test public void anUnlocatedOrAbstainedGoalDoesNotGiveAnArbitraryExchangeAPreparationClaim() {
        Match3Position p=board();
        Match3Goals ice=goal(8,Match3Goals.Kind.ICE,1);
        Match3MoveValue unlocated=Match3MoveRanker.rankedMoves(p,ice).get(0);
        assertEquals(0,unlocated.swap.fromRow);assertEquals("",unlocated.reason);
        Match3MoveValue abstained=Match3MoveRanker.rankedMoves(p,goal(8,Match3Goals.Kind.COIN,1),
                Collections.singleton(Match3Goals.Kind.COIN)).get(0);
        assertEquals(0,abstained.swap.fromRow);assertEquals("",abstained.reason);
    }
    @Test public void coldSkyUnknownForegroundAndUnverifiedIceCannotBecomeDestinations() {
        Match3Position p=board();Match3Position.Cell[][] cells=new Match3Position.Cell[p.rows][p.cols];
        for(int r=0;r<p.rows;r++)for(int c=0;c<p.cols;c++)cells[r][c]=p.cell(r,c);
        cells[5][3]=Match3Position.Cell.obstacle(Match3Position.Kind.EMPTY,0).withIce(1);
        cells[5][4]=Match3Position.Cell.obstacle(Match3Position.Kind.SURFACE,0).withIce(1);
        cells[5][5]=Match3Position.Cell.obstacle(Match3Position.Kind.UNKNOWN,-1).withIce(1);
        cells[5][6]=Match3Position.Cell.animalIdentity('B');
        Match3MoveValue best=Match3MoveRanker.rankedMoves(new Match3Position(cells),goal(8,Match3Goals.Kind.ICE,1)).get(0);
        assertEquals(0,best.swap.fromRow);assertEquals("",best.reason);assertEquals(0,best.directUnits);
    }
    @Test public void knownIceLocationCanGuidePreparationWithoutGrantingUnknownAnimalExchangeRules() {
        Match3Position p=board();Match3Position.Cell[][] cells=new Match3Position.Cell[p.rows][p.cols];
        for(int r=0;r<p.rows;r++)for(int c=0;c<p.cols;c++)cells[r][c]=p.cell(r,c);
        cells[5][6]=Match3Position.Cell.animalIdentity('B').withIce(1);
        Match3Position iceBoard=new Match3Position(cells);
        List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(iceBoard,goal(8,Match3Goals.Kind.ICE,1));
        Match3MoveValue best=moves.get(0);
        assertEquals(4,best.swap.fromRow);assertEquals(5,best.targetDistance);
        assertEquals("准备消冰",best.reason);assertEquals(0,best.directUnits);assertEquals(0,best.relevantHits);
        assertEquals(0,best.collected(Match3Goals.Kind.ICE));assertFalse(best.allTargetsFinish);
        assertEquals(Match3Position.SwapPermission.UNKNOWN,iceBoard.cell(5,6).swapPermission);
        assertFalse(iceBoard.cell(5,6).swappable);assertEquals('#',iceBoard.cell(5,6).code());
        for(Match3MoveValue value:moves) {
            assertFalse(value.swap.fromRow==5 && value.swap.fromCol==6);
            assertFalse(value.swap.toRow==5 && value.swap.toCol==6);
            assertFalse(value.swap.matchedPositions().get(5*p.cols+6));
            assertEquals(0,value.directUnits);
        }
    }
    @Test public void anUnreadCountDoesNotAddSpatialPreparationOrInventCompletion() {
        Match3MoveValue best=Match3MoveRanker.rankedMoves(board(),goal(8,Match3Goals.Kind.COIN,-1)).get(0);
        assertEquals(0,best.swap.fromRow);assertEquals(0,best.directUnits);assertFalse(best.allTargetsFinish);
        assertTrue(best.grounded);assertEquals("",best.reason);
        assertEquals(Match3TargetFrontier.UNAVAILABLE,best.targetDistance);
    }
    private static Match3Position with(Match3Position source,int r,int c,Match3Position.Cell cell) {
        Match3Position.Cell[][] cells=new Match3Position.Cell[source.rows][source.cols];
        for(int row=0;row<source.rows;row++)for(int col=0;col<source.cols;col++)cells[row][col]=source.cell(row,col);
        cells[r][c]=cell;return new Match3Position(cells);
    }
    @Test public void seeingAnotherIceLocationDoesNotCancelAStillValidPreparationSentence() {
        Match3Position source=with(board(),5,6,Match3Position.Cell.animalIdentity('B').withIce(1));
        Match3Goals goals=goal(8,Match3Goals.Kind.ICE,2);
        Match3MoveValue spoken=Match3MoveRanker.rankedMoves(source,goals).get(0);
        Match3Position clearer=with(source,5,4,Match3Position.Cell.animalIdentity('O').withIce(1));
        List<Match3MoveValue> current=Match3MoveRanker.rankedMoves(clearer,goals);
        assertEquals("准备消冰",spoken.reason);assertNotEquals(spoken.targetDistance,current.get(0).targetDistance);
        assertTrue(Match3HintValidity.valid(source,clearer,spoken,current));
    }
    @Test public void losingTheOnlySupportedIceLocationStillCancelsPreparation() {
        Match3Position source=with(board(),5,6,Match3Position.Cell.animalIdentity('B').withIce(1));
        Match3Goals goals=goal(8,Match3Goals.Kind.ICE,1);
        Match3MoveValue spoken=Match3MoveRanker.rankedMoves(source,goals).get(0);
        Match3Position lost=with(source,5,6,Match3Position.Cell.animalIdentity('B'));
        assertFalse(Match3HintValidity.valid(source,lost,spoken,Match3MoveRanker.rankedMoves(lost,goals)));
    }
    @Test public void proximityMatchesManhattanDistanceAcrossHolesWithoutGrantingMovementRules() {
        Match3Position source=with(board(),0,7,Match3Position.Cell.obstacle(Match3Position.Kind.COIN,1));
        source=with(source,5,6,Match3Position.Cell.obstacle(Match3Position.Kind.COIN,1));
        Match3TargetFrontier.Field field=Match3TargetFrontier.prepare(source,goal(8,Match3Goals.Kind.COIN,3),Collections.emptySet());
        for(int r=0;r<source.rows;r++)for(int c=0;c<source.cols;c++) {
            java.util.BitSet point=new java.util.BitSet();point.set(r*source.cols+c);
            Match3TargetFrontier distance=field.at(point);
            int expected=Math.min(Math.abs(r)+Math.abs(c-7),
                    Math.min(Math.abs(r-5)+Math.abs(c-5),Math.abs(r-5)+Math.abs(c-6)));
            assertEquals("Manhattan proximity at "+r+","+c,expected,distance.distance);
            assertEquals(Match3Goals.Kind.COIN,distance.kind);
        }
        assertFalse(source.cell(0,7).swappable);assertFalse(source.cell(5,6).swappable);
    }
}
