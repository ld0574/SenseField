package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Observed HUD values. An unread count is -1, never an invented zero. */
final class Match3Goals {
    enum Kind {
        RED('R', "红狐狸"), BEAR('O', "棕熊"), CHICK('Y', "小鸡"),
        FROG('G', "青蛙"), HIPPO('B', "河马"), CAT('P', "紫猫"),
        COIN('\0', "银币"), SNOW('\0', "白色方块"), ICE('\0', "冰层"),
        ICEFLOWER('\0', "冰花"), HONEY('\0', "蜜罐"),
        EGG('\0', "鸡蛋"), COOKIE('\0', "饼干"), UNKNOWN('\0', "未确认目标");
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

    static final class Target {
        final int slot;
        final Kind kind;
        final int remaining;
        final boolean completed;
        final String visualId;
        Target(int slot, Kind kind, int remaining, boolean completed) {
            this(slot,kind,remaining,completed,"");
        }
        Target(int slot, Kind kind, int remaining, boolean completed,String visualId) {
            if (slot < 0 || slot > 7 || kind == null || remaining < -1 || remaining > 9999
                    || completed && remaining > 0 || visualId==null || visualId.length()>80)
                throw new IllegalArgumentException("Invalid target");
            this.slot = slot; this.kind = kind;
            this.remaining = completed ? 0 : remaining; this.completed = completed;
            this.visualId=kind==Kind.UNKNOWN?visualId:"";
        }
        boolean known() { return kind != Kind.UNKNOWN && remaining >= 0; }
        @Override public boolean equals(Object object) {
            if (!(object instanceof Target)) return false;
            Target other = (Target) object;
            return slot == other.slot && kind == other.kind && remaining == other.remaining
                    && completed == other.completed && visualId.equals(other.visualId);
        }
        @Override public int hashCode() { return Objects.hash(slot, kind, remaining, completed,visualId); }
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
                    || !targets.get(i).visualId.equals(other.targets.get(i).visualId)) return false;
        return true;
    }
    boolean sameValues(Match3Goals other) {
        return other != null && level == other.level && steps == other.steps && hudVerified == other.hudVerified
                && targets.equals(other.targets);
    }
    int remaining(Kind kind) {
        if (!hudVerified || kind == Kind.UNKNOWN) return -1;
        int count = -1; boolean found = false;
        for (Target target : targets) if (target.kind == kind) {
            if (target.remaining < 0) return -1;
            // A duplicated HUD field is not two independent tasks. Conflicting
            // copies are ambiguous; identical copies count only once.
            if (found && count != target.remaining) return -1;
            found = true; count = target.remaining;
        }
        return found ? count : -1;
    }
    /** An unread numeral does not erase an explicitly observed, unfinished task kind. */
    boolean active(Kind kind) {
        if(!hudVerified || kind==Kind.UNKNOWN)return false;
        boolean unfinished=false,finished=false;
        int known=-1;
        for(Target target:targets)if(target.kind==kind) {
            if(target.remaining>=0) {
                if(known>=0 && known!=target.remaining)return false;
                known=target.remaining;
            }
            if(target.completed || target.remaining==0)finished=true;
            else unfinished=true;
        }
        return unfinished && !finished;
    }
    String key() {
        if (!hudVerified) return "unread";
        StringBuilder out = new StringBuilder().append(level).append(':').append(steps);
        for (Target target : targets) out.append('|').append(target.slot).append(':').append(target.kind)
                .append(':').append(target.remaining).append(':').append(target.completed).append(':').append(target.visualId);
        return out.toString();
    }
}
