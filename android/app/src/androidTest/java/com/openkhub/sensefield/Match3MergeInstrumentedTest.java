package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic pixels only. Checks merge integration using Android's actual Bitmap/storage APIs. */
@RunWith(AndroidJUnit4.class)
public class Match3MergeInstrumentedTest {
    private static Context context() { return ApplicationProvider.getApplicationContext(); }

    @Test public void iceAndGapAreNamedAndNeverSuggestedAsSwappablePieces() {
        Bitmap frame = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        try {
            frame.eraseColor(Color.rgb(220, 236, 246));
            assertEquals('I', Match3Sampler.classifyPoint(frame, 20, 20));
            frame.eraseColor(Color.rgb(40, 56, 88));
            assertEquals(Match3Sampler.GAP_CELL, Match3Sampler.classifyPoint(frame, 20, 20));
            assertEquals("冰块", Match3Coach.pieceName('I'));
            assertEquals("空位", Match3Coach.pieceName(Match3Sampler.GAP_CELL));
            assertTrue(Match3Board.findRuns(new char[][]{{'I', 'I', 'I'}, {'H', 'H', 'H'}}).isEmpty());
            char[][] board = {{'R', 'R', 'I'}, {'G', 'H', 'R'}, {'Y', 'O', 'B'}};
            for (Match3Board.Swap swap : Match3Board.findSwaps(board)) {
                assertTrue(Match3Board.isPiece(board[swap.fromRow][swap.fromCol]));
                assertTrue(Match3Board.isPiece(board[swap.toRow][swap.toCol]));
            }
        } finally { frame.recycle(); }
    }

