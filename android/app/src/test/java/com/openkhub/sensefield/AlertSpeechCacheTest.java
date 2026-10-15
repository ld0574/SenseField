package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.HashSet;
import org.junit.Test;

public final class AlertSpeechCacheTest {
    @Test public void liveSpeechNeverWaitsBehindSilentCacheSynthesis() {
        CueRequest near = new CueRequest("test", "near", "near", "NEAR_ZONE",
                CueRequest.Category.NEAR_ZONE, 80, 0, 1200, CueRequest.CHANNEL_SPEECH,
                7, 0, 0, "左上");
        assertEquals(android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                CuePlayer.alertQueueMode(near, false, false));
        CueRequest system = new CueRequest("test", "system", "system", "NARRATION",
                CueRequest.Category.SYSTEM, 20, 0, 4000, CueRequest.CHANNEL_SPEECH,
                0, 0, 0, "说明");
        assertEquals(android.speech.tts.TextToSpeech.QUEUE_ADD,
                CuePlayer.alertQueueMode(system, false, false));
        assertEquals(android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                CuePlayer.alertQueueMode(system, false, true));
    }
    @Test public void fixedVocabularyIncludesBothDirectionsWithoutDuplicates() {
        assertEquals(18, AlertSpeechCache.phrases(false).size());
        assertEquals(18, new HashSet<>(AlertSpeechCache.phrases(false)).size());
        assertEquals(NearZoneRouting.twoWordSpeech(0), AlertSpeechCache.phrases(true).get(0));
        assertEquals(new HashSet<>(AlertSpeechCache.phrases(false)),
                new HashSet<>(AlertSpeechCache.phrases(true)));
    }

    @Test public void zeroPaddingIsTrimmedWithTwentyMillisecondsPreserved() {
        short[] input = new short[2400];
        java.util.Arrays.fill(input, 800, 1600, (short) 1000);
        short[] prepared = AlertSpeechCache.prepare(input, 16000);
        assertEquals(1440, prepared.length);
        assertEquals(0, prepared[319]);
        assertEquals(1000, prepared[320]);
        assertEquals(1000, prepared[1119]);
        assertEquals(0, prepared[1120]);
        short[] quiet = {(short) 1, (short) 1};
        assertArrayEquals("Quiet real signal is never threshold-trimmed", quiet,
                AlertSpeechCache.prepare(quiet, 16000));
    }

    @Test public void silentOversizedAndArbitrarySpeechAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AlertSpeechCache.prepare(new short[100], 16000));
        assertThrows(IllegalArgumentException.class,
                () -> AlertSpeechCache.prepare(new short[64001], 16000));
        assertThrows(IllegalArgumentException.class,
                () -> AlertSpeechCache.put("test-bounds", "任意问题", new short[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> AlertSpeechCache.put("test-bounds", "左上", new short[AlertSpeechCache.MAX_SAMPLES + 1]));
    }

    @Test public void voiceProfilesHaveBoundedStorageAndOwnTheirInputs() {
        short[] input = {10, 20, 30};
        AlertSpeechCache.put("voice-A", "左上", input);
        input[0] = 99;
        AlertSpeechCache.put("voice-B", "左上", new short[]{100});
        assertEquals(10, AlertSpeechCache.get("voice-A", "左上")[0]);
        assertEquals(100, AlertSpeechCache.get("voice-B", "左上")[0]);
        assertEquals(1, AlertSpeechCache.readyCount("voice-B"));
        AlertSpeechCache.put("voice-C", "左上", new short[]{200});
        assertNull(AlertSpeechCache.get("voice-A", "左上"));
        assertNotNull(AlertSpeechCache.get("voice-B", "左上"));
        assertNotNull(AlertSpeechCache.get("voice-C", "左上"));
    }

    @Test public void oneBufferCentersSpeechAndPreservesOnlyTheTonesStereoDifference() {
        short[] voice = new short[]{1000, 1000, 1000};
        short[] tone = new short[]{10000, 2000, 10000, 2000};
        short[] mixed = AlertSpeechCache.mix(voice, tone);
        assertEquals(18, mixed.length); // Three 16 kHz samples become nine stereo frames.
        assertEquals(2600, mixed[0]);
        assertEquals(1320, mixed[1]);
        for (int frame = 2; frame < 9; frame++) {
            assertEquals(1000, mixed[frame * 2]);
            assertEquals(mixed[frame * 2], mixed[frame * 2 + 1]);
        }
        assertEquals(Short.MAX_VALUE, AlertSpeechCache.mix(new short[]{32000},
                new short[]{13000, 13000})[0]);
        assertArrayEquals(new short[]{1000, 1000, 1000, 1000, 1000, 1000},
                AlertSpeechCache.mix(new short[]{1000}, null));
    }
}
