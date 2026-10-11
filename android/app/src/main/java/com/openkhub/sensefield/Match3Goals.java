package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Observed HUD values. An unread count is -1, never an invented zero. */
final class Match3Goals {
    enum Kind {
        RED('R', "红狐狸"), BEAR('O', "棕色动物"), CHICK('Y', "小鸡"),
        FROG('G', "青蛙"), HIPPO('B', "河马"), CAT('P', "紫猫头鹰"),
        COIN('\0', "银币"), SNOW('\0', "雪块"), ICE('\0', "冰层"),
        ICEFLOWER('\0', "冰花"), HONEY('\0', "蜜罐"),
        EGG('\0', "鸡蛋"), COOKIE('\0', "饼干"), UNKNOWN('\0', "未确认目标"),
        POISON('\0', "毒液"), PENGUIN('\0', "企鹅"), SNOW_BEAR('\0', "雪熊"),
        GOLD_POD('\0', "金豆荚"), KEY('\0', "钥匙"), FURBALL('\0', "毛球"),
        CAGE('\0', "牢笼"), STAR_BOTTLE('\0', "星星瓶"), DYE_BABY('\0', "染色宝宝"),
        CANNON('\0', "魔法炮"), MAGIC_STONE('\0', "魔法石"),
        DOLPHIN('\0', "海豚"), SPRITE('\0', "精灵"), GEM('\0', "宝石"), STAR('\0', "星星"), HONEY_COVER('\0',"蜂蜜覆盖"), LOCK('\0',"藤蔓"),
        FLOWER('\0', "花团");
        final char color;
        final String name;
        Kind(char color, String name) { this.color = color; this.name = name; }
        static Kind animal(char color) {
            switch (color) {
                case 'R': return RED;
                case 'O': return BEAR;
                case 'Y': return CHICK;
                case 'G': return FROG;
                case 'B': return HIPPO;
                case 'P': return CAT;
                default: return UNKNOWN;
            }
        }
    }

    enum Mode { CLEAR, HATCH, RESCUE, DELIVER, CHARGE, TRIGGER, UNKNOWN, UNLOCK }
    static boolean supportsMode(Kind kind,Mode mode) {
        if(mode==Mode.UNKNOWN)return true;
        switch(mode) {
            case CLEAR:return kind.color!='\0' || kind==Kind.SNOW || kind==Kind.COIN || kind==Kind.ICE
                    || kind==Kind.ICEFLOWER || kind==Kind.HONEY || kind==Kind.HONEY_COVER || kind==Kind.POISON || kind==Kind.FURBALL;
            case HATCH:return kind==Kind.CHICK;
            case RESCUE:return kind==Kind.PENGUIN || kind==Kind.SNOW_BEAR || kind==Kind.DOLPHIN || kind==Kind.CAGE;
            case DELIVER:return kind==Kind.GOLD_POD || kind==Kind.KEY;
            case CHARGE:return kind==Kind.STAR_BOTTLE || kind==Kind.DYE_BABY;
            case TRIGGER:return kind==Kind.CANNON || kind==Kind.MAGIC_STONE;
            case UNLOCK:return kind==Kind.LOCK;
            default:return false;
        }
    }
    static final class Target {
        final int slot;
        final Kind kind;
        final int remaining;
        final boolean completed;
        final String visualId;
        final Mode mode;
        final String modeEvidence;
        /** Explicit programmatic task model. Runtime icons must use observed(), not this compatibility API. */
        Target(int slot, Kind kind, int remaining, boolean completed) {
            this(slot,kind,remaining,completed,"");
        }
        Target(int slot, Kind kind, int remaining, boolean completed,String visualId) {
            this(slot,kind,remaining,completed,visualId,kind==Kind.FLOWER?Mode.UNKNOWN:kind==Kind.LOCK?Mode.UNLOCK:Mode.CLEAR,
                    kind==Kind.FLOWER?"icon_identity_only":"explicit_legacy_task_model");
        }
        Target(int slot,Kind kind,int remaining,boolean completed,String visualId,Mode mode,String evidence) {
            if (slot < 0 || slot > 7 || kind == null || remaining < -1 || remaining > 9999
                    || completed && remaining > 0 || visualId==null || visualId.length()>80
                    || mode==null || evidence==null || evidence.length()>160 || mode!=Mode.UNKNOWN && evidence.isEmpty()
                    || kind==Kind.FLOWER && mode!=Mode.UNKNOWN)
                throw new IllegalArgumentException("Invalid target");
            this.slot = slot; this.kind = kind;
            this.remaining = completed ? 0 : remaining; this.completed = completed;
            this.visualId=kind==Kind.UNKNOWN?visualId:"";
            this.mode=kind==Kind.UNKNOWN?Mode.UNKNOWN:mode;this.modeEvidence=evidence;
        }
        static Target observed(int slot,Kind kind,int remaining,boolean complete,String visualId) {
            boolean clear=kind.color!='\0' && kind!=Kind.CHICK || kind==Kind.SNOW || kind==Kind.COIN
                    || kind==Kind.ICE || kind==Kind.ICEFLOWER || kind==Kind.HONEY || kind==Kind.POISON || kind==Kind.FURBALL;
            Mode mode=kind==Kind.LOCK?Mode.UNLOCK:clear?Mode.CLEAR:kind==Kind.GOLD_POD || kind==Kind.KEY?Mode.DELIVER:Mode.UNKNOWN;
            return new Target(slot,kind,remaining,complete,visualId,mode,
                    mode!=Mode.UNKNOWN?"reviewed_hud_rule":"icon_identity_only");
        }
        boolean known() { return kind != Kind.UNKNOWN && remaining >= 0; }
        @Override public boolean equals(Object object) {
            if (!(object instanceof Target)) return false;
            Target other = (Target) object;
            return slot == other.slot && kind == other.kind && remaining == other.remaining
                    && completed == other.completed && visualId.equals(other.visualId) && mode==other.mode
                    && modeEvidence.equals(other.modeEvidence);
        }
        @Override public int hashCode() { return Objects.hash(slot,kind,remaining,completed,visualId,mode,modeEvidence); }
    }

