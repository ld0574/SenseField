package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Generated layout/appearance perturbations, not unseen-patient accuracy evidence. */
public final class Match3CrossLevelVisionInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    @Test public void generatedRectanglesWithUnfamiliarObjectsKeepTheirKnownAnimalIsland() {
        int[] colors={0xffeb2020,0xff44d832,0xff55a9ed,0xffefb630,0xffb15edb,0xffc78030};
        String names="RGBYPO";String[] island={"RGR","BRY","GPO"};
        Match3TestSprites sprites=new Match3TestSprites(context());
        for(int rows=6;rows<=9;rows++)for(int cols=6;cols<=9;cols++) {
            Bitmap frame=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(frame);Paint paint=new Paint();
            int left=20,top=320,cell=42;canvas.drawColor(0xff33c9ef);
            paint.setColor(0xff1e2a58);canvas.drawRect(left,top,left+cols*cell,top+rows*cell,paint);
            for(int r=0;r<rows;r++)for(int c=0;c<cols;c++) {
                if(r<3 && c<3)sprites.draw(canvas,left+cell*c,top+cell*r,cell,island[r].charAt(c));
                else { paint.setColor(0xff909090);canvas.drawCircle(left+cell*(c+.5f),top+cell*(r+.5f),cell*.42f,paint); }
            }
            try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,left,top,left+cols*cell,top+rows*cell,rows,cols))) {
                Match3Position position=sampler.samplePosition(frame);
                for(int r=0;r<rows;r++)for(int c=0;c<cols;c++)
                    if(r<3 && c<3)assertEquals("Known island "+rows+"x"+cols+" "+r+","+c,island[r].charAt(c),position.cell(r,c).color);
                    else assertFalse("Unknown object is not an animal",position.cell(r,c).swappable);
            } finally {frame.recycle();}
        }
    }
    @Test public void animalFamilyFacesSurviveNewLaneArtAndLightingInTheNativeSampler() throws Exception {
        JSONObject catalog;
        try(InputStream in=context().getAssets().open("match3-fixed-ui-v1.json")) {
            catalog=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
        JSONArray cells=catalog.getJSONArray("cells");int checked=0;
        for(int index=0;index<cells.length();index++) {
            JSONObject source=cells.getJSONObject(index);String kind=source.getString("kind");
            if(!kind.startsWith("animal_") || !"ordinary_uncovered_animal".equals(source.optString("rule")))continue;
            JSONArray pixels=source.getJSONArray("pixels");
            for(int offset:new int[]{0,16}) {
                Bitmap frame=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888),tile=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
                int[] transformed=new int[256];
                for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                    int pixel=pixels.getInt(y*16+x),color=0xff000000;
                    for(int shift=0;shift<=16;shift+=8)color|=Math.min(255,(pixel>>shift&255)+offset)<<shift;
                    transformed[y*16+x]=x<3||x>12||y<3||y>12?0xffda6699:color;
                }
                tile.setPixels(transformed,0,16,0,0,16,16);Canvas canvas=new Canvas(frame);Paint p=new Paint();canvas.drawColor(0xffda6699);
                canvas.drawBitmap(tile,null,new Rect(24,324,56,356),p);
                try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,20,320,340,640,8,8))) {
                    Match3Position position=sampler.samplePosition(frame);
                    assertEquals(kind+" decorated lane offset="+offset,kind.charAt(7),position.cell(0,0).color);
                    assertFalse("Unfamiliar outer covering is not certified by the identical center face",position.cell(0,0).swappable);
                    checked++;
                } finally {tile.recycle();frame.recycle();}
            }
        }
        assertEquals(40,checked);
    }
}
