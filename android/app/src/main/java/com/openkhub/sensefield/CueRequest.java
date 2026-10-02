package com.openkhub.sensefield;

import java.util.Objects;

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
        NEAR_ZONE
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

    CueRequest(String sessionId, String cueId, String eventKey, String kind,
               Category category, int priority, long createdAtMs, long expiresAtMs,
               int requestedChannels, int toneKind, int direction, int hapticCode,
               String speech) {
        this(sessionId, cueId, eventKey, kind, category, priority, createdAtMs, expiresAtMs,
                requestedChannels, toneKind, direction, hapticCode, speech, Float.NaN);
    }

    CueRequest(String sessionId, String cueId, String eventKey, String kind,
               Category category, int priority, long createdAtMs, long expiresAtMs,
               int requestedChannels, int toneKind, int direction, int hapticCode,
               String speech, float pan) {
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
    }

    boolean hasPan() {
        return Float.isFinite(pan);
    }
}
