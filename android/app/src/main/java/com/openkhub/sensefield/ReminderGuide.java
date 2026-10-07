package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Meaning and examples of the outputs actually enabled for this session. */
final class ReminderGuide {
    static final String PREF_FULL_GUIDE_COMPLETED = "full_reminder_guide_completed";
    // Choosing to start is distinct from successfully listening to the full guide.
    static final String PREF_FULL_GUIDE_SKIPPED = "full_reminder_guide_skipped";
    // Separate from the old, default-on directory preference to avoid repeat-on-upgrade.
    static final String PREF_REPEAT_BEFORE_START = "repeat_full_reminder_guide_before_start";
    static final String NARRATION_KIND = "REMINDER_GUIDE";
    static final String SAMPLE_KIND = "REMINDER_GUIDE_SAMPLE";
    static final long NARRATION_TIMEOUT_MS = 30_000;

    /** Already resolved against the auto-selected profile and the output policy. */
    static final class Outputs {
        final int near;
        final int appearance;
        final int player;
        final int danger;
        final boolean peripheralTone;
        final boolean systemSpeech;

        Outputs(int near, int appearance, int player, int danger,
                boolean peripheralTone, boolean systemSpeech) {
            this.near = near;
            this.appearance = appearance;
            this.player = player;
            this.danger = danger;
            this.peripheralTone = peripheralTone;
            this.systemSpeech = systemSpeech;
        }
    }

    static final class Step {
        final String title;
        final String text;
        final int channel;
        final int tone;
        final float pan;
        final boolean sample;
        final float distance;
        final float urgency;

        private Step(String title, String text, int channel, int tone,
                     float pan, boolean sample) {
            this(title, text, channel, tone, pan, sample, Float.NaN, 0f);
        }

        private Step(String title, String text, int channel, int tone,
                     float pan, boolean sample, float distance, float urgency) {
            this.title = title;
            this.text = text;
            this.channel = channel;
            this.tone = tone;
            this.pan = pan;
            this.sample = sample;
            this.distance = distance;
            this.urgency = urgency;
        }

        Step withText(String value) {
            return new Step(title, value, channel, tone, pan, sample, distance, urgency);
        }

        Step withRange(float value, float urgency) {
            return new Step(title, text, channel, tone, pan, sample, value, urgency);
        }

        CueRequest request(String id, long now) {
            boolean nearSample = sample && tone == NearZoneRouting.TONE_NEAR;
            return new CueRequest("reminder-guide", id, id,
                    sample ? SAMPLE_KIND : NARRATION_KIND,
                    nearSample ? CueRequest.Category.NEAR_ZONE : CueRequest.Category.SYSTEM,
                    80, now, now + (sample ? 2000 : NARRATION_TIMEOUT_MS), channel,
                    tone, 0, 0, channel == CueRequest.CHANNEL_SPEECH ? text : null,
                    pan, distance, urgency, -1, () -> true);
        }
    }

    private ReminderGuide() {}

    static boolean shouldShowBeforeStart(boolean completed, boolean skipped, boolean repeat) {
        return repeat || (!completed && !skipped);
    }

