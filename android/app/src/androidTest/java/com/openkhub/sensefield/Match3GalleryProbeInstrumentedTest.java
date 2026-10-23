package com.openkhub.sensefield;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
/** Local-only development diagnosis; contains no inferred truth labels. */
public final class Match3GalleryProbeInstrumentedTest {
    @Test public void reportGalleryCompetitionOnExistingHudAndObstacleFrames() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Match3VisualCatalog fixed;try(InputStream in=context.getAssets().open("match3-fixed-ui-v1.json")) {
            fixed=Match3VisualCatalog.withGallery(new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)),null);
        }
        Match3VisualCatalog gallery=Match3VisualCatalog.get(context);JSONArray report=new JSONArray();
        for(String name:new String[]{"screen-99-2252775972.jpg","screen-513-2325997784.jpg","task-feedback-1.png"}) {
            Bitmap frame;try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {frame=BitmapFactory.decodeStream(in);}
            BoardGeometry g=Match3Sampler.autoDetectGeometry(frame);
            if(g==null)g=new BoardGeometry(frame.getWidth(),frame.getHeight(),0,frame.getHeight()/3,frame.getWidth(),frame.getHeight()*9/10,9,9);
            Match3HudReader base=new Match3HudReader(fixed),candidate=new Match3HudReader(gallery);
            try {
                Match3Goals before=base.read(frame,g,100),after=candidate.read(frame,g,100);
                JSONObject result=new JSONObject().put("file",name).put("fixed",before.key()).put("gallery",after.key());
                JSONArray cards=new JSONArray();int[][] patches=candidate.diagnosticGoalPatches();
                for(int[] patch:patches) {
                    JSONObject card=new JSONObject().put("pixels",new JSONArray(patch));JSONArray distances=new JSONArray();int[] top=new int[256];
                    for(int y=0;y<16;y++)for(int x=0;x<16;x++)top[y*16+x]=patch[(y/2)*16+x];
                    Match3AnimalAppearance.Face shape=new Match3AnimalAppearance.Face('.',top);
                    for(Match3VisualCatalog.Pattern p:gallery.goals) {
                        int[] t=new int[256];for(int y=0;y<16;y++)for(int x=0;x<16;x++)t[y*16+x]=p.pixels[(y/2)*16+x];
                        float score=shape.difference(new Match3AnimalAppearance.Face('.',t));
                        distances.put(new JSONObject().put("kind",p.kind).put("rule",p.rule).put("mad",p.difference(patch))
                                .put("shape",Float.isFinite(score)?score:-1));
                    }
                    cards.put(card.put("distances",distances));
                }
                report.put(result.put("cards",cards));
            } finally {base.close();candidate.close();frame.recycle();}
        }
        Files.write(new File(context.getExternalFilesDir("match3-holdout"),"gallery-probe.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
