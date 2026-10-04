package com.openkhub.sensefield;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/** Small on-device classifier for greetings and checks that the microphone was heard. */
final class AssistantConversationIntent {
    enum Type { GREETING, HEARING_CHECK }

    static final class Match {
        final Type type;
        final String answer;

        Match(Type type, String answer) {
            this.type = type;
            this.answer = answer;
        }
    }

    private static final Pattern GREETING_PREFIX =
            Pattern.compile("^(?:(?:你好|您好)){1,4}");
    private static final Pattern HEARING_CHECK = Pattern.compile(
            "^(?:你)?(?:(?:能|可以|可不可以|是否))?听(?:得)?(?:到|见|清楚?|懂)"
                    + "(?:了|没)?(?:我)?(?:说话|讲话|声音)?(?:吗|嘛|么)?$");
    private static final Pattern SUBJECT_FIRST_HEARING_CHECK = Pattern.compile(
            "^(?:我)?(?:说话|讲话|声音)(?:你)?(?:(?:能|可以))?"
                    + "听(?:得)?(?:到|见|清楚?|懂)(?:吗|嘛|么)?$");

    private AssistantConversationIntent() {}

    static boolean matches(String utterance) {
        return classify(utterance) != null;
    }

    static Match classify(String utterance) {
        if (utterance == null || utterance.isEmpty()) return null;
        String compact = utterance.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？!?、,.…~～]", "");
        compact = compact.replaceFirst("^(听野|助手|喂)", "");
        Matcher greeting = GREETING_PREFIX.matcher(compact);
        if (greeting.find()) {
            String remainder = compact.substring(greeting.end());
            if (remainder.isEmpty() || remainder.matches("(?:啊|呀|哦)+"))
                return new Match(Type.GREETING,
                        "我在，能听到。你可以继续问，或说“读取画面”让我看当前画面。");
            if (isHearingCheck(remainder)) return hearingCheck();
        }
        if (isHearingCheck(compact)) return hearingCheck();
        return null;
    }

    private static boolean isHearingCheck(String text) {
        String withoutFiller = text.replaceAll("^(?:啊|哦|喂)+", "")
                .replaceAll("(?:啊|呀|哦)+$", "");
        return HEARING_CHECK.matcher(withoutFiller).matches()
                || SUBJECT_FIRST_HEARING_CHECK.matcher(withoutFiller).matches();
    }

    private static Match hearingCheck() {
        return new Match(Type.HEARING_CHECK,
                "听得到。请继续说你的问题，或说“读取画面”让我看当前画面。");
    }
}
