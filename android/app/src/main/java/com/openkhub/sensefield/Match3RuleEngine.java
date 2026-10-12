package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure bounded settlement. Unknown parameters never manufacture hits, units or refill colors. */
final class Match3RuleEngine {
    static final int MAX_ROUNDS=12;
    private static final Match3Goals.Kind[] GOAL_KINDS=Match3Goals.Kind.values();
    enum EventType { CLEAR, HIT, LAYER, TRANSFORM, GENERATE, HATCH, RESCUE, DELIVER, CHARGE, TRIGGER, TEMPORARY, UNLOCK }
    static final class Event {
        final EventType type;
        final Match3Goals.Kind kind;
        final int units, cell, objectId, round;
        final String ruleId, detail;
        final int ruleVersion=Match3Rules.VERSION;
        Event(EventType type,Match3Goals.Kind kind,int units,int cell,int objectId,int round,String rule,String detail) {
            this.type=type;this.kind=kind;this.units=units;this.cell=cell;this.objectId=objectId;this.round=round;
            ruleId=rule;this.detail=detail;
        }
        Match3Goals.Mode mode() {
            switch(type) {
                case CLEAR:return Match3Goals.Mode.CLEAR;case HATCH:return Match3Goals.Mode.HATCH;
                case RESCUE:return Match3Goals.Mode.RESCUE;case DELIVER:return Match3Goals.Mode.DELIVER;
                case CHARGE:return Match3Goals.Mode.CHARGE;case TRIGGER:return Match3Goals.Mode.TRIGGER;
                case UNLOCK:return Match3Goals.Mode.UNLOCK;
                default:return Match3Goals.Mode.UNKNOWN;
            }
        }
        String evidence() { return type+":"+kind+":"+units+":"+cell+":"+objectId+":"+round+":"+ruleId+"@"+ruleVersion+":"+detail; }
    }
    static final class Result {
        final Match3Action action;
        final Match3Position before,after;
        final List<Event> events;
        final Set<String> unknowns;
        final int potentialSpecials, rounds;
        final boolean successorKnown;
        private final BitSet affected;
        Result(Work work) {
            // Ownership transfers from a private, finished Work. Consumers cannot mutate its containers.
            action=work.action;before=work.original;after=work.position();events=Collections.unmodifiableList(work.events);
            unknowns=Collections.unmodifiableSet(work.unknown);potentialSpecials=work.specials;
            rounds=work.round;successorKnown=work.exact && work.topologyKnown;
            affected=work.affected;
        }
        int collected(Match3Goals.Kind kind,Match3Goals.Mode mode) {
            if(mode==Match3Goals.Mode.UNKNOWN)return 0;
            int units=0;for(Event e:events)if(e.kind==kind && e.mode()==mode && e.units>0)units+=e.units;
            return units;
        }
        int hits(Match3Goals.Kind kind) {
            int units=0;for(Event e:events)if(e.kind==kind && e.type==EventType.HIT)units++;
            return units;
        }
        int preparation(Match3Goals.Kind kind,Match3Goals.Mode mode) {
            int opportunities=0;
            for(Event e:events)if(e.kind==kind && e.mode()==mode && e.units<0)opportunities++;
            return opportunities;
        }
        BitSet affectedPositions() { return (BitSet)affected.clone(); }
        String evidence() {
            return "rule_catalog="+Match3Rules.CATALOG_ID+" rule_version="+Match3Rules.VERSION+" action="+action.key()
                    +" rounds="+rounds+" successor_known="+successorKnown+" unknown="+String.join(",",unknowns);
        }
    }
    static Result resolve(Match3Position board,Match3Action action) {
        if(action==null)throw new IllegalArgumentException("Missing action");
        for(Match3Action legal:Match3Action.enumerate(board))if(legal.same(action))return resolveLegal(board,legal);
        throw new IllegalArgumentException("Illegal or unsupported action");
    }
    /** Caller owns a candidate returned by enumerate for this exact immutable position. */
    static Result resolveLegal(Match3Position board,Match3Action action) {
        Work work=new Work(board,action);work.settle();return new Result(work);
    }
    private static final class Work {
        final Match3Position original;
        final Match3Action action;
        final Match3Position.Cell[][] cells;
        final List<Event> events=new ArrayList<>();
        final Set<String> unknown=new LinkedHashSet<>();
        final BitSet affected=new BitSet();
        Set<Integer> emittedTriggers;
        boolean exact=true,topologyKnown;
        int specials,round,activatedLine=-1;
        Work(Match3Position board,Match3Action action) {
            original=board;this.action=action;cells=board.copyCells();
            topologyKnown=board.topology!=null && board.topology.validFor(board);
        }
        Match3Position position() { return new Match3Position(cells,original.topology); }
        Match3Position.Cell get(int p) { return cells[p/original.cols][p%original.cols]; }
        void put(int p,Match3Position.Cell value) { cells[p/original.cols][p%original.cols]=value; }
        void add(EventType type,Match3Goals.Kind kind,int units,int p,String rule,String detail) {
            if(p>=0 && type==EventType.TRIGGER) {
                if(emittedTriggers==null)emittedTriggers=new HashSet<>();
                if(!emittedTriggers.add((round*GOAL_KINDS.length+kind.ordinal())*144+p))return;
            }
            events.add(new Event(type,kind,units,p,p<0?-1:get(p).objectId,round,rule,detail));
        }
        void uncertain(String reason) { unknown.add(reason);exact=false; }
        void settle() {
            Match3Board.Swap s=action.geometry;
            int from=s.fromRow*original.cols+s.fromCol,to=s.toRow*original.cols+s.toCol;
            if(action.type!=Match3Action.Type.ACTIVATE) {
                Match3Position.Cell a=get(from),b=get(to);
                put(from,b.withFloorOf(a));put(to,a.withFloorOf(b));
            }
            round=1;
            if(action.type==Match3Action.Type.SWAP)apply(s.matchedPositions(),s.formedRuns(),new BitSet());
            else {
                BitSet direct=new BitSet();
                if(action.type==Match3Action.Type.ACTIVATE)lineMask(from,direct);
                else if(action.type==Match3Action.Type.LINE_SWAP) {
                    lineMask(Match3Action.line(get(from))?from:to,direct);
                    // The reference does not specify whether simultaneous triples settle before the line.
                    // Keep the documented line lower bound, but never search an unresolved successor.
                    for(Match3Board.Run run:Match3Board.findRuns(position().matrix())) {
                        BitSet mask=runMask(run);
                        if(mask.get(from) || mask.get(to)) {
                            mask.andNot(direct);
                            if(!mask.isEmpty())uncertain("line_swap_match_settlement_order_unknown");
                        }
                    }
                }
                else {
                    char color='\0';
                    if(action.type==Match3Action.Type.BIRD_COLOR)color=get(from).kind==Match3Position.Kind.ANIMAL?get(from).color:get(to).color;
                    for(int p=0;p<original.rows*original.cols;p++)if(get(p).kind==Match3Position.Kind.ANIMAL
                            && (color=='\0' || color==get(p).color))direct.set(p);
                    if(action.type==Match3Action.Type.CHARGED_PAIR) {
                        uncertain("charged_pair_terminal_state_unknown");
                    } else {
                        for(int p:new int[]{from,to})if(get(p).kind==Match3Position.Kind.BIRD) {
                            add(EventType.TRIGGER,Match3Goals.Kind.UNKNOWN,1,p,action.ruleId,"bird_swap");
                            if(get(p).iceLayers>0)uncertain("nonanimal_floor_damage_unverified");
                            affected.set(p);put(p,Match3Position.Cell.vacant().withFloorOf(get(p)));
                        }
                    }
                }
                apply(new BitSet(),Collections.emptyList(),direct);
            }
            unknownStepDynamics();
            settleGravity();
            for(int r=2;topologyKnown && exact && r<=MAX_ROUNDS;r++) {
                List<Match3Board.Run> runs=Match3Board.findRuns(position().matrix());
                if(runs.isEmpty())break;
                round=r;BitSet mask=new BitSet();for(Match3Board.Run run:runs)mask.or(runMask(run));
                apply(mask,runs,new BitSet());settleGravity();
                if(r==MAX_ROUNDS && !Match3Board.findRuns(position().matrix()).isEmpty())uncertain("cascade_round_budget");
            }
            if(!topologyKnown)unknown.add("gravity_topology_unobserved");
        }
        void unknownStepDynamics() {
            for(int p=original.nextDynamic(0);p>=0;p=original.nextDynamic(p+1)) {
                Match3Position.Cell cell=get(p);
                if(cell.kind==Match3Position.Kind.POISON || cell.kind==Match3Position.Kind.OCTOPUS)uncertain("poison_step_spread_unknown");
                if(cell.timedGate || cell.gate==Match3Position.Gate.UNKNOWN
                        || cell.gate==Match3Position.Gate.CLOSED && cell.kind!=Match3Position.Kind.CHEST)
                    uncertain("gate_period_or_state_unknown");
                if(cell.kind==Match3Position.Kind.INVINCIBLE_FURBALL)uncertain("temporary_return_period_unknown");
                if(cell.kind==Match3Position.Kind.MOVING_FLOOR)uncertain("dynamic_floor_transition_unknown");
                if(cell.cover==Match3Position.Cover.GRAY_FURBALL)uncertain("gray_furball_jump_destination_unknown");
            }
        }
        BitSet runMask(Match3Board.Run run) {
            BitSet set=new BitSet();for(int i=0;i<run.length;i++)set.set((run.row+(run.horizontal?0:i))*original.cols
                    +run.col+(run.horizontal?i:0));return set;
        }
        void lineMask(int p,BitSet mask) {
            activatedLine=p;
            Match3Position.Cell cell=get(p);boolean horizontal=cell.effect==Match3Position.Effect.HORIZONTAL;
            for(int i=0;i<(horizontal?original.cols:original.rows);i++)mask.set(horizontal?p/original.cols*original.cols+i:i*original.cols+p%original.cols);
            add(EventType.TRIGGER,Match3Goals.Kind.UNKNOWN,1,p,cell.ruleId,horizontal?"horizontal":"vertical");
        }
        List<BitSet> components(List<Match3Board.Run> runs) {
            if(runs.isEmpty() || runs.size()==1 && runs.get(0).length==3)return Collections.emptyList();
            List<BitSet> sets=new ArrayList<>();
            for(Match3Board.Run run:runs) {
                BitSet set=runMask(run);
                for(int i=sets.size()-1;i>=0;i--)if(sets.get(i).intersects(set))set.or(sets.remove(i));
                sets.add(set);
            }
            sets.removeIf(set->set.cardinality()<4);return sets;
        }
        void apply(BitSet matched,List<Match3Board.Run> runs,BitSet direct) {
            char[] sourceColors=new char[original.rows*original.cols];
            for(int p=matched.nextSetBit(0);p>=0;p=matched.nextSetBit(p+1))sourceColors[p]=get(p).color;
            for(int p=direct.nextSetBit(0);p>=0;p=direct.nextSetBit(p+1))sourceColors[p]=get(p).color;
            List<BitSet> retained=components(runs);BitSet ambiguous=new BitSet(),unboundedRetention=new BitSet();
            for(BitSet component:retained) {
                ambiguous.or(component);String type=generation(component,runs);
                if(type.isEmpty()) {
                    unboundedRetention.or(component);
                    add(EventType.GENERATE,Match3Goals.Kind.UNKNOWN,-1,-1,"ordinary_uncovered_animal","shape_not_documented");
                    uncertain("generation_shape_unverified");continue;
                }
                specials++;
                int first=component.nextSetBit(0);Match3Goals.Kind kind=Match3Goals.Kind.animal(get(first).color);
                add(EventType.CLEAR,kind,component.cardinality()-1,-1,"ordinary_uncovered_animal","retained_special_lower_bound");
                int ice=0;for(int p=component.nextSetBit(0);p>=0;p=component.nextSetBit(p+1))if(get(p).iceLayers==1)ice++;
                if(ice>1)add(EventType.CLEAR,Match3Goals.Kind.ICE,ice-1,-1,"stationary_single_layer_ice","retained_position_unknown");
                add(EventType.GENERATE,Match3Goals.Kind.UNKNOWN,1,-1,type,"anchor_unknown");
                uncertain("generation_anchor_unobserved");
            }
            // Direction alone does not establish range, blocking or a cannon-to-cannon link.
            for(int p=direct.nextSetBit(0);p>=0;p=direct.nextSetBit(p+1)) {
                Match3Position.Cell cell=get(p);
                if(cell.kind!=Match3Position.Kind.CANNON)continue;
                if(cell.direction<0 || cell.gate!=Match3Position.Gate.OPEN || cell.cover!=Match3Position.Cover.NONE) {
                    uncertain("cannon_direction_or_guard_unknown");continue;
                }
                add(EventType.TRIGGER,Match3Goals.Kind.CANNON,1,p,"cannon","direction="+cell.direction+";effect_mask_unknown");
                uncertain("cannon_range_blocking_targets_unknown");
            }
            BitSet removed=(BitSet)matched.clone();removed.andNot(ambiguous);
            for(int p=direct.nextSetBit(0);p>=0;p=direct.nextSetBit(p+1)) {
                Match3Position.Cell cell=get(p);
                if(cell.kind==Match3Position.Kind.ANIMAL && cell.matchPermission==Match3Position.SwapPermission.YES)removed.set(p);
            }
            BitSet possibleSources=(BitSet)matched.clone();possibleSources.or(removed);
            // Capture adjacency before mutation; special retention may make a single adjacent source uncertain.
            BitSet neighbours=new BitSet();
            for(int p=possibleSources.nextSetBit(0);p>=0;p=possibleSources.nextSetBit(p+1))
                for(int direction=0;direction<4;direction++) { int n=neighbour(p,direction);if(n>=0)neighbours.set(n); }
            affected.or(possibleSources);affected.or(direct);
            for(int p=removed.nextSetBit(0);p>=0;p=removed.nextSetBit(p+1)) {
                Match3Position.Cell cell=get(p);
                if(cell.kind!=Match3Position.Kind.ANIMAL)continue;
                add(EventType.CLEAR,Match3Goals.Kind.animal(cell.color),1,p,cell.ruleId,"animal_removed");
                if(cell.cover==Match3Position.Cover.LOCK) {
                    if(cell.coverLayers==1)add(EventType.UNLOCK,Match3Goals.Kind.LOCK,1,p,cell.coverRuleId,"matched_locked_animal");
                    else uncertain("lock_removal_layers_unknown");
                }
                if(cell.effect==Match3Position.Effect.BLAST)uncertain("blast_activation_mask_unknown");
                if(cell.effect==Match3Position.Effect.HORIZONTAL || cell.effect==Match3Position.Effect.VERTICAL) {
                    if(p!=activatedLine)
                        uncertain("line_hit_or_match_trigger_unverified");
                }
                if(cell.iceLayers>0) {
                    add(EventType.HIT,Match3Goals.Kind.ICE,1,p,"stationary_single_layer_ice","under_removed_animal");
                    if(cell.iceLayers==1)add(EventType.CLEAR,Match3Goals.Kind.ICE,1,p,"stationary_single_layer_ice","floor_removed");
                }
                put(p,Match3Position.Cell.vacant().withFloorOf(cell).withIce(Math.max(0,cell.iceLayers-1)));
            }
            Set<String> objectHits=new HashSet<>();Set<Integer> rescueObjects=new HashSet<>();
            BitSet impacted=(BitSet)neighbours.clone();impacted.or(direct);
            for(int p=impacted.nextSetBit(0);p>=0;p=impacted.nextSetBit(p+1)) {
                if(removed.get(p))continue;
                Match3Position.Cell cell=get(p);boolean adjacent=neighbours.get(p)
                        && guaranteedNeighbour(p,possibleSources,retained,unboundedRetention),special=direct.get(p);
                // A chest's own closed lock is a known nondamageable state, separate from a timed stone gate.
                if(cell.kind==Match3Position.Kind.CHEST && !cell.timedGate)continue;
                if(cell.gate!=Match3Position.Gate.OPEN) { uncertain("gate_damage_guard_unknown");continue; }
                // Removing this cover preserves the animal and stationary floor.
                // An opaque observation supplies neither an animal color nor a
                // future jump destination. A direct special hit is still unverified.
                if(cell.cover==Match3Position.Cover.GRAY_FURBALL) {
                    if(adjacent) {
                        add(EventType.HIT,Match3Goals.Kind.GRAY_FURBALL,1,p,cell.coverRuleId,"orthogonal_clear");
                        if(cell.coverLayers==1) {
                            add(EventType.CLEAR,Match3Goals.Kind.GRAY_FURBALL,1,p,cell.coverRuleId,"cover_removed_animal_preserved");
                            put(p,cell.withCover(Match3Position.Cover.NONE,0));
                            if(cell.kind==Match3Position.Kind.UNKNOWN)uncertain("gray_furball_foreground_unobserved");
                        } else uncertain("gray_furball_cover_layers_unknown");
                    } else if(special)uncertain("gray_furball_special_damage_unverified");
                    continue;
                }
                if(adjacent && cell.cover!=Match3Position.Cover.NONE) {
                    if(special)uncertain("special_cover_damage_unknown");
                    Match3Goals.Kind kind=cell.cover==Match3Position.Cover.FROZEN_ICE?Match3Goals.Kind.ICE
                            :cell.cover==Match3Position.Cover.HONEY?Match3Goals.Kind.HONEY_COVER
                            :cell.cover==Match3Position.Cover.CAGE?Match3Goals.Kind.CAGE
                            :cell.cover==Match3Position.Cover.LOCK?Match3Goals.Kind.LOCK:Match3Goals.Kind.UNKNOWN;
                    add(EventType.HIT,kind,1,p,cell.coverRuleId,"cover_hit");
                    if(cell.coverLayers<0)uncertain("cover_layers_unknown");
                    if(cell.cover!=Match3Position.Cover.FROZEN_ICE && cell.cover!=Match3Position.Cover.LOCK) {
                        uncertain("cover_damage_parameters_unknown");continue;
                    }
                    if(cell.coverLayers>0) {
                        add(EventType.LAYER,kind,1,p,cell.coverRuleId,"cover_decrement");
                        if(cell.kind==Match3Position.Kind.PENGUIN || cell.kind==Match3Position.Kind.SNOW_BEAR) {
                            String progress="rescue:"+(cell.objectId>=0?cell.objectId:p);
                            if(objectHits.add(progress))add(EventType.HIT,goalKind(cell.kind),1,p,cell.ruleId,"rescue_cover_progress");
                        }
                        if(cell.coverLayers==1 && kind==Match3Goals.Kind.ICE)add(EventType.CLEAR,kind,1,p,cell.coverRuleId,"cover_removed");
                        if(cell.coverLayers==1 && kind==Match3Goals.Kind.LOCK)add(EventType.UNLOCK,kind,1,p,cell.coverRuleId,"cover_removed");
                        put(p,cell.withCover(cell.coverLayers==1?Match3Position.Cover.NONE:cell.cover,cell.coverLayers-1));
                        if(cell.kind==Match3Position.Kind.PENGUIN || cell.kind==Match3Position.Kind.SNOW_BEAR)rescueObjects.add(cell.objectId>=0?cell.objectId:p);
                    } else uncertain("cover_layers_unknown");
                    continue;
                }
                if(cell.kind==Match3Position.Kind.COOKIE) {
                    String key="cookie:"+(cell.objectId>=0?cell.objectId:p);
                    adjacent=guaranteedObjectNeighbour(p,possibleSources,retained,unboundedRetention);
                    if(adjacent && objectHits.add(key))add(EventType.HIT,Match3Goals.Kind.COOKIE,1,p,cell.ruleId,"identity_only_opportunity");
                    if(adjacent || special)uncertain("cookie_damage_rule_unknown");continue;
                }
                if(cell.kind==Match3Position.Kind.SNOW && (adjacent || special)
                        || (cell.kind==Match3Position.Kind.COIN || cell.kind==Match3Position.Kind.HONEY || cell.kind==Match3Position.Kind.ICEFLOWER) && adjacent) {
                    Match3Goals.Kind kind=goalKind(cell.kind);add(EventType.HIT,kind,1,p,cell.ruleId,"one_hit_per_round");
                    if(cell.layers<1) { uncertain("obstacle_layers_unknown");continue; }
                    if(cell.kind!=Match3Position.Kind.SNOW && cell.layers>1) {
                        uncertain("legacy_multilayer_damage_unverified");continue;
                    }
                    if(cell.kind==Match3Position.Kind.SNOW && special && !adjacent && cell.layers>1) {
                        uncertain("special_multilayer_damage_unknown");continue;
                    }
                    add(EventType.LAYER,kind,1,p,cell.ruleId,"layer_decrement");
                    if(cell.layers==1) {
                        if(cell.iceLayers>0)uncertain("nonanimal_floor_damage_unverified");
                        add(EventType.CLEAR,kind,1,p,cell.ruleId,"terminal_hit");
                        if(cell.kind==Match3Position.Kind.ICEFLOWER) {
                            put(p,Match3Position.Cell.obstacle(Match3Position.Kind.SNOW,-1).withFloorOf(cell));
                            add(EventType.TRANSFORM,Match3Goals.Kind.SNOW,-1,p,cell.ruleId,"snow_layers_unknown");uncertain("iceflower_snow_layers_unknown");
                        } else put(p,Match3Position.Cell.vacant().withFloorOf(cell));
                        if(cell.kind==Match3Position.Kind.COIN)triggerCuttlefish(p);
                    } else put(p,cell.withLayers(cell.layers-1));
                } else if(cell.kind==Match3Position.Kind.EGG && adjacent) {
                    add(EventType.HIT,Match3Goals.Kind.EGG,1,p,"egg","hatch_opportunity");
                    add(EventType.HATCH,Match3Goals.Kind.CHICK,-1,p,"egg","spawn_count_positions_unknown");uncertain("chick_spawn_count_positions_unknown");
                } else if(cell.kind==Match3Position.Kind.DYE_EGG && adjacent) {
                    Set<Character> colors=new HashSet<>();
                    for(int direction=0;direction<4;direction++) {
                        int n=neighbour(p,direction);
                        if(n>=0 && matched.get(n) && sourceColors[n]!='\0')colors.add(sourceColors[n]);
                    }
                    if(colors.size()==1) {
                        char color=colors.iterator().next();put(p,Match3Position.Cell.animal(color).withFloorOf(cell));
                        add(EventType.TRANSFORM,Match3Goals.Kind.animal(color),0,p,"dye_egg","color="+color);
                    } else uncertain(colors.isEmpty()?"dye_egg_special_trigger_unverified":"dye_egg_multiple_trigger_colors");
                } else if(cell.kind==Match3Position.Kind.POISON && adjacent) {
                    add(EventType.HIT,Match3Goals.Kind.POISON,1,p,"poison","adjacent_clear");
                    add(EventType.CLEAR,Match3Goals.Kind.POISON,1,p,"poison","poison_removed");
                    put(p,Match3Position.Cell.vacant().withFloorOf(cell));
                    boolean charging=false,maybeCharging=false;
                    for(int bottle=0;bottle<original.rows*original.cols;bottle++) {
                        Match3Position.Cell energy=get(bottle);
                        if(energy.kind==Match3Position.Kind.STAR_BOTTLE && (energy.capacity<1 || energy.charge<0
                                || energy.charge<energy.capacity))maybeCharging=true;
                        if(energy.kind==Match3Position.Kind.STAR_BOTTLE && energy.capacity>0 && energy.charge>=0
                                && energy.charge<energy.capacity && energy.gate==Match3Position.Gate.OPEN
                                && energy.cover==Match3Position.Cover.NONE)charging=true;
                    }
                    if(charging)add(EventType.CHARGE,Match3Goals.Kind.STAR_BOTTLE,-1,p,"star_bottle","energy_amount_unknown");
                    if(maybeCharging)uncertain("poison_charge_amount_unknown");
                } else if(cell.kind==Match3Position.Kind.FURBALL && special) {
                    add(EventType.CLEAR,Match3Goals.Kind.FURBALL,1,p,"furball","special_only");put(p,Match3Position.Cell.vacant().withFloorOf(cell));
                } else if(cell.kind==Match3Position.Kind.INVINCIBLE_FURBALL && special) {
                    add(EventType.TEMPORARY,Match3Goals.Kind.UNKNOWN,-1,p,"invincible_furball","temporary_absence");
                    put(p,cell.withAbsentTurns(-1));uncertain("temporary_return_period_unknown");
                } else if((cell.kind==Match3Position.Kind.JELLYFISH || cell.kind==Match3Position.Kind.MAGIC_STONE) && adjacent) {
                    add(EventType.TRIGGER,cell.kind==Match3Position.Kind.MAGIC_STONE?Match3Goals.Kind.MAGIC_STONE:Match3Goals.Kind.UNKNOWN,
                            1,p,cell.ruleId,"effect_mask_unknown");uncertain(cell.ruleId+"_effect_mask_unknown");
                } else if(cell.kind!=Match3Position.Kind.ANIMAL && cell.kind!=Match3Position.Kind.EMPTY
                        && cell.kind!=Match3Position.Kind.VACANT && cell.kind!=Match3Position.Kind.CHEST
                        && cell.kind!=Match3Position.Kind.CANNON && cell.kind!=Match3Position.Kind.FURBALL) {
                    if(cell.kind==Match3Position.Kind.GOLD_POD || cell.kind==Match3Position.Kind.KEY)continue;
                    uncertain(Match3Rules.unknownDamage(cell.ruleId));
                } else if(special && cell.cover!=Match3Position.Cover.NONE)uncertain("special_cover_damage_unknown");
            }
            for(int id:rescueObjects)rescue(id);
            for(int p=ambiguous.nextSetBit(0);p>=0;p=ambiguous.nextSetBit(p+1))put(p,Match3Position.Cell.obstacle(Match3Position.Kind.UNKNOWN,-1).withFloorOf(get(p)));
        }
        String generation(BitSet component,List<Match3Board.Run> runs) {
            List<Match3Board.Run> connected=new ArrayList<>();
            for(Match3Board.Run run:runs)if(component.intersects(runMask(run)))connected.add(run);
            if(connected.size()==1) {
                Match3Board.Run run=connected.get(0);
                if(run.length==5)return "magic_bird";
                if(run.length==4)return run.horizontal?"line_horizontal":"line_vertical";
            }
            if(component.cardinality()==5 && connected.size()==2) {
                Match3Board.Run a=connected.get(0),b=connected.get(1);
                if(a.length==3 && b.length==3 && a.horizontal!=b.horizontal) {
                    Match3Board.Run h=a.horizontal?a:b,v=a.horizontal?b:a;
                    boolean horizontalEnd=v.col==h.col || v.col==h.col+h.length-1;
                    boolean verticalEnd=h.row==v.row || h.row==v.row+v.length-1;
                    if(horizontalEnd || verticalEnd)return "blast";
                }
            }
            return "";
        }
        int neighbour(int p,int direction) {
            switch(direction) {
                case 0:return p>=original.cols?p-original.cols:-1;
                case 1:return p<original.cols*(original.rows-1)?p+original.cols:-1;
                case 2:return p%original.cols>0?p-1:-1;
                default:return p%original.cols+1<original.cols?p+1:-1;
            }
        }
        boolean guaranteedNeighbour(int p,BitSet sources,List<BitSet> retained,BitSet unbounded) {
            if(retained.isEmpty()) {
                for(int direction=0;direction<4;direction++) { int n=neighbour(p,direction);if(n>=0 && sources.get(n))return true; }
                return false;
            }
            BitSet around=new BitSet();
            for(int direction=0;direction<4;direction++) { int n=neighbour(p,direction);if(n>=0 && sources.get(n))around.set(n); }
            return guaranteedSources(around,retained,unbounded);
        }
        boolean guaranteedObjectNeighbour(int p,BitSet sources,List<BitSet> retained,BitSet unbounded) {
            int id=get(p).objectId;if(id<0)return guaranteedNeighbour(p,sources,retained,unbounded);
            BitSet around=new BitSet();
            for(int at=0;at<original.rows*original.cols;at++)if(get(at).kind==Match3Position.Kind.COOKIE && get(at).objectId==id)
                for(int direction=0;direction<4;direction++) { int n=neighbour(at,direction);if(n>=0 && sources.get(n))around.set(n); }
            return guaranteedSources(around,retained,unbounded);
        }
        boolean guaranteedSources(BitSet around,List<BitSet> retained,BitSet unbounded) {
            around.andNot(unbounded);
            for(BitSet component:retained) {
                BitSet overlap=(BitSet)component.clone();overlap.and(around);
                if(overlap.cardinality()>=2)return true;around.andNot(component);
            }
            return !around.isEmpty();
        }
        void rescue(int id) {
            List<Integer> object=new ArrayList<>();Match3Position.Kind kind=null;
            for(int p=0;p<original.rows*original.cols;p++) {
                Match3Position.Cell cell=get(p);
                if((cell.kind==Match3Position.Kind.PENGUIN || cell.kind==Match3Position.Kind.SNOW_BEAR)
                        && (cell.objectId>=0?cell.objectId:p)==id) {
                    if(cell.cover!=Match3Position.Cover.NONE || cell.gate!=Match3Position.Gate.OPEN)return;
                    if(kind!=null && kind!=cell.kind) { uncertain("rescue_object_identity_conflict");return; }
                    kind=cell.kind;object.add(p);
                }
            }
            if(object.isEmpty())return;
            if(kind==Match3Position.Kind.SNOW_BEAR && object.size()==1 && get(object.get(0)).objectId<0) {
                uncertain("large_rescue_object_membership_unknown");return;
            }
            add(EventType.RESCUE,goalKind(kind),1,object.get(0),get(object.get(0)).ruleId,"whole_object_uncovered");
            for(int p:object)put(p,Match3Position.Cell.vacant().withFloorOf(get(p)));
            if(kind==Match3Position.Kind.SNOW_BEAR)uncertain("rescue_cannon_links_unknown");
        }
        void triggerCuttlefish(int coin) {
            for(int p=0;p<original.rows*original.cols;p++)if(get(p).kind==Match3Position.Kind.CUTTLEFISH) {
                add(EventType.TRIGGER,Match3Goals.Kind.UNKNOWN,-1,p,"cuttlefish","coin_clear_at="+coin);
                uncertain("cuttlefish_effect_links_unknown");
            }
        }
        void settleGravity() {
            if(!topologyKnown || !exact)return;
            int size=original.rows*original.cols,keys=0;boolean changed=true;int iterations=0;
            while(changed && ++iterations<=size*size+size) {
                changed=false;
                for(int p=size-1;p>=0;p--) {
                    Match3Position.Cell cell=get(p);
                    if(original.topology.collects(p,cell.kind) && cell.mobileByGravity()) {
                        add(EventType.DELIVER,goalKind(cell.kind),1,p,cell.ruleId,"confirmed_collector");
                        if(cell.kind==Match3Position.Kind.KEY)keys++;
                        put(p,Match3Position.Cell.vacant().withFloorOf(cell));changed=true;continue;
                    }
                    int to=original.topology.next(p);
                    if(to>=0 && cell.mobileByGravity() && get(to).kind==Match3Position.Kind.VACANT && get(to).gate==Match3Position.Gate.OPEN) {
                        put(to,cell.withFloorOf(get(to)));put(p,Match3Position.Cell.vacant().withFloorOf(cell));changed=true;
                    }
                }
            }
            if(changed)uncertain("gravity_iteration_budget");
            if(keys>0)for(int p=0;p<size;p++)if(get(p).kind==Match3Position.Kind.CHEST) {
                Match3Position.Cell chest=get(p);
                if(chest.capacity<1 || chest.charge<0) { uncertain("chest_key_threshold_unknown");continue; }
                int charge=Math.min(chest.capacity,chest.charge+keys);put(p,chest.withEnergy(charge,chest.capacity));
                if(charge==chest.capacity && chest.gate!=Match3Position.Gate.OPEN) {
                    put(p,get(p).withGate(Match3Position.Gate.OPEN));
                    add(EventType.TRIGGER,Match3Goals.Kind.KEY,0,p,"chest","key_threshold_reached");uncertain("chest_hidden_contents_unknown");
                }
            }
            if(original.topology.refills)for(int p=0;p<size;p++)if(get(p).kind==Match3Position.Kind.VACANT) {
                put(p,Match3Position.Cell.obstacle(Match3Position.Kind.UNKNOWN,-1).withFloorOf(get(p)));
                uncertain("random_refill_colors_unknown");
            }
        }
    }
    static Match3Goals.Kind goalKind(Match3Position.Kind kind) {
        try { return Match3Goals.Kind.valueOf(kind.name()); }catch(IllegalArgumentException missing) { return Match3Goals.Kind.UNKNOWN; }
    }
    private Match3RuleEngine() { }
}