    final int level, steps;
    final boolean hudVerified;
    final long observedAtMs;
    final List<Target> targets;
    Match3Goals(int level, int steps, List<Target> targets, boolean hudVerified, long observedAtMs) {
        if (level < -1 || level > 99999 || steps < -1 || steps > 999 || targets == null || targets.size() > 8)
            throw new IllegalArgumentException("Invalid HUD");
        boolean[] slots = new boolean[8];
        for (Target target : targets) {
            if (target == null || slots[target.slot]) throw new IllegalArgumentException("Duplicate target");
            slots[target.slot] = true;
        }
        this.level = level; this.steps = steps; this.hudVerified = hudVerified; this.observedAtMs = observedAtMs;
        this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
    }
    static Match3Goals unknown(long at) { return new Match3Goals(-1, -1, Collections.emptyList(), false, at); }
    boolean fullyKnown() {
        if (!hudVerified || steps < 0 || targets.isEmpty()) return false;
        for (Target target : targets) if (!target.known() || remaining(target.kind) < 0) return false;
        return true;
    }
    boolean finished() {
        if (!fullyKnown()) return false;
        for (Target target : targets) if (target.remaining != 0) return false;
        return true;
    }
    boolean sameIdentity(Match3Goals other) {
        if (other == null || level != other.level || targets.size() != other.targets.size()) return false;
        for (int i = 0; i < targets.size(); i++)
            if (targets.get(i).slot != other.targets.get(i).slot || targets.get(i).kind != other.targets.get(i).kind
                    || targets.get(i).mode!=other.targets.get(i).mode
                    || !targets.get(i).visualId.equals(other.targets.get(i).visualId)) return false;
        return true;
    }
    boolean sameValues(Match3Goals other) {
        return other != null && level == other.level && steps == other.steps && hudVerified == other.hudVerified
                && targets.equals(other.targets);
    }
    int remaining(Kind kind) {
        if (!hudVerified || kind == Kind.UNKNOWN) return -1;
        int count = -1; boolean found = false; Mode mode=null;
        for (Target target : targets) if (target.kind == kind) {
            if (target.remaining < 0) return -1;
            // A duplicated HUD field is not two independent tasks. Conflicting
            // copies are ambiguous; identical copies count only once.
            if (found && (count != target.remaining || mode!=target.mode)) return -1;
            found = true; count = target.remaining;mode=target.mode;
        }
        return found ? count : -1;
    }
    /** An unread numeral does not erase an explicitly observed, unfinished task kind. */
    boolean active(Kind kind) {
        if(!hudVerified || kind==Kind.UNKNOWN)return false;
        boolean unfinished=false,finished=false;
        int known=-1;Mode mode=null;
        for(Target target:targets)if(target.kind==kind) {
            if(mode!=null && mode!=target.mode)return false;mode=target.mode;
            if(target.remaining>=0) {
                if(known>=0 && known!=target.remaining)return false;
                known=target.remaining;
            }
            if(target.completed || target.remaining==0)finished=true;
            else unfinished=true;
        }
        return unfinished && !finished;
    }
    Mode mode(Kind kind) {
        Mode found=null;
        for(Target target:targets)if(target.kind==kind) {
            if(found!=null && found!=target.mode)return Mode.UNKNOWN;found=target.mode;
        }
        return found==null?Mode.UNKNOWN:found;
    }
    boolean hasTarget(Kind kind) {
        for(Target target:targets)if(target.kind==kind)return true;return false;
    }
    String key() {
        if (!hudVerified) return "unread";
        StringBuilder out = new StringBuilder().append(level).append(':').append(steps);
        for (Target target : targets) out.append('|').append(target.slot).append(':').append(target.kind)
                .append(':').append(target.remaining).append(':').append(target.completed).append(':').append(target.visualId)
                .append(':').append(target.mode).append(':').append(target.modeEvidence);
        return out.toString();
    }
}
