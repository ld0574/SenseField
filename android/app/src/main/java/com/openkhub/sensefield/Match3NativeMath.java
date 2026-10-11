package com.openkhub.sensefield;

/** Optional exact pixel arithmetic. The catalog, thresholds and permissions stay in Java. */
final class Match3NativeMath {
    static final boolean AVAILABLE;
    static {
        boolean loaded;
        try { System.loadLibrary("match3_math_jni");loaded=true; }
        catch(UnsatisfiedLinkError unavailable) { loaded=false; }
        AVAILABLE=loaded;
    }
    static native float bodyDifference(int[] observed,int[] reference,boolean[] background);
    static native float coverDifference(int[] reference,int[] observed,boolean[] cover,float ceiling);
    static native float[] patternDifferences(int[] observed,java.nio.ByteBuffer table,int count,float ceiling);
    static native boolean preparePatternTable(java.nio.ByteBuffer table,int count);
}
