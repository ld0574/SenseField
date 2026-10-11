package com.openkhub.sensefield;

import java.util.Arrays;

/** Confirmed directed gravity lanes, including portal links. Ambiguous merges/cycles are rejected. */
final class Match3Topology {
    private final int[] next;
    private final Match3Position.Kind[] collectors;
    final boolean refills;
    final String evidence;
    Match3Topology(int[] next,Match3Position.Kind[] collectors,boolean refills,String evidence) {
        if(next==null || next.length==0 || next.length>144 || collectors==null || next.length!=collectors.length
                || evidence==null || evidence.trim().isEmpty() || evidence.length()>160)
            throw new IllegalArgumentException("Unconfirmed topology");
        this.next=next.clone();this.collectors=collectors.clone();this.refills=refills;this.evidence=evidence;
        int[] incoming=new int[next.length];
        for(int i=0;i<next.length;i++) {
            int to=next[i];if(to < -1 || to>=next.length || to>=0 && ++incoming[to]>1)
                throw new IllegalArgumentException("Ambiguous gravity connection");
            if(collectors[i]!=null && collectors[i]!=Match3Position.Kind.GOLD_POD && collectors[i]!=Match3Position.Kind.KEY)
                throw new IllegalArgumentException("Unconfirmed collector");
        }
        for(int i=0;i<next.length;i++) {
            boolean[] seen=new boolean[next.length];
            for(int p=i;p>=0;p=next[p]) {
                if(seen[p])throw new IllegalArgumentException("Gravity cycle");
                seen[p]=true;
            }
        }
    }
    int size() { return next.length; }
    int next(int index) { return next[index]; }
    boolean collects(int index,Match3Position.Kind kind) { return collectors[index]==kind; }
    /** Edges through an observed hole are not usable, even if metadata incorrectly links them. */
    boolean validFor(Match3Position board) {
        for(int p=0;p<next.length;p++)if((next[p]>=0 || collectors[p]!=null)
                && (board.cell(p).kind==Match3Position.Kind.EMPTY || next[p]>=0
                && board.cell(next[p]).kind==Match3Position.Kind.EMPTY))return false;
        return true;
    }
    int distance(int from,int to) {
        int distance=0;
        for(int p=from;p>=0;p=next[p],distance++)if(p==to)return distance;
        return -1;
    }
    @Override public boolean equals(Object object) {
        if(!(object instanceof Match3Topology))return false;Match3Topology o=(Match3Topology)object;
        return Arrays.equals(next,o.next) && Arrays.equals(collectors,o.collectors) && refills==o.refills && evidence.equals(o.evidence);
    }
    @Override public int hashCode() { return Arrays.hashCode(next)*31+Arrays.hashCode(collectors); }
}
