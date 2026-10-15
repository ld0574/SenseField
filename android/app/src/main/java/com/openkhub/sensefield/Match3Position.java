package com.openkhub.sensefield;

import java.util.Objects;

/** Immutable observed state: foreground, cover and stationary floor have independent provenance. */
final class Match3Position {
    enum Kind {
        ANIMAL, COIN, SNOW, ICEFLOWER, HONEY, EGG, COOKIE, SPECIAL, EMPTY, SURFACE, UNKNOWN,
        BIRD, POISON, CHEST, FURBALL, INVINCIBLE_FURBALL, SNOWFLAKE, LEAF, CLOUD,
        FLOWER, EGG_SHELL, ICE_BARRIER, NEST, PENGUIN, SNOW_BEAR, SPRITE, ONE_EYE,
        DOLPHIN, GOLD_POD, KEY, GEM, STAR, JELLYFISH, CANNON, SHELL, PUFFER,
        MAGIC_STONE, DYE_EGG, DYE_BABY, STAR_BOTTLE, ENERGY_BOTTLE, OCTOPUS,
        CUTTLEFISH, MOVING_FLOOR, VACANT
    }
    enum SwapPermission { YES, NO, UNKNOWN }
    enum Cover { NONE, FROZEN_ICE, LOCK, HONEY, CAGE, UNKNOWN, GRAY_FURBALL }
    enum Effect { NONE, HORIZONTAL, VERTICAL, BLAST, UNKNOWN }
    enum Gate { OPEN, CLOSED, UNKNOWN }
    static final class Cell {
        private static final Cell VACANCY=create(Kind.VACANT,'\0',"unknown",SwapPermission.NO,0,-1);
        final Kind kind;
        final char color;
        final boolean swappable;
        final SwapPermission swapPermission, matchPermission, activationPermission, baseSwapPermission;
        final SwapPermission adjacentDamagePermission,specialDamagePermission;
        final boolean timedGate;
        final int layers, iceLayers, objectId, coverLayers, charge, capacity, direction, absentTurns;
        final Cover cover;
        final Effect effect;
        final Gate gate;
        final String ruleId, coverRuleId, floorRuleId;
        final int ruleVersion;
        /** Historical explicit fixture: NO movement still permits an uncovered animal to match. */
        Cell(Kind kind, char color, boolean swappable, int layers, int iceLayers) {
            this(kind,color,swappable?SwapPermission.YES:kind==Kind.ANIMAL || kind==Kind.SNOW
                    || kind==Kind.EMPTY?SwapPermission.NO:SwapPermission.UNKNOWN,
                    layers,iceLayers,-1,Cover.NONE,0,Effect.NONE,-1,-1,-1,0,Gate.OPEN,
                    legacyRule(kind),iceLayers>0?"stationary_single_layer_ice":"",1);
        }
        private Cell(Kind kind,char color,SwapPermission move,int layers,int iceLayers,int objectId,
                     Cover cover,int coverLayers,Effect effect,int charge,int capacity,int direction,
                     int absentTurns,Gate gate,String ruleId,String floorRuleId,int version) {
            this(kind,color,move,layers,iceLayers,objectId,cover,coverLayers,effect,charge,capacity,direction,
                    absentTurns,gate,ruleId,floorRuleId,version,false);
        }
        private Cell(Kind kind,char color,SwapPermission move,int layers,int iceLayers,int objectId,
                     Cover cover,int coverLayers,Effect effect,int charge,int capacity,int direction,
                     int absentTurns,Gate gate,String ruleId,String floorRuleId,int version,boolean timedGate) {
            if(kind==null || move==null || cover==null || effect==null || gate==null
                    || layers < -1 || layers>5 || iceLayers < -1 || iceLayers>1 || coverLayers < -1 || coverLayers>5
                    || charge < -1 || capacity < -1 || charge>9999 || capacity>9999 || capacity>=0 && charge>capacity
                    || direction < -1 || direction>3 || absentTurns < -1 || absentTurns>999
                    || kind!=Kind.ANIMAL && color!='\0'
                    || kind==Kind.ANIMAL && Match3Goals.Kind.animal(color)==Match3Goals.Kind.UNKNOWN
                    || kind!=Kind.ANIMAL && effect!=Effect.NONE
                    || move==SwapPermission.YES && kind!=Kind.ANIMAL && kind!=Kind.BIRD
                    || cover==Cover.FROZEN_ICE && coverLayers>2 || (cover==Cover.LOCK || cover==Cover.GRAY_FURBALL) && coverLayers>1
                    || !Match3Rules.knownRule(ruleId,version))
                throw new IllegalArgumentException("Unverified cell state");
            this.kind=kind;this.color=color;this.layers=layers;this.iceLayers=iceLayers;this.objectId=objectId;
            this.cover=cover;this.coverLayers=coverLayers;this.effect=effect;this.charge=charge;this.capacity=capacity;
            this.direction=direction;this.absentTurns=absentTurns;this.gate=gate;this.ruleId=ruleId;
            this.timedGate=timedGate;
            ruleVersion=version;this.floorRuleId=floorRuleId;baseSwapPermission=move;
            coverRuleId=cover==Cover.FROZEN_ICE?"frozen_ice":cover==Cover.LOCK?"locked_animal"
                    :cover==Cover.HONEY?"honey_cover":cover==Cover.CAGE?"cage":cover==Cover.GRAY_FURBALL?"gray_furball":"";
            boolean blocked=gate!=Gate.OPEN || cover!=Cover.NONE || layers<0 || iceLayers<0
                    || effect==Effect.BLAST || effect==Effect.UNKNOWN;
            swapPermission=move==SwapPermission.NO?SwapPermission.NO:blocked?(cover==Cover.FROZEN_ICE || cover==Cover.LOCK || cover==Cover.GRAY_FURBALL || gate==Gate.CLOSED
                    ?SwapPermission.NO:SwapPermission.UNKNOWN):move;
            swappable=swapPermission==SwapPermission.YES;
            matchPermission=kind!=Kind.ANIMAL || gate==Gate.CLOSED || cover==Cover.FROZEN_ICE || cover==Cover.GRAY_FURBALL?SwapPermission.NO
                    :gate==Gate.UNKNOWN || layers<0 || iceLayers<0 || cover==Cover.UNKNOWN || cover==Cover.HONEY || cover==Cover.CAGE
                    || move==SwapPermission.UNKNOWN || effect==Effect.UNKNOWN || effect==Effect.BLAST
                    ?SwapPermission.UNKNOWN:SwapPermission.YES;
            activationPermission=gate!=Gate.OPEN || cover!=Cover.NONE?SwapPermission.NO
                    :layers<0 || iceLayers<0?SwapPermission.UNKNOWN
                    :kind==Kind.ANIMAL && (effect==Effect.HORIZONTAL || effect==Effect.VERTICAL) || kind==Kind.BIRD
                    ?SwapPermission.YES:effect==Effect.BLAST || effect==Effect.UNKNOWN?SwapPermission.UNKNOWN:SwapPermission.NO;
            adjacentDamagePermission=gate!=Gate.OPEN?SwapPermission.UNKNOWN
                    :cover==Cover.FROZEN_ICE || cover==Cover.LOCK || cover==Cover.HONEY || cover==Cover.CAGE || cover==Cover.GRAY_FURBALL
                    || kind==Kind.SNOW || kind==Kind.COIN || kind==Kind.HONEY || kind==Kind.ICEFLOWER || kind==Kind.EGG
                    || kind==Kind.DYE_EGG || kind==Kind.POISON || kind==Kind.JELLYFISH || kind==Kind.MAGIC_STONE
                    ?SwapPermission.YES:kind==Kind.CHEST || kind==Kind.FURBALL || kind==Kind.INVINCIBLE_FURBALL
                    || kind==Kind.GOLD_POD || kind==Kind.KEY?SwapPermission.NO:SwapPermission.UNKNOWN;
            specialDamagePermission=gate!=Gate.OPEN || cover!=Cover.NONE?SwapPermission.UNKNOWN
                    :kind==Kind.SNOW || kind==Kind.FURBALL || kind==Kind.INVINCIBLE_FURBALL || kind==Kind.CANNON
                    || kind==Kind.ANIMAL && matchPermission==SwapPermission.YES?SwapPermission.YES
                    :kind==Kind.CHEST || kind==Kind.GOLD_POD || kind==Kind.KEY?SwapPermission.NO:SwapPermission.UNKNOWN;
        }
        private static String legacyRule(Kind kind) {
            switch(kind) {
                case ANIMAL:return "ordinary_uncovered_animal";
                case SNOW:return "snow";case COIN:return "coin";case HONEY:return "single_clear_honey";
                case ICEFLOWER:return "multistage_iceflower";case EGG:return "egg";case COOKIE:return "identity_only_2x2";
                default:return "unknown";
            }
        }
        private static Cell create(Kind kind,char color,String rule,SwapPermission move,int layers,int id) {
            return new Cell(kind,color,move,layers,0,id,Cover.NONE,0,Effect.NONE,-1,-1,-1,0,Gate.OPEN,rule,"",1);
        }
        static Cell animal(char c) { return create(Kind.ANIMAL,c,"ordinary_uncovered_animal",SwapPermission.YES,0,-1); }
        static Cell animalOnIce(char c) { return animal(c).withIce(1); }
        static Cell animalIdentity(char c) {
            return new Cell(Kind.ANIMAL,c,SwapPermission.UNKNOWN,-1,-1,-1,Cover.UNKNOWN,-1,Effect.UNKNOWN,
                    -1,-1,-1,0,Gate.UNKNOWN,"unknown","",1);
        }
        static Cell obstacle(Kind kind,int layers) { return new Cell(kind,'\0',false,layers,0); }
        static Cell cookie(int anchor) { return create(Kind.COOKIE,'\0',"identity_only_2x2",SwapPermission.NO,-1,anchor); }
        /** Typed identity is safe even if its mechanism has missing parameters. */
        static Cell observed(Kind kind,String rule,int layers,int objectId) {
            if(!Match3Rules.ruleForKind(kind).equals(rule))throw new IllegalArgumentException("Identity/rule mismatch");
            return create(kind,'\0',rule,kind==Kind.BIRD?SwapPermission.YES:SwapPermission.NO,layers,objectId);
        }
        static Cell bird() { return observed(Kind.BIRD,"magic_bird",0,-1); }
        static Cell vacant() { return VACANCY; }
        Cell withCover(Cover cover,int count) {
            if(cover==Cover.NONE && count!=0 || cover!=Cover.NONE && count==0)
                throw new IllegalArgumentException("Cover/layer mismatch");
            return copy(cover,count,effect,charge,capacity,direction,absentTurns,gate,iceLayers,floorRuleId);
        }
        Cell withEffect(Effect effect) {
            return new Cell(kind,color,baseMovement(),layers,iceLayers,objectId,cover,coverLayers,effect,charge,capacity,
                    direction,absentTurns,gate,effect==Effect.HORIZONTAL?"line_horizontal":effect==Effect.VERTICAL
                    ?"line_vertical":effect==Effect.BLAST?"blast":ruleId,floorRuleId,ruleVersion,timedGate);
        }
        Cell withEnergy(int charge,int capacity) {
            return copy(cover,coverLayers,effect,charge,capacity,direction,absentTurns,gate,iceLayers,floorRuleId);
        }
        Cell withDirection(int direction) {
            return copy(cover,coverLayers,effect,charge,capacity,direction,absentTurns,gate,iceLayers,floorRuleId);
        }
        Cell withGate(Gate gate) {
            return withAccess(gate,kind!=Kind.CHEST);
        }
        Cell withAccess(Gate gate,boolean timed) {
            return new Cell(kind,color,baseMovement(),layers,iceLayers,objectId,cover,coverLayers,effect,charge,capacity,
                    direction,absentTurns,gate,ruleId,floorRuleId,ruleVersion,timed);
        }
        Cell withAbsentTurns(int turns) {
            return copy(cover,coverLayers,effect,charge,capacity,direction,turns,gate,iceLayers,floorRuleId);
        }
        Cell withIce(int ice) {
            if(ice==iceLayers)return this;
            return copy(cover,coverLayers,effect,charge,capacity,direction,absentTurns,gate,ice,
                    ice>0?"stationary_single_layer_ice":"");
        }
        Cell withLayers(int count) {
            if(count==layers)return this;
            return new Cell(kind,color,baseMovement(),count,iceLayers,objectId,cover,coverLayers,effect,charge,capacity,
                    direction,absentTurns,gate,ruleId,floorRuleId,ruleVersion,timedGate);
        }
        Cell withFloorOf(Cell destination) {
            Cell cell=withIce(destination.iceLayers);
            return cell.gate==destination.gate && cell.timedGate==destination.timedGate?cell
                    :cell.withAccess(destination.gate,destination.timedGate);
        }
        private SwapPermission baseMovement() {
            return baseSwapPermission;
        }
        private Cell copy(Cover cover,int count,Effect effect,int charge,int capacity,int direction,
                          int absent,Gate gate,int ice,String floor) {
            return new Cell(kind,color,baseMovement(),layers,ice,objectId,cover,count,effect,charge,capacity,
                    direction,absent,gate,ruleId,floor,ruleVersion,timedGate);
        }
        boolean mobileByGravity() {
            return gate==Gate.OPEN && cover==Cover.NONE && (kind==Kind.ANIMAL && swappable || kind==Kind.BIRD
                    || kind==Kind.GOLD_POD || kind==Kind.KEY);
        }
        char code() {
            if(kind==Kind.ANIMAL)return matchPermission==SwapPermission.YES?color:'#';
            if(kind==Kind.SNOW)return 'I';if(kind==Kind.COIN)return 'C';if(kind==Kind.EGG)return 'E';
            if(kind==Kind.EMPTY)return 'H';if(kind==Kind.UNKNOWN)return '.';return '#';
        }
        @Override public boolean equals(Object object) {
            if(!(object instanceof Cell))return false;Cell o=(Cell)object;
            return kind==o.kind && color==o.color && baseSwapPermission==o.baseSwapPermission
                    && swapPermission==o.swapPermission && matchPermission==o.matchPermission
                    && layers==o.layers && iceLayers==o.iceLayers && objectId==o.objectId && cover==o.cover
                    && coverLayers==o.coverLayers && effect==o.effect && charge==o.charge && capacity==o.capacity
                    && direction==o.direction && absentTurns==o.absentTurns && gate==o.gate && timedGate==o.timedGate && ruleId.equals(o.ruleId)
                    && ruleVersion==o.ruleVersion && floorRuleId.equals(o.floorRuleId);
        }
        @Override public int hashCode() {
            return Objects.hash(kind,color,swapPermission,layers,iceLayers,objectId,cover,coverLayers,effect,
                    charge,capacity,direction,absentTurns,gate,ruleId,ruleVersion,floorRuleId);
        }
    }
    final int rows,cols;
    final Match3Topology topology;
    private final Cell[][] cells;
    private final java.util.BitSet stepDynamics=new java.util.BitSet();
    Match3Position(Cell[][] source) { this(source,null); }
    Match3Position(Cell[][] source,Match3Topology topology) {
        if(source==null || source.length==0 || source[0]==null || source[0].length==0)
            throw new IllegalArgumentException("Incomplete board");
        rows=source.length;cols=source[0].length;
        if(rows>12 || cols>12)throw new IllegalArgumentException("Unsupported board");
        cells=new Cell[rows][];
        for(int r=0;r<rows;r++) {
            if(source[r]==null || source[r].length!=cols)throw new IllegalArgumentException("Ragged board");
            cells[r]=source[r].clone();
            for(int c=0;c<cols;c++) {
                Cell cell=cells[r][c];if(cell==null)throw new IllegalArgumentException("Missing cell");
                if(cell.gate!=Gate.OPEN || cell.timedGate || cell.kind==Kind.POISON || cell.kind==Kind.OCTOPUS
                        || cell.kind==Kind.INVINCIBLE_FURBALL || cell.kind==Kind.MOVING_FLOOR || cell.cover==Cover.GRAY_FURBALL)
                    stepDynamics.set(r*cols+c);
            }
        }
        if(topology!=null && topology.size()!=rows*cols)throw new IllegalArgumentException("Topology size");
        this.topology=topology;
    }
    Cell cell(int row,int col) { return cells[row][col]; }
    Cell cell(int index) { return cell(index/cols,index%cols); }
    int nextDynamic(int from) { return stepDynamics.nextSetBit(from); }
    Cell[][] copyCells() { Cell[][] out=new Cell[rows][];for(int r=0;r<rows;r++)out[r]=cells[r].clone();return out; }
    char[][] matrix() {
        char[][] out=new char[rows][cols];for(int r=0;r<rows;r++)for(int c=0;c<cols;c++)out[r][c]=cells[r][c].code();return out;
    }
    boolean sameCells(Match3Position other) {
        if(other==null || rows!=other.rows || cols!=other.cols)return false;
        for(int r=0;r<rows;r++)for(int c=0;c<cols;c++)if(!cells[r][c].equals(other.cells[r][c]))return false;
        return Objects.equals(topology,other.topology);
    }
}
