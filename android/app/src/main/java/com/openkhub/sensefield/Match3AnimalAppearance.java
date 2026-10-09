package com.openkhub.sensefield;

import java.util.List;

/** Animal-family face descriptor. Ignores lane/outline pixels and uniform lighting offsets. */
final class Match3AnimalAppearance {
    static final float MAXIMUM = .12f, MARGIN = .025f;
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
            long shape=0;
            for(int i=0;i<centered.length;i++)shape+=Math.abs(centered[i]-other.centered[i]);
            // Similar average hue cannot substitute for agreement on face detail.
            if(shape*2>variation+other.variation)return Float.POSITIVE_INFINITY;
            int chroma=0;
            for(int k=0;k<3;k++) {
                int next=(k+1)%3;
                chroma+=Math.abs((mean[k]-mean[next])-(other.mean[k]-other.mean[next]));
            }
            return Math.max(shape/(100*765f),chroma/765f);
        }
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
