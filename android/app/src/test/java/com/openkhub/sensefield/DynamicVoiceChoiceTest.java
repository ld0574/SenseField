package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

/**
 * Alarm for brief 16: the player must be able to name a voice and that name must win.
 * {@link CuePlayer#resolveOfflineVoice} is the caller-side decision that used to hand the
 * engine voice set as {@code null} whenever the engine default already passed the offline
 * Chinese guard, which silently swallowed the stored {@code cue_voice_name}. Every
 * {@code fake_*} name below is an obviously fabricated fixture identity.
 *
 * <p>Machine boundary: this is a JVM unit test only. The device has no adb and no GPU here,
 * so the real number of enumerated voices, screen-reader wording and how human the sampled
 * voice sounds stay for the player round; nothing here claims on-device verification.
 */
public final class DynamicVoiceChoiceTest {

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

    /** Offline Chinese guard copied verbatim, so the oracle never depends on production visibility. */
    private static boolean engineVoiceQualified(Voice voice) {
        return voice != null && OfflineTtsPolicy.allows(voice.getLocale(), voice.isNetworkConnectionRequired(),
                voice.getFeatures() != null
                        && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
    }

    /** The caller-side short circuit as it behaved before this brief, kept as the oracle. */
    private static Voice legacyCallerExpression(String preference, Voice current, Set<Voice> available) {
        boolean preserveEngineVoice = engineVoiceQualified(current);
        return CuePlayer.applyVoicePreference(preference, current,
                preserveEngineVoice ? null : available);
    }

    @Test public void explicitChoiceWinsOverAQualifiedEngineVoice() {
        Voice engineCurrent = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Voice chosen = offline("fake_yunsi", Locale.TRADITIONAL_CHINESE);
        Set<Voice> enumerated = voices(engineCurrent, chosen);
        assertSame(chosen, CuePlayer.resolveOfflineVoice("fake_yunsi", engineCurrent, enumerated));
        assertNotSame(engineCurrent, CuePlayer.resolveOfflineVoice("fake_yunsi", engineCurrent, enumerated));
        // The dead path this brief removes: the same inputs resolved through the old caller
        // expression still handed the engine voice back, swallowing the player's choice.
        assertSame(engineCurrent, legacyCallerExpression("fake_yunsi", engineCurrent, enumerated));
    }

    @Test public void explicitChoiceBeatsTheAutomaticOrderAndAMissingEngineVoice() {
        Voice automatic = offline("fake_anxi", Locale.SIMPLIFIED_CHINESE);
        Voice chosen = offline("fake_zhanting", Locale.TRADITIONAL_CHINESE);
        Set<Voice> enumerated = voices(automatic, chosen);
        assertSame(automatic, CuePlayer.selectedOfflineVoice(null, enumerated));
        assertSame(chosen, CuePlayer.resolveOfflineVoice("fake_zhanting", null, enumerated));
        assertSame(chosen, CuePlayer.resolveOfflineVoice("fake_zhanting", automatic, enumerated));
    }

    @Test public void unknownPreferenceFallsBackToTheAutomaticVoice() {
        Voice engineCurrent = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Voice automatic = offline("fake_anxi", Locale.SIMPLIFIED_CHINESE);
        Set<Voice> enumerated = voices(engineCurrent, automatic);
        assertSame(CuePlayer.selectedOfflineVoice(engineCurrent, enumerated),
                CuePlayer.resolveOfflineVoice("fake_ghost", engineCurrent, enumerated));
        assertSame(engineCurrent, CuePlayer.resolveOfflineVoice("fake_ghost", engineCurrent, enumerated));
        Voice english = offline("fake_samantha", Locale.ENGLISH);
        assertSame(automatic, CuePlayer.resolveOfflineVoice("fake_ghost", english, enumerated));
    }

    @Test public void blankPreferenceKeepsThePreChangeResultVerbatim() {
        Voice qualified = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Voice english = offline("fake_samantha", Locale.ENGLISH);
        Voice pending = fakeVoice("fake_pending", Locale.SIMPLIFIED_CHINESE, false, notInstalled());
        Voice clouded = fakeVoice("fake_cloud", Locale.SIMPLIFIED_CHINESE, true, Collections.emptySet());
        Set<Voice> enumerated = voices(qualified, english, pending, clouded);
        for (String preference : new String[] {"", null}) {
            assertSame(legacyCallerExpression(preference, qualified, enumerated),
                    CuePlayer.resolveOfflineVoice(preference, qualified, enumerated));
            assertSame(legacyCallerExpression(preference, english, enumerated),
                    CuePlayer.resolveOfflineVoice(preference, english, enumerated));
            assertSame(legacyCallerExpression(preference, null, enumerated),
                    CuePlayer.resolveOfflineVoice(preference, null, enumerated));
            assertSame(legacyCallerExpression(preference, pending, enumerated),
                    CuePlayer.resolveOfflineVoice(preference, pending, enumerated));
            assertSame(legacyCallerExpression(preference, clouded, enumerated),
                    CuePlayer.resolveOfflineVoice(preference, clouded, enumerated));
        }
    }

    @Test public void menuListsOfflineChineseVoicesOnly() {
        Voice simplified = offline("fake_anxi", Locale.SIMPLIFIED_CHINESE);
        Voice traditional = offline("fake_zhanting", Locale.TRADITIONAL_CHINESE);
        Voice networkChinese = fakeVoice("fake_cloud", Locale.SIMPLIFIED_CHINESE, true, Collections.emptySet());
        Voice uninstalledChinese = fakeVoice("fake_pending", Locale.SIMPLIFIED_CHINESE, false, notInstalled());
        Voice english = offline("fake_samantha", Locale.ENGLISH);
        List<String> names = CuePlayer.offlineChineseVoiceNames(
                voices(simplified, traditional, networkChinese, uninstalledChinese, english));
        assertEquals(2, names.size());
        assertTrue(names.contains("fake_anxi"));
        assertTrue(names.contains("fake_zhanting"));
        assertFalse(names.contains("fake_cloud"));
        assertFalse(names.contains("fake_pending"));
        assertFalse(names.contains("fake_samantha"));
    }

    @Test public void menuOrderIsStableAndDuplicateFree() {
        Voice first = offline("fake_anxi", Locale.SIMPLIFIED_CHINESE);
        Voice second = offline("fake_bei", Locale.SIMPLIFIED_CHINESE);
        Voice third = offline("fake_zhanting", Locale.TRADITIONAL_CHINESE);
        List<String> forward = CuePlayer.offlineChineseVoiceNames(voices(first, second, third));
        List<String> reversed = CuePlayer.offlineChineseVoiceNames(voices(third, second, first));
        assertEquals(forward, reversed);
        assertEquals(java.util.Arrays.asList("fake_anxi", "fake_bei", "fake_zhanting"), forward);
        List<String> doubled = CuePlayer.offlineChineseVoiceNames(
                voices(first, offline("fake_anxi", Locale.SIMPLIFIED_CHINESE), first));
        assertEquals(new ArrayList<>(java.util.Arrays.asList("fake_anxi")), doubled);
    }

    @Test public void emptyOrMissingVoiceSetYieldsAnEmptyMenu() {
        assertTrue(CuePlayer.offlineChineseVoiceNames(null).isEmpty());
        assertTrue(CuePlayer.offlineChineseVoiceNames(Collections.<Voice>emptySet()).isEmpty());
    }

    @Test public void statusLineNamesTheChosenVoiceOrSaysAutomatic() {
        assertTrue(CuePlayer.voicePreferenceStatusText("fake_yunsi").contains("fake_yunsi"));
        assertTrue(CuePlayer.voicePreferenceStatusText("").contains("自动"));
        assertTrue(CuePlayer.voicePreferenceStatusText(null).contains("自动"));
        assertFalse(CuePlayer.voicePreferenceStatusText(null).contains("fake_yunsi"));
    }

    /**
     * Criterion four from the other side: a hand-edited preference that names a voice the
     * menu refuses to offer - cloud-only, not yet downloaded, or simply not Chinese - must
     * not be honoured either, or the picker filter would be decoration.
     */
    @Test public void namedVoiceOutsideTheOfflineChineseGuardIsNeverHonoured() {
        Voice engineCurrent = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Voice clouded = fakeVoice("fake_cloud", Locale.SIMPLIFIED_CHINESE, true, Collections.emptySet());
        Voice pending = fakeVoice("fake_pending", Locale.SIMPLIFIED_CHINESE, false, notInstalled());
        Voice english = offline("fake_samantha", Locale.ENGLISH);
        Set<Voice> enumerated = voices(engineCurrent, clouded, pending, english);
        Voice automatic = CuePlayer.selectedOfflineVoice(engineCurrent, enumerated);
        for (String preference : new String[] {"fake_cloud", "fake_pending", "fake_samantha"}) {
            assertSame(automatic, CuePlayer.resolveOfflineVoice(preference, engineCurrent, enumerated));
            assertFalse(CuePlayer.offlineChineseVoiceNames(enumerated).contains(preference));
        }
    }

    /**
     * The outward contract of the read side, quoted in the brief: blank and unknown values
     * fall back to the automatic pick and never throw. A whitespace-only string is "blank"
     * to a player and must land on the same automatic voice.
     */
    @Test public void blankOrHostilePreferenceNeverThrowsAndKeepsTheAutomaticVoice() {
        Voice qualified = offline("fake_xiaoyu", Locale.SIMPLIFIED_CHINESE);
        Voice automatic = offline("fake_anxi", Locale.SIMPLIFIED_CHINESE);
        Set<Voice> enumerated = voices(qualified, automatic);
        Voice currentPick = CuePlayer.selectedOfflineVoice(qualified, enumerated);
        for (String preference : new String[] {"", " ", "   ", "\t", "\n", "fake_"}) {
            assertSame(currentPick, CuePlayer.resolveOfflineVoice(preference, qualified, enumerated));
            assertSame(currentPick, CuePlayer.applyVoicePreference(preference, qualified, enumerated));
        }
        assertSame(qualified, CuePlayer.resolveOfflineVoice("fake_ghost", qualified, null));
        assertNull(CuePlayer.resolveOfflineVoice("fake_ghost", null, null));
        assertNull(CuePlayer.resolveOfflineVoice("", null, null));
        // Null entries inside the enumerated set are a real remote-service shape, not a typo.
        assertSame(qualified, CuePlayer.resolveOfflineVoice("fake_ghost", qualified, voices(null, qualified)));
        assertSame(qualified, CuePlayer.resolveOfflineVoice(null, qualified, voices(null, qualified)));
        assertSame(automatic, CuePlayer.resolveOfflineVoice("fake_ghost", null, voices(null, automatic)));
        assertEquals(Collections.singletonList("fake_anxi"), CuePlayer.offlineChineseVoiceNames(
                voices(null, automatic, offline("fake_anxi", Locale.SIMPLIFIED_CHINESE))));
    }

    /**
     * The menu builder sorts by name, so an unnamed voice has to be filtered out before the
     * comparator reads it; the picker runs on the UI thread and must not be able to throw.
     */
    @Test public void unnamedVoicesAreSkippedInsteadOfBreakingTheMenu() {
        Voice named = offline("fake_anxi", Locale.SIMPLIFIED_CHINESE);
        Voice unnamed = fakeVoice(null, Locale.SIMPLIFIED_CHINESE, false, Collections.emptySet());
        Voice blankName = fakeVoice("", Locale.SIMPLIFIED_CHINESE, false, Collections.emptySet());
        assertEquals(Collections.singletonList("fake_anxi"),
                CuePlayer.offlineChineseVoiceNames(voices(named, unnamed, blankName)));
        List<String> both = new ArrayList<>(CuePlayer.offlineChineseVoiceNames(voices(unnamed, named)));
        assertEquals(Collections.singletonList("fake_anxi"), both);
    }
}
