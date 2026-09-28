package org.openrd.mapassist;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class GameProfileTest {
    private static void assertInvalidCounts(int deadCount, int aliveCount, String message)
            throws Exception {
        try {
            GameProfile.validatePlayerLifeCounts(deadCount, aliveCount);
            fail("Expected invalid player-life profile: " + message);
        } catch (JSONException expected) {
            // Expected.
        }
    }

    @Test public void acceptsSixSignaturesAndStrictEnabledBoolean() throws Exception {
        GameProfile.validatePlayerLifeCounts(3, 3);
        assertTrue(GameProfile.playerLifeEnabled(Boolean.TRUE, true));
        assertFalse(GameProfile.playerLifeEnabled(Boolean.TRUE, false));
        assertFalse(GameProfile.playerLifeEnabled(Boolean.FALSE, true));
    }

    @Test public void rejectsMalformedEnabledType() throws Exception {
        try {
            GameProfile.playerLifeEnabled("true", true);
            fail("Expected non-Boolean enabled flag to be rejected");
        } catch (JSONException expected) {
            // Expected.
        }
    }

    @Test public void rejectsTooFewPerClassAndDuplicateCrops() throws Exception {
        assertInvalidCounts(2, 3, "per-class minimum");
        Set<String> crops = new HashSet<>();
        String hash = "0000000000000000000000000000000000000000000000000000000000000001";
        GameProfile.validatePlayerLifeCropHash(crops, hash);
        try {
            GameProfile.validatePlayerLifeCropHash(crops, hash);
            fail("Expected duplicate crop hash to be rejected");
        } catch (JSONException expected) {
            // Expected.
        }
    }

    @Test public void rejectsTotalSignatureCountOutsideSixTo64() throws Exception {
        assertInvalidCounts(3, 2, "total minimum");
        assertInvalidCounts(32, 33, "total maximum");
    }
}