    private static Bitmap board() {
        Bitmap frame = Bitmap.createBitmap(576, 1280, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        canvas.drawColor(Color.rgb(90, 224, 245));
        Paint paint = new Paint();
        paint.setColor(Color.rgb(30, 42, 88));
        canvas.drawRect(88, 508, 490, 918, paint);
        int[] palette = {Color.rgb(216, 120, 48), Color.rgb(240, 208, 96),
                Color.rgb(64, 176, 64), Color.rgb(80, 160, 224)};
        int cw = (490 - 88) / 7, ch = (918 - 508) / 7;
        for (int row = 0; row < 7; row++) for (int col = 0; col < 7; col++) {
            paint.setColor(palette[(row * 3 + col) % palette.length]);
            canvas.drawOval(88 + cw * col + 3, 508 + ch * row + 3,
                    88 + cw * (col + 1) - 3, 508 + ch * (row + 1) - 3, paint);
        }
        return frame;
    }

    @Test public void actualAndroidGridCountAndTouchReadAgreeOnSevenRows() {
        Bitmap frame = board();
        try {
            int[] bounds = Match3Sampler.autoDetectBoard(frame);
            assertNotNull(bounds);
            assertEquals(7, Match3Sampler.detectGridCount(frame, bounds));
            Match3Sampler sampler = new Match3Sampler(context(), 7, 7,
                    bounds[0], bounds[1], bounds[2], bounds[3]);
            int left = frame.getWidth() * bounds[0] / 100;
            int top = frame.getHeight() * bounds[1] / 100;
            int width = frame.getWidth() * bounds[2] / 100 - left;
            int height = frame.getHeight() * bounds[3] / 100 - top;
            int[] hit = sampler.touchRead(frame, left + width * 9 / 14, top + height * 7 / 14);
            assertNotNull(hit);
            assertEquals(3, hit[0]);
            assertEquals(4, hit[1]);
            assertEquals(sampler.sample(frame)[3][4], (char) hit[2]);
            assertNull(sampler.touchRead(frame, left - 1, top));
        } finally { frame.recycle(); }
    }

    @Test public void offWhiteTutorialPopupIsDetectedWithoutRoundingBrightnessToZero() {
        Bitmap frame = Bitmap.createBitmap(576, 1280, Bitmap.Config.ARGB_8888);
        try {
            frame.eraseColor(Color.rgb(245, 245, 245));
            assertTrue(Match3Coach.isPopupShowing(frame));
            frame.eraseColor(Color.rgb(30, 42, 88));
            assertFalse(Match3Coach.isPopupShowing(frame));
        } finally { frame.recycle(); }
    }

    private static void drainDiagnostics() throws Exception {
        DiagnosticRecorder.IO.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private static DiagnosticRecorder recordPortrait(boolean allowPortrait) throws Exception {
        long now = SystemClock.elapsedRealtime();
        DiagnosticRecorder recorder = DiagnosticRecorder.start(context(), UUID.randomUUID().toString(), now, allowPortrait);
        int width = 72, height = 128;
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        for (int i = 0; i < width * height; i++) pixels.put((byte) 220).put((byte) 40).put((byte) 20).put((byte) 255);
        pixels.rewind();
        recorder.frame(NativeFrameResult.empty(), DiagnosticSnapshot.parse(null), pixels,
                width, height, width * 4, now, now + 1, 800);
        drainDiagnostics(); // Images complete before finishing the session invalidates pending image work.
        recorder.finish("synthetic_match3_merge_test");
        drainDiagnostics();
        assertEquals("", recorder.failure);
        return recorder;
    }

    @Test public void portraitDiagnosticsSaveRgbaAndMapModeKeepsLandscapeGuard() throws Exception {
        SharedPreferences prefs = GameProfile.settings(context());
        boolean present = prefs.contains(DiagnosticRecorder.PREF_IMAGES);
        boolean previous = prefs.getBoolean(DiagnosticRecorder.PREF_IMAGES, true);
        prefs.edit().putBoolean(DiagnosticRecorder.PREF_IMAGES, true).commit();
        try {
            DiagnosticRecorder match3 = recordPortrait(true);
            org.json.JSONObject metadata = new org.json.JSONObject(new String(Files.readAllBytes(
                    new File(match3.directory, "metadata.json").toPath()), StandardCharsets.UTF_8));
            assertEquals("0.4.3", metadata.getString("app_version"));
            assertEquals("0.4.3", metadata.getString("version_name"));
            assertEquals(20, metadata.getInt("version_code"));
            File[] images = new File(match3.directory, "images").listFiles((dir, name) -> name.startsWith("screen-"));
            assertNotNull(images);
            assertTrue(images.length > 0);
            Bitmap image = BitmapFactory.decodeFile(images[0].getAbsolutePath());
            assertNotNull(image);
            assertEquals(72, image.getWidth());
            assertEquals(128, image.getHeight());
            assertTrue(Color.red(image.getPixel(36, 64)) > 180);
            image.recycle();
            DiagnosticRecorder map = recordPortrait(false);
            assertFalse(new File(map.directory, "images").exists());
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (present) edit.putBoolean(DiagnosticRecorder.PREF_IMAGES, previous);
            else edit.remove(DiagnosticRecorder.PREF_IMAGES);
            edit.commit();
        }
    }

    @Test public void bothGameEntriesResolveAndMatch3RemainsAnExperienceBuild() {
        assertEquals(2, GameCatalog.GAMES.size());
        for (GameCatalog.GameEntry game : GameCatalog.GAMES) {
            assertTrue(game.available);
            assertNotNull(new Intent(context(), game.activityClass).resolveActivity(context().getPackageManager()));
        }
        assertEquals("体验版", GameCatalog.GAMES.get(1).availability);
    }

    private static boolean hasText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasText(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }

    @Test public void honorScreenKeepsItsOriginalControlsWithoutMatch3DeveloperTest() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(
                new Intent(context(), MainActivity.class))) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertTrue(hasText(root, "王者荣耀辅助"));
                assertTrue(hasText(root, "开始辅助"));
                assertTrue(hasText(root, "停止"));
                assertTrue(hasText(root, "设置"));
                assertTrue(hasText(root, "返回游戏选择"));
                assertFalse(hasText(root, "判定自测"));
                assertFalse(hasText(root, "实验判定设置与自测"));
            });
        }
    }

    @Test public void match3ScreenOpensWithLocalActionsWithoutExperimentalCloudControls() {
        SharedPreferences prefs = GameProfile.settings(context());
        boolean enabled = JevSettings.enabled(context());
        prefs.edit().putBoolean(JevSettings.PREF_ENABLED, false).commit();
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(
                new Intent(context(), Match3AssistActivity.class))) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertTrue(hasText(root, "开始实时识别（录屏授权）"));
                assertTrue(hasText(root, "选择游戏截图"));
                assertTrue(hasText(root, "查看诊断记录与导出（需先停止识别）"));
                assertTrue(hasText(root, "实验判定设置与自测"));
                assertFalse(hasText(root, "图标消歧判定"));
            });
        } finally { prefs.edit().putBoolean(JevSettings.PREF_ENABLED, enabled).commit(); }
    }
}
