package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Wording guard for the presentation-layer help copy (brief 28).
 *
 * <p>Every string asserted below is read out of {@link SettingHelpContent} through its own
 * accessor; this class never retypes a whole help paragraph as an expected value. The only
 * literals written here are short needles used with {@code contains} / {@code indexOf}: the three
 * disclaimer fragments that must never disappear, the boundary that separates the original
 * paragraph from the appended off-state sentence, and the three claim words the copy is not
 * allowed to escalate into. If somebody deletes a disclaimer, softens "默认关闭", drops "仍需玩家
 * 验证", or upgrades the off-state sentence into a 3D / spatial-audio / surround claim, this class
 * goes red.
 */
public final class PresentationHelpWordingGuardTest {

    private static final String GROUP = "presentation_group";
    private static final String SPATIAL = "presentation_spatial";
    private static final String HAPTIC = "presentation_haptic";
    private static final String TWO_WORD = "presentation_two_word";

    /** The last original sentence; everything after it is the appended off-state sentence. */
    private static final String ORIGINAL_TAIL = "单声道播放或外放可能听不出左右差异。";

    /** Claims the measured implementation never supports, so the copy must not make them. */
    private static final String[] FORBIDDEN_CLAIMS = {"3D", "空间音频", "环绕"};

    /** Denials of the ordinary tone path, which does keep a left/right gain while switched off. */
    private static final String[] FORBIDDEN_TONE_PATH_DENIALS = {
            "关着时没有任何方向提示", "不会有任何提示", "只有开启后才有方向", "不再有提示音"
    };

    private static String spatial() {
        return SettingHelpContent.text(SPATIAL);
    }

    /** The sentence appended after the original disclaimer paragraph, read back out of the copy. */
    private static String appendedOffStateSentence(String text) {
        int cut = text.indexOf(ORIGINAL_TAIL);
        assertTrue("original disclaimer tail disappeared from the spatial help copy", cut >= 0);
        return text.substring(cut + ORIGINAL_TAIL.length());
    }

    /** ① The four presentation readings are all reachable through the accessor and non-empty. */
    @Test
    public void allFourPresentationHelpTextsAreRetrievableAndNonEmpty() {
        for (String key : Arrays.asList(GROUP, SPATIAL, HAPTIC, TWO_WORD)) {
            String text = SettingHelpContent.text(key);
            assertNotNull(key + " help text is null", text);
            assertFalse(key + " help text is empty", text.trim().isEmpty());
            assertFalse(key + " help text falls through to the generic default copy",
                    text.equals(SettingHelpContent.text("presentation_unexisting_key")));
        }
    }

    /** ① Second half: titles, the group summary and the group item keys are also real readings. */
    @Test
    public void titlesAndGroupSummaryAndItemKeysAreRetrievableAndNonEmpty() {
        for (String key : Arrays.asList(GROUP, SPATIAL, HAPTIC, TWO_WORD)) {
            String title = SettingHelpContent.title(key);
            assertNotNull(key + " title is null", title);
            assertFalse(key + " title is empty", title.trim().isEmpty());
            assertFalse(key + " title falls back to the generic 设置 label",
                    title.equals(SettingHelpContent.title("presentation_unexisting_key")));
        }
        String summary = SettingHelpContent.summary(GROUP);
        assertFalse(GROUP + " summary is empty", summary.trim().isEmpty());
        assertEquals("the presentation group explains exactly three switches", 3,
                SettingHelpContent.itemKeys(GROUP).size());
    }

    /** ② The full-HRTF disclaimer stays word for word in the spatial help copy. */
    @Test
    public void spatialCopyStillCarriesTheFullHrtfDisclaimer() {
        assertTrue("the spatial help copy lost its not-a-full-HRTF disclaimer",
                spatial().contains("不等同于完整 HRTF"));
    }

    /** ② The binaural-headphone requirement stays word for word in the spatial help copy. */
    @Test
    public void spatialCopyStillCarriesTheBinauralHeadphoneRequirement() {
        String text = spatial();
        assertTrue("the spatial help copy lost 双耳耳机", text.contains("双耳耳机"));
        assertTrue("the spatial help copy lost the stereo-headphone requirement sentence",
                text.contains("需使用支持立体声的双耳耳机"));
    }

    /** ② The mono / loudspeaker caveat stays word for word in the spatial help copy. */
    @Test
    public void spatialCopyStillCarriesTheMonoOrLoudspeakerCaveat() {
        assertTrue("the spatial help copy lost the mono-or-speaker caveat",
                spatial().contains("单声道播放或外放可能听不出左右差异"));
    }

    /** The three disclaimers keep their original order: requirement, HRTF, mono/loudspeaker. */
    @Test
    public void theThreeDisclaimersAppearInTheirOriginalOrder() {
        String text = spatial();
        int headphones = text.indexOf("需使用支持立体声的双耳耳机");
        int hrtf = text.indexOf("不等同于完整 HRTF");
        int mono = text.indexOf(ORIGINAL_TAIL);
        assertTrue("all three disclaimer fragments are present",
                headphones >= 0 && hrtf >= 0 && mono >= 0);
        assertTrue("the disclaimer order changed", headphones < hrtf && hrtf < mono);
    }

