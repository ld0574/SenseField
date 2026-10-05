package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Individually replayable examples, resolved from the same enabled outputs as live cues. */
final class ReminderGuideCatalog {
    static final class Item {
        final String id;
        final String title;
        final String explanation;
        final List<ReminderGuide.Step> samples;

        Item(String id, String title, String explanation, ReminderGuide.Step... samples) {
            this.id = id;
            this.title = title;
            this.explanation = explanation;
            List<ReminderGuide.Step> values = new ArrayList<>();
            Collections.addAll(values, samples);
            this.samples = Collections.unmodifiableList(values);
        }
    }

    private ReminderGuideCatalog() {}

    static List<Item> build(ReminderGuide.Outputs outputs, boolean twoWord, boolean distanceHaptic) {
        List<Item> items = new ArrayList<>();
        if (has(outputs.near, CueRequest.CHANNEL_TONE)) {
            items.add(new Item("near_tone", "附近敌人短音",
                    "连续两声短音表示附近发现敌人。这里是示例，播放不会识别游戏画面。",
                    sample("附近敌人短音", CueRequest.CHANNEL_TONE, 7, null, 0f)));
            items.add(new Item("left_tone", "左侧短音",
                    "戴立体声耳机时，这个短音偏向左耳，表示小地图上自己左侧附近的敌人。短音不区分上下。",
                    sample("左侧短音", CueRequest.CHANNEL_TONE, 7, null, -1f)));
            items.add(new Item("right_tone", "右侧短音",
                    "戴立体声耳机时，这个短音偏向右耳，表示小地图上自己右侧附近的敌人。两侧相同时请检查单声道设置。",
                    sample("右侧短音", CueRequest.CHANNEL_TONE, 7, null, 1f)));
        }
        if (has(outputs.near, CueRequest.CHANNEL_SPEECH)) {
            String[] labels = {"右侧", "右上", "上方", "左上", "左侧", "左下", "下方", "右下"};
            for (int sector = 1; sector <= 8; sector++) {
                String speech = twoWord ? NearZoneRouting.twoWordSpeech(sector) : NearZoneRouting.speech(sector);
                items.add(new Item("near_speech_" + sector, labels[sector - 1] + "方位语音",
                        "方向以小地图上的自己为中心，不随主画面镜头转动。当前示例会说“" + speech + "”。",
                        sample(labels[sector - 1] + "方位语音", CueRequest.CHANNEL_SPEECH, 7, speech, Float.NaN)));
            }
            String speech = twoWord ? NearZoneRouting.twoWordSpeech(0) : NearZoneRouting.speech(0);
            items.add(new Item("near_speech_unknown", "方向不明确时的语音",
                    "附近发现敌人但方向不明确时，会说“" + speech + "”。没有提醒不能当作周围没有敌人。",
                    sample("方向不明确时的语音", CueRequest.CHANNEL_SPEECH, 7, speech, Float.NaN)));
        }
        if (has(outputs.near, CueRequest.CHANNEL_HAPTIC)) {
            items.add(new Item("near_haptic", "附近敌人振动",
                    "连续两次振动表示附近发现敌人。使用当前震感与节奏设置；振动不表示手机左侧或右侧。",
                    sample("附近敌人振动", CueRequest.CHANNEL_HAPTIC, 7, null, 0f).withRange(0.08f, 1f)));
            if (distanceHaptic) {
                items.add(new Item("near_haptic_close", "距离振动：较近示例",
                        "距离振动实验已开启，这是较近目标的示例。强弱或时长效果取决于设备能力，需要实际感受后比较。",
                        sample("较近振动", CueRequest.CHANNEL_HAPTIC, 7, null, 0f).withRange(0.04f, 1f)));
                items.add(new Item("near_haptic_far", "距离振动：较远示例",
                        "这是近区内较远目标的示例。与较近示例使用同一震感设置，不代表真实游戏中正在有敌人。",
                        sample("较远振动", CueRequest.CHANNEL_HAPTIC, 7, null, 0f).withRange(0.16f, 1f)));
            }
        }
        if (has(outputs.appearance, CueRequest.CHANNEL_TONE)) {
            items.add(new Item("portrait_tone", "新敌方头像短音",
                    "另一种单声短音表示小地图出现了新敌方头像，没有距离含义。",
                    sample("新敌方头像短音", CueRequest.CHANNEL_TONE, 2, null, 0f)));
        }
        if (has(outputs.appearance, CueRequest.CHANNEL_SPEECH)) {
            items.add(new Item("portrait_speech", "新敌方头像语音",
                    "这条语音只表示出现新敌方头像，不能据此判断敌人就在身边。",
                    sample("新敌方头像语音", CueRequest.CHANNEL_SPEECH, 2,
                            CueRouting.unlocatedMinimapEnemySpeech(), Float.NaN)));
        }
        if (has(outputs.appearance, CueRequest.CHANNEL_HAPTIC)) {
            items.add(new Item("portrait_haptic", "新敌方头像振动",
                    "一次短振动表示出现新敌方头像，使用当前震感设置。",
                    sample("新敌方头像振动", CueRequest.CHANNEL_HAPTIC, 2, null, 0f)));
        }
        if (outputs.peripheralTone) {
            items.add(new Item("peripheral_left", "主画面左边缘短音",
                    "偏左的单声短音表示主画面左边缘检测到目标。它与小地图附近敌人的双声短音不同。",
                    sample("主画面左边缘短音", CueRequest.CHANNEL_TONE, 1, null, -1f)));
            items.add(new Item("peripheral_right", "主画面右边缘短音",
                    "偏右的单声短音表示主画面右边缘检测到目标。",
                    sample("主画面右边缘短音", CueRequest.CHANNEL_TONE, 1, null, 1f)));
        }
        if (has(outputs.danger, CueRequest.CHANNEL_TONE)) {
            items.add(new Item("danger_tone", "游戏危险标记短音",
                    "表示识别到游戏里的危险提示标记，请留意游戏信息。",
                    sample("游戏危险标记短音", CueRequest.CHANNEL_TONE, 3, null, 0f)));
        }
        if (has(outputs.danger, CueRequest.CHANNEL_SPEECH)) {
            items.add(new Item("danger_speech", "游戏危险标记语音",
                    "识别到游戏里的危险提示标记时，会说“危险信号”。",
                    sample("游戏危险标记语音", CueRequest.CHANNEL_SPEECH, 3, "危险信号", Float.NaN)));
        }
        if (has(outputs.player, CueRequest.CHANNEL_SPEECH)) {
            items.add(new Item("player_dead", "阵亡语音", "表示角色已阵亡，需要等待复活。",
                    sample("阵亡语音", CueRequest.CHANNEL_SPEECH, 4, "你已阵亡", Float.NaN)));
            items.add(new Item("player_alive", "复活语音", "表示角色已复活，可以继续操作。",
                    sample("复活语音", CueRequest.CHANNEL_SPEECH, 5, "你已复活", Float.NaN)));
        }
        if (has(outputs.player, CueRequest.CHANNEL_HAPTIC)) {
            items.add(new Item("player_haptic", "角色状态振动", "阵亡或复活时的一次短振动，振动本身不区分这两种状态。",
                    sample("角色状态振动", CueRequest.CHANNEL_HAPTIC, 4, null, 0f)));
        }
        if (outputs.systemSpeech) {
            items.add(new Item("system_projection", "截屏授权结束语音",
                    "听到这个提示，请返回辅助首页重新开始，并重新确认屏幕共享。",
                    sample("截屏授权结束语音", CueRequest.CHANNEL_SPEECH, 0, "截屏授权已结束", Float.NaN)));
        }
        return Collections.unmodifiableList(items);
    }

