package com.openkhub.sensefield;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;

/** Explicit opt-in configuration, separate from local radar preferences. */
final class AssistantSettings {
    static final String VOICE = "assistant_voice";
    static final String VISION = "assistant_vision";
    static final String PROACTIVE = "assistant_proactive";
    static final String AUDIO_CONSENT = "assistant_audio_consent";
    static final String IMAGE_CONSENT = "assistant_image_consent";
    static final String ENDPOINT = "assistant_endpoint";
    static final String TOKEN = "assistant_device_token";
    final boolean voice, vision, proactive;
    final String endpoint, token;
    AssistantSettings(SharedPreferences preferences) {
        voice = preferences.getBoolean(VOICE, false) && preferences.getBoolean(AUDIO_CONSENT, false);
        vision = preferences.getBoolean(VISION, false) && preferences.getBoolean(IMAGE_CONSENT, false);
        proactive = vision && preferences.getBoolean(PROACTIVE, false);
        endpoint = preferences.getString(ENDPOINT, "").trim().replaceAll("/+$", "");
        token = preferences.getString(TOKEN, "").trim();
    }
    static AssistantSettings from(Context context) { return new AssistantSettings(GameProfile.settings(context)); }
    boolean configured() { return validEndpoint(endpoint) && token.length() >= 24 && token.length() <= 256; }
    boolean enabled() { return (voice || vision) && configured(); }
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
