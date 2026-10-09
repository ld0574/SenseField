package com.openkhub.sensefield;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Observed task deltas are distinct from inferring which exchange a player made. */
final class Match3GoalOutcome {
    interface Listener { void record(Result result); }
    static final class Result {
        final long hintRevision;
        final String outcome, actionEvidence;
        final int stepsSpent;
        final Map<Match3Goals.Kind,Integer> deltas, expected;
        Result(long revision,String outcome,String action,int steps,Map<Match3Goals.Kind,Integer> deltas,
               Map<Match3Goals.Kind,Integer> expected) {
            hintRevision=revision;this.outcome=outcome;actionEvidence=action;stepsSpent=steps;
            this.deltas=Collections.unmodifiableMap(new EnumMap<>(deltas));
            this.expected=Collections.unmodifiableMap(new EnumMap<>(expected));
        }
    }
    private final Listener listener;
    private final EnumSet<Match3Goals.Kind> abstained=EnumSet.noneOf(Match3Goals.Kind.class);
    private Match3Position before;
    private Match3Goals baseline;
    private Match3MoveValue value;
    private long revision,started;
    private boolean visuallyMatched;
    Match3GoalOutcome(Listener listener) { this.listener=listener; }
    Set<Match3Goals.Kind> abstainedRules() { return Collections.unmodifiableSet(abstained); }
    void begin(long revision,Match3Position board,Match3Goals goals,Match3MoveValue move,long at) {
        if(goals==null || !goals.hudVerified || goals.steps<1 || move==null)return;
        boolean known=false;
        for(Match3Goals.Kind kind:Match3Goals.Kind.values())if(goals.remaining(kind)>0)known=true;
        if(!known)return;
        // A temporary basic hint while HUD digits animate must not erase the
        // previous observation before its task counters can be confirmed.
        if(value!=null)cancel("replaced_without_attributable_outcome");
        this.revision=revision;before=board;baseline=goals;value=move;started=at;visuallyMatched=false;
    }
    void observeFrame(Match3Position board,Match3Goals goals,long at) {
        if(value==null)return;
        if(at<=started)return;
        if(at-started>12000) { cancel("outcome_timeout");return; }
        if(board.rows!=before.rows || board.cols!=before.cols) { cancel("geometry_changed");return; }
        if(goals.hudVerified && !baseline.sameIdentity(goals)) { cancel("mission_changed");return; }
        Match3Board.Swap s=value.swap;
        boolean exact=true;
        for(int r=0;r<board.rows;r++)for(int c=0;c<board.cols;c++) {
            char expected=before.cell(r,c).color;
            if(r==s.fromRow && c==s.fromCol)expected=before.cell(s.toRow,s.toCol).color;
            if(r==s.toRow && c==s.toCol)expected=before.cell(s.fromRow,s.fromCol).color;
            if(board.cell(r,c).color!=expected || board.cell(r,c).kind!=before.cell(r,c).kind
                    || board.cell(r,c).layers!=before.cell(r,c).layers || board.cell(r,c).iceLayers!=before.cell(r,c).iceLayers
                    || board.cell(r,c).swapPermission!=before.cell(r,c).swapPermission)exact=false;
        }
        if(exact)visuallyMatched=true;
    }
    void confirmed(Match3Goals after,long at) {
        if(value==null || !after.hudVerified)return;
        if(after.observedAtMs<=baseline.observedAtMs || at<after.observedAtMs)return;
        if(at-started>12000) { cancel("outcome_timeout");return; }
        if(!baseline.sameIdentity(after)) { cancel("mission_changed");return; }
        if(after.steps<0 || after.steps==baseline.steps)return;
        int spent=baseline.steps-after.steps;
        EnumMap<Match3Goals.Kind,Integer> delta=new EnumMap<>(Match3Goals.Kind.class);
        EnumMap<Match3Goals.Kind,Integer> expected=new EnumMap<>(Match3Goals.Kind.class);
        boolean low=false,increased=false,missing=false,progress=false;
        EnumSet<Match3Goals.Kind> lower=EnumSet.noneOf(Match3Goals.Kind.class);
        for(Match3Goals.Kind kind:Match3Goals.Kind.values()) {
            int old=baseline.remaining(kind),now=after.remaining(kind);
            if(old>=0 && now<0 && value.collected(kind)>0)missing=true;
            if(old<0 || now<0)continue;
            delta.put(kind,old-now);expected.put(kind,Math.min(old,value.collected(kind)));
            progress|=old>now;
            increased|=now>old;
            if(spent==1 && visuallyMatched && now<=old && old-now<expected.get(kind)) {
                low=true;lower.add(kind);
            }
        }
        if(!increased && !missing && visuallyMatched && spent==1)abstained.addAll(lower);
        String outcome=increased || spent<0?"counter_reset_or_added_steps":missing?"counter_coverage_incomplete":spent!=1?"multiple_actions_unattributed"
                :!visuallyMatched?"progress_unattributed":low?"below_lower_bound_rule_abstained"
                :progress?"visual_exchange_progress_observed":"visual_exchange_no_target_gain";
        emit(outcome,spent,delta,expected);
    }
    void cancel(String reason) {
        if(value!=null)emit(reason,-1,new EnumMap<>(Match3Goals.Kind.class),new EnumMap<>(Match3Goals.Kind.class));
    }
    private void emit(String reason,int steps,EnumMap<Match3Goals.Kind,Integer> delta,EnumMap<Match3Goals.Kind,Integer> expected) {
        Result result=new Result(revision,reason,visuallyMatched?"exact_visual_exchange_only":"not_observed",steps,delta,expected);
        value=null;baseline=null;before=null;visuallyMatched=false;
        if(listener!=null)listener.record(result);
    }
}
