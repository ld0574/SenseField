package com.openkhub.sensefield;

/** Bounded session-local appearance identity, without assigning an unknown sprite a game rule. */
final class Match3VisualIdentity {
    private final int[][] appearances=new int[8][];
    private final String[] identities=new String[8];
    private int next;
    String identify(int slot,int[] pixels) {
        if(slot<0 || slot>=8 || pixels==null || pixels.length!=256)throw new IllegalArgumentException("Appearance");
        int[] old=appearances[slot];
        long error=0;
        if(old!=null)for(int i=0;i<256;i++)for(int shift=0;shift<=16;shift+=8)
            error+=Math.abs((old[i]>>shift&255)-(pixels[i]>>shift&255));
        if(old==null || error/(256*765f)>.14f) {
            appearances[slot]=pixels.clone();identities[slot]="unverified_sprite_"+(++next);
        }
        return identities[slot];
    }
    void clear() { java.util.Arrays.fill(appearances,null);java.util.Arrays.fill(identities,null); }
}
