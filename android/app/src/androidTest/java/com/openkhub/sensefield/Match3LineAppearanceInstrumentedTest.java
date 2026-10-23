package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Reviewed real poses and controlled removal of their directional evidence. */
public final class Match3LineAppearanceInstrumentedTest {
    private static Context app() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }
    private static JSONArray poses() throws Exception {
        try (InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("match3-reviewed-line-poses-v1.json")) {
            JSONObject fixture=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            assertFalse(fixture.getBoolean("independent"));
            return fixture.getJSONArray("samples");
        }
    }
    private static int[] pixels(JSONObject pose) throws Exception {
        byte[] rgb=Base64.decode(pose.getString("pixels_base64"),Base64.DEFAULT);
        assertEquals(768,rgb.length);
        int[] result=new int[256];
        for(int i=0;i<result.length;i++) result[i]=0xff000000 | (rgb[3*i]&255)<<16
                | (rgb[3*i+1]&255)<<8 | rgb[3*i+2]&255;
        return result;
    }
    private static Match3Position.Cell observe(int[] pixels) {
        Bitmap frame=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
        try (Match3Sampler sampler=new Match3Sampler(app(),new BoardGeometry(16,16,0,0,16,16,1,1))) {
            frame.setPixels(pixels,0,16,0,0,16,16);
            return sampler.samplePosition(frame,100).cell(0,0);
        } finally { frame.recycle(); }
    }
    @Test public void reviewedHorizontalChickPosesHaveCurrentCompletePermissions() throws Exception {
        JSONArray poses=poses();assertEquals(22,poses.length());
        int admitted=0;StringBuilder missing=new StringBuilder();
        for(int i=0;i<poses.length();i++) {
            JSONObject pose=poses.getJSONObject(i);assertTrue(pose.getBoolean("reviewed"));
            Match3Position.Cell cell=observe(pixels(pose));
            if(cell.kind==Match3Position.Kind.ANIMAL && cell.color=='Y' && cell.swappable
                    && cell.effect==Match3Position.Effect.HORIZONTAL && cell.iceLayers==0
                    && cell.cover==Match3Position.Cover.NONE
                    && cell.activationPermission==Match3Position.SwapPermission.YES) admitted++;
            else missing.append(pose.getString("id")).append(':').append(cell.effect).append('/').append(cell.swapPermission).append(' ');
        }
        assertEquals(missing.toString(),22,admitted);
    }
    @Test public void identicalFacesWithoutDirectionalArrowsCannotAcquireLineActivation() throws Exception {
        JSONArray poses=poses();
        for(int i=0;i<poses.length();i++) {
            JSONObject pose=poses.getJSONObject(i);int[] original=pixels(pose),withoutArrows=original.clone();
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)
                if(x<4 || x>=12) withoutArrows[y*16+x]=0xff203050;
            // The entire central face is unchanged; only direction evidence is removed.
            for(int y=0;y<16;y++)for(int x=4;x<12;x++) assertEquals(original[y*16+x],withoutArrows[y*16+x]);
            Match3Position.Cell cell=observe(withoutArrows);
            assertNotEquals(pose.getString("id"),Match3Position.Effect.HORIZONTAL,cell.effect);
            assertNotEquals(pose.getString("id"),Match3Position.Effect.VERTICAL,cell.effect);
            assertNotEquals(pose.getString("id"),Match3Position.SwapPermission.YES,cell.activationPermission);
        }
    }
}
