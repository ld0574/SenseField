package com.openkhub.sensefield;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Task value comes from typed settlement events; future preparation is reported separately. */
final class Match3MoveValue {
    private static final Match3Goals.Kind[] KINDS=Match3Goals.Kind.values();
    final Match3Board.Swap swap;
    final Match3Action action;
    final Match3RuleEngine.Result result;
    final int completedTargets,progressMilli,directUnits,relevantHits,potentialSpecials,targetDistance,physicalClears,poisonRemoved;
    final int cookieContactCells;
    final boolean allTargetsFinish,grounded;
    final String reason;
    final Match3Search.Evidence search;
    private final int[] collected,hits;
    private final Match3Goals goals;
    private final Match3TargetFrontier frontier;
    private Match3MoveValue(Match3RuleEngine.Result result,Match3Goals goals,int[] collected,int[] hits,
                            Match3TargetFrontier frontier,Match3Search.Evidence search) {
        this.result=result;action=result.action;swap=action.geometry;this.goals=goals;this.frontier=frontier;
        this.collected=collected;this.hits=hits;this.search=search;
        potentialSpecials=result.potentialSpecials;targetDistance=frontier.distance;
        int clears=0;
        for(Match3RuleEngine.Event event:result.events)if(event.type==Match3RuleEngine.EventType.CLEAR && event.units>0)clears+=event.units;
        physicalClears=clears;
        poisonRemoved=result.collected(Match3Goals.Kind.POISON,Match3Goals.Mode.CLEAR);
        int complete=0,progress=0,units=0,relevant=0,positive=0;boolean known=false;
        boolean all=goals.fullyKnown() && !goals.finished();
        Match3Goals.Kind spoken=null,related=null;
        for(Match3Goals.Kind kind:KINDS) {
            if(!goals.active(kind))continue;known=true;
            int remaining=goals.remaining(kind),gain=remaining>0?Math.min(remaining,collected[kind.ordinal()]):0;
            if(gain>0) { units+=gain;progress+=(int)(1000L*gain/remaining);positive++;spoken=kind; }
            if(remaining>0 && gain>=remaining)complete++;else all=false;
            if(goals.mode(kind)==Match3Goals.Mode.UNKNOWN)all=false;
            relevant+=hits[kind.ordinal()];
            relevant+=result.preparation(kind,goals.mode(kind));
            if(goals.mode(kind)==Match3Goals.Mode.DELIVER)relevant+=channelProgress(result,kind);
            if(remaining<0)relevant+=collected[kind.ordinal()];
            if(hits[kind.ordinal()]>0 || remaining<0 && collected[kind.ordinal()]>0)related=kind;
        }
        boolean hatch=goals.active(Match3Goals.Kind.CHICK) && goals.mode(Match3Goals.Kind.CHICK)!=Match3Goals.Mode.CLEAR;
        if(hatch)relevant+=hits[Match3Goals.Kind.EGG.ordinal()];
        completedTargets=complete;progressMilli=progress;directUnits=units;grounded=known;
        relevantHits=relevant;allTargetsFinish=all;
        cookieContactCells=hits[Match3Goals.Kind.COOKIE.ordinal()]>0 && goals.active(Match3Goals.Kind.COOKIE)
                && goals.mode(Match3Goals.Kind.COOKIE)==Match3Goals.Mode.UNKNOWN && goals.steps!=1
                ?cookieContacts(result):0;
        if(positive>1)reason="兼顾任务";
        else if(positive==1) {
            Match3Goals.Mode mode=goals.mode(spoken);
            reason=mode==Match3Goals.Mode.DELIVER?"送达"+spoken.name:mode==Match3Goals.Mode.RESCUE?"解救"+spoken.name
                    :mode==Match3Goals.Mode.CHARGE?"充能"+spoken.name:mode==Match3Goals.Mode.TRIGGER?"触发"+spoken.name
                    :mode==Match3Goals.Mode.UNLOCK?"解除"+spoken.name
                    :spoken.color!='\0'?"收集"+spoken.name:spoken==Match3Goals.Kind.COIN?"收集银币"
                    :spoken==Match3Goals.Kind.ICE?"消除冰层":spoken==Match3Goals.Kind.ICEFLOWER?"消除冰花"
                    :spoken==Match3Goals.Kind.HONEY?"消除蜜罐":spoken==Match3Goals.Kind.GRAY_FURBALL?"消除灰毛球":"清除障碍";
        } else if(relevant>0)reason=goals.active(Match3Goals.Kind.STAR_BOTTLE) && goals.mode(Match3Goals.Kind.STAR_BOTTLE)==Match3Goals.Mode.CHARGE
                && result.preparation(Match3Goals.Kind.STAR_BOTTLE,Match3Goals.Mode.CHARGE)>0?"准备充能星星瓶"
                :goals.active(Match3Goals.Kind.GOLD_POD) && channelProgress(result,Match3Goals.Kind.GOLD_POD)>0?"打通金豆荚通道"
                :goals.active(Match3Goals.Kind.KEY) && channelProgress(result,Match3Goals.Kind.KEY)>0?"打通钥匙通道"
                :goals.active(Match3Goals.Kind.PENGUIN) && hits[Match3Goals.Kind.PENGUIN.ordinal()]>0?"清理企鹅覆盖"
                :goals.active(Match3Goals.Kind.SNOW_BEAR) && hits[Match3Goals.Kind.SNOW_BEAR.ordinal()]>0?"清理雪熊覆盖"
                :hatch && hits[Match3Goals.Kind.EGG.ordinal()]>0?"靠近鸡蛋"
                :goals.active(Match3Goals.Kind.COOKIE) && hits[Match3Goals.Kind.COOKIE.ordinal()]>0
                ?action.type==Match3Action.Type.BIRD_COLOR || action.type==Match3Action.Type.BIRD_PAIR
                ?"用魔力鸟清理饼干周边":"靠近饼干"
                :related!=null && related.color!='\0'?"优先"+related.name:related==Match3Goals.Kind.ICE?"优先消冰":"清理障碍";
        else reason=poisonRemoved>0 && goals.steps!=1?"清理毒液":frontier.reason();
    }
    /** Distinct guaranteed first-round clears beside contacted trays, never cookie damage or units.
     * Four quadrants still form one HIT. This spatial tie-break lets a bird's useful color
     * outrank creating an unrelated special without claiming an unknown tray transition. */
    private static int cookieContacts(Match3RuleEngine.Result result) {
        BitSet trays=new BitSet(),sources=new BitSet();Set<Integer> objects=new HashSet<>();
        for(Match3RuleEngine.Event event:result.events) {
            if(event.round!=1)continue;
            if(event.type==Match3RuleEngine.EventType.HIT && event.kind==Match3Goals.Kind.COOKIE) {
                if(event.objectId>=0)objects.add(event.objectId);else if(event.cell>=0)trays.set(event.cell);
            }
            if(event.type==Match3RuleEngine.EventType.CLEAR && event.kind.color!='\0' && event.units>0 && event.cell>=0)
                sources.set(event.cell);
        }
        Match3Position board=result.before;
        for(int p=0;p<board.rows*board.cols;p++) {
            Match3Position.Cell cell=board.cell(p);
            if(cell.kind==Match3Position.Kind.COOKIE && objects.contains(cell.objectId)
                    && cell.gate==Match3Position.Gate.OPEN && cell.cover==Match3Position.Cover.NONE)trays.set(p);
        }
        int contacts=0;
        for(int p=sources.nextSetBit(0);p>=0;p=sources.nextSetBit(p+1))
            if(p>=board.cols && trays.get(p-board.cols)
                    || p+board.cols<board.rows*board.cols && trays.get(p+board.cols)
                    || p%board.cols>0 && trays.get(p-1)
                    || p%board.cols+1<board.cols && trays.get(p+1))contacts++;
        return contacts;
    }
    private static int channelProgress(Match3RuleEngine.Result result,Match3Goals.Kind target) {
        Match3Position before=result.before;
        if(before.topology==null || !before.topology.validFor(before))return 0;
        java.util.BitSet lanes=new java.util.BitSet();
        for(int p=0;p<before.rows*before.cols;p++)if(Match3RuleEngine.goalKind(before.cell(p).kind)==target) {
            java.util.BitSet lane=new java.util.BitSet();boolean collector=false;
            for(int at=p;at>=0;at=before.topology.next(at)) {
                lane.set(at);if(before.topology.collects(at,before.cell(p).kind)) { collector=true;break; }
            }
            if(collector)lanes.or(lane);
        }
        java.util.BitSet progress=new java.util.BitSet();
        for(Match3RuleEngine.Event event:result.events)if(event.cell>=0 && lanes.get(event.cell)
                && (event.type==Match3RuleEngine.EventType.CLEAR || event.type==Match3RuleEngine.EventType.LAYER)
                && !event.ruleId.equals("stationary_single_layer_ice"))progress.set(event.cell);
        return progress.cardinality();
    }
    int collected(Match3Goals.Kind kind) { return collected[kind.ordinal()]; }
    int hit(Match3Goals.Kind kind) { return hits[kind.ordinal()]; }
    Match3MoveValue withSearch(Match3Search.Evidence evidence) {
        return new Match3MoveValue(result,goals,collected,hits,frontier,evidence);
    }
    boolean sameEvidence(Match3MoveValue other) {
        return other!=null && action.same(other.action) && Arrays.equals(collected,other.collected)
                && Arrays.equals(hits,other.hits) && cookieContactCells==other.cookieContactCells
                && potentialSpecials==other.potentialSpecials && reason.equals(other.reason)
                && grounded==other.grounded && result.affectedPositions().equals(other.result.affectedPositions());
    }
    static Match3MoveValue evaluate(Match3Position board,Match3Goals goals,Match3Board.Swap swap) {
        return evaluate(board,goals,swap,Collections.emptySet());
    }
    static Match3MoveValue evaluate(Match3Position board,Match3Goals goals,Match3Board.Swap swap,Set<Match3Goals.Kind> abstained) {
        return evaluate(board,goals,swap,abstained,Match3TargetFrontier.prepare(board,goals,abstained));
    }
    static Match3MoveValue evaluate(Match3Position board,Match3Goals goals,Match3Board.Swap swap,
                                   Set<Match3Goals.Kind> abstained,Match3TargetFrontier.Field frontier) {
        return value(Match3RuleEngine.resolve(board,new Match3Action(swap)),goals,abstained,frontier);
    }
    static Match3MoveValue evaluate(Match3Position board,Match3Goals goals,Match3Action action,
                                   Set<Match3Goals.Kind> abstained,Match3TargetFrontier.Field frontier) {
        Match3RuleEngine.Result result=Match3RuleEngine.resolveLegal(board,action);
        return value(result,goals,abstained,frontier);
    }
    private static Match3MoveValue value(Match3RuleEngine.Result result,Match3Goals goals,
                                        Set<Match3Goals.Kind> abstained,Match3TargetFrontier.Field frontier) {
        int[] collected=new int[KINDS.length],hits=new int[KINDS.length];
        for(Match3RuleEngine.Event event:result.events)if(!abstained.contains(event.kind)) {
            int index=event.kind.ordinal();
            if(event.type==Match3RuleEngine.EventType.HIT)hits[index]++;
            Match3Goals.Mode mode=goals.hasTarget(event.kind)?goals.mode(event.kind):Match3Goals.Mode.CLEAR;
            if(mode!=Match3Goals.Mode.UNKNOWN && event.mode()==mode && event.units>0)collected[index]+=event.units;
        }
        return new Match3MoveValue(result,goals,collected,hits,frontier.at(result.affectedPositions()),Match3Search.Evidence.one());
    }
    String evidence() {
        return "scope="+(grounded?"observed_goal_rule_lower_bound":"basic_goal_unread")+" complete="+completedTargets
                +" progress_milli="+progressMilli+" direct_units="+directUnits+" relevant_hits="+relevantHits
                +" cookie_contact_cells="+cookieContactCells+" cookie_contact_scope=adjacent_clears_not_task_units"
                +" physical_clear_lower_bound="+physicalClears
                +" poison_sources_removed="+poisonRemoved
                +" potential_specials="+potentialSpecials+" target_distance="+(targetDistance==Match3TargetFrontier.UNAVAILABLE?-1:targetDistance)
                +" target_distance_scope=spatial_only random_falls=excluded "+result.evidence()+" "+search.describe();
    }
}
