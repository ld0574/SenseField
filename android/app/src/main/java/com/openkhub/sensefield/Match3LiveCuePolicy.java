package com.openkhub.sensefield;

/**
 * Applies the saved cue preferences to live Match3 announcements. The speech channel is always
 * granted, every other channel is granted exactly as the saved settings grant it, so a player who
 * turned sound effects or haptics on actually gets them instead of having them folded back to
 * speech at this gate.
 */
final class Match3LiveCuePolicy implements CueDispatcher.Policy {
    static final int REQUESTED_CHANNELS = CueRequest.CHANNEL_SPEECH;

    /**
     * 纯函数：交换提示该请的通道位 —— 只请玩家愿意听的通道。
     *
     * <p>入参是存储设置（{@code CueSettings}）在该分类上已启用的通道位掩码：音效位开着才连音效一起请，
     * 音效被玩家关掉时退回只请语音。本函数不新增任何 SharedPreferences 键，也不解释开关，
     * 最终是否真出声仍由 {@code CueDispatcher} 既有的分类与通道闸门裁
     * （{@code category_disabled} / {@code channels_disabled}）。
     *
     * @param storedEnabledChannels 存储设置里已启用的通道位
     * @return 提交给 {@link CueRequest#requestedChannels} 的通道位，语音位恒在
     */
    static int hintChannelsForStored(int storedEnabledChannels) {
        int tone = storedEnabledChannels & CueRequest.CHANNEL_TONE;
        return tone == 0 ? CueRequest.CHANNEL_SPEECH : CueRequest.CHANNEL_SPEECH | tone;
    }

    /**
     * 纯函数：交换提示格子的立体声 pan，喂给 {@link CueRequest#pan} 槽。
     *
     * <p>入参口径与诊断读数 {@link BoardPan#readingOf} 逐字相同（同一个格子、同一套行列与盘面尺寸），
     * 不另起一套映射。盘面尺寸不可用或行列越界时返回 {@link Float#NaN}，
     * 即维持「没有读数、沿用旧的方向 pan」的现行为，绝不瞎猜居中。
     *
     * @param row  行下标（从 0 起）
     * @param col  列下标（从 0 起）
     * @param rows 盘面行数
     * @param cols 盘面列数
     * @return [-1,1] 的 pan；不可用时为 NaN
     */
    static float hintPanOf(int row, int col, int rows, int cols) {
        return BoardPan.panOf(row, col, rows, cols);
    }

    private final CueDispatcher.Policy storedSettings;

    Match3LiveCuePolicy(CueDispatcher.Policy storedSettings) {
        this.storedSettings = storedSettings;
    }

    /** 提交侧请的通道位：只做一件事，把玩家存的通道开关原样喂给纯函数 {@link #hintChannelsForStored}。 */
    int hintRequestedChannels() {
        return hintChannelsForStored(storedSettings.enabledChannels(CueRequest.Category.SYSTEM));
    }

    @Override public boolean categoryEnabled(CueRequest.Category category) {
        return storedSettings.categoryEnabled(category);
    }

    /**
     * 交付侧闸门读数：原样交回存储设置的通道位，让玩家存的音效位／触觉位／视觉位真的算数，
     * 不再在这里被回收；语音位恒开（玩家即使把语音关掉，实况提示仍走语音），
     * 用或运算把语音位显式补上，不假定存储里一定开着。
     */
    @Override public int enabledChannels() {
        return storedSettings.enabledChannels() | CueRequest.CHANNEL_SPEECH;
    }

    /** 与 {@link #enabledChannels()} 同一口径，按分类取存储读数后同样只补语音位、不回收任何位。 */
    @Override public int enabledChannels(CueRequest.Category category) {
        return storedSettings.enabledChannels(category) | CueRequest.CHANNEL_SPEECH;
    }

    @Override public long dedupeWindowMs(CueRequest.Category category) {
        return Math.max(1500, storedSettings.dedupeWindowMs(category));
    }
}
