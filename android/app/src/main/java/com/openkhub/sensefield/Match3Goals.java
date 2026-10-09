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
        EGG('\0', "鸡蛋"), UNKNOWN('\0', "未确认目标");
        final char color;
        final String name;
        Kind(char color, String name) { this.color = color; this.name = name; }
        static Kind animal(char color) {
            for (Kind kind : values()) if (color != '\0' && kind.color == color) return kind;
            return UNKNOWN;
        }
    }

    static final class Target {
        final int slot;
        final Kind kind;
        final int remaining;
        final boolean completed;
        Target(int slot, Kind kind, int remaining, boolean completed) {
            if (slot < 0 || slot > 7 || kind == null || remaining < -1 || remaining > 9999
                    || completed && remaining > 0) throw new IllegalArgumentException("Invalid target");
            this.slot = slot; this.kind = kind;
            this.remaining = completed ? 0 : remaining; this.completed = completed;
        }
        boolean known() { return kind != Kind.UNKNOWN && remaining >= 0; }
        @Override public boolean equals(Object object) {
            if (!(object instanceof Target)) return false;
            Target other = (Target) object;
            return slot == other.slot && kind == other.kind && remaining == other.remaining
                    && completed == other.completed;
        }
        @Override public int hashCode() { return Objects.hash(slot, kind, remaining, completed); }
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
            if (targets.get(i).slot != other.targets.get(i).slot || targets.get(i).kind != other.targets.get(i).kind) return false;
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
    String key() {
        if (!hudVerified) return "unread";
        StringBuilder out = new StringBuilder().append(level).append(':').append(steps);
        for (Target target : targets) out.append('|').append(target.slot).append(':').append(target.kind)
                .append(':').append(target.remaining).append(':').append(target.completed);
        return out.toString();
    }
}
