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
    /** Exact current-pixel comparison; nulls and lengths retain Arrays.equals semantics. */
    static boolean samePixels(int[] first,int[] second) {
        if(first==second)return true;
        if(first==null || second==null || first.length!=second.length)return false;
        return AVAILABLE?equalPixels(first,second):java.util.Arrays.equals(first,second);
    }
    static native boolean equalPixels(int[] first,int[] second);
    static native float bodyDifference(int[] observed,int[] reference,boolean[] background);
    static native boolean prepareBodyDescriptor(java.nio.ByteBuffer descriptor,int[] pixels);
    static native float preparedBodyDifference(java.nio.ByteBuffer observed,java.nio.ByteBuffer reference);
    static native float bodyDifferenceAgainstPrepared(int[] observed,java.nio.ByteBuffer reference);
    static native float faceShapeDifference(int[] observed,int[] reference);
    static native float[] faceDifferences(int[] centered,int[] mean,java.nio.ByteBuffer table,int count);
    static native boolean bodyMatchesTable(int[] observed,java.nio.ByteBuffer table,int count,int color);
    static native float[] bodyDifferences(int[] observed,java.nio.ByteBuffer table,int count,boolean[] compatible);
    static native float coverDifference(int[] reference,int[] observed,boolean[] cover,float ceiling);
    static native float[] patternDifferences(int[] observed,java.nio.ByteBuffer table,int count,float ceiling);
    static native boolean preparePatternTable(java.nio.ByteBuffer table,int count);
}
