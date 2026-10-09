package com.openkhub.sensefield;

/** Adjacent swaps and coherent downward displacement, including sprites without known rules. */
final class Match3AppearanceMotion {
    private Match3AnimalAppearance.Face[][] previous;
    private long lastAt=-1;
    boolean accept(Match3AnimalAppearance.Face[][] current,long at) {
        boolean motion=false;
        if(previous!=null && at>lastAt && at-lastAt<=2400 && previous.length==current.length
                && previous[0].length==current[0].length) {
            int rows=current.length,cols=current[0].length;
            for(int r=0;r<rows && !motion;r++)for(int c=0;c<cols && !motion;c++) {
                if(!changed(current[r][c],previous[r][c]))continue;
                if(c+1<cols && exchanged(current[r][c],current[r][c+1],previous[r][c],previous[r][c+1]))motion=true;
                if(r+1<rows && exchanged(current[r][c],current[r+1][c],previous[r][c],previous[r+1][c]))motion=true;
            }
            // Require two pieces moving by the same offset in one column.
            for(int c=0;c<cols && !motion;c++)for(int distance=1;distance<rows && !motion;distance++) {
                int support=0;
                for(int r=distance;r<rows;r++)if(changed(current[r][c],previous[r][c])
                        && matches(current[r][c],previous[r-distance][c]) && ++support>=2) { motion=true;break; }
            }
        }
        previous=current;lastAt=at;return motion;
    }
    private static boolean matches(Match3AnimalAppearance.Face a,Match3AnimalAppearance.Face b) {
        return a!=null && b!=null && a.detailed && b.detailed
                && a.difference(b)<=Match3AnimalAppearance.MAXIMUM;
    }
    private static boolean changed(Match3AnimalAppearance.Face a,Match3AnimalAppearance.Face b) {
        return a!=null && b!=null && a!=b && a.detailed && b.detailed
                && a.difference(b)>Match3AnimalAppearance.MAXIMUM+Match3AnimalAppearance.MARGIN;
    }
    private static boolean exchanged(Match3AnimalAppearance.Face a,Match3AnimalAppearance.Face b,
                                     Match3AnimalAppearance.Face oldA,Match3AnimalAppearance.Face oldB) {
        return changed(b,oldB) && matches(a,oldB) && matches(b,oldA);
    }
}
