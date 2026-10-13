package com.openkhub.sensefield;

/** Bind one confirmed native batch to output without changing recognition or episode rules. */
final class NearZoneCuePolicy {
    private NearZoneCuePolicy() {}

    static CueRequest request(String sessionId, String cueId, long resetGeneration,
                              NativeFrameResult.Relation relation,
                              NearZoneCombatPolicy.Decision combat, long observedAtMs,
                              int channels, boolean preferTwoWord) {
        boolean dense = combat != null && combat.dense;
        return new CueRequest(sessionId, cueId,
                CueEventKeys.nearZone(resetGeneration, relation.episodeId),
                "NEAR_ZONE", CueRequest.Category.NEAR_ZONE,
                combat == null ? NearZoneRouting.NEAR_PRIORITY : combat.priority,
                observedAtMs, observedAtMs + NearZoneRouting.NEAR_TTL_MS, channels,
                NearZoneRouting.TONE_NEAR, 0, NearZoneRouting.hapticDirection(relation.sector),
                NearZoneRouting.speech(relation.sector, preferTwoWord),
                relation.pan, relation.nearestDistance, dense ? 2f : 1f,
                combat == null ? -1 : combat.freshEnemyCount, () -> true);
    }

    /** Fixed near-zone request parameters; not evidence of physical sound or perception. */
    static String presentationAudit(CueRequest request, int sector, boolean dense,
                                    boolean preferTwoWord) {
        int speechChars = request.speech.codePointCount(0, request.speech.length());
        return "NearZonePresentation cueId=" + request.cueId
                + " policy=saved_speech_style_v2 dense=" + dense
                + " nearTwoWordEnabled=" + preferTwoWord
                + " speechStyle=" + (speechChars == 2 ? "two_word" : "full")
                + " speechChars=" + speechChars
                + " sector=" + sector + " hapticCode=" + request.hapticCode
                + " panMilli=" + (request.hasPan() ? Math.round(request.pan * 1000f) : "unknown")
                + " distanceMilli=" + (Float.isFinite(request.distance)
                ? Math.round(request.distance * 1000f) : "unknown")
                + " priority=" + request.priority + " freshEnemies=" + request.freshEnemyCount;
    }
}
