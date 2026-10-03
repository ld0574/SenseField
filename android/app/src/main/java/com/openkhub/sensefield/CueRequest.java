package com.openkhub.sensefield;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Immutable, channel-independent description of one accessibility event. */
final class CueRequest {
    enum Category {
        VISION_MEMORY,
        /** A confirmed left/right main-screen edge threat. */
        PERIPHERAL_THREAT,
        DANGER,
        PLAYER_STATE,
        SYSTEM,
        /** An enemy marker entered the player's minimap near zone. */
        NEAR_ZONE,
        /** Locally generated conversational speech owned by CuePlayer. */
        ASSISTANT
    }

    static final int CHANNEL_TONE = 1;
    static final int CHANNEL_SPEECH = 1 << 1;
    static final int CHANNEL_HAPTIC = 1 << 2;
    static final int CHANNEL_VISUAL = 1 << 3;

    final String sessionId;
    final String cueId;
    final String eventKey;
    final String kind;
    final Category category;
    final int priority;
    final long createdAtMs;
    final long expiresAtMs;
    final int requestedChannels;
    final int toneKind;
    final int direction;
    final int hapticCode;
    final String speech;
    /** Continuous stereo position in [-1, 1]; NaN keeps the legacy direction pan. */
    final float pan;
    /** Optional minimap range in map-short-edge units; NaN means unavailable. */
    final float distance;
    /** Optional urgency tier in [0, 2], with 2 as the strongest cue. */
    final float urgency;
    /** Number of currently fresh enemies, or -1 when not supplied. */
    final int freshEnemyCount;
    /** A last-moment guard for short-lived speech such as assistant answers. */
    private final BooleanSupplier playbackAllowed;

    CueRequest(String sessionId, String cueId, String eventKey, String kind,
               Category category, int priority, long createdAtMs, long expiresAtMs,
               int requestedChannels, int toneKind, int direction, int hapticCode,
               String speech) {
        this(sessionId, cueId, eventKey, kind, category, priority, createdAtMs, expiresAtMs,
                requestedChannels, toneKind, direction, hapticCode, speech, Float.NaN,
                Float.NaN, 0f, -1, () -> true);
    }

    CueRequest(String sessionId, String cueId, String eventKey, String kind,
               Category category, int priority, long createdAtMs, long expiresAtMs,
               int requestedChannels, int toneKind, int direction, int hapticCode,
               String speech, float pan) {
        this(sessionId, cueId, eventKey, kind, category, priority, createdAtMs, expiresAtMs,
                requestedChannels, toneKind, direction, hapticCode, speech, pan,
                Float.NaN, 0f, -1, () -> true);
    }

    CueRequest(String sessionId, String cueId, String eventKey, String kind,
               Category category, int priority, long createdAtMs, long expiresAtMs,
               int requestedChannels, int toneKind, int direction, int hapticCode,
               String speech, float distance, float urgency, int freshEnemyCount,
               BooleanSupplier playbackAllowed) {
        this(sessionId, cueId, eventKey, kind, category, priority, createdAtMs, expiresAtMs,
                requestedChannels, toneKind, direction, hapticCode, speech, Float.NaN,
                distance, urgency, freshEnemyCount, playbackAllowed);
    }

    CueRequest(String sessionId, String cueId, String eventKey, String kind,
               Category category, int priority, long createdAtMs, long expiresAtMs,
               int requestedChannels, int toneKind, int direction, int hapticCode,
               String speech, float pan, float distance, float urgency, int freshEnemyCount,
               BooleanSupplier playbackAllowed) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.cueId = Objects.requireNonNull(cueId);
        this.eventKey = Objects.requireNonNull(eventKey);
        this.kind = Objects.requireNonNull(kind);
        this.category = Objects.requireNonNull(category);
        this.priority = priority;
        this.createdAtMs = createdAtMs;
        this.expiresAtMs = expiresAtMs;
        this.requestedChannels = requestedChannels;
        this.toneKind = toneKind;
        this.direction = direction;
        this.hapticCode = hapticCode;
        this.speech = speech;
        this.pan = Float.isFinite(pan) ? Math.max(-1f, Math.min(1f, pan)) : Float.NaN;
        this.distance = Float.isFinite(distance) && distance >= 0f
                ? distance : Float.NaN;
        this.urgency = Float.isFinite(urgency)
                ? Math.max(0f, Math.min(2f, urgency)) : 0f;
        this.freshEnemyCount = freshEnemyCount < 0 ? -1 : freshEnemyCount;
        this.playbackAllowed = playbackAllowed == null ? () -> true : playbackAllowed;
    }

    boolean hasPan() {
        return Float.isFinite(pan);
    }

    /** Re-evaluate expiration and the caller's freshness condition at playback start. */
    boolean playbackAllowedAt(long atMs) {
        if (atMs > expiresAtMs) return false;
        try {
            return playbackAllowed.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
