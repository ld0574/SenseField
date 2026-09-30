package com.openkhub.sensefield;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
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

    @Test public void verifiedYoloxRequiresReadyModelMetadata() throws Exception {
        assertTrue(GameProfile.metadataAllowsVerifiedYolox(1, true, true, true));
        assertFalse(GameProfile.metadataAllowsVerifiedYolox(1, false, true, true));
        assertFalse(GameProfile.metadataAllowsVerifiedYolox(1, true, true, false));
        assertFalse(GameProfile.metadataAllowsVerifiedYolox(1, true, false, true));
        assertFalse(GameProfile.metadataAllowsVerifiedYolox(2, true, true, true));
    }

    @Test public void yoloxMetadataMapsCurrentAndFutureClasses() throws Exception {
        GameProfile.YoloxModelData model = GameProfile.yoloxModelData(
                new String[] {"minimap_enemy", "minimap_player"},
                new float[] {0.67f, 0.91f});
        assertArrayEquals(new int[] {2, 6}, model.classKinds);
        assertArrayEquals(new float[] {0.67f, 0.91f}, model.classThresholds, 0.0001f);

        GameProfile.YoloxModelData current = GameProfile.yoloxModelData(
                new String[] {"minimap_enemy"}, new float[] {0.67f});
        assertArrayEquals(new int[] {2}, current.classKinds);
        assertArrayEquals(new float[] {0.67f}, current.classThresholds, 0.0001f);
    }

    @Test public void yoloxMetadataRejectsUnknownOrDuplicateClasses() throws Exception {
        for (String[] classes : new String[][] {
                {"hero_identity"},
                {"minimap_enemy", "minimap_enemy"}
        }) {
            try {
                float[] thresholds = new float[classes.length];
                java.util.Arrays.fill(thresholds, 0.67f);
                GameProfile.yoloxModelData(classes, thresholds);
                fail("Expected unsupported model metadata to be rejected");
            } catch (JSONException expected) {
                // Expected.
            }
        }
    }

    @Test public void yoloxMetadataValidatesTensorContract() throws Exception {
        GameProfile.validateYoloxTensorContract(
                new int[] {1, 3, 320, 320}, new int[] {1, 2100, 7}, 2);
        GameProfile.validateYoloxTensorContract(
                new int[] {1, 3, 416, 416}, new int[] {1, 3549, 7}, 2);
        assertTrue(GameProfile.validYoloxInputSize(320));
        assertTrue(GameProfile.validYoloxInputSize(416));
        assertTrue(GameProfile.validYoloxInputSize(640));
        assertFalse(GameProfile.validYoloxInputSize(1056));
        assertTrue(GameProfile.yoloxAnchorCount(320) == 2100);
        assertTrue(GameProfile.yoloxAnchorCount(416) == 3549);
        int[][] invalidInputs = {
                {1, 3, 319, 319},
                {1, 3, 321, 321},
                {1, 3, 300, 300},
                {1, 3, 416, 384},
                {1, 320, 320},
        };
        for (int[] input : invalidInputs) {
            try {
                GameProfile.validateYoloxTensorContract(
                        input, new int[] {1, 2100, 7}, 2);
                fail("Expected invalid YOLOX tensor contract to be rejected");
            } catch (JSONException expected) {
                // Expected.
            }
        }
        try {
            GameProfile.validateYoloxTensorContract(
                    new int[] {1, 3, 416, 416}, new int[] {1, 3548, 7}, 2);
            fail("Expected class/output width mismatch to be rejected");
        } catch (JSONException expected) {
            // Expected.
        }
    }

    @Test public void yoloxMetadataRequiresCanonicalTensorShapes() throws Exception {
        try {
            GameProfile.validateYoloxTensorContract(null, new int[] {1, 2100, 7}, 2);
            fail("Expected a missing canonical input shape to be rejected");
        } catch (JSONException expected) {
            // Expected.
        }
    }

    @Test public void yoloxAssetNamesStayInsideTheApkAssetNamespace() {
        assertTrue(GameProfile.validModelAssetName(
                GameProfile.DEFAULT_YOLOX_PARAM_ASSET));
        assertTrue(GameProfile.validModelAssetName("models/yolox-416.bin"));
        assertFalse(GameProfile.validModelAssetName("../outside.bin"));
        assertFalse(GameProfile.validModelAssetName("/absolute.bin"));
        assertFalse(GameProfile.validModelAssetName("models//yolox.bin"));
        assertFalse(GameProfile.validModelAssetName("models/yolox\\.bin"));
    }

    @Test public void yoloxMetadataAcceptsCanonicalStrides() throws Exception {
        GameProfile.validateYoloxStrides(new int[] {8, 16, 32});
    }

    @Test public void yoloxMetadataRejectsNonCanonicalStrides() throws Exception {
        try {
            GameProfile.validateYoloxStrides(new int[] {4, 8, 16});
            fail("Expected non-canonical YOLOX strides to be rejected");
        } catch (JSONException expected) {
            // Expected.
        }
    }

    @Test public void yoloxMetadataUsesScalarAndProfileClassThresholdPrecedence()
            throws Exception {
        float[] profile = {0.41f, 0.82f};
        assertArrayEquals(new float[] {0.73f, 0.73f},
                GameProfile.selectYoloxThresholds(2, 0.29f, 0.73f, null, profile),
                0.0001f);
        assertArrayEquals(new float[] {0.67f, 0.91f},
                GameProfile.selectYoloxThresholds(2, 0.29f, 0.73f,
                        new float[] {0.67f, 0.91f}, profile),
                0.0001f);
        assertArrayEquals(new float[] {0.41f, 0.82f},
                GameProfile.selectYoloxThresholds(2, 0.29f, null, null, profile),
                0.0001f);
        assertArrayEquals(new float[] {0.29f, 0.29f},
                GameProfile.selectYoloxThresholds(2, 0.29f, null, null, null),
                0.0001f);
    }

    @Test public void yoloxMetadataRejectsMalformedClassThresholds() throws Exception {
        try {
            GameProfile.yoloxThreshold("0.67", "minimap_enemy");
            fail("Expected non-numeric class threshold to be rejected");
        } catch (JSONException expected) {
            // Expected.
        }
        for (Number invalid : new Number[] {Double.NaN, -0.1, 1.1}) {
            try {
                GameProfile.yoloxThreshold(invalid, "minimap_player");
                fail("Expected out-of-range class threshold to be rejected");
            } catch (JSONException expected) {
                // Expected.
            }
        }
    }
}
