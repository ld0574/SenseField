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

    static boolean recoverBoardFailure(String eventKey, String reason) {
        return Match3SpeechText.isBoardCue(eventKey) && reason != null
                && (reason.equals("START_TIMEOUT") || reason.equals("PLAYBACK_TIMEOUT")
                || reason.equals("SUBMIT_REJECTED") || reason.startsWith("ENGINE_ERROR"));
    }
}
