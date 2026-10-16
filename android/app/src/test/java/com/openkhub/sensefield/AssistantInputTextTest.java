package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

public class AssistantInputTextTest {
    @Test public void decoderPunctuationAndInvisibleOnlyOutputAreNotSpeech() {
        for (String value : new String[] { null, "", " \t\n", ".", "。", "。。", "，。！？",
                "...", "……", "—·：；", "\u00a0\u3000", "\u200b\ufeff。", "🎮🔊", "\u0000" }) {
            assertEquals(String.valueOf(value), "", AssistantInputText.normalize(value));
        }
    }

    @Test public void shortRepliesAndOpenQuestionsKeepTheirWording() {
        for (String value : new String[] { "好", "停", "嗯", "上", "A", "2", "３", "𠮷",
                "分析一下局势", "劳驾捋一捋上面那排亮着的东西", "升级二技能还是三技能？",
                "我选桑启。", "看一下这个？", "go?", "🎮 选哪个？" }) {
            assertEquals(value, AssistantInputText.normalize(value));
        }
    }

    @Test public void unicodeSpacingIsRemovedWithoutStrippingSentencePunctuation() {
        assertEquals("好。", AssistantInputText.normalize("\u200b\u3000 好。 \u00a0\ufeff"));
        assertEquals("。帮我看一下！", AssistantInputText.normalize(" 。帮我看一下！ "));
    }
}
