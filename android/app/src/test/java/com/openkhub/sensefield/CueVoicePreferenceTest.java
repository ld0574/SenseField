package com.openkhub.sensefield;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

/**
 * Alarm for {@link CuePlayer#applyVoicePreference}: the player-chosen voice may only ever
 * swap which offline Chinese voice wins, never widen the offline Chinese judgement itself.
 * Every {@code fake_*} name below is an obviously fabricated fixture identity.
 */
public final class CueVoicePreferenceTest {

    private static Voice fakeVoice(String name, Locale locale, boolean networkRequired,
                                   Set<String> features) {
        return new Voice(name, locale, 0, 0, networkRequired, features) {
            @Override public String getName() { return name; }
            @Override public Locale getLocale() { return locale; }
            @Override public boolean isNetworkConnectionRequired() { return networkRequired; }
            @Override public Set<String> getFeatures() { return features; }
        };
    }

    private static Voice offline(String name, Locale locale) {
        return fakeVoice(name, locale, false, Collections.emptySet());
    }

    private static Set<Voice> voices(Voice... values) {
        Set<Voice> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return result;
    }

    private static Set<String> notInstalled() {
        return Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    @Test public void missingPreferenceReturnsTheAutomaticVoiceUnchanged() {
        Voice automatic = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Voice alternative = offline("fake_yunsi", Locale.TRADITIONAL_CHINESE);
        Set<Voice> available = voices(automatic, alternative);
        Voice auto = CuePlayer.selectedOfflineVoice(automatic, available);
        assertSame(auto, CuePlayer.applyVoicePreference(null, automatic, available));
        assertSame(auto, CuePlayer.applyVoicePreference("", automatic, available));
    }

    @Test public void unknownNameFallsBackToTheAutomaticVoice() {
        Voice automatic = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Set<Voice> available = voices(automatic, offline("fake_yunsi", Locale.TRADITIONAL_CHINESE));
        Voice auto = CuePlayer.selectedOfflineVoice(automatic, available);
        assertSame(auto, CuePlayer.applyVoicePreference("fake_ghost_voice", automatic, available));
    }

    @Test public void preferredAvailableVoiceOverridesTheDefaultOrder() {
        Voice automatic = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Voice preferred = offline("fake_yunsi", Locale.TRADITIONAL_CHINESE);
        Set<Voice> available = voices(automatic, preferred);
        assertSame(preferred, CuePlayer.applyVoicePreference("fake_yunsi", null, available));
        assertSame(automatic, CuePlayer.selectedOfflineVoice(null, available));
        assertNotSame(CuePlayer.selectedOfflineVoice(null, available),
                CuePlayer.applyVoicePreference("fake_yunsi", null, available));
    }

    @Test public void preferredButUnavailableVoiceFallsBackWithoutThrowing() {
        Voice automatic = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Set<Voice> available = voices(automatic);
        assertSame(automatic, CuePlayer.applyVoicePreference("fake_removed_voice", automatic, available));
    }

    @Test public void nonChinesePreferredVoiceNeverBeatsTheAutomaticPath() {
        Voice automatic = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Voice english = offline("fake_samantha", Locale.ENGLISH);
        Set<Voice> available = voices(automatic, english);
        assertSame(automatic, CuePlayer.applyVoicePreference("fake_samantha", automatic, available));
    }

    @Test public void notInstalledChinesePreferredVoiceFallsBack() {
        Voice automatic = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Voice notInstalled = fakeVoice("fake_pending", Locale.SIMPLIFIED_CHINESE, false, notInstalled());
        Set<Voice> available = voices(automatic, notInstalled);
        assertSame(automatic, CuePlayer.selectedOfflineVoice(null, available));
        assertSame(automatic, CuePlayer.applyVoicePreference("fake_pending", null, available));
    }

    @Test public void emptyAndNullAvailableSetsKeepTheAutomaticResult() {
        Voice automatic = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        assertSame(automatic, CuePlayer.applyVoicePreference("fake_x", automatic, Collections.emptySet()));
        assertSame(automatic, CuePlayer.applyVoicePreference("fake_x", automatic, null));
        Voice englishOnly = offline("fake_samantha", Locale.ENGLISH);
        assertNull(CuePlayer.applyVoicePreference("fake_x", englishOnly, null));
        assertNull(CuePlayer.applyVoicePreference(null, englishOnly, null));
    }

    @Test public void networkOnlyChineseVoiceStaysUnreachableByPreference() {
        Voice networkChinese = fakeVoice("fake_cloud", Locale.SIMPLIFIED_CHINESE, true, Collections.emptySet());
        Set<Voice> available = voices(networkChinese);
        // The preference string must not loosen offlineChinese: a network voice that matches
        // by name is still rejected, and the automatic path with no offline candidate returns null.
        assertNull(CuePlayer.applyVoicePreference("fake_cloud", null, available));
        assertNull(CuePlayer.selectedOfflineVoice(null, available));
        assertSame(networkChinese, available.iterator().next());
    }
}
