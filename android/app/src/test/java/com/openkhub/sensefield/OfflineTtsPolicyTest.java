package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.Locale;
import org.junit.Test;

public final class OfflineTtsPolicyTest {
    @Test public void networkAndNotInstalledVoicesCannotBecomeReady() {
        assertFalse(OfflineTtsPolicy.allows(Locale.SIMPLIFIED_CHINESE, true, false));
        assertFalse(OfflineTtsPolicy.allows(Locale.SIMPLIFIED_CHINESE, false, true));
        assertFalse(OfflineTtsPolicy.allows(Locale.ENGLISH, false, false));
        assertFalse(OfflineTtsPolicy.allows(null, false, false));
        assertTrue(OfflineTtsPolicy.allows(Locale.SIMPLIFIED_CHINESE, false, false));
        assertTrue(OfflineTtsPolicy.allows(Locale.TRADITIONAL_CHINESE, false, false));
    }

    @Test public void mandarinMainlandLocaleIsPreferredWithinAllowedVoices() {
        assertTrue(OfflineTtsPolicy.localeOrder(Locale.SIMPLIFIED_CHINESE)
                < OfflineTtsPolicy.localeOrder(Locale.CHINESE));
        assertTrue(OfflineTtsPolicy.localeOrder(Locale.CHINESE)
                < OfflineTtsPolicy.localeOrder(Locale.TRADITIONAL_CHINESE));
    }
}
