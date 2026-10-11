package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;
import org.junit.Test;

/** Explicit private development inputs; expectations never pin the runtime grid. */
public final class Match3FlowerCvInstrumentedTest {
    private static Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static File directory() { return app().getExternalFilesDir("match3-flower-cv"); }
    private static JSONArray inputs() throws Exception {
        JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(directory(),"manifest.json").toPath()),StandardCharsets.UTF_8));
        assertFalse(manifest.getBoolean("independent"));assertEquals("developer_visual_review",manifest.getString("review_scope"));
        return manifest.getJSONArray("frames");
    }
    private static Bitmap frame(JSONObject entry) throws Exception {
        File input=new File(directory(),entry.getString("file"));
        assertTrue(input.getCanonicalPath().startsWith(directory().getCanonicalPath()+File.separator));
        byte[] bytes=Files.readAllBytes(input.toPath());assertEquals(entry.getString("sha256"),Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(bitmap);return bitmap;
    }
    @Test public void reviewedFlowerFramesKeepAutomaticGeometryIdentityOnlyPermissionsAndCounts() throws Exception {
        JSONArray inputs=inputs();assertEquals(9,inputs.length());
        for(int i=0;i<inputs.length();i++) {
            JSONObject entry=inputs.getJSONObject(i);Bitmap bitmap=frame(entry);Match3HudReader hud=new Match3HudReader(app());
            try {
                BoardGeometry g=Match3Sampler.autoDetectGeometry(bitmap);assertNotNull(entry.getString("file"),g);
                JSONObject expected=entry.getJSONObject("expected_geometry");
                assertEquals(expected.getInt("rows"),g.rows);assertEquals(expected.getInt("cols"),g.cols);
                assertTrue(Math.abs(expected.getInt("left")-g.left)<=4);assertTrue(Math.abs(expected.getInt("top")-g.top)<=4);
                try(Match3Sampler sampler=new Match3Sampler(app(),g)) {
                    Match3Position p=sampler.samplePosition(bitmap);int flowers=0;
                    for(int r=0;r<p.rows;r++)for(int c=0;c<p.cols;c++)if(p.cell(r,c).kind==Match3Position.Kind.FLOWER) {
                        flowers++;assertFalse(p.cell(r,c).swappable);assertEquals(Match3Position.Cover.UNKNOWN,p.cell(r,c).cover);
                        assertEquals(-1,p.cell(r,c).layers);assertEquals(Match3Position.SwapPermission.UNKNOWN,p.cell(r,c).adjacentDamagePermission);
                    }
                    assertEquals(entry.getInt("expected_supported_flower_observations"),flowers);
                }
                if(entry.has("expected_flower_count"))for(long at:new long[]{100,900}) {
                    Match3Goals goals=hud.read(bitmap,g,at);assertTrue(hud.status(),goals.hudVerified);
                    assertEquals(entry.getInt("expected_flower_count"),goals.remaining(Match3Goals.Kind.FLOWER));
                    assertEquals(Match3Goals.Mode.UNKNOWN,goals.targets.get(0).mode);
                }
            } finally {hud.close();bitmap.recycle();}
        }
    }
    @Test public void sameBaselineUnknownAndClippedDigitsCannotDisappearWithIconFragments() throws Exception {
        Bitmap original=frame(inputs().getJSONObject(0));
        for(boolean clipped:new boolean[]{false,true}) {
            Bitmap changed=original.copy(Bitmap.Config.ARGB_8888,true);Match3HudReader hud=new Match3HudReader(app());
            try {
                if(clipped)for(int y=89;y<98;y++)for(int x=202;x<205;x++)changed.setPixel(x,y,0xffffffff);
                else {
                    for(int y=83;y<95;y++)for(int x=160;x<166;x++)changed.setPixel(x,y,0xff503020);
                    for(int y=84;y<91;y++)for(int x=161;x<165;x++)changed.setPixel(x,y,0xffffffff);
                }
                BoardGeometry g=Match3Sampler.autoDetectGeometry(changed);assertNotNull(g);
                Match3Goals goals=hud.read(changed,g,100);assertTrue(goals.hudVerified);
                assertEquals(-1,goals.remaining(Match3Goals.Kind.FLOWER));assertFalse(goals.finished());
            } finally {hud.close();changed.recycle();}
        }
        original.recycle();
    }
}