    /** ③ The group copy still says this layer is off by default. */
    @Test
    public void groupCopyStillSaysTheLayerDefaultsToOff() {
        assertTrue("the presentation group copy lost 默认关闭",
                SettingHelpContent.text(GROUP).contains("默认关闭"));
    }

    /** ④ The haptic copy still says the player has to verify it. */
    @Test
    public void hapticCopyStillSaysThePlayerMustVerify() {
        assertTrue("the haptic copy lost its 仍需玩家验证 hedge",
                SettingHelpContent.text(HAPTIC).contains("仍需玩家验证"));
    }

    /** The two-word copy keeps pointing at the full-sentence alternative it switches from. */
    @Test
    public void twoWordCopyStillContrastsWithTheFullReminderSentence() {
        String text = SettingHelpContent.text(TWO_WORD);
        assertTrue("the two-word copy lost its left-up example", text.contains("左上"));
        assertTrue("the two-word copy lost the full-sentence counterpart",
                text.contains("左上有敌人"));
    }

    /** The new sentence is appended at the end; nothing in front of it moved. */
    @Test
    public void offStateSentenceIsAppendedAfterTheOriginalParagraph() {
        String text = spatial();
        String appended = appendedOffStateSentence(text);
        assertFalse("no off-state sentence was appended to the spatial help copy",
                appended.trim().isEmpty());
        assertTrue("the appended part is not at the very end of the copy", text.endsWith(appended));
        assertTrue("the appended sentence does not end the copy with a full stop",
                appended.endsWith("。"));
    }

    /** ⑤ Neither the appended sentence nor the whole paragraph escalates into a bigger claim. */
    @Test
    public void offStateSentenceNeverEscalatesIntoAThreeDimensionalClaim() {
        String appended = appendedOffStateSentence(spatial());
        StringBuilder offenders = new StringBuilder();
        for (String claim : FORBIDDEN_CLAIMS) {
            if (appended.contains(claim)) offenders.append("appended=").append(claim).append(';');
            if (spatial().contains(claim)) offenders.append("whole=").append(claim).append(';');
        }
        assertEquals("the spatial help copy升级成了实测不支持的宣称", "", offenders.toString());
    }

    /** ⑤ Positive half: the appended sentence names what players actually hear while off. */
    @Test
    public void offStateSentenceNamesTheLeftRightVolumeDifferenceWhileSwitchedOff() {
        String appended = appendedOffStateSentence(spatial());
        assertTrue("the appended sentence does not say direction survives as a left/right "
                + "volume difference", appended.contains("左右音量"));
        assertTrue("the appended sentence does not say the binaural-cue route is what stays off",
                appended.contains("双耳线索"));
    }

    /** ⑥ Red line two: the ordinary tone path is never described as absent while switched off. */
    @Test
    public void offStateSentenceNeverDeniesTheOrdinaryTonePath() {
        String appended = appendedOffStateSentence(spatial());
        assertTrue("the appended sentence does not credit the ordinary cue tone",
                appended.contains("普通提示音"));
        StringBuilder offenders = new StringBuilder();
        for (String denial : FORBIDDEN_TONE_PATH_DENIALS) {
            if (appended.contains(denial)) offenders.append(denial).append(';');
        }
        assertEquals("the appended sentence denied the ordinary tone path", "", offenders.toString());
    }

    /** ⑥ The copy is read from {@link SettingHelpContent}; the off-state part is removable. */
    @Test
    public void readingsComeFromTheContentClassAndNotFromACopyInsideThisTest() {
        String text = spatial();
        String appended = appendedOffStateSentence(text);
        String originalParagraph = text.substring(0, text.length() - appended.length());
        assertTrue("the paragraph in front of the appended sentence no longer opens with 开启后",
                originalParagraph.startsWith("开启后"));
        assertTrue("the paragraph in front of the appended sentence no longer ends with the "
                + "original caveat", originalParagraph.endsWith(ORIGINAL_TAIL));
        // The same accessor drives the on-screen copy for every presentation key, so a value
        // read twice has to be byte-identical; nothing here was typed by this test.
        assertEquals(text, spatial());
    }

    /** The four keys stay the same literals, and the group still lists those three of them. */
    @Test
    public void presentationKeysAndTheirGroupListingAreUnchanged() {
        List<String> listed = SettingHelpContent.itemKeys(GROUP);
        assertTrue(listed.contains(SPATIAL));
        assertTrue(listed.contains(HAPTIC));
        assertTrue(listed.contains(TWO_WORD));
        for (String key : Arrays.asList(GROUP, SPATIAL, HAPTIC, TWO_WORD)) {
            assertNotNull(key, SettingHelpContent.text(key));
        }
    }

    /** The switch label itself is off-limits: it still carries its two measured qualifiers. */
    @Test
    public void spatialSwitchLabelStillCarriesItsOwnTwoQualifiers() {
        String label = SettingHelpContent.title(SPATIAL);
        assertTrue("the switch label lost 左右双耳线索", label.contains("左右双耳线索"));
        assertTrue("the switch label lost the headphone requirement", label.contains("双耳耳机"));
    }
}
