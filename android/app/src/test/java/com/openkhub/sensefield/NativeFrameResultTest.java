package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NativeFrameResultTest {
    @Test public void legacyPacketParsesMarkersAsEnemyEntities() {
        int[] packet = new int[NativeFrameResult.LEGACY_HEADER_SIZE
                + NativeFrameResult.LEGACY_MARKER_STRIDE];
        packet[0] = 1;
        packet[1] = 2;
        packet[2] = 40;
        packet[3] = 3;
        packet[4] = 1234;
        packet[5] = 1;
        packet[6] = 900;
        packet[7] = 100_000;
        packet[8] = 200_000;
        packet[9] = 300_000;
        packet[10] = 400_000;
        packet[11] = 1;
        packet[12] = TrackedEntity.STATE_VISIBLE;
        packet[13] = 1;
        packet[14] = 100_000;
        packet[15] = 200_000;
        packet[16] = 300_000;
        packet[17] = 400_000;
        packet[18] = 75;
        packet[19] = TrackedEntity.TRANSITION_APPEAR;
        packet[20] = 17;

        NativeFrameResult result = NativeFrameResult.parse(packet, 1000);
        assertEquals(NativeFrameResult.LEGACY_FORMAT_VERSION, result.formatVersion);
        assertEquals(1, result.entities.size());
        TrackedEntity entity = result.entities.get(0);
        assertEquals(TrackedEntity.KIND_MINIMAP_ENEMY, entity.entityKind);
        assertEquals(17, entity.trackId);
        assertEquals(925, entity.lastSeenAtMs);
        assertTrue(entity.hasAppearanceTransition());
    }

    @Test public void versionedPacketKeepsFutureEntityKindAndTypedFields() {
        int[] packet = new int[NativeFrameResult.VERSIONED_HEADER_SIZE
                + NativeFrameResult.VERSIONED_RECORD_STRIDE];
        packet[0] = NativeFrameResult.MAGIC;
        packet[1] = NativeFrameResult.VERSION_1;
        packet[2] = NativeFrameResult.VERSIONED_HEADER_SIZE;
        packet[3] = NativeFrameResult.VERSIONED_RECORD_STRIDE;
        packet[4] = 0;
        packet[5] = 0;
        packet[6] = 0;
        packet[7] = 1;
        packet[8] = 222;
        packet[9] = 1;
        packet[10] = 950;
        packet[11] = 100_000;
        packet[12] = 200_000;
        packet[13] = 500_000;
        packet[14] = 600_000;
        packet[15] = 1;
        int base = NativeFrameResult.VERSIONED_HEADER_SIZE;
        packet[base] = TrackedEntity.KIND_MINIMAP_PLAYER;
        packet[base + 1] = 8;
        packet[base + 2] = TrackedEntity.STATE_VISIBLE;
        packet[base + 3] = TrackedEntity.TRANSITION_NONE;
        packet[base + 4] = 0;
        packet[base + 5] = 250_000;
        packet[base + 6] = 350_000;
        packet[base + 7] = 100_000;
        packet[base + 8] = 100_000;
        packet[base + 9] = 875;
        packet[base + 10] = 20;
        packet[base + 11] = -10_000;
        packet[base + 12] = 25_000;

        NativeFrameResult result = NativeFrameResult.parse(packet, 1000);
        assertEquals(NativeFrameResult.VERSION_1, result.formatVersion);
        assertEquals(TrackedEntity.KIND_MINIMAP_PLAYER,
                result.entities.get(0).entityKind);
        assertEquals(0.875f, result.entities.get(0).confidence, 0.00001f);
        assertEquals(-0.01f, result.entities.get(0).velocityX, 0.00001f);
        assertEquals(980, result.entities.get(0).lastSeenAtMs);
    }

    @Test public void malformedVersionedPacketDoesNotBecomeLegacyCue() {
        NativeFrameResult result = NativeFrameResult.parse(new int[] {
                NativeFrameResult.MAGIC, NativeFrameResult.VERSION_1, 2, 3, 99
        });
        assertEquals(0, result.cueKind);
        assertTrue(result.entities.isEmpty());
    }

    @Test public void playerCoordinateStopsBeingUsableAfterHalfSecond() {
        int[] packet = new int[NativeFrameResult.VERSIONED_HEADER_SIZE
                + NativeFrameResult.VERSIONED_RECORD_STRIDE];
        packet[0] = NativeFrameResult.MAGIC;
        packet[1] = NativeFrameResult.VERSION_1;
        packet[2] = NativeFrameResult.VERSIONED_HEADER_SIZE;
        packet[3] = NativeFrameResult.VERSIONED_RECORD_STRIDE;
        packet[15] = 1;
        int base = NativeFrameResult.VERSIONED_HEADER_SIZE;
        packet[base] = TrackedEntity.KIND_MINIMAP_PLAYER;
        packet[base + 2] = TrackedEntity.STATE_VISIBLE;
        packet[base + 10] = 500;
        NativeFrameResult result = NativeFrameResult.parse(packet, 1000);
        assertTrue(result.entities.get(0).isUsablePlayer());
        assertTrue(!result.entities.get(0).isUsablePlayer(1501));
    }
}
