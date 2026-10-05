package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Groups each explanation with the samples that immediately follow it. */
final class ReminderGuideSections {
    static final class Section {
        final String id;
        final String title;
        final String text;
        final List<ReminderGuide.Step> steps;
        final int startIndex;

        private Section(int startIndex, List<ReminderGuide.Step> steps) {
            ReminderGuide.Step first = steps.get(0);
            this.id = "guide-section:" + startIndex;
            this.title = first.title;
            this.text = first.text;
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.startIndex = startIndex;
        }
    }

    private ReminderGuideSections() {}

    /**
     * Returns one section for each non-sample step and its immediately following samples.
     * Leading samples have no explanation to attach to and are ignored.
     */
    static List<Section> build(List<ReminderGuide.Step> steps) {
        if (steps == null || steps.isEmpty()) return Collections.emptyList();

        List<Section> sections = new ArrayList<>();
        int index = 0;
        while (index < steps.size()) {
            if (steps.get(index).sample) {
                index++;
                continue;
            }

            int startIndex = index;
            List<ReminderGuide.Step> sectionSteps = new ArrayList<>();
            sectionSteps.add(steps.get(index++));
            while (index < steps.size() && steps.get(index).sample) {
                sectionSteps.add(steps.get(index++));
            }
            sections.add(new Section(startIndex, sectionSteps));
        }
        return Collections.unmodifiableList(sections);
    }

    /** Splits multi-sentence narration into playback items without changing displayed text. */
    static List<ReminderGuide.Step> sentenceSteps(List<ReminderGuide.Step> steps) {
        if (steps == null || steps.isEmpty()) return Collections.emptyList();

        List<ReminderGuide.Step> sentences = new ArrayList<>();
        for (ReminderGuide.Step step : steps) {
            if (step.sample || step.text == null || step.text.isEmpty()) {
                sentences.add(step);
                continue;
            }

            List<String> chunks = splitSentences(step.text);
            if (chunks.size() <= 1) {
                sentences.add(step);
            } else {
                for (String chunk : chunks) sentences.add(step.withText(chunk));
            }
        }
        return Collections.unmodifiableList(sentences);
    }

    private static List<String> splitSentences(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < text.length()) {
            if (!isSentenceBoundary(text.charAt(index))) {
                index++;
                continue;
            }
            // Keep punctuation runs such as "？！" together with their sentence.
            while (index + 1 < text.length() && isSentenceBoundary(text.charAt(index + 1))) index++;
            int end = index + 1;
            if (end > start) chunks.add(text.substring(start, end));
            start = end;
            index = end;
        }
        if (start < text.length()) {
            String tail = text.substring(start);
            if (!chunks.isEmpty() && tail.trim().isEmpty()) {
                int last = chunks.size() - 1;
                chunks.set(last, chunks.get(last) + tail);
            } else {
                chunks.add(tail);
            }
        }
        return chunks;
    }

    private static boolean isSentenceBoundary(char value) {
        return value == '。' || value == '？' || value == '！'
                || value == '?' || value == '!' || value == '.';
    }
}
