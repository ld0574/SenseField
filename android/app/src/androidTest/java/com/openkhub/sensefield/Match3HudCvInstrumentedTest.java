package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.*;
import org.junit.Test;

/** Frozen development screenshots; reviewed labels and geometry, no independent accuracy claim. */
public final class Match3HudCvInstrumentedTest {
    private static Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static byte[] asset(String name) throws Exception {
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) { return in.readAllBytes(); }
    }
    private static Bitmap frame(String id) throws Exception {
        JSONObject input=new JSONObject(new String(asset("live-elements-frames.json"),java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(input.getBoolean("independent"));JSONArray frames=input.getJSONArray("frames");
        for(int i=0;i<frames.length();i++) {
            JSONObject entry=frames.getJSONObject(i);if(!id.equals(entry.getString("id")))continue;
            byte[] bytes=asset(entry.getString("file"));StringBuilder sha=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format(Locale.ROOT,"%02x",b&255));
            assertEquals(entry.getString("sha256"),sha.toString());
            Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(bitmap);return bitmap;
        }
        throw new IllegalArgumentException("Missing frozen frame: "+id);
    }
    private static BoardGeometry header(Bitmap bitmap) {
        // Explicit reviewed header boundary; this does not test automatic grid detection.
        return new BoardGeometry(bitmap.getWidth(),bitmap.getHeight(),0,bitmap.getHeight()/3,
                bitmap.getWidth(),bitmap.getHeight()*9/10,9,9);
    }
    @Test public void seventyTwoStaysExactOnFreshCachedAndProjectionScaleReads() throws Exception {
        for(String id:new String[]{"83train","83test"}) {
            Bitmap original=frame(id),scaled=Bitmap.createScaledBitmap(original,1220,2712,true);
            Match3HudReader reader=new Match3HudReader(app());
            try {
                for(Bitmap bitmap:new Bitmap[]{original,scaled,original})for(long at:new long[]{100,900}) {
                    Match3Goals goals=reader.read(bitmap,header(bitmap),at);
                    assertTrue(id+":"+reader.status(),goals.hudVerified);
                    assertEquals(id,72,goals.remaining(Match3Goals.Kind.ICE));assertEquals(id,27,goals.steps);
                    assertEquals(at,goals.observedAtMs);assertFalse(goals.finished());
                }
            } finally {reader.close();original.recycle();scaled.recycle();}
        }
    }
    @Test public void aClippedSevenInvalidatesTheWholeCountInsteadOfDroppingTheGlyph() throws Exception {
        Bitmap original=frame("83train"),clipped=original.copy(Bitmap.Config.ARGB_8888,true);
        // Extend the observed stem into the counter's lower crop edge. It is a
        // substantial clipped glyph, so neither 72 nor the surviving 2 is valid.
        for(int y=88;y<=97;y++)for(int x=222;x<=224;x++)clipped.setPixel(x,y,0xffffffff);
        Match3HudReader reader=new Match3HudReader(app());
        try {
            Match3Goals goals=reader.read(clipped,header(clipped),100);
            assertTrue(goals.hudVerified);assertEquals(-1,goals.remaining(Match3Goals.Kind.ICE));assertFalse(goals.finished());
        } finally {reader.close();original.recycle();clipped.recycle();}
    }
}
