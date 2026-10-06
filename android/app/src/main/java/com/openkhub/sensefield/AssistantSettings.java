package com.openkhub.sensefield;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;

/** Explicit opt-in configuration, separate from local radar preferences. */
final class AssistantSettings {
    // A separate opt-in also disables old test preferences on the first upgrade.
    static final String ENABLED = "assistant_enabled";
    static final String VOICE = "assistant_voice";
    static final String VISION = "assistant_vision";
    static final String PROACTIVE = "assistant_proactive";
    static final String AUDIO_CONSENT = "assistant_audio_consent";
    static final String IMAGE_CONSENT = "assistant_image_consent";
    static final String ENDPOINT = "assistant_endpoint";
    static final String TOKEN = "assistant_device_token";
    static final String CUSTOM_SERVICE = "assistant_custom_service";
    private static final String INSTALLATION = "assistant_installation_id";
    final boolean optedIn, voice, vision, proactive, customService;
    final String endpoint, token, installationId;
    AssistantSettings(SharedPreferences preferences) {
        optedIn = preferences.getBoolean(ENABLED, false);
        voice = voiceEnabled(preferences);
        vision = optedIn && preferences.getBoolean(VISION, false) && preferences.getBoolean(IMAGE_CONSENT, false);
        proactive = vision && preferences.getBoolean(PROACTIVE, false);
        customService = preferences.getBoolean(CUSTOM_SERVICE, false);
        String configuredEndpoint = customService ? preferences.getString(ENDPOINT, "")
                : BuildConfig.ASSISTANT_DEFAULT_ENDPOINT;
        endpoint = (configuredEndpoint == null ? "" : configuredEndpoint).trim().replaceAll("/+$", "");
        token = customService ? preferences.getString(TOKEN, "").trim() : "";
        synchronized (AssistantSettings.class) {
            String id = preferences.getString(INSTALLATION, "");
            if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
                id = java.util.UUID.randomUUID().toString();
                preferences.edit().putString(INSTALLATION, id).apply();
            }
            installationId = id;
        }
    }
    static AssistantSettings from(Context context) { return new AssistantSettings(GameProfile.settings(context)); }
    static boolean voiceEnabled(SharedPreferences preferences) {
        return preferences.getBoolean(ENABLED, false)
                && preferences.getBoolean(VOICE, false) && preferences.getBoolean(AUDIO_CONSENT, false);
    }
    boolean configured() { return validEndpoint(endpoint) && (!customService || (token.length() >= 24 && token.length() <= 256)); }
    /** Voice capture and ASR are local; only screen understanding needs the gateway. */
    boolean enabled() { return voice || (vision && configured()); }
    static boolean validEndpoint(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    && uri.getPort() <= 65535;
        } catch (Exception ignored) { return false; }
    }
}
