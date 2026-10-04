package com.openkhub.sensefield;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/** Small on-device classifier for greetings, microphone checks, and assistant status questions. */
final class AssistantConversationIntent {
    enum Type { GREETING, HEARING_CHECK, ASSISTANT_STATUS }

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
    private static final Pattern ASSISTANT_FAILURE = Pattern.compile(
            "^(?:你)?(?:怎么|为什么|为何)(?:就)?(?:(?:又|还是|突然|现在|一直))?"
                    + "(?:不可用|不能用|用不了|没法用|失灵|不工作|报错|失败|出问题)"
                    + "(?:了)?(?:啊|呀|呢|吗)?$");
    private static final Pattern ASSISTANT_UNCLEAR = Pattern.compile(
            "^(?:你)?(?:怎么|为什么|为何)(?:就)?(?:(?:又|还是|一直|总是))?"
                    + "(?:看不清|看不出来|没看清|读不出来|识别不出来)"
                    + "(?:啊|呀|呢|吗)?$");
    private static final Pattern ASSISTANT_WAITING = Pattern.compile(
            "^(?:(?:你)?(?:怎么|为什么|为何))?(?:还没|一直没)"
                    + "(?:响应|回应|回复|回答|反应|回话)(?:我)?(?:了)?(?:啊|呀|呢|吗)?$"
                    + "|^(?:你)?(?:怎么|为什么|为何)(?:没有|没)"
                    + "(?:反应|回应|响应|回复|回答|回话)"
                    + "(?:了)?(?:啊|呀|呢|吗)?$"
                    + "|^(?:还要等多久|要等多久|要等到什么时候)(?:啊|呀|呢|吗)?$"
                    + "|^(?:你)?(?:还在吗|在吗|在不在)(?:啊|呀|呢)?$");

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
            if (isAssistantStatus(remainder)) return assistantStatus();
        }
        if (isHearingCheck(compact)) return hearingCheck();
        if (isAssistantStatus(compact)) return assistantStatus();
        return null;
    }

    private static boolean isAssistantStatus(String text) {
        return ASSISTANT_FAILURE.matcher(text).matches()
                || ASSISTANT_UNCLEAR.matcher(text).matches()
                || ASSISTANT_WAITING.matcher(text).matches();
    }

    private static Match assistantStatus() {
        return new Match(Type.ASSISTANT_STATUS,
                "我在。刚才的请求可能没有成功返回，请再说一次；需要我看当前画面时，可以说“读取画面”。");
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
