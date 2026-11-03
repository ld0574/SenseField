package com.openkhub.sensefield;

import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

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

    /** One synthesis error describes an utterance, not proof that its engine is broken. */
    static final class BoardRecovery {
        enum Action { NONE, RECONNECT, NEXT_ENGINE }
        private final Set<String> reconnectedEngines = new HashSet<>();

        synchronized Action onFailure(String engineName, String eventKey, String reason) {
            if (!recoverBoardFailure(eventKey, reason)) return Action.NONE;
            // Android TextToSpeech.ERROR_SYNTHESIS is -3. Rebind the same actual engine
            // once per player; a failed rebind or another failure still reaches fallback.
            if ("ENGINE_ERROR:-3".equals(reason) && engineName != null && !engineName.isEmpty()
                    && reconnectedEngines.add(engineName)) return Action.RECONNECT;
            return Action.NEXT_ENGINE;
        }
    }
}
