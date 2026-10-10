package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

public final class Match3GeometryTest {
    @Test public void overlappingDisconnectedBorderEnvelopesFormOneCandidateWithoutJoiningLetterboxes() {
        boolean[][] mask=new boolean[300][160];
        for(int y=0;y<300;y++) {mask[y][2]=true;mask[y][157]=true;}
        // Two disconnected L shapes. Their envelopes overlap, their dark pixels do not.
        for(int y=100;y<220;y++)for(int x=20;x<66;x++)if(y<160 || x<25)mask[y][x]=true;
        for(int y=100;y<220;y++)for(int x=58;x<140;x++)if(y>=200 || x>=135)mask[y][x]=true;
        int original=0;for(boolean[] row:mask)for(boolean pixel:row)if(pixel)original++;
        assertArrayEquals(new int[]{58,100,140,220},Match3Sampler.connectedBounds(mask));
        assertArrayEquals(new int[]{20,100,140,220},Match3Sampler.connectedBoardBounds(mask));
        int after=0;for(boolean[] row:mask)for(boolean pixel:row)if(pixel)after++;
        assertEquals("The candidate never fills or invents dark pixels",original,after);
    }
    private static BoardGeometry geometry(int rows, int cols) {
        return new BoardGeometry(1260, 2800, 140, 900, 1120, 1880, rows, cols);
    }
    @Test public void connectedBoardRejectsDisconnectedFullHeightLetterboxes() {
        boolean[][] mask = new boolean[300][160];
        for (int y = 0; y < 300; y++) { mask[y][2] = true; mask[y][157] = true; }
        for (int y = 100; y < 220; y++) for (int x = 20; x < 140; x++)
            if (y == 219 || x == 139 || (y - 100) % 17 == 0 || (x - 20) % 17 == 0)
                mask[y][x] = true;
        assertArrayEquals(new int[]{20, 100, 140, 220}, Match3Sampler.connectedBounds(mask));
        assertNull(Match3Sampler.connectedBounds(new boolean[300][160]));
    }
    @Test public void centersAndTouchUseTheSameGlobalBoardOriginForAllSupportedSizes() {
        for (int rows = 6; rows <= 9; rows++) for (int cols = 6; cols <= 9; cols++) {
            BoardGeometry g = geometry(rows, cols);
            for (int row = 0; row < rows; row++) for (int col = 0; col < cols; col++)
                assertArrayEquals(new int[]{row, col}, g.cellAt(g.centerX(col), g.centerY(row)));
            assertNull(g.cellAt(g.left - 1, g.top));
            assertNull(g.cellAt(g.right, g.bottom - 1));
            assertTrue(g.mapsToDisplay(1260, 2800));
            assertTrue(g.mapsToDisplay(630, 1400));
            assertFalse(g.mapsToDisplay(2800, 1260));
        }
    }
    @Test public void pooledNineBySixBoundsAllowOnlyTheirMaskQuantization() {
        boolean[][] mask = new boolean[170][300];
        for (int y = 40; y < 143; y++) for (int x = 25; x < 93; x++)
            if ((y - 40) % 12 == 0 || (x - 25) % 17 == 0 || y == 142 || x == 92) mask[y][x] = true;
        assertArrayEquals(new int[]{25, 40, 93, 143}, Match3Sampler.connectedBounds(mask));
        boolean[][] strip = new boolean[170][300];
        for (int y = 10; y < 160; y++) for (int x = 25; x < 93; x++) strip[y][x] = true;
        assertNull("A thin strip is still not a supported 6..9 grid", Match3Sampler.connectedBounds(strip));
    }
    @Test public void threeConsecutiveGeometryFramesCannotReuseAFailedOrDifferentGrid() {
        Match3GeometryConfirmation gate = new Match3GeometryConfirmation();
        BoardGeometry seven = geometry(7, 7), eight = geometry(8, 8);
        assertNull(gate.accept(seven)); assertNull(gate.accept(seven));
        assertNull(gate.accept(null)); assertNull(gate.accept(seven));
        assertNull(gate.accept(eight)); assertNull(gate.accept(eight));
        assertSame(eight, gate.accept(eight));
        gate.reset(); assertNull(gate.accept(eight));
    }
    @Test public void horizontalAndVerticalSpeechNameBothAxesWithoutRenumberingRegions() {
        assertEquals("第 5 行，第 4 列和第 5 列交换",
                Match3Board.swapSpeech(new Match3Board.Swap(4, 3, 4, 4, 1)));
        assertEquals("第 2 行和第 3 行，第 6 列交换",
                Match3Board.swapSpeech(new Match3Board.Swap(1, 5, 2, 5, 1)));
        Match3Hint hint = new Match3Hint("s", 3, 100, geometry(7, 7),
                new Match3Board.Swap(4, 3, 4, 4, 1));
        assertEquals("右下区域，第 5 行，第 4 列和第 5 列交换。", hint.speech);
        assertFalse(hint.speech.contains("个"));
    }
}
