package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic pixels only. Proves the shrunk APK loads its recognizer, not detection quality. */
@RunWith(AndroidJUnit4.class)
public final class ReleaseRuntimeInstrumentedTest {
    @Test public void packagedMinimapProfileLoadsAndProcessesSyntheticPixelsThroughJni() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GameProfile profile = GameProfile.load(context);
        GameProfile.TemplateData enemy = profile.enemyTemplate, ping = profile.pingTemplate;
        GameProfile.PlayerLifeData life = profile.playerLife;
        long session = NativeBridge.nativeCreate(context.getAssets(), profile.rois, profile.flags,
                profile.tuning, profile.eventInts, profile.minConfidence,
                enemy == null ? null : enemy.rgba, enemy == null ? 0 : enemy.width,
                enemy == null ? 0 : enemy.height, ping == null ? null : ping.rgba,
                ping == null ? 0 : ping.width, ping == null ? 0 : ping.height,
                profile.minimapYolox, profile.yoloxInputSize, profile.yoloxParamAsset,
                profile.yoloxBinAsset, profile.yoloxConfidence, profile.yoloxNms,
                profile.yoloxClassKinds, profile.yoloxClassThresholds,
                profile.minimapLocatorEnabled, profile.minimapLocatorFloats,
                profile.minimapLocatorInts, profile.minimapLocatorDescriptor, life != null,
                life == null ? null : life.roiAndThresholds, life == null ? 0 : life.maxDhashDistance,
                life == null ? null : life.hashes, life == null ? null : life.states,
                life == null ? null : life.luma, life == null ? null : life.chroma);
        assertTrue("The compressed native recognizer must load its packaged model", session != 0);
        try {
            if (profile.relation != null) assertTrue(NativeBridge.nativeConfigureRelation(session,
                    profile.relation.floats, profile.relation.ints));
            int width = 1280, height = 720;
            ByteBuffer synthetic = ByteBuffer.allocateDirect(width * height * 4);
            long now = SystemClock.elapsedRealtime();
            int[] packed = NativeBridge.nativeProcess(session, synthetic, width, height,
                    width * 4, now, now);
            assertNotNull(packed);
            assertTrue(packed.length > 0);
            assertNotNull(NativeBridge.nativeReadDiagnosticSnapshot(session));
            NativeBridge.nativeReset(session);
        } finally { NativeBridge.nativeDestroy(session); }
    }
}
