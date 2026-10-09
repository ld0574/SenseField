package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Bounded local read of hanging goal cards and step badge; unsupported layouts abstain. */
final class Match3HudReader {
    private static final int WIDTH = 432, MAX_HEIGHT = 200;
    private final Match3VisualCatalog catalog;
    private final Match3VisualIdentity identities=new Match3VisualIdentity();
    private int[] previous;
    private int previousHeight;
    private Match3Goals cached;
    private Bitmap headerBitmap;
    private Canvas headerCanvas;
    private int[] working;
    private final boolean[] visited = new boolean[WIDTH*MAX_HEIGHT];
    private final int[] queue = new int[WIDTH*MAX_HEIGHT];
    private String status = "unread";
    Match3HudReader(Context context) { catalog = Match3VisualCatalog.get(context); }
    String status() { return status; }
    void clear() { previous = null; cached = null; previousHeight = 0; status = "unread";identities.clear(); }
    void close() { clear(); if(headerBitmap!=null)headerBitmap.recycle();headerBitmap=null;headerCanvas=null;working=null; }

    private static final class Box {
        final int left,top,right,bottom,area;
        Box(int l,int t,int r,int b,int area) { left=l;top=t;right=r;bottom=b;this.area=area; }
        int width() { return right-left; } int height() { return bottom-top; }
    }
    private static boolean orange(int color) {
        int r=color>>16&255,g=color>>8&255,b=color&255;
        return r>140 && r-g>20 && g-b>20;
    }
    private static boolean white(int color) {
        int r=color>>16&255,g=color>>8&255,b=color&255;
        return Math.min(r,Math.min(g,b))>=180 && Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<75;
    }
    private List<Box> components(boolean[] mask,int width,int height,int originX,int originY) {
        boolean[] seen = visited; Arrays.fill(seen,0,mask.length,false); List<Box> boxes = new ArrayList<>();
        for (int p=0;p<mask.length;p++) {
            if (!mask[p] || seen[p]) continue;
            int head=0,tail=1,l=p%width,r=l,t=p/width,b=t;queue[0]=p;seen[p]=true;
            while (head<tail) {
                int at=queue[head++],x=at%width,y=at/width;l=Math.min(l,x);r=Math.max(r,x);t=Math.min(t,y);b=Math.max(b,y);
                if (x>0 && mask[at-1] && !seen[at-1]) { seen[at-1]=true;queue[tail++]=at-1; }
                if (x+1<width && mask[at+1] && !seen[at+1]) { seen[at+1]=true;queue[tail++]=at+1; }
                if (y>0 && mask[at-width] && !seen[at-width]) { seen[at-width]=true;queue[tail++]=at-width; }
                if (y+1<height && mask[at+width] && !seen[at+width]) { seen[at+width]=true;queue[tail++]=at+width; }
            }
            boxes.add(new Box(l+originX,t+originY,r+originX+1,b+originY+1,tail));
        }
        return boxes;
    }
    Match3Goals read(Bitmap frame,BoardGeometry geometry,long at) {
        if (!catalog.available || geometry==null || frame.getHeight()<=frame.getWidth()) {
            status="unsupported_layout"; return Match3Goals.unknown(at);
        }
        int sourceHeight=Math.min(geometry.top,MAX_HEIGHT*frame.getWidth()/WIDTH);
        if (sourceHeight<=0) { status="no_header"; return Match3Goals.unknown(at); }
        int height=Math.max(1,sourceHeight*WIDTH/frame.getWidth());
        if(headerBitmap==null || headerBitmap.getHeight()!=height) {
            if(headerBitmap!=null)headerBitmap.recycle();
            headerBitmap=Bitmap.createBitmap(WIDTH,height,Bitmap.Config.ARGB_8888);headerCanvas=new Canvas(headerBitmap);
            working=new int[WIDTH*height];previous=null;
        }
        headerCanvas.drawBitmap(frame,new Rect(0,0,frame.getWidth(),sourceHeight),new Rect(0,0,WIDTH,height),null);
        int[] pixels=working;headerBitmap.getPixels(pixels,0,WIDTH,0,0,WIDTH,height);
        if (height==previousHeight && Arrays.equals(previous,pixels) && cached!=null)
            return new Match3Goals(cached.level,cached.steps,cached.targets,cached.hudVerified,at);
        int[] old=previous;previous=pixels;working=old==null?new int[pixels.length]:old;previousHeight=height;
        cached=readPixels(pixels,height,at);
        return cached;
    }
    private Match3Goals readPixels(int[] pixels,int height,long at) {
        if (height<80) { status="header_short"; return Match3Goals.unknown(at); }
        boolean[] mask=new boolean[WIDTH*height];
        for (int y=20;y<height;y++) for (int x=0;x<WIDTH;x++) mask[y*WIDTH+x]=orange(pixels[y*WIDTH+x]);
        List<Box> candidates=new ArrayList<>();
        for (Box box:components(mask,WIDTH,height,0,0)) {
            if (box.width()<22 || box.width()>100 || box.height()<18 || box.height()>180 || box.area<100) continue;
            int top=box.top;
            for (int y=box.top;y<box.bottom;y++) {
                int n=0;for(int x=box.left;x<box.right;x++) if(mask[y*WIDTH+x]) n++;
                if (n>=box.width()*.45f) { top=y;break; }
            }
            if (box.bottom-top>=18 && box.bottom-top<=110)
                candidates.add(new Box(box.left,top,box.right,box.bottom,box.area));
        }
        Box step=null;float best=Float.POSITIVE_INFINITY;
        List<Box> stepCandidates=new ArrayList<>(candidates);
        // Wide white digits can split the orange badge into separate components.
        // Join overlapping component envelopes only in the step area, then keep
        // the same positive badge template and independent numeral checks.
        List<Box> fragments=new ArrayList<>();
        for(Box box:candidates)if(box.left>WIDTH*.70f)fragments.add(box);
        for(int i=0;i<fragments.size();i++) {
            Box joined=fragments.get(i);boolean expanded;
            do {
                expanded=false;
                for(Box other:fragments)if(other.left<joined.right && other.right>joined.left
                        && other.top<joined.bottom && other.bottom>joined.top
                        && (other.left<joined.left || other.right>joined.right || other.top<joined.top || other.bottom>joined.bottom)) {
                    joined=new Box(Math.min(joined.left,other.left),Math.min(joined.top,other.top),
                            Math.max(joined.right,other.right),Math.max(joined.bottom,other.bottom),0);expanded=true;
                }
            }while(expanded);
            if(joined.width()<=100 && joined.height()<=110)stepCandidates.add(joined);
        }
        for (Box box:stepCandidates) if (box.left>WIDTH*.70f) {
            int[] patch=Match3VisualCatalog.patch(pixels,WIDTH,box.left,box.top,box.right,box.bottom);
            for (Match3VisualCatalog.Pattern pattern:catalog.steps) {
                float distance=pattern.difference(patch);
                if (distance<best && distance<=.14f) { best=distance;step=box; }
            }
        }
        if (step==null) { status="step_badge_unverified";return Match3Goals.unknown(at); }
        List<Box> boxes=new ArrayList<>();
        for (Box b:candidates) if (b.right<step.left && Math.abs(b.top-step.top)<36 && b.height()>=30 && b.height()<=75)
            boxes.add(b);
        boxes.sort(Comparator.comparingInt(b->b.left));
        if (boxes.isEmpty() || boxes.size()>6) { status="goal_cards_unverified";return Match3Goals.unknown(at); }
        float center=0;
        for (Box b:boxes) center+=(b.left+b.right)/2f;
        center/=boxes.size();
        // Missing or merged cards must not turn a partly read mission into "all done".
        if (Math.abs(center-WIDTH/2f)>WIDTH*.045f) { status="incomplete_goal_layout";return Match3Goals.unknown(at); }
        for (int i=1;i<boxes.size();i++) if (boxes.get(i).left-boxes.get(i-1).right>
                Math.max(boxes.get(i).width(),boxes.get(i-1).width())*.4f) {
            status="missing_goal_card";return Match3Goals.unknown(at);
        }
        List<Match3Goals.Target> targets=new ArrayList<>();
        for (int i=0;i<boxes.size();i++) {
            Box b=boxes.get(i);int[] patch=Match3VisualCatalog.patch(pixels,WIDTH,b.left,b.top,b.right,b.bottom);
            String identity=Match3VisualCatalog.recognize(catalog.goals,patch,.14f,.025f);
            Match3Goals.Kind kind=Match3Goals.Kind.UNKNOWN;
            if (identity!=null) try { kind=Match3Goals.Kind.valueOf(identity); } catch (IllegalArgumentException ignored) { }
            Box counter=new Box((int)(b.left+b.width()*.53f),(int)(b.top+b.height()*.49f),b.right-3,b.bottom-3,0);
            boolean complete=kind!=Match3Goals.Kind.UNKNOWN && checkmark(pixels,counter);
            int count=complete?0:integer(pixels,counter,false);
            // Only the icon's upper area is compared: changing counter digits are
            // not a new type, and different unknown mission artwork is not one kind.
            String visualId=kind==Match3Goals.Kind.UNKNOWN?identities.identify(i,
                    Match3VisualCatalog.patch(pixels,WIDTH,b.left+3,b.top+2,b.right-3,
                            b.top+Math.max(3,(int)(b.height()*.49f)))):"";
            targets.add(new Match3Goals.Target(i,kind,count,complete,visualId));
        }
        Box number=new Box((int)(step.left+step.width()*.10f),(int)(step.top+step.height()*.20f),
                (int)(step.right-step.width()*.10f),(int)(step.top+step.height()*.78f),0);
        int remaining=integer(pixels,number,true);
        status=remaining>=0?"observed":"steps_unread";
        int top=boxes.stream().mapToInt(b->b.top).min().orElse(0);
        Box levelBox=new Box(5,Math.max(0,top-12),Math.min(112,boxes.get(0).left-8),Math.min(height,top+24),0);
        int level=level(pixels,levelBox);
        return new Match3Goals(level,remaining,targets,true,at);
    }
    private boolean checkmark(int[] pixels,Box box) {
        int green=0;boolean[] mask=new boolean[box.width()*box.height()];
        for (int y=box.top;y<box.bottom;y++) for (int x=box.left;x<box.right;x++) {
            int color=pixels[y*WIDTH+x],r=color>>16&255,g=color>>8&255,b=color&255;
            boolean selected=g>120 && g-r>35 && g-b>35;
            mask[(y-box.top)*box.width()+x-box.left]=selected; if(selected)green++;
        }
        if (green<12) return false;
        for (Box b:components(mask,box.width(),box.height(),box.left,box.top))
            if(b.area>=12 && b.width()>=8 && b.height()>=7 && b.left>=box.left && b.right<=box.right
                    && b.bottom>=box.bottom-6) {
                StringBuilder bits=new StringBuilder(160);
                for(int y=0;y<16;y++)for(int x=0;x<10;x++) {
                    int xx=b.left+Math.min(b.width()-1,(2*x+1)*b.width()/20)-box.left;
                    int yy=b.top+Math.min(b.height()-1,(2*y+1)*b.height()/32)-box.top;
                    bits.append(mask[yy*box.width()+xx]?'1':'0');
                }
                if(catalog.checkmark(bits.toString(),b.width()/(float)b.height()))return true;
            }
        return false;
    }
    private int integer(int[] pixels,Box box,boolean step) {
        if (box.width()<=0 || box.height()<=0) return -1;
        int bgGreen=0,bgBlue=0;
        if(step) {
            int[] gs=new int[box.width()*box.height()],bs=new int[gs.length];int n=0;
            for(int y=box.top;y<box.bottom;y++)for(int x=box.left;x<box.right;x++) {
                int color=pixels[y*WIDTH+x];gs[n]=color>>8&255;bs[n++]=color&255;
            }
            Arrays.sort(gs);Arrays.sort(bs);bgGreen=gs[gs.length/2];bgBlue=bs[bs.length/2];
        }
        boolean[] mask=new boolean[box.width()*box.height()];
        for(int y=box.top;y<box.bottom;y++)for(int x=box.left;x<box.right;x++) {
            int color=pixels[y*WIDTH+x];mask[(y-box.top)*box.width()+x-box.left]=step
                    ?(color>>8&255)-bgGreen>40 && (color&255)-bgBlue>40:white(color);
        }
        List<Box> glyphs=new ArrayList<>();int tallest=0;
        for(Box b:components(mask,box.width(),box.height(),box.left,box.top)) {
            if(b.height()>=7 && b.width()>=Math.max(3,b.height()*.25f)
                    && (b.right>=box.right || b.bottom>=box.bottom || step && (b.left<=box.left || b.top<=box.top))) return -1;
            if(b.left<=box.left || b.top<=box.top || b.right>=box.right || b.bottom>=box.bottom
                    || b.height()<7 || b.width()>b.height()*.85f || b.area<5) continue;
            glyphs.add(b);tallest=Math.max(tallest,b.height());
        }
        final int maxHeight=tallest;glyphs.removeIf(b->b.height()<maxHeight*.72f);
        glyphs.sort(Comparator.comparingInt(b->b.left));
        if(glyphs.isEmpty() || glyphs.size()>(step?3:4)) return -1;
        int result=0,lastRight=-1;
        for(Box b:glyphs) {
            if(lastRight>=0 && b.left-lastRight>b.height())return -1;
            StringBuilder bits=new StringBuilder(160);
            for(int y=0;y<16;y++)for(int x=0;x<10;x++) {
                int xx=b.left+Math.min(b.width()-1,(2*x+1)*b.width()/20)-box.left;
                int yy=b.top+Math.min(b.height()-1,(2*y+1)*b.height()/32)-box.top;
                bits.append(mask[yy*box.width()+xx]?'1':'0');
            }
            int digit=catalog.digit(bits.toString(),b.width()/(float)b.height());
            if(digit<0 || result==0 && digit==0 && glyphs.size()>1)return -1;
            result=result*10+digit;lastRight=b.right;
        }
        return result;
    }
    /** The Chinese suffix has disconnected strokes; whole-column character bounds keep it out of the numeral. */
    private int level(int[] pixels,Box box) {
        if(box.width()<=0 || box.height()<=0)return -1;
        List<Box> glyphs=new ArrayList<>();int start=-1,top=box.bottom,bottom=box.top,area=0;
        for(int x=box.left;x<=box.right;x++) {
            int n=0;
            if(x<box.right)for(int y=box.top;y<box.bottom;y++)if(white(pixels[y*WIDTH+x])) {
                n++;top=Math.min(top,y);bottom=Math.max(bottom,y+1);
            }
            if(n>0) { if(start<0)start=x;area+=n; }
            else if(start>=0) {
                glyphs.add(new Box(start,top,x,bottom,area));start=-1;top=box.bottom;bottom=box.top;area=0;
            }
        }
        int result=0,count=0;
        for(Box b:glyphs) {
            if(b.height()<7 || b.area<5)continue;
            StringBuilder bits=new StringBuilder(160);
            for(int y=0;y<16;y++)for(int x=0;x<10;x++) {
                int xx=b.left+Math.min(b.width()-1,(2*x+1)*b.width()/20);
                int yy=b.top+Math.min(b.height()-1,(2*y+1)*b.height()/32);
                bits.append(white(pixels[yy*WIDTH+xx])?'1':'0');
            }
            if(catalog.levelSuffix(bits.toString(),b.width()/(float)b.height()))return count>0?result:-1;
            if(b.width()>b.height()*.85f)return -1;
            int digit=catalog.digit(bits.toString(),b.width()/(float)b.height());
            if(digit<0 || count==0 && digit==0 || count>=5)return -1;
            result=result*10+digit;count++;
        }
        return count>0?result:-1;
    }
}