    static List<Step> build(Outputs outputs) {
        List<Step> steps = new ArrayList<>();
        say(steps, "开始前听一听", "先说明提醒的含义。接下来都是示例。可随时点击“跳过并开始”进入辅助，以后可以在设置里按段重听。");
        if (has(outputs.near, CueRequest.CHANNEL_TONE)) {
            say(steps, "两声短音：附近有敌人",
                    "连续两声短音，表示小地图里有敌人进入你的附近。先听一次。");
            sample(steps, "附近敌人短音示例", CueRequest.CHANNEL_TONE,
                    NearZoneRouting.TONE_NEAR, null, 0f);
        }
        if (has(outputs.near, CueRequest.CHANNEL_SPEECH)) {
            say(steps, "方位语音：以你为中心",
                    "听到“左上有敌人”，表示小地图上，以你的位置为中心，左上方附近发现敌人。方向按地图，不随镜头转动。");
            sample(steps, "左上方位语音示例", CueRequest.CHANNEL_SPEECH,
                    NearZoneRouting.TONE_NEAR, NearZoneRouting.speech(4), -0.7071f);
            say(steps, "没有方向的语音",
                    "“附近有敌人”表示附近发现敌人，但方向不明确。没听到提醒，也不能当作周围没人。");
        }
        if (has(outputs.near, CueRequest.CHANNEL_TONE)) {
            say(steps, "耳机声音：区分左右",
                    "戴立体声耳机时，偏左的短音表示敌人在左侧，偏右表示在右侧。短音不能区分上下方向。接下来分别听左侧和右侧。");
            say(steps, "左侧短音", "这是左侧的短音。");
            sample(steps, "左侧附近敌人短音示例", CueRequest.CHANNEL_TONE,
                    NearZoneRouting.TONE_NEAR, null, -1f);
            say(steps, "右侧短音", "这是右侧的短音。");
            sample(steps, "右侧附近敌人短音示例", CueRequest.CHANNEL_TONE,
                    NearZoneRouting.TONE_NEAR, null, 1f);
            say(steps, "确认左右是否能听清",
                    "如果两边听起来一样，请检查耳机是否支持立体声，以及系统是否开启了单声道。声音左右相反时，请检查耳机佩戴。手机外放可能难以听出左右，可以使用方位语音。");
            if (!has(outputs.near, CueRequest.CHANNEL_SPEECH)) {
                say(steps, "当前没有方位语音",
                        "你当前关闭了方位语音。需要听清上下方向，可以在声音与语音设置里开启。");
            }
        }
        if (has(outputs.near, CueRequest.CHANNEL_HAPTIC)) {
            say(steps, "两次震动：提醒注意附近",
                    "手机连续震动两次，也表示附近有敌人。震动不区分左右方向。接下来试一次震动。");
            sample(steps, "附近敌人双震动示例", CueRequest.CHANNEL_HAPTIC,
                    NearZoneRouting.TONE_NEAR, null, 0f);
        }
        if (has(outputs.appearance, CueRequest.CHANNEL_TONE)) {
            say(steps, "另一种单声短音：新头像",
                    "另一种单声短音，表示小地图出现了新敌方头像。它没有距离含义，不能据此判断敌人就在身边。");
            sample(steps, "新敌方头像短音示例", CueRequest.CHANNEL_TONE, 2, null, 0f);
        }
        if (has(outputs.appearance, CueRequest.CHANNEL_SPEECH)) {
            say(steps, "新头像语音",
                    "“小地图发现新敌方头像”，也是出现新头像的意思，没有距离含义。");
        }
        if (has(outputs.appearance, CueRequest.CHANNEL_HAPTIC)) {
            say(steps, "新头像震动", "小地图出现新敌方头像时，也会有一次短震动。");
        }
        if (outputs.peripheralTone) {
            say(steps, "画面边缘短音",
                    "单声、偏左或偏右的短音，表示主画面对应边缘检测到目标。");
            sample(steps, "画面左侧短音示例", CueRequest.CHANNEL_TONE, 1, null, -1f);
        }
        if (outputs.danger != 0) {
            say(steps, "游戏危险提示", has(outputs.danger, CueRequest.CHANNEL_SPEECH)
                    ? "“危险信号”表示识别到游戏里的危险提示标记，请留意游戏信息。"
                    : "这声短音表示识别到游戏里的危险提示标记，请留意游戏信息。");
            if (has(outputs.danger, CueRequest.CHANNEL_TONE))
                sample(steps, "危险提示短音示例", CueRequest.CHANNEL_TONE, 3, null, 0f);
        }
        if (has(outputs.player, CueRequest.CHANNEL_SPEECH)) {
            say(steps, "阵亡与复活语音",
                    "“你已阵亡”表示等待复活。“你已复活”表示可以继续操作。");
        }
        if (has(outputs.player, CueRequest.CHANNEL_HAPTIC)) {
            say(steps, "角色状态震动", "检测到阵亡或复活时，也会请求一次震动。");
        }
        if (outputs.near == 0) {
            say(steps, "当前没有附近敌人提醒",
                    "当前设置没有附近敌人的声音或震动提醒。可以在声音与语音设置里检查。");
        }
        if (outputs.systemSpeech) {
            say(steps, "重新授权的语音",
                    "听到“截屏授权已结束”或“截屏恢复失败，请重新授权”，请返回辅助首页重新开始。");
        }
        say(steps, "开始辅助", "说明结束。点击“开始辅助”，授权并启动后，会自动打开王者荣耀。需要重听或调声音，可以到设置里操作。");
        return Collections.unmodifiableList(steps);
    }

    private static boolean has(int mask, int channel) { return (mask & channel) != 0; }

    private static void say(List<Step> steps, String title, String text) {
        steps.add(new Step(title, text, CueRequest.CHANNEL_SPEECH, 0, Float.NaN, false));
    }

    private static void sample(List<Step> steps, String title, int channel,
                               int tone, String speech, float pan) {
        steps.add(sampleStep(title, channel, tone, speech, pan));
    }

    static Step sampleStep(String title, int channel, int tone, String speech, float pan) {
        return new Step(title, speech, channel, tone, pan, true);
    }
}
