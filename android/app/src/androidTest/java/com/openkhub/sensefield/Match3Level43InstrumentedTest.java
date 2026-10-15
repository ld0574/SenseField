package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.Collections;

/** Explicit diagnostic JPEG regression; these images are never shipped in the app. */
@RunWith(AndroidJUnit4.class)
public final class Match3Level43InstrumentedTest {
    private static final String[] BOARDS = {
            "screen-34-2258491893.jpg", "screen-47-2258502432.jpg", "screen-86-2258534080.jpg"
    };

    private static Bitmap load(String name) throws Exception {
        InputStream input;
        try { input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name); }
        catch (java.io.IOException absent) { Assume.assumeNoException(absent); return null; }
        try (input) { return BitmapFactory.decodeStream(input); }
    }

    @Test public void irregularBoardWithLargeObstaclesKeepsItsNineByNineGrid() throws Exception {
        for (String name : BOARDS) {
            Bitmap frame = load(name);
            try {
                BoardGeometry detected = Match3Sampler.autoDetectGeometry(frame);
                BoardGeometry labelled = new BoardGeometry(frame.getWidth(), frame.getHeight(),
                        12, 322, 420, 730, 9, 9);
                char[][] matrix = Match3Sampler.sample(frame, labelled, Collections.emptyList());
                StringBuilder rows = new StringBuilder();
                for (char[] row : matrix) rows.append(new String(row).replace(' ', '_')).append('/');
                Log.i("Match3Level43Replay", name + " detected=" + detected + " board=" + rows
                        + " runs=" + Match3Board.findRuns(matrix).size()
                        + " swaps=" + Match3Board.findSwaps(matrix).size());
                assertNotNull(name, detected);
                assertEquals(name, 9, detected.rows);
                assertEquals(name, 9, detected.cols);
                assertTrue(name + " left", Math.abs(12 - detected.left) <= 4);
                assertTrue(name + " top", Math.abs(322 - detected.top) <= 4);
                assertTrue(name + " right", Math.abs(420 - detected.right) <= 4);
                assertTrue(name + " bottom", Math.abs(730 - detected.bottom) <= 4);
            } finally { frame.recycle(); }
        }
    }

    @Test public void staticObstaclesAndHolesDoNotBecomeAnimalsOrAFalseCascade() throws Exception {
        Bitmap frame = load(BOARDS[1]);
        try {
            BoardGeometry g = new BoardGeometry(frame.getWidth(), frame.getHeight(), 12, 322, 420, 730, 9, 9);
            char[][] board = Match3Sampler.sample(frame, g, Collections.emptyList());
            assertFalse("large orange obstacle is not a bear", Match3Sampler.isMovable(board[5][5]));
            assertFalse("sky hole is not a hippo", Match3Sampler.isMovable(board[3][0]));
            assertTrue("stable screenshot must not wait forever for a cascade", Match3Board.findRuns(board).isEmpty());
            assertFalse("visible ordinary animals have valid exchanges", Match3Board.findSwaps(board).isEmpty());
        } finally { frame.recycle(); }
    }

    @Test public void captureSizedReplayAlsoExcludesLargeObstacles() throws Exception {
        Bitmap source = load(BOARDS[1]);
        Bitmap frame = Bitmap.createScaledBitmap(source, 1220, 2712, true);
        try {
            BoardGeometry g = Match3Sampler.autoDetectGeometry(frame);
            Log.i("Match3Level43Replay", "capture_sized_geometry=" + g);
            assertNotNull(g); assertEquals(9, g.rows); assertEquals(9, g.cols);
            char[][] board = Match3Sampler.sample(frame, g, Collections.emptyList());
            assertFalse(Match3Sampler.isMovable(board[5][5]));
            assertFalse(Match3Sampler.isMovable(board[3][0]));
            assertTrue(Match3Board.findRuns(board).isEmpty());
            assertFalse(Match3Board.findSwaps(board).isEmpty());
        } finally { frame.recycle(); source.recycle(); }
    }

}
