package com.openkhub.sensefield;

import android.graphics.RectF;

/**
 * A platform-neutral view of one entity in a native frame snapshot.
 *
 * <p>The native bridge returns confirmed minimap enemy and local-player
 * markers. Additional fields deliberately live here so future entity kinds
 * can be introduced without making Java callers understand another packed
 * array layout.</p>
 */
final class TrackedEntity {
    static final int KIND_UNKNOWN = 0;
    static final int KIND_MAIN_ENEMY = 1;
    static final int KIND_MINIMAP_ENEMY = 2;
    static final int KIND_DANGER_PING = 3;
    static final int KIND_PLAYER_STATE = 4;
    static final int KIND_MINIMAP_PLAYER = 6;

    static final int STATE_UNKNOWN = 0;
    static final int STATE_CANDIDATE = 0;
    static final int STATE_VISIBLE = 1;
    static final int STATE_LOST = 2;
    static final int STATE_EXPIRED = 3;

    static final int TRANSITION_NONE = 0;
    static final int TRANSITION_APPEAR = 1;
    static final int TRANSITION_DISAPPEAR = 2;
    static final int PLAYER_MAX_FRESHNESS_MS = 500;

    final int entityKind;
    final int trackId;
    final int state;
    final int movementDirection;
    final RectF bbox;
    final float confidence;
    final long lastSeenAtMs;
    final float velocityX;
    final float velocityY;
    final int ageMs;
    /** Freshness of the latest detector observation at the frame timestamp. */
    final int freshnessMs;
    final int transition;

    TrackedEntity(int entityKind, int trackId, int state, int movementDirection,
                  float x, float y, float width, float height, float confidence,
                  long lastSeenAtMs, float velocityX, float velocityY, int ageMs,
                  int transition) {
        this.entityKind = entityKind;
        this.trackId = trackId;
        this.state = state;
        this.movementDirection = movementDirection;
        // Assign the public fields directly. Besides avoiding an extra mutable
        // RectF operation, this keeps the value visible in Android's
        // return-default-values JVM tests where framework constructors are
        // intentionally no-ops.
        RectF bounds = new RectF();
        bounds.left = x;
        bounds.top = y;
        bounds.right = x + width;
        bounds.bottom = y + height;
        this.bbox = bounds;
        this.confidence = confidence;
        this.lastSeenAtMs = lastSeenAtMs;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.ageMs = Math.max(0, ageMs);
        this.freshnessMs = this.ageMs;
        this.transition = transition;
    }

    boolean isMinimapEnemy() {
        return entityKind == KIND_MINIMAP_ENEMY;
    }

    boolean isMinimapPlayer() {
        return entityKind == KIND_MINIMAP_PLAYER;
    }

    boolean isMinimapTrack() {
        return isMinimapEnemy() || isMinimapPlayer();
    }

    boolean hasAppearanceTransition() {
        return transition == TRANSITION_APPEAR;
    }

    boolean isFresh(long nowMs, long maxAgeMs) {
        return lastSeenAtMs >= 0 && nowMs >= lastSeenAtMs
                && nowMs - lastSeenAtMs <= Math.max(0L, maxAgeMs);
    }

    /** Only a confirmed, recently observed player marker can anchor relations. */
    boolean isUsablePlayer(long nowMs) {
        if (!isMinimapPlayer() || state != STATE_VISIBLE ||
                freshnessMs > PLAYER_MAX_FRESHNESS_MS) return false;
        return nowMs < 0 || lastSeenAtMs < 0 ||
                (nowMs >= lastSeenAtMs && nowMs - lastSeenAtMs <= PLAYER_MAX_FRESHNESS_MS);
    }

    /** Check the freshness recorded by the native frame itself. */
    boolean isUsablePlayer() {
        return isMinimapPlayer() && state == STATE_VISIBLE &&
                freshnessMs <= PLAYER_MAX_FRESHNESS_MS;
    }

    String eventKey() {
        return entityKind + ":" + trackId + ":" + transition;
    }
}
