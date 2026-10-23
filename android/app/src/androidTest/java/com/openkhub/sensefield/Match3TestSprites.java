package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.util.HashMap;
import java.util.Map;

/** Synthetic layouts use certified sprite artwork; a coloured circle is no longer a positive animal. */
final class Match3TestSprites {
    private final Map<Character,Bitmap> tiles=new HashMap<>();
    private final Paint paint=new Paint();
    Match3TestSprites(Context context) {
        Match3VisualCatalog catalog=Match3VisualCatalog.get(context);
        for(int index=0;index<catalog.animalBodies.size();index++) {
            char color=catalog.bodyColors.get(index);
            if(tiles.containsKey(color))continue;
            int[] pixels=catalog.animalBodies.get(index).copyPatch();for(int i=0;i<pixels.length;i++)pixels[i]|=0xff000000;
            tiles.put(color,Bitmap.createBitmap(pixels,16,16,Bitmap.Config.ARGB_8888));
        }
    }
    void draw(Canvas canvas,float left,float top,float size,char color) {
        Bitmap tile=tiles.get(color);
        if(tile==null)throw new AssertionError("Missing certified sprite: "+color);
        canvas.drawBitmap(tile,null,new RectF(left,top,left+size,top+size),paint);
    }
}
