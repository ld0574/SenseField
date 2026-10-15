package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

/** Advisory actions. No game clicks or consumable tools are executed here. */
final class Match3Action {
    enum Type { SWAP, ACTIVATE, LINE_SWAP, BIRD_COLOR, BIRD_PAIR, BIRD_LINE, CHARGED_PAIR }
    final Match3Board.Swap geometry;
    final Type type;
    final String ruleId;
    final int version=Match3Rules.VERSION;
    Match3Action(Match3Board.Swap swap) {
        geometry=swap;type=swap.actionType;ruleId=swap.ruleId;
    }
    private static Match3Action special(Match3Position board,int from,int to,Type type,String rule) {
        return new Match3Action(new Match3Board.Swap(from/board.cols,from%board.cols,to/board.cols,to%board.cols,
                0,0,0,0,new BitSet(),Collections.emptyList(),type,rule));
    }
    static boolean line(Match3Position.Cell cell) {
        return cell.activationPermission==Match3Position.SwapPermission.YES && cell.kind==Match3Position.Kind.ANIMAL
                && (cell.effect==Match3Position.Effect.HORIZONTAL || cell.effect==Match3Position.Effect.VERTICAL);
    }
    private static boolean ordinary(Match3Position.Cell cell) {
        return cell.kind==Match3Position.Kind.ANIMAL && cell.effect==Match3Position.Effect.NONE && cell.swappable;
    }
    private static boolean open(Match3Position.Cell cell) {
        return cell.gate==Match3Position.Gate.OPEN && cell.cover==Match3Position.Cover.NONE;
    }
    private static boolean full(Match3Position.Cell cell) {
        return open(cell) && cell.capacity>0 && cell.charge==cell.capacity;
    }
    static List<Match3Action> enumerate(Match3Position board) {
        List<Match3Action> actions=new ArrayList<>();
        if(board==null)return actions;
        Set<String> specialPairs=new HashSet<>();
        for(int p=0;p<board.rows*board.cols;p++) {
            Match3Position.Cell a=board.cell(p);
            if(line(a))actions.add(special(board,p,p,Type.ACTIVATE,a.ruleId));
            int r=p/board.cols,c=p%board.cols;
            if(c+1<board.cols)addPair(actions,specialPairs,board,p,p+1);
            if(r+1<board.rows)addPair(actions,specialPairs,board,p,p+board.cols);
        }
        for(Match3Board.Swap swap:Match3Board.findSwaps(board)) {
            int from=swap.fromRow*board.cols+swap.fromCol,to=swap.toRow*board.cols+swap.toCol;
            if(!specialPairs.contains(from+":"+to) && ordinary(board.cell(from)) && ordinary(board.cell(to)))
                actions.add(new Match3Action(swap));
        }
        return actions;
    }
    /** Board-level omissions are separate from an action's settlement uncertainty. */
    static Set<String> omittedReasons(Match3Position board) {
        Set<String> reasons=new LinkedHashSet<>();
        if(board==null)return reasons;
        for(int p=0;p<board.rows*board.cols;p++) {
            Match3Position.Cell a=board.cell(p);
            if(a.effect==Match3Position.Effect.BLAST)reasons.add("blast_activation_parameters_unknown");
            if(a.effect==Match3Position.Effect.UNKNOWN || a.kind==Match3Position.Kind.SPECIAL)
                reasons.add("special_identity_or_effect_unknown");
            if(p%board.cols+1<board.cols)omittedPair(a,board.cell(p+1),reasons);
            if(p/board.cols+1<board.rows)omittedPair(a,board.cell(p+board.cols),reasons);
        }
        return Collections.unmodifiableSet(reasons);
    }
    private static void omittedPair(Match3Position.Cell a,Match3Position.Cell b,Set<String> reasons) {
        if(!open(a) || !open(b))return;
        boolean bird=a.kind==Match3Position.Kind.BIRD || b.kind==Match3Position.Kind.BIRD;
        Match3Position.Cell other=a.kind==Match3Position.Kind.BIRD?b:a;
        if(bird && other.effect!=Match3Position.Effect.NONE && !line(other))reasons.add("bird_special_combination_unverified");
        if(a.effect!=Match3Position.Effect.NONE && b.effect!=Match3Position.Effect.NONE)
            reasons.add("special_pair_combination_unverified");
        if(a.kind==Match3Position.Kind.DYE_BABY && b.kind==Match3Position.Kind.DYE_BABY
                || a.kind==Match3Position.Kind.STAR_BOTTLE && b.kind==Match3Position.Kind.STAR_BOTTLE)
            reasons.add("charged_pair_effect_mask_unknown");
        if(a.kind==Match3Position.Kind.ENERGY_BOTTLE || b.kind==Match3Position.Kind.ENERGY_BOTTLE)
            reasons.add("energy_bottle_identity_or_combination_unverified");
        if(a.kind==Match3Position.Kind.DYE_BABY && b.kind==Match3Position.Kind.STAR_BOTTLE
                || b.kind==Match3Position.Kind.DYE_BABY && a.kind==Match3Position.Kind.STAR_BOTTLE) {
            if(!full(a) || !full(b))reasons.add("charged_pair_energy_not_confirmed_full");
        }
    }
    private static void addPair(List<Match3Action> actions,Set<String> pairs,Match3Position board,int from,int to) {
        Match3Position.Cell a=board.cell(from),b=board.cell(to);
        if(!open(a) || !open(b))return;
        Type type=null;String rule="";
        if(a.kind==Match3Position.Kind.BIRD && a.swappable && b.kind==Match3Position.Kind.BIRD && b.swappable) {
            type=Type.BIRD_PAIR;rule="combo_bird_bird";
        } else if(a.kind==Match3Position.Kind.BIRD && a.swappable && ordinary(b)
                || b.kind==Match3Position.Kind.BIRD && b.swappable && ordinary(a)) {
            type=Type.BIRD_COLOR;rule="combo_bird_color";
        } else if(a.kind==Match3Position.Kind.BIRD && a.swappable && line(b) && b.swappable
                || b.kind==Match3Position.Kind.BIRD && b.swappable && line(a) && a.swappable) {
            type=Type.BIRD_LINE;rule="combo_bird_line";
        } else if(line(a) && a.swappable && ordinary(b) || line(b) && b.swappable && ordinary(a)) {
            type=Type.LINE_SWAP;rule=line(a)?a.ruleId:b.ruleId;
        } else if((a.kind==Match3Position.Kind.DYE_BABY && b.kind==Match3Position.Kind.STAR_BOTTLE
                || b.kind==Match3Position.Kind.DYE_BABY && a.kind==Match3Position.Kind.STAR_BOTTLE) && full(a) && full(b)) {
            type=Type.CHARGED_PAIR;rule="combo_dye_star";
        }
        if(type!=null) { actions.add(special(board,from,to,type,rule));pairs.add(from+":"+to); }
    }
    String key() {
        return type+":"+geometry.fromRow+","+geometry.fromCol+":"+geometry.toRow+","+geometry.toCol+":"+ruleId+"@"+version;
    }
    boolean same(Match3Action other) { return other!=null && key().equals(other.key()); }
}
