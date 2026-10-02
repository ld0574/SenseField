package com.openkhub.sensefield;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small, stateful policy for reducing output load during a changing team
 * fight. It deliberately consumes the already parsed frame entities; no new
 * native ABI or model signal is needed.
 */
final class NearZoneCombatPolicy {
    static final long DENSE_WINDOW_MS = 800;
    static final long DENSE_QUIET_MS = 1500;
    static final int DENSE_TARGET_COUNT = 2;

    static final class Decision {
        final int freshEnemyCount;
        final float score;
        final int priority;
        final boolean dense;
        final boolean enteredDense;
        final boolean exitedDense;
        final boolean allowSpeech;
        final String denseReason;
        final float bestWindowScore;

        Decision(int freshEnemyCount, float score, int priority, boolean dense,
                 boolean enteredDense, boolean exitedDense, boolean allowSpeech,
                 String denseReason, float bestWindowScore) {
            this.freshEnemyCount = freshEnemyCount;
            this.score = score;
            this.priority = priority;
            this.dense = dense;
            this.enteredDense = enteredDense;
            this.exitedDense = exitedDense;
            this.allowSpeech = allowSpeech;
            this.denseReason = denseReason;
            this.bestWindowScore = bestWindowScore;
        }
    }

    private final ArrayDeque<Long> nearEventTimes = new ArrayDeque<>();
    private boolean dense;
    private String lastMultiTargetSignature = "";
    private long lastChangeAtMs = Long.MIN_VALUE / 2;
    private float bestDenseScore;
    private long scoreWindowStartedAtMs = -1;

    Decision observe(NativeFrameResult frame, long observedAtMs, int maxAgeMs) {
        int freshEnemies = 0;
        List<Integer> ids = new ArrayList<>();
        if (frame != null && frame.entities != null) {
            for (TrackedEntity entity : frame.entities) {
                if (!entity.isMinimapEnemy() || entity.state != TrackedEntity.STATE_VISIBLE
                        || entity.freshnessMs < 0
                        || entity.freshnessMs > Math.max(0, maxAgeMs)) continue;
                freshEnemies++;
                ids.add(entity.trackId);
            }
        }
        Collections.sort(ids);
        String signature = ids.toString();
        boolean multiTargetChange = freshEnemies >= DENSE_TARGET_COUNT
                && !signature.equals(lastMultiTargetSignature);
        if (multiTargetChange) {
            lastMultiTargetSignature = signature;
            lastChangeAtMs = observedAtMs;
        } else if (freshEnemies < DENSE_TARGET_COUNT) {
            lastMultiTargetSignature = "";
        }

        NativeFrameResult.Relation relation = frame == null ? null : frame.relation;
        boolean nearEvent = relation != null
                && relation.event == NearZoneRouting.EVENT_NEAR_ENTER;
        if (nearEvent) {
            nearEventTimes.addLast(observedAtMs);
            lastChangeAtMs = observedAtMs;
        }
        while (!nearEventTimes.isEmpty()
                && observedAtMs - nearEventTimes.peekFirst() > DENSE_WINDOW_MS) {
            nearEventTimes.removeFirst();
        }
        boolean denseTrigger = multiTargetChange || nearEventTimes.size() >= DENSE_TARGET_COUNT;
        boolean entered = false;
        boolean exited = false;
        String reason = "none";
        if (!dense && denseTrigger) {
            dense = true;
            entered = true;
            bestDenseScore = 0f;
            scoreWindowStartedAtMs = -1;
            reason = multiTargetChange ? "multiple_fresh_enemies" : "multiple_near_events";
        } else if (dense && observedAtMs - lastChangeAtMs > DENSE_QUIET_MS) {
            dense = false;
            exited = true;
            bestDenseScore = 0f;
            scoreWindowStartedAtMs = -1;
            reason = "quiet_window";
            nearEventTimes.clear();
        }

        float score = score(freshEnemies, nearEvent, relation == null
                ? -1f : relation.nearestDistance);
        boolean allowSpeech = true;
        if (dense && nearEvent) {
            // A non-event frame is not a spoken competitor. Each new window
            // also expires the previous winner, even if dense mode continues.
            if (scoreWindowStartedAtMs < 0 || observedAtMs < scoreWindowStartedAtMs
                    || observedAtMs - scoreWindowStartedAtMs >= DENSE_WINDOW_MS) {
                scoreWindowStartedAtMs = observedAtMs;
                bestDenseScore = 0f;
            }
            allowSpeech = score >= bestDenseScore;
            if (allowSpeech) bestDenseScore = score;
        }
        return new Decision(freshEnemies, score, priorityForScore(score), dense, entered,
                exited, allowSpeech, reason, bestDenseScore);
    }

    void reset() {
        nearEventTimes.clear();
        dense = false;
        lastMultiTargetSignature = "";
        lastChangeAtMs = Long.MIN_VALUE / 2;
        bestDenseScore = 0f;
        scoreWindowStartedAtMs = -1;
    }

    boolean isDense() { return dense; }

    static float score(int threat, boolean urgent, float distance) {
        float safeThreat = Math.max(1, threat);
        float safeUrgency = urgent ? 2f : 1f;
        float safeDistance = Float.isFinite(distance) && distance > 0f
                ? Math.max(0.05f, distance) : 1f;
        return safeThreat * safeUrgency / safeDistance;
    }

    static int priorityForScore(float score) {
        if (!Float.isFinite(score) || score <= 0f) return NearZoneRouting.NEAR_PRIORITY;
        // Preserve ordering over the normal near-distance range, rather than
        // saturating every nearby target at 99. Critical system/player cues
        // keep their reserved priority of 100.
        return Math.min(99, NearZoneRouting.NEAR_PRIORITY
                + Math.round(19f * (score / (score + 10f))));
    }
}
