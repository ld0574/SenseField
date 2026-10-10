package com.openkhub.sensefield;

import java.util.List;

/** Animal-family face descriptor. Ignores lane/outline pixels and uniform lighting offsets. */
final class Match3AnimalAppearance {
    static final float MAXIMUM = .12f, MARGIN = .025f;
    /** Complete sprite evidence, independent of the center-face comparison.
     * Includes the surrounding contour; a face under an unfamiliar cover must abstain.
     * Uses the same published distance budget, not an adaptive or per-level threshold. */
    static final class Body {
        private final int[] patch;
        private final boolean[] background = new boolean[256];
        private volatile boolean[] exterior;
        Body(int[] patch) {
            if(patch==null || patch.length!=256)throw new IllegalArgumentException("Body patch");
            this.patch=patch.clone();
            for(int i=0;i<256;i++)background[i]=backdrop(patch[i]);
        }
        int[] copyPatch() { return patch.clone(); }
        float difference(Body other) {
            float best=aligned(other,0,0);
            if(best<=MAXIMUM)return best;
            for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)if(dx!=0||dy!=0)best=Math.min(best,aligned(other,dx,dy));
            return best;
        }
        /** Replace only positively observed stationary ice behind a certified
         * animal. Foreground/unknown covers still receive the full-body and
         * quadrant checks; an animal face alone never grants swap permission. */
        float differenceOnIce(Body ordinary,int[] iceBackground) {
            int[] normalized=patch.clone();int support=0;
            boolean[] behind=ordinary.exteriorBackground();
            for(int i=0;i<256;i++)if(behind[i]) {
                int a=patch[i],b=iceBackground[i];
                int error=Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))
                        +Math.abs((a&255)-(b&255));
                if(error/(float)765<=MAXIMUM) { normalized[i]=ordinary.patch[i];support++; }
            }
            return support==0?Float.POSITIVE_INFINITY:new Body(normalized).difference(ordinary);
        }
        private boolean[] exteriorBackground() {
            boolean[] cached=exterior;
            if(cached!=null)return cached;
            boolean[] result=new boolean[256];int[] queue=new int[256];int head=0,tail=0;
            // A dark-blue eye/mouth is foreground even when its colour matches
            // the lane. Only backdrop connected to the tile edge may change.
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(x==0 || y==0 || x==15 || y==15) {
                int at=y*16+x;if(background[at]) { result[at]=true;queue[tail++]=at; }
            }
            while(head<tail) {
                int at=queue[head++],x=at%16,y=at/16;
                if(x>0 && background[at-1] && !result[at-1]) { result[at-1]=true;queue[tail++]=at-1; }
                if(x<15 && background[at+1] && !result[at+1]) { result[at+1]=true;queue[tail++]=at+1; }
                if(y>0 && background[at-16] && !result[at-16]) { result[at-16]=true;queue[tail++]=at-16; }
                if(y<15 && background[at+16] && !result[at+16]) { result[at+16]=true;queue[tail++]=at+16; }
            }
            // Catalog bodies are shared by live and screenshot samplers. Publish
            // only the complete immutable mask; concurrent construction is safe.
            exterior=result;
            return result;
        }
        private float aligned(Body other,int dx,int dy) {
            // Registration may discard only visible board background, never an unknown cover or sprite detail.
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(x-dx<0 || x-dx>=16 || y-dy<0 || y-dy>=16)
                if(!background[y*16+x])return Float.POSITIVE_INFINITY;
            long total=0;
            long[] quarters=new long[4];
            int[] counts=new int[4];
            int supported=(16-Math.abs(dx))*(16-Math.abs(dy));
            // Scores above maximum+margin cannot admit a body or make a
            // valid family match ambiguous. Preserve exact contender scores.
            double budget=(double)Math.nextUp(MAXIMUM+MARGIN)*supported*765;
            for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                int xx=x+dx,yy=y+dy;if(xx<0 || yy<0 || xx>=16 || yy>=16)continue;
                int a=patch[yy*16+xx],b=other.patch[y*16+x];
                int error=Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))
                        +Math.abs((a&255)-(b&255));
                int quarter=(y/8)*2+x/8;total+=error;quarters[quarter]+=error;counts[quarter]++;
                if(total>budget)return Float.POSITIVE_INFINITY;
            }
            float distance=total/(supported*765f);
            // A small cover in one quadrant must not disappear in a whole-sprite average.
            for(int i=0;i<4;i++)if(quarters[i]/(counts[i]*765f)>MAXIMUM+MARGIN)return Float.POSITIVE_INFINITY;
            return distance;
        }
        private static boolean backdrop(int pixel) {
            int r=pixel>>16&255,g=pixel>>8&255,b=pixel&255,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
            if(max>=128 || max==min)return false;
            float delta=max-min,hue=max==r?60*((g-b)/delta%6):max==g?60*((b-r)/delta+2):60*((r-g)/delta+4);
            if(hue<0)hue+=360;
            return hue>=170 && hue<=300;
        }
        int detail() {
            int total=0;
            for(int y=0;y<16;y++)for(int x=0;x<15;x++) {
                int a=patch[y*16+x],b=patch[y*16+x+1];
                total+=Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255));
            }
            return total;
        }
    }
    static final class Face {
        final char color;
        private final int[] centered = new int[300];
        private final int[] mean = new int[3];
        private final long variation;
        final boolean detailed;
        Face(char color, int[] patch) {
            this.color = color;
            if (patch == null || patch.length != 256) throw new IllegalArgumentException("Face patch");
            for (int y=3;y<13;y++) for(int x=3;x<13;x++) {
                int p=patch[y*16+x];
                for(int k=0;k<3;k++)mean[k]+=p>>(16-8*k)&255;
            }
            for(int k=0;k<3;k++)mean[k]/=100;
            long spread=0;int i=0;
            for(int y=3;y<13;y++)for(int x=3;x<13;x++) {
                int p=patch[y*16+x];
                for(int k=0;k<3;k++) { int value=(p>>(16-8*k)&255)-mean[k];centered[i++]=value;spread+=Math.abs(value); }
            }
            // A nearly uniform cyan hole is not a positive animal reference.
            variation=spread;detailed=variation/(100*765f)>=MARGIN;
        }
        float difference(Face other) {
            if(!detailed || !other.detailed)return Float.POSITIVE_INFINITY;
            int chroma=0;
            for(int k=0;k<3;k++) {
                int next=(k+1)%3;
                chroma+=Math.abs((mean[k]-mean[next])-(other.mean[k]-other.mean[next]));
            }
            // This is a lower bound on the existing combined score. Outside
            // the decision budget no shape comparison can change the result.
            float colorDistance=chroma/765f;
            if(colorDistance>MAXIMUM+MARGIN)return Float.POSITIVE_INFINITY;
            float best=aligned(other,0,0);
            if(best>MAXIMUM)for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)if(dx!=0||dy!=0)
                best=Math.min(best,aligned(other,dx,dy));
            return Math.max(best,colorDistance);
        }
        private float aligned(Face other,int dx,int dy) {
            long shape=0,spread=0;int support=0;
            for(int y=0;y<10;y++)for(int x=0;x<10;x++) {
                int xx=x+dx,yy=y+dy;if(xx<0||yy<0||xx>=10||yy>=10)continue;
                for(int k=0;k<3;k++) {
                    int a=centered[(yy*10+xx)*3+k],b=other.centered[(y*10+x)*3+k];
                    shape+=Math.abs(a-b);spread+=Math.abs(a)+Math.abs(b);
                }
                support++;
            }
            // One rigid offset compensates sample registration; it cannot rearrange facial features.
            return shape*2>spread?Float.POSITIVE_INFINITY:shape/(support*765f);
        }
    }
    static final class Reference {
        final Face face;
        final Body body;
        Reference(Face face,Body body) { this.face=face;this.body=body; }
    }
    /** References must be frozen direct-catalog observations, never outputs of this method. */
    static Match3Position.Cell infer(Face face,Body body,List<Reference> trusted) {
        List<Face> faces=new java.util.ArrayList<>();
        for(Reference reference:trusted)faces.add(reference.face);
        char color=recognize(face,faces,2);
        if(Match3Goals.Kind.animal(color)==Match3Goals.Kind.UNKNOWN)return null;
        int support=0;
        for(Reference reference:trusted)if(reference.face.color==color && face.difference(reference.face)<=MAXIMUM
                && body.difference(reference.body)<=MAXIMUM && ++support>=2)
            return Match3Position.Cell.animal(color);
        return Match3Position.Cell.animalIdentity(color);
    }
    private Match3AnimalAppearance() { }
    static char recognize(Face observed,List<Face> references,int requiredSupport) {
        float best=Float.POSITIVE_INFINITY,second=Float.POSITIVE_INFINITY;char winner='.';
        for(char color:new char[]{'R','O','Y','G','B','P'}) {
            float error=Float.POSITIVE_INFINITY;int support=0;
            for(Face reference:references)if(reference.color==color) {
                float distance=observed.difference(reference);
                if(distance<=MAXIMUM)support++;
                error=Math.min(error,distance);
            }
            if(support<requiredSupport)continue;
            if(error<best) { second=best;best=error;winner=color; }
            else second=Math.min(second,error);
        }
        return best<=MAXIMUM && second-best>=MARGIN?winner:'.';
    }
}
