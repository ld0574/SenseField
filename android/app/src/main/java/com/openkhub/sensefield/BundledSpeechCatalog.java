package com.openkhub.sensefield;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Text is the source of truth: changing guide wording invalidates the corresponding recording. */
final class BundledSpeechCatalog {
    static final String PREF_VOICE = "cue_bundled_voice";
    static final String DEFAULT_VOICE = "game_male";
    static final String[] VOICES = {"game_female", "game_male"};
    static final String[] VOICE_LABELS = {"游戏向导 · 女声", "游戏解说 · 男声"};
    static final int MIN_RATE = 80, MAX_RATE = 240, RATE_STEP = 10;

    private static final List<String> FIXED = fixedPhrases();
    private static final List<String> GUIDE = guideSentences();

    private BundledSpeechCatalog() {}

    static String voice(String value) {
        return "game_female".equals(value) || "xiaoxiao".equals(value) ? "game_female" : DEFAULT_VOICE;
    }

    static int rate(int value) {
        int bounded = Math.max(MIN_RATE, Math.min(MAX_RATE, value));
        return Math.round(bounded / (float) RATE_STEP) * RATE_STEP;
    }

    static List<String> fixed() { return FIXED; }
    static List<String> guide() { return GUIDE; }
    static boolean isFixed(String text) { return text != null && FIXED.contains(text); }

    static String id(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = "0123456789abcdef".toCharArray();
            char[] value = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                value[i * 2] = hex[(digest[i] & 255) >>> 4];
                value[i * 2 + 1] = hex[digest[i] & 15];
            }
            return new String(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<String> fixedPhrases() {
        Set<String> phrases = new LinkedHashSet<>(AlertSpeechCache.phrases(false));
        Collections.addAll(phrases, CueRouting.unlocatedMinimapEnemySpeech(), "危险信号", "你已阵亡",
                "你已复活", "截屏授权已结束", "截屏恢复失败，请重新授权");
        return Collections.unmodifiableList(new ArrayList<>(phrases));
    }

    private static List<String> guideSentences() {
        Set<String> phrases = new LinkedHashSet<>();
        // Each group varies independently in ReminderGuide. Cover every mask for each group,
        // with both wording modes; do not synthesize explanations from a separate copy of text.
        for (int mask = 0; mask <= 7; mask++) {
            for (int group = 0; group < 4; group++) {
                int[] channels = {7, 7, 7, 7};
                channels[group] = mask;
                for (boolean twoWord : new boolean[]{false, true}) {
                    ReminderGuide.Outputs outputs = new ReminderGuide.Outputs(channels[0],
                            channels[1], channels[2], channels[3], true, true);
                    for (ReminderGuide.Step step : ReminderGuideSections.sentenceSteps(
                            ReminderGuideCatalog.fullGuide(outputs, twoWord))) {
                        if (!step.sample && step.text != null) phrases.add(step.text);
                    }
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(phrases));
    }

    /** Development-only inventory export; also works on the JVM without an Android TTS engine. */
    public static void main(String[] args) {
        System.out.println("{\"schema\":1,\"voices\":[\"" + String.join("\",\"", VOICES)
                + "\"],\"fixed\":" + jsonList(FIXED) + ",\"guide\":" + jsonList(GUIDE) + "}");
    }

    private static String jsonList(List<String> texts) {
        StringBuilder out = new StringBuilder("[");
        for (String text : texts) {
            if (out.length() > 1) out.append(',');
            out.append("{\"id\":\"").append(id(text)).append("\",\"text\":\"")
                    .append(text.replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n").replace("\r", "\\r"))
                    .append("\"}");
        }
        return out.append(']').toString();
    }
}
