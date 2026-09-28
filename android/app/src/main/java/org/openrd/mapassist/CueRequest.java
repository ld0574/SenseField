package org.openrd.mapassist;

import java.util.Objects;

/** Immutable, channel-independent description of one accessibility event. */
final class CueRequest {
    enum Category { VISION_MEMORY, DANGER, PLAYER_STATE, SYSTEM }

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

    CueRequest(String sessionId, String cueId, String eventKey, String kind,
               Category category, int priority, long createdAtMs, long expiresAtMs,
               int requestedChannels, int toneKind, int direction, int hapticCode,
               String speech) {
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
    }
}