    static Item find(List<Item> items, String id) {
        for (Item item : items) if (item.id.equals(id)) return item;
        return null;
    }

    static List<ReminderGuide.Step> fullGuide(ReminderGuide.Outputs outputs, boolean twoWord) {
        List<ReminderGuide.Step> steps = new ArrayList<>();
        for (ReminderGuide.Step step : ReminderGuide.build(outputs)) {
            if (twoWord && step.text != null && step.text.contains(NearZoneRouting.speech(4)))
                step = step.withText(step.text.replace(NearZoneRouting.speech(4), NearZoneRouting.twoWordSpeech(4)));
            if (twoWord && "没有方向的语音".equals(step.title))
                step = step.withText(step.text.replace(NearZoneRouting.speech(0), NearZoneRouting.twoWordSpeech(0)));
            if (step.sample && step.channel == CueRequest.CHANNEL_HAPTIC && step.tone == 7)
                step = step.withRange(0.08f, 1f);
            steps.add(step);
        }
        return Collections.unmodifiableList(steps);
    }

    private static boolean has(int value, int channel) { return (value & channel) != 0; }
    private static ReminderGuide.Step sample(String title, int channel, int tone, String speech, float pan) {
        return ReminderGuide.sampleStep(title, channel, tone, speech, pan);
    }
}
