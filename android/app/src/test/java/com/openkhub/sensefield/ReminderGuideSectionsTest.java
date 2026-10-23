package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ReminderGuideSectionsTest {
    @Test public void sectionsFlattenToTheOriginalStepsInOrderAndByReference() {
        List<ReminderGuide.Step> original = ReminderGuide.build(
                new ReminderGuide.Outputs(7, 7, 0, 0, true, true));
        List<ReminderGuideSections.Section> sections = ReminderGuideSections.build(original);
        List<ReminderGuide.Step> flattened = new ArrayList<>();
        int originalIndex = 0;

        for (ReminderGuideSections.Section section : sections) {
            assertEquals("guide-section:" + originalIndex, section.id);
            assertEquals(originalIndex, section.startIndex);
            assertFalse(section.steps.isEmpty());
            assertFalse(section.steps.get(0).sample);
            assertEquals(section.steps.get(0).title, section.title);
            assertEquals(section.steps.get(0).text, section.text);
            flattened.addAll(section.steps);
            originalIndex += section.steps.size();
        }

        assertEquals(original.size(), flattened.size());
        for (int i = 0; i < original.size(); i++) assertSame(original.get(i), flattened.get(i));
        assertTrue(sections.size() > 1);
    }

    @Test public void sectionsAndTheirStepsAreImmutable() {
        List<ReminderGuide.Step> original = ReminderGuide.build(
                new ReminderGuide.Outputs(7, 0, 0, 0, false, false));
        List<ReminderGuideSections.Section> sections = ReminderGuideSections.build(original);

        try {
            sections.clear();
            fail("section collection should be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
        try {
            sections.get(0).steps.clear();
            fail("section steps should be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test public void sampleOnlyAndEmptyInputsAreSafe() {
        ReminderGuide.Step sample = ReminderGuide.sampleStep("试听", CueRequest.CHANNEL_TONE,
                NearZoneRouting.TONE_NEAR, null, 0f);
        assertTrue(ReminderGuideSections.build(Collections.<ReminderGuide.Step>emptyList()).isEmpty());
        assertTrue(ReminderGuideSections.build(Collections.singletonList(sample)).isEmpty());
        assertTrue(ReminderGuideSections.build(null).isEmpty());
    }

    @Test public void sentenceStepsSplitNarrationAndPreserveAllTextAndSamples() {
        ReminderGuide.Step template = ReminderGuide.build(
                new ReminderGuide.Outputs(0, 0, 0, 0, false, false)).get(0);
        String originalText = "第一句。第二句！第三句？第四句! fifth? final.  ";
        ReminderGuide.Step narration = template.withText(originalText);
        ReminderGuide.Step sample = ReminderGuide.sampleStep("试听", CueRequest.CHANNEL_SPEECH,
                NearZoneRouting.TONE_NEAR, "试听句一。试听句二！", Float.NaN);
        ReminderGuide.Step oneSentence = template.withText("只有一句。");

        List<ReminderGuide.Step> result = ReminderGuideSections.sentenceSteps(
                Arrays.asList(narration, sample, oneSentence));

        assertEquals(8, result.size());
        StringBuilder reconstructed = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            ReminderGuide.Step sentence = result.get(i);
            assertFalse(sentence.sample);
            assertEquals(narration.title, sentence.title);
            assertEquals(narration.channel, sentence.channel);
            assertEquals(narration.tone, sentence.tone);
            assertEquals(narration.pan, sentence.pan, 0f);
            assertEquals(narration.distance, sentence.distance, 0f);
            assertEquals(narration.urgency, sentence.urgency, 0f);
            reconstructed.append(sentence.text);
        }
        assertEquals(originalText, reconstructed.toString());
        assertSame(sample, result.get(6));
        assertSame(oneSentence, result.get(7));
    }

    @Test public void sentenceStepsLeavesSingleSentenceAndUnsplittableStepsAlone() {
        ReminderGuide.Step template = ReminderGuide.build(
                new ReminderGuide.Outputs(0, 0, 0, 0, false, false)).get(0);
        ReminderGuide.Step single = template.withText("一句话 without terminal punctuation");
        ReminderGuide.Step noText = template.withText(null);

        List<ReminderGuide.Step> result = ReminderGuideSections.sentenceSteps(
                Arrays.asList(single, noText));

        assertEquals(2, result.size());
        assertSame(single, result.get(0));
        assertSame(noText, result.get(1));
        assertTrue(ReminderGuideSections.sentenceSteps(null).isEmpty());
        assertTrue(ReminderGuideSections.sentenceSteps(Collections.<ReminderGuide.Step>emptyList()).isEmpty());
    }
}
