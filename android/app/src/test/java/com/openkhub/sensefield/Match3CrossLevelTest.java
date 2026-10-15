package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** New arrangements and unknown artwork, independently of numbered level fixtures. */
public final class Match3CrossLevelTest {
    private static Match3Position board(int rows,int cols) {
        Match3Position.Cell[][] cells=new Match3Position.Cell[rows][cols];
        for(int r=0;r<rows;r++)for(int c=0;c<cols;c++)cells[r][c]=Match3Position.Cell.obstacle(Match3Position.Kind.UNKNOWN,-1);
        String[] island={"RGR","BRY","GPO"};
        for(int r=0;r<3;r++)for(int c=0;c<3;c++)cells[r][c]=Match3Position.Cell.animal(island[r].charAt(c));
        return new Match3Position(cells);
    }
    private static Match3Position replace(Match3Position source,int row,int col,Match3Position.Cell cell) {
        Match3Position.Cell[][] cells=new Match3Position.Cell[source.rows][source.cols];
        for(int r=0;r<source.rows;r++)for(int c=0;c<source.cols;c++)cells[r][c]=source.cell(r,c);
        cells[row][col]=cell;return new Match3Position(cells);
    }
    private static Match3CellConfirmation.Snapshot confirm(Match3CellConfirmation gate,Match3Position board,long start) {
        Match3CellConfirmation.Snapshot out=null;
        for(int i=0;i<3;i++)out=gate.accept(board,start+800*i);
        return out;
    }
    private static Match3MoveValue first(Match3Position position) {
        List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(position,Match3Goals.unknown(0));
        assertFalse(moves.isEmpty());return moves.get(0);
    }
    @Test public void mostlyUnknownBoardsCanUseAFreshProvenExchange() {
        Match3Position position=board(9,9);
        assertTrue(Match3LiveService.isUnreadableBoard(position.matrix())); // historical diagnostic only
        Match3CellConfirmation.Snapshot result=confirm(new Match3CellConfirmation(),position,100);
        assertTrue(result.quiet);assertEquals(0,result.unconfirmed);
        Match3MoveValue move=first(result.position);
        assertEquals(3,move.swap.matchedCells);
        for(int p=move.swap.matchedPositions().nextSetBit(0);p>=0;p=move.swap.matchedPositions().nextSetBit(p+1))
            assertEquals(Match3Position.Kind.ANIMAL,result.position.cell(p/9,p%9).kind);
    }
    @Test public void unrelatedUnfamiliarAnimationDoesNotRetireTheExchange() {
        Match3Position position=board(8,9);Match3CellConfirmation gate=new Match3CellConfirmation();
        Match3Position source=confirm(gate,position,100).position;Match3MoveValue move=first(source);
        for(int i=0;i<20;i++) {
            Match3Position live=replace(position,7,8,Match3Position.Cell.obstacle(
                    i%2==0?Match3Position.Kind.SURFACE:Match3Position.Kind.UNKNOWN,-1));
            Match3CellConfirmation.Snapshot current=gate.accept(live,2500+800L*i);
            assertTrue(current.quiet);
            assertTrue(Match3HintValidity.valid(source,current.position,move,
                    Match3MoveRanker.rankedMoves(current.position,Match3Goals.unknown(0))));
        }
    }
    @Test public void endpointLossRetiresImmediatelyAndReturningHistoryNeedsThreeFreshFrames() {
        Match3Position position=board(8,8);Match3CellConfirmation gate=new Match3CellConfirmation();
        Match3Position source=confirm(gate,position,100).position;Match3MoveValue move=first(source);
        Match3Position lost=replace(position,move.swap.fromRow,move.swap.fromCol,
                Match3Position.Cell.obstacle(Match3Position.Kind.UNKNOWN,-1));
        Match3Position current=gate.accept(lost,2500).position;
        assertFalse(Match3HintValidity.valid(source,current,move,Match3MoveRanker.rankedMoves(current,Match3Goals.unknown(0))));
        for(int i=0;i<3;i++) {
            current=gate.accept(position,3300+800L*i).position;
            assertEquals(i==2,Match3HintValidity.valid(source,current,move,
                    Match3MoveRanker.rankedMoves(current,Match3Goals.unknown(0))));
        }
    }
    @Test public void aRealColourChangeAnywhereWaitsForTheWholeBoardToSettle() {
        Match3Position position=replace(board(8,8),7,7,Match3Position.Cell.animal('G'));
        Match3CellConfirmation gate=new Match3CellConfirmation();confirm(gate,position,100);
        Match3Position changed=replace(position,7,7,Match3Position.Cell.animal('B'));
        assertFalse(gate.accept(changed,2500).quiet);
        assertFalse(gate.accept(changed,3300).quiet);assertFalse(gate.accept(changed,4100).quiet);
        assertTrue(gate.accept(changed,4900).quiet);
    }
    @Test public void aClaimedTaskNeighbourIsPartOfHintValidity() {
        Match3Position source=replace(board(8,8),0,3,Match3Position.Cell.obstacle(Match3Position.Kind.SNOW,1));
        Match3Goals goals=new Match3Goals(999,10,Collections.singletonList(
                new Match3Goals.Target(0,Match3Goals.Kind.SNOW,1,false)),true,100);
        Match3MoveValue expected=Match3MoveRanker.rankedMoves(source,goals).get(0);
        assertEquals(1,expected.collected(Match3Goals.Kind.SNOW));
        Match3Position lost=replace(source,0,3,Match3Position.Cell.obstacle(Match3Position.Kind.UNKNOWN,-1));
        assertFalse(Match3HintValidity.valid(source,lost,expected,Match3MoveRanker.rankedMoves(lost,goals)));
    }
    @Test public void reusedFramesLongGapsAndResetsCannotCertifyHistoricalAnimals() {
        Match3Position position=board(8,8);Match3CellConfirmation gate=new Match3CellConfirmation();
        assertFalse(gate.accept(position,100).quiet);
        for(int i=0;i<5;i++)assertFalse(gate.accept(position,100).quiet);
        assertFalse(gate.accept(position,900).quiet);assertTrue(gate.accept(position,1700).quiet);
        assertFalse(gate.accept(position,5000).quiet);assertFalse(gate.accept(position,100).quiet);
        gate.reset();assertFalse(gate.accept(position,9000).quiet);
        confirm(gate,position,10000);
        assertFalse(gate.accept(replace(position,0,0,Match3Position.Cell.animal('B')),11600).quiet);
    }
    @Test public void aThousandNewLayoutsNeedNoNumberedLevelTemplates() {
        Random random=new Random(0x20261010);
        int checked=0;
        for(int rows=6;rows<=9;rows++)for(int cols=6;cols<=9;cols++)for(int trial=0;trial<64;trial++) {
            Match3Position layout=board(rows,cols);
            for(int r=3;r<rows;r++)for(int c=3;c<cols;c++)layout=replace(layout,r,c,
                    Match3Position.Cell.obstacle(new Match3Position.Kind[]{Match3Position.Kind.SURFACE,
                            Match3Position.Kind.UNKNOWN,Match3Position.Kind.EMPTY}[random.nextInt(3)],-1));
            Match3Position current=confirm(new Match3CellConfirmation(),layout,100).position;
            Match3MoveValue move=first(current);
            assertEquals("Independent island: RGR becomes RRR",3,move.collected(Match3Goals.Kind.RED));
            assertEquals(0,move.swap.fromRow);assertEquals(1,move.swap.fromCol);
            assertEquals(1,move.swap.toRow);assertEquals(1,move.swap.toCol);
            checked++;
        }
        assertEquals(1024,checked);
    }
    private static int[] face(int offset,boolean decorated) {
        int[] patch=new int[256];
        for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
            int brightness=((x==5||x==10)&&y>=5&&y<=7)?-65:y==10&&x>=5&&x<=10?35:0;
            int red=170+brightness+offset,green=105+brightness+offset,blue=85+brightness+offset;
            patch[y*16+x]=0xff000000|red<<16|green<<8|blue;
            if(decorated && (x<3||x>12||y<3||y>12))patch[y*16+x]=0xffdd00ff;
        }
        return patch;
    }
    @Test public void animalFamiliesIgnoreLaneDecorationAndUniformBrightnessWithoutLearning() {
        List<Match3AnimalAppearance.Face> references=new ArrayList<>();
        references.add(new Match3AnimalAppearance.Face('O',face(0,false)));
        references.add(new Match3AnimalAppearance.Face('O',face(8,false)));
        for(int offset:new int[]{0,8,16,24})assertEquals('O',Match3AnimalAppearance.recognize(
                new Match3AnimalAppearance.Face('.',face(offset,true)),references,2));
        assertEquals('.',Match3AnimalAppearance.recognize(new Match3AnimalAppearance.Face('.',face(0,true)),
                references.subList(0,1),2));
        int[] hole=new int[256];java.util.Arrays.fill(hole,0xff31aecd);
        assertFalse(new Match3AnimalAppearance.Face('B',hole).detailed);
        assertEquals('.',Match3AnimalAppearance.recognize(new Match3AnimalAppearance.Face('.',hole),references,1));
        int[] unrelated=face(0,false);Random random=new Random(10);
        for(int y=3;y<13;y++)for(int x=3;x<13;x++) {
            int shift=random.nextInt(101)-50;
            unrelated[y*16+x]=0xff000000|(170+shift)<<16|(105+shift)<<8|85+shift;
        }
        assertEquals("Texture of similar hue is not an animal face",'.',Match3AnimalAppearance.recognize(
                new Match3AnimalAppearance.Face('.',unrelated),references,1));
        references.add(new Match3AnimalAppearance.Face('R',face(0,false)));
        assertEquals("Ambiguous artwork cannot invent a colour",'.',Match3AnimalAppearance.recognize(
                new Match3AnimalAppearance.Face('.',face(0,true)),references,1));
    }
    @Test public void differentUnknownMissionArtworkHasAnIdentityButNoRuleOrInventedGain() {
        Match3VisualIdentity identities=new Match3VisualIdentity();
        int[] first=new int[256],second=new int[256];java.util.Arrays.fill(first,0xff002080);java.util.Arrays.fill(second,0xffd08020);
        String a=identities.identify(0,first);assertEquals(a,identities.identify(0,first));
        String b=identities.identify(0,second);assertNotEquals(a,b);
        Match3Goals old=new Match3Goals(-1,10,Collections.singletonList(new Match3Goals.Target(0,Match3Goals.Kind.UNKNOWN,10,false,a)),true,100);
        Match3Goals next=new Match3Goals(-1,10,Collections.singletonList(new Match3Goals.Target(0,Match3Goals.Kind.UNKNOWN,10,false,b)),true,900);
        assertFalse(old.sameIdentity(next));assertFalse(old.sameValues(next));assertFalse(old.fullyKnown());
        assertEquals(-1,old.remaining(Match3Goals.Kind.UNKNOWN));
        for(Match3MoveValue value:Match3MoveRanker.rankedMoves(board(8,8),old)) {
            assertFalse(value.grounded);assertEquals(0,value.directUnits);assertEquals("",value.reason);
        }
    }
    private static Match3AnimalAppearance.Face unknownFace(int rotate) {
        int[] pixels=face(0,false);
        for(int i=0;i<pixels.length;i++) {
            int p=pixels[i];
            for(int k=0;k<rotate;k++)p=0xff000000|((p>>8&255)<<16)|((p&255)<<8)|(p>>16&255);
            pixels[i]=p;
        }
        return new Match3AnimalAppearance.Face('.',pixels);
    }
    @Test public void unfamiliarSpritesStillRevealExchangesAndCoherentFallsButNotAnIdleOutline() {
        Match3AnimalAppearance.Face a=unknownFace(0),b=unknownFace(1),c=unknownFace(2);
        Match3AppearanceMotion swap=new Match3AppearanceMotion();
        assertFalse(swap.accept(new Match3AnimalAppearance.Face[][]{{a,b},{c,c}},100));
        assertTrue(swap.accept(new Match3AnimalAppearance.Face[][]{{b,a},{c,c}},900));
        Match3AppearanceMotion fall=new Match3AppearanceMotion();
        assertFalse(fall.accept(new Match3AnimalAppearance.Face[][]{{a},{b},{c}},100));
        assertTrue(fall.accept(new Match3AnimalAppearance.Face[][]{{c},{a},{b}},900));
        Match3AppearanceMotion idle=new Match3AppearanceMotion();
        assertFalse(idle.accept(new Match3AnimalAppearance.Face[][]{{a,b},{c,c}},100));
        assertFalse(idle.accept(new Match3AnimalAppearance.Face[][]{
                {new Match3AnimalAppearance.Face('.',face(16,true)),b},{c,c}},900));
        assertFalse(idle.accept(new Match3AnimalAppearance.Face[][]{{b,a},{c,c}},4000));
    }
}
