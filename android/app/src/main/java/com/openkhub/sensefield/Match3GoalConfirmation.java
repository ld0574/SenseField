package com.openkhub.sensefield;

/** Three distinct fresh samples confirm the HUD; invalid/changed values retire the old state immediately. */
final class Match3GoalConfirmation {
    private Match3Goals pending, current;
    private int samples;
    private long lastAt = -1;
    Match3Goals accept(Match3Goals candidate, long now) {
        if (candidate == null || !candidate.hudVerified || candidate.observedAtMs < 0
                || now < candidate.observedAtMs || now - candidate.observedAtMs > 2400) {
            clear(); return Match3Goals.unknown(now);
        }
        if (candidate.observedAtMs == lastAt) return current == null ? Match3Goals.unknown(now) : current;
        if (candidate.observedAtMs < lastAt || lastAt >= 0 && candidate.observedAtMs - lastAt > 2400) clear();
        if (pending == null || !pending.sameValues(candidate)) {
            pending = candidate; samples = 1; current = null;
        } else samples++;
        lastAt = candidate.observedAtMs;
        if (samples >= 3) current = candidate;
        return current == null ? Match3Goals.unknown(now) : current;
    }
    void clear() { pending = current = null; samples = 0; lastAt = -1; }
}
