package com.openkhub.sensefield;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Versioned Java view of the packed JNI frame result.
 *
 * <p>Version zero is the 0.2.2 ABI already shipped in the application:
 * {@code [cue kind, direction, priority, observation count, processing us,
 * locator state, locator score, minimap ROI x/y/w/h ppm, marker count,
 * markers...]}.  A future bridge can prepend {@link #MAGIC} and use the
 * versioned record layout below without making old APKs reinterpret the new
 * fields as legacy markers.</p>
 */
final class NativeFrameResult {
    static final int LEGACY_FORMAT_VERSION = 0;
    static final int VERSION_1 = 1;
    /** Version 1 plus the near-zone relation output in header fields 16..23. */
    static final int VERSION_2 = 2;
    static final int VERSION_2_HEADER_SIZE = 24;
    // ASCII "NFR1".  No legacy cue kind can accidentally equal this value.
    static final int MAGIC = 0x4e465231;

    static final int LEGACY_HEADER_SIZE = 12;
    static final int LEGACY_MARKER_STRIDE = 9;
    static final int LEGACY_MARKER_CAPACITY = 8;
    static final int MAX_ENTITY_CAPACITY = 16;

    /** Versioned header: magic, version, header size, record stride, ... */
    static final int VERSIONED_HEADER_SIZE = 16;
    static final int VERSIONED_RECORD_STRIDE = 16;

    // Versioned header fields after magic/version/headerSize/recordStride.
    private static final int H_CUE_KIND = 4;
    private static final int H_DIRECTION = 5;
    private static final int H_PRIORITY = 6;
    private static final int H_OBSERVATION_COUNT = 7;
    private static final int H_PROCESSING_MICROS = 8;
    private static final int H_LOCATOR_STATE = 9;
    private static final int H_LOCATOR_SCORE = 10;
    private static final int H_ROI_X = 11;
    private static final int H_ROI_Y = 12;
    private static final int H_ROI_W = 13;
    private static final int H_ROI_H = 14;
    private static final int H_ENTITY_COUNT = 15;
    // Version 2 relation fields; state -1 means the relation layer is off.
    private static final int H_RELATION_STATE = 16;
    private static final int H_RELATION_EVENT = 17;
    private static final int H_RELATION_SECTOR = 18;
    private static final int H_RELATION_PAN_MILLI = 19;
    private static final int H_RELATION_DISTANCE_MILLI = 20;
    private static final int H_RELATION_EPISODE = 21;
    private static final int H_RELATION_SUPPRESSION = 22;
    private static final int H_RELATION_RELIABLE = 23;

    /** Near-zone relation output for one frame. */
    static final class Relation {
        static final Relation UNAVAILABLE = new Relation(
                NearZoneRouting.STATE_UNAVAILABLE, NearZoneRouting.EVENT_NONE, 0, 0f, -1f, 0, 0,
                false);

        final int state;
        final int event;
        final int sector;
        final float pan;
        /** Nearest eligible enemy in map short-edge units, or -1. */
        final float nearestDistance;
        final int episodeId;
        final int suppression;
        final boolean reliable;

        Relation(int state, int event, int sector, float pan, float nearestDistance,
                 int episodeId, int suppression, boolean reliable) {
            this.state = state;
            this.event = event;
            this.sector = sector;
            this.pan = pan;
            this.nearestDistance = nearestDistance;
            this.episodeId = episodeId;
            this.suppression = suppression;
            this.reliable = reliable;
        }

        boolean available() {
            return state != NearZoneRouting.STATE_UNAVAILABLE;
        }
    }

    // Versioned record: kind, track id, state, transition, direction,
    // bbox x/y/w/h ppm, confidence milli, last-seen age ms,
    // velocity x/y ppm, reserved.
    private static final int R_KIND = 0;
    private static final int R_TRACK_ID = 1;
    private static final int R_STATE = 2;
    private static final int R_TRANSITION = 3;
    private static final int R_DIRECTION = 4;
    private static final int R_X = 5;
    private static final int R_Y = 6;
    private static final int R_W = 7;
    private static final int R_H = 8;
    private static final int R_CONFIDENCE_MILLI = 9;
    private static final int R_AGE_MS = 10;
    private static final int R_VELOCITY_X = 11;
    private static final int R_VELOCITY_Y = 12;
    // Optional in future records: little-endian absolute lastSeenAtMs.
    private static final int R_LAST_SEEN_LOW = 13;
    private static final int R_LAST_SEEN_HIGH = 14;

    final int formatVersion;
    final int cueKind;
    final int cueDirection;
    final int cuePriority;
    final int observationCount;
    final int processingMicros;
    final int locatorState;
    final int locatorScoreMilli;
    final RectF minimapRoi;
    final List<TrackedEntity> entities;
    final Relation relation;

    private NativeFrameResult(int formatVersion, int cueKind, int cueDirection,
                              int cuePriority, int observationCount, int processingMicros,
                              int locatorState, int locatorScoreMilli, RectF minimapRoi,
                              List<TrackedEntity> entities) {
        this(formatVersion, cueKind, cueDirection, cuePriority, observationCount,
                processingMicros, locatorState, locatorScoreMilli, minimapRoi, entities,
                Relation.UNAVAILABLE);
    }

    private NativeFrameResult(int formatVersion, int cueKind, int cueDirection,
                              int cuePriority, int observationCount, int processingMicros,
                              int locatorState, int locatorScoreMilli, RectF minimapRoi,
                              List<TrackedEntity> entities, Relation relation) {
        this.formatVersion = formatVersion;
        this.cueKind = cueKind;
        this.cueDirection = cueDirection;
        this.cuePriority = cuePriority;
        this.observationCount = observationCount;
        this.processingMicros = processingMicros;
        this.locatorState = locatorState;
        this.locatorScoreMilli = locatorScoreMilli;
        this.minimapRoi = minimapRoi == null ? new RectF() : new RectF(minimapRoi);
        this.entities = Collections.unmodifiableList(new ArrayList<>(entities));
        this.relation = relation == null ? Relation.UNAVAILABLE : relation;
    }

    static NativeFrameResult empty() {
        return new NativeFrameResult(LEGACY_FORMAT_VERSION, 0, 0, 0, 0, 0,
                -1, 0, new RectF(), Collections.emptyList());
    }

    static NativeFrameResult parse(int[] packed) {
        return parse(packed, -1L);
    }

    /** Parse a result and use the frame timestamp to derive entity lastSeenAtMs. */
    static NativeFrameResult parse(int[] packed, long frameTimestampMs) {
        if (packed == null || packed.length == 0) return empty();
        if (packed[0] == MAGIC) return parseVersioned(packed, frameTimestampMs);
        return parseLegacy(packed, frameTimestampMs);
    }

    private static NativeFrameResult parseLegacy(int[] packed, long frameTimestampMs) {
        int cueKind = at(packed, 0, 0);
        int direction = at(packed, 1, 0);
        int priority = at(packed, 2, 0);
        int observations = at(packed, 3, 0);
        int micros = at(packed, 4, 0);
        int locatorState = at(packed, 5, -1);
        int locatorScore = at(packed, 6, 0);
        RectF roi = ppmRect(packed, 7);

        List<TrackedEntity> entities = new ArrayList<>();
        if (packed.length > 11) {
            int count = clamp(at(packed, 11, 0), 0, LEGACY_MARKER_CAPACITY);
            for (int index = 0; index < count; index++) {
                int base = LEGACY_HEADER_SIZE + index * LEGACY_MARKER_STRIDE;
                if (base < 0 || base + LEGACY_MARKER_STRIDE > packed.length) break;
                int age = Math.max(0, packed[base + 6]);
                long lastSeen = frameTimestampMs >= 0
                        ? Math.max(0L, frameTimestampMs - age) : -1L;
                entities.add(new TrackedEntity(
                        TrackedEntity.KIND_MINIMAP_ENEMY,
                        packed[base + 8],
                        packed[base],
                        packed[base + 1],
                        ppm(packed[base + 2]), ppm(packed[base + 3]),
                        ppm(packed[base + 4]), ppm(packed[base + 5]),
                        0.0f, lastSeen, 0.0f, 0.0f, age, packed[base + 7]));
            }
        }
        return new NativeFrameResult(LEGACY_FORMAT_VERSION, cueKind, direction,
                priority, observations, micros, locatorState, locatorScore, roi, entities);
    }

    private static NativeFrameResult parseVersioned(int[] packed, long frameTimestampMs) {
        if (packed.length < VERSIONED_HEADER_SIZE) return empty();
        int version = packed[1];
        int headerSize = packed[2];
        int recordStride = packed[3];
        // Unknown versions remain inspectable only when their common header is
        // safely described.  Malformed packets are ignored rather than guessed.
        int minimumHeader = version == VERSION_2 ? VERSION_2_HEADER_SIZE : VERSIONED_HEADER_SIZE;
        if ((version != VERSION_1 && version != VERSION_2) || headerSize < minimumHeader ||
                headerSize > packed.length || recordStride < 13) return empty();
        Relation relation = version == VERSION_2 ? new Relation(
                packed[H_RELATION_STATE], packed[H_RELATION_EVENT], packed[H_RELATION_SECTOR],
                packed[H_RELATION_PAN_MILLI] / 1000.0f,
                packed[H_RELATION_DISTANCE_MILLI] / 1000.0f,
                packed[H_RELATION_EPISODE], packed[H_RELATION_SUPPRESSION],
                packed[H_RELATION_RELIABLE] != 0) : Relation.UNAVAILABLE;
        int count = clamp(packed[H_ENTITY_COUNT], 0, MAX_ENTITY_CAPACITY);
        List<TrackedEntity> entities = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long baseLong = (long) headerSize + (long) index * recordStride;
            long endLong = baseLong + (long) recordStride;
            if (baseLong > Integer.MAX_VALUE || endLong > packed.length) break;
            int base = (int) baseLong;
            if (base < 0) break;
            int age = Math.max(0, packed[base + R_AGE_MS]);
            long lastSeen = frameTimestampMs >= 0
                    ? Math.max(0L, frameTimestampMs - age) : -1L;
            if (recordStride > R_LAST_SEEN_HIGH) {
                long explicitLastSeen = (packed[base + R_LAST_SEEN_LOW] & 0xffffffffL)
                        | ((packed[base + R_LAST_SEEN_HIGH] & 0xffffffffL) << 32);
                if (explicitLastSeen > 0L) lastSeen = explicitLastSeen;
            }
            entities.add(new TrackedEntity(
                    packed[base + R_KIND], packed[base + R_TRACK_ID],
                    packed[base + R_STATE], packed[base + R_DIRECTION],
                    ppm(packed[base + R_X]), ppm(packed[base + R_Y]),
                    ppm(packed[base + R_W]), ppm(packed[base + R_H]),
                    packed[base + R_CONFIDENCE_MILLI] / 1000.0f, lastSeen,
                    ppm(packed[base + R_VELOCITY_X]), ppm(packed[base + R_VELOCITY_Y]),
                    age, packed[base + R_TRANSITION]));
        }
        return new NativeFrameResult(version, packed[H_CUE_KIND], packed[H_DIRECTION],
                packed[H_PRIORITY], packed[H_OBSERVATION_COUNT], packed[H_PROCESSING_MICROS],
                packed[H_LOCATOR_STATE], packed[H_LOCATOR_SCORE], ppmRect(packed, H_ROI_X),
                entities, relation);
    }

    private static int at(int[] values, int index, int fallback) {
        return index >= 0 && index < values.length ? values[index] : fallback;
    }

    private static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    private static float ppm(int value) {
        return value / 1_000_000.0f;
    }

    private static RectF ppmRect(int[] values, int offset) {
        return new RectF(ppm(at(values, offset, 0)), ppm(at(values, offset + 1, 0)),
                ppm(at(values, offset, 0) + at(values, offset + 2, 0)),
                ppm(at(values, offset + 1, 0) + at(values, offset + 3, 0)));
    }
}
