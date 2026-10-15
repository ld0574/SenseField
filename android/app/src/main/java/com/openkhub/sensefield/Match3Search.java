package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/** Declared before measurement: two actions, 96 nodes, 8ms checked at node boundaries. */
final class Match3Search {
    static final int MAX_DEPTH=2, MAX_NODES=96;
    static final long MAX_NANOS=8_000_000L;
    static final class Evidence {
        final int depth,nodes,completed,progress,units;
        final boolean finish;
        final String stopped,nextAction;
        Evidence(int depth,int nodes,int completed,int progress,int units,boolean finish,String stopped,String next) {
            this.depth=depth;this.nodes=nodes;this.completed=completed;this.progress=progress;this.units=units;
            this.finish=finish;this.stopped=stopped;nextAction=next;
        }
        static Evidence one() { return new Evidence(1,0,0,0,0,false,"one_action"," "); }
        String describe() { return "search_depth="+depth+" search_nodes="+nodes+" search_stop="+stopped
                +" projected_complete="+completed+" projected_units="+units+" projected_progress_milli="+progress; }
    }
    static List<Match3MoveValue> apply(List<Match3MoveValue> first,Match3Goals goals,Set<Match3Goals.Kind> abstained) {
        return apply(first,goals,abstained,System::nanoTime);
    }
    static List<Match3MoveValue> apply(List<Match3MoveValue> first,Match3Goals goals,Set<Match3Goals.Kind> abstained,LongSupplier clock) {
        if(goals.steps<2 || !goals.hudVerified)return first;
        boolean eligible=false;for(Match3MoveValue move:first)if(move.result.successorKnown)eligible=true;
        if(!eligible)return first;
        long deadline=clock.getAsLong()+MAX_NANOS;int nodes=0;List<Match3MoveValue> output=new ArrayList<>();
        for(Match3MoveValue move:first) {
            Evidence best=new Evidence(1,0,move.completedTargets,move.progressMilli,move.directUnits,move.allTargetsFinish,
                    move.result.successorKnown?"no_known_successor_action":"unknown_successor","");
            if(move.result.successorKnown && !move.allTargetsFinish) {
                Match3Goals remaining=remaining(goals,move);Match3Position board=move.result.after;
                Match3TargetFrontier.Field frontier=Match3TargetFrontier.prepare(board,remaining,abstained);
                int evaluated=0;boolean exhausted=false;
                for(Match3Action action:Match3Action.enumerate(board)) {
                    if(nodes>=MAX_NODES || clock.getAsLong()>=deadline) { exhausted=true;break; }
                    nodes++;evaluated++;
                    Match3MoveValue next=Match3MoveValue.evaluate(board,remaining,action,abstained,frontier);
                    if(clock.getAsLong()>=deadline) { exhausted=true;break; }
                    int complete=0,progress=0,units=0;boolean finish=goals.fullyKnown() && !goals.finished();
                    for(Match3Goals.Kind kind:Match3Goals.Kind.values())if(goals.active(kind)) {
                        int count=goals.remaining(kind),gain=count>0?Math.min(count,move.collected(kind)+next.collected(kind)):0;
                        if(count>0 && gain>=count)complete++;else finish=false;
                        if(count>0)progress+=(int)(1000L*gain/count);units+=gain;
                    }
                    Evidence candidate=new Evidence(2,evaluated,complete,progress,units,finish,"known_successor",action.key());
                    if(compare(candidate,best)<0)best=candidate;
                }
                // Partial exploration cannot choose a favored prefix: restore the already completed one-action result.
                if(exhausted)best=new Evidence(1,evaluated,move.completedTargets,move.progressMilli,move.directUnits,
                        move.allTargetsFinish,"budget_fallback","");
                else best=new Evidence(best.depth,evaluated,best.completed,best.progress,best.units,best.finish,best.stopped,best.nextAction);
            }
            output.add(move.withSearch(best));
        }
        // A global time/node cutoff restores all candidates, so scan order is not an unreported preference.
        boolean cutoff=false;for(Match3MoveValue move:output)if(move.search.stopped.equals("budget_fallback"))cutoff=true;
        if(cutoff) {
            output.clear();for(Match3MoveValue move:first)output.add(move.withSearch(new Evidence(1,nodes,move.completedTargets,
                    move.progressMilli,move.directUnits,move.allTargetsFinish,"budget_fallback","")));
        }
        return output;
    }
    static int compare(Evidence a,Evidence b) {
        int order=Boolean.compare(b.finish,a.finish);
        if(order==0)order=Integer.compare(b.completed,a.completed);
        if(order==0)order=Integer.compare(b.progress,a.progress);
        if(order==0)order=Integer.compare(b.units,a.units);return order;
    }
    private static Match3Goals remaining(Match3Goals goals,Match3MoveValue move) {
        List<Match3Goals.Target> targets=new ArrayList<>();
        for(Match3Goals.Target target:goals.targets) {
            int remaining=target.remaining<0?-1:Math.max(0,target.remaining-move.collected(target.kind));
            targets.add(new Match3Goals.Target(target.slot,target.kind,remaining,remaining==0,target.visualId,target.mode,target.modeEvidence));
        }
        return new Match3Goals(goals.level,goals.steps-1,targets,goals.hudVerified,goals.observedAtMs);
    }
    private Match3Search() { }
}
