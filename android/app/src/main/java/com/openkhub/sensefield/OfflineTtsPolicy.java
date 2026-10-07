package com.openkhub.sensefield;

import java.util.Locale;

/** Network-only or uninstalled voices must never be used for timely game alerts. */
final class OfflineTtsPolicy {
    private OfflineTtsPolicy() {}

    static boolean allows(Locale locale, boolean networkRequired, boolean dataMissing) {
        return locale != null && "zh".equals(locale.getLanguage())
                && !networkRequired && !dataMissing;
    }

    static int localeOrder(Locale locale) {
        if (locale == null) return 3;
        return "CN".equals(locale.getCountry()) ? 0 : locale.getCountry().isEmpty() ? 1 : 2;
    }
}
