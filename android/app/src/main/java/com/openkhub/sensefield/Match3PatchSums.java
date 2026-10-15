package com.openkhub.sensefield;

/** Exact RGB rectangle sums for lower bounds, never an appearance decision. */
final class Match3PatchSums {
    private final int[][] sums=new int[3][17*17];
    Match3PatchSums(int[] pixels) {
        if(pixels.length!=256)throw new IllegalArgumentException("Patch size");
        for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
            int at=(y+1)*17+x+1,pixel=pixels[y*16+x];
            for(int k=0;k<3;k++)sums[k][at]=(pixel>>(16-8*k)&255)
                    +sums[k][at-1]+sums[k][at-17]-sums[k][at-18];
        }
    }
    int sum(int channel,int left,int top,int right,int bottom) {
        int[] s=sums[channel];
        return s[bottom*17+right]-s[top*17+right]-s[bottom*17+left]+s[top*17+left];
    }
    /** Partition the overlap into disjoint 4x4 reference blocks. Triangle
     * inequality makes the sum no larger than the full pixel Manhattan error,
     * including at a clipped registration edge. */
    long lowerBound(Match3PatchSums observed,int dx,int dy,double ceiling) {
        long lower=0;
        int left=Math.max(0,-dx),top=Math.max(0,-dy),right=Math.min(16,16-dx),bottom=Math.min(16,16-dy);
        for(int k=0;k<3;k++)lower+=Math.abs(sum(k,left,top,right,bottom)-observed.sum(k,left+dx,top+dy,right+dx,bottom+dy));
        if(lower>ceiling)return lower;
        lower=0;
        for(int y=0;y<16;y+=4)for(int x=0;x<16;x+=4) {
            int l=Math.max(x,-dx),t=Math.max(y,-dy),r=Math.min(x+4,16-dx),b=Math.min(y+4,16-dy);
            if(l>=r || t>=b)continue;
            for(int k=0;k<3;k++)lower+=Math.abs(sum(k,l,t,r,b)-observed.sum(k,l+dx,t+dy,r+dx,b+dy));
            if(lower>ceiling)return lower;
        }
        return lower;
    }
}
