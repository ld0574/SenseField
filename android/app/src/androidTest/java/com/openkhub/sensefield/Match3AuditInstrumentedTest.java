package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Native Bitmap checks with an isolated learning directory; no user templates are touched. */
@RunWith(AndroidJUnit4.class)
public final class Match3AuditInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static Context isolated(File files) {
        return new ContextWrapper(context()) { @Override public File getFilesDir() { return files; } };
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    @Test public void cachedTemplatePixelsAreIdenticalToNativeScaling() {
        Bitmap thumb = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        Bitmap cell = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Bitmap previous = null;
        try {
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++)
                thumb.setPixel(x, y, Color.rgb((x * 31 + y) % 256, y * 7, x * 5));
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                cell.setPixel(x, y, Color.rgb(x * 11, y * 13, 50));
            Match3Sampler.SpecialTemplate template = new Match3Sampler.SpecialTemplate("cache-test", thumb);
            previous = Bitmap.createScaledBitmap(thumb, 16, 16, true);
            int[] pixels = new int[256]; cell.getPixels(pixels, 0, 16, 0, 0, 16, 16);
            long difference = 0;
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int a = cell.getPixel(x, y), b = previous.getPixel(x, y);
                assertEquals(b, template.comparisonPixels[y * 16 + x]);
                difference += Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                        + Math.abs(Color.blue(a) - Color.blue(b));
            }
            assertEquals(difference / (16f * 16f * 3f), Match3Sampler.meanAbsDiff(pixels, template.comparisonPixels), 0f);
        } finally {
            if (previous != null && previous != thumb) previous.recycle(); thumb.recycle(); cell.recycle();
        }
    }

    @Test public void repeatedLearningLoadsReleaseBitmapsAndCloseIsIdempotent() throws Exception {
        File root = new File(context().getCacheDir(), "match3-audit-" + System.nanoTime()); root.mkdirs();
        Context fixture = isolated(root);
        Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        Bitmap frame = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        try {
            source.eraseColor(Color.RED);
            Match3Sampler.saveTemplate(fixture, "红狐狸", source);
            assertFalse("Saving a same-sized image must not recycle the caller's Bitmap", source.isRecycled());
            for (int i = 0; i < 60; i++) {
                List<Match3Sampler.SpecialTemplate> loaded = Match3Sampler.loadTemplates(fixture);
                assertEquals(1, loaded.size()); Bitmap nativeThumb = loaded.get(0).thumb;
                Match3Sampler.recycleTemplates(loaded);
                assertTrue(nativeThumb.isRecycled());
            }
            Match3Sampler sampler = new Match3Sampler(fixture, new BoardGeometry(128, 128, 0, 0, 128, 128, 1, 1));
            assertNotNull(sampler.sample(frame)); sampler.close(); sampler.close();
            try { sampler.sample(frame); fail("A retired sampler must not use recycled resources"); }
            catch (IllegalStateException expected) { }
            assertFalse("The frame belongs to the capture owner", frame.isRecycled());
        } finally { source.recycle(); frame.recycle(); delete(root); }
    }

    @Test public void learningNamesCannotWriteOutsideTheTemplateDirectory() throws Exception {
        File root = new File(context().getCacheDir(), "match3-name-audit-" + System.nanoTime()); root.mkdirs();
        Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        try {
            for (String name : new String[]{"../other", "nested/name", "nested\\name", "", null, "a\0b"}) {
                try { Match3Sampler.saveTemplate(isolated(root), name, source); fail("Invalid name accepted: " + name); }
                catch (IOException expected) { }
            }
            assertFalse(new File(root, "other.png").exists()); assertFalse(source.isRecycled());
        } finally { source.recycle(); delete(root); }
    }

    @Test public void anotherCatalogCannotRenameOrRecodeTheActiveSampler() throws Exception {
        File root = new File(context().getCacheDir(), "match3-catalog-audit-" + System.nanoTime()); root.mkdirs();
        Context first = isolated(new File(root, "first")), second = isolated(new File(root, "second"));
        first.getFilesDir().mkdirs(); second.getFilesDir().mkdirs();
        Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        try {
            Match3Sampler.saveTemplate(first, "木箱", source);
            try (Match3Sampler active = new Match3Sampler(first, new BoardGeometry(128, 128, 0, 0, 128, 128, 1, 1))) {
                List<Match3Sampler.SpecialTemplate> old = Match3Sampler.loadTemplates(first);
                try {
                    assertEquals('1', old.get(0).code); assertEquals("木箱", active.pieceName('1'));
                    Match3Sampler.saveTemplate(second, "木箱", source);
                    Match3Sampler.saveTemplate(second, "彩虹球", source);
                    List<Match3Sampler.SpecialTemplate> newer = Match3Sampler.loadTemplates(second);
                    try {
                        assertEquals('2', Match3Sampler.templateCode("木箱"));
                        assertEquals("木箱", active.pieceName('1'));
                        assertEquals('1', Match3Sampler.classifyCell(source, 16, 16, 4, old));
                    } finally { Match3Sampler.recycleTemplates(newer); }
                } finally { Match3Sampler.recycleTemplates(old); }
            }
        } finally { source.recycle(); delete(root); }
    }
}
