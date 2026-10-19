package com.openkhub.sensefield;

/**
 * 玩家自陈视野受损部位后的线索通道与优先级调整策略。
 *
 * <p>本类只读玩家自己申报的档位来改变<b>已有线索</b>的输出方式：周边自陈档下，边缘线索
 * （{@link CueRouting#isPeripheralThreat} 既成判定）的通道集合升一档、优先级抬到既成档。
 * 它不做任何「检测你的视野」的功能，数据来源只允许用户自陈三选一。
 *
 * <p><b>产品边界：这不是医疗建议，本应用不诊断任何疾病。</b>档位读什么、生效与否，都由
 * 玩家自己的设置决定，本类对玩家身体状况不做任何推断。
 *
 * <p>冻结的一半（任务书 42 约束 1）：「中心视野受损 → 看不清小图标／格内细节」这一半在本仓
 * 没有可用几何输入——{@link CueRequest} 的字段面里没有二维屏幕坐标，{@code pan} 是立体声像、
 * {@code distance} 是小地图量程，谁也不描述「事件画在哪、图标多大」。所以中心档不接任何输入，
 * 其输出必须与默认档逐字段相同；{@code FieldLossPolicyTest} 用一组逐字段比对钉死这条锁，
 * 防止将来有人拿 {@code pan} 或 {@code distance} 冒充中心／周边映射。
 *
 * <p>纯 JVM：不 import 任何 {@code android.*} 类（照 {@link HapticPolicy} 的先例，离线可测）。
 */
final class FieldLossPolicy {
    /** 默认档：未自陈／无受限。新装用户读到的就是这个值，行为与没有本轴时逐字节相同。 */
    static final String TIER_NONE = "none";
    /** 玩家自陈周边视野受损。本卷唯一接线的档位。 */
    static final String TIER_PERIPHERAL_LOSS = "peripheral_loss";
    /** 玩家自陈中心视野受损。本卷冻结：输出与 {@link #TIER_NONE} 逐字段相同，见类注释。 */
    static final String TIER_CENTRAL_LOSS = "central_loss";

    /**
     * 升一档时只追加这一个既成位（{@code tone} 之后走 {@code haptic}）。不新造枚举值，
     * 不追加 {@code speech}——边缘线索在既成出口上没有播报文本，追加了也永远不被渲染；
     * 不追加 {@code visual}——{@code CueSettings} 里边缘威胁「没有可靠的屏幕覆盖坐标」。
     */
    static final int PROMOTION_CHANNEL = CueRequest.CHANNEL_HAPTIC;

    /**
     * 升级用的优先级复用 {@code CaptureService.dispatchNativeCue} 里危险信号（kind==3）
     * 的既成档 80，不在本文件新造数值档位。
     */
    static final int PROMOTION_PRIORITY_FLOOR = 80;

    /** 一次判定的全部输出，逐字段可比对；相等性供冻结锁测试整批断言用。 */
    static final class Outcome {
        /** 该线索实际请求的通道位掩码。 */
        final int channels;
        /** 是否把这条线索的优先级抬到升级档。 */
        final boolean elevatedPriority;
        /** 是否常驻显示。本卷没有任何配置把它置真——见类注释的冻结说明。 */
        final boolean keepPersistentDisplay;

        private Outcome(int channels, boolean elevatedPriority, boolean keepPersistentDisplay) {
            this.channels = channels;
            this.elevatedPriority = elevatedPriority;
            this.keepPersistentDisplay = keepPersistentDisplay;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Outcome)) return false;
            Outcome outcome = (Outcome) other;
            return channels == outcome.channels
                    && elevatedPriority == outcome.elevatedPriority
                    && keepPersistentDisplay == outcome.keepPersistentDisplay;
        }

        @Override public int hashCode() {
            return (channels * 31 + (elevatedPriority ? 1 : 0)) * 31
                    + (keepPersistentDisplay ? 1 : 0);
        }

        @Override public String toString() {
            return "Outcome{channels=" + channels + ", elevatedPriority=" + elevatedPriority
                    + ", keepPersistentDisplay=" + keepPersistentDisplay + "}";
        }
    }

    private FieldLossPolicy() {}

    /**
     * 决定「要不要把这条线索的通道升一档／要不要常驻显示」。
     *
     * <p>升级只由三件事共同决定：自陈档等于 {@link #TIER_PERIPHERAL_LOSS}、既成 edge 判定为真、
     * 这条线索所属类别当前没有被玩家关掉。除此之外的一切输入都只被接收、不被使用：
     * {@code pan}／{@code distance} 为 NaN 不抛异常，也不会触发或阻止升级（升级靠的是
     * 离散 direction 折进 edge 的那条既成判定，几何量在本仓不描述视野部位）；
     * {@code urgency} 再高也不许让已关闭的类别复活——{@code categoryEnabled} 为假时整组
     * 输出原样返回。
     *
     * @param selfReport          玩家自陈档位；{@code null}、未知字符串与冻结的中心档一律按默认档
     * @param edge                {@link CueRouting#isPeripheralThreat} 的既成判定
     * @param requestedChannels   这条线索当前请求的通道位掩码；为零时不升级（不凭空造通道）
     * @param pan                 既成 {@link CueRequest#pan}，仅接收
     * @param distance            既成 {@link CueRequest#distance}，仅接收
     * @param urgency             既成 {@link CueRequest#urgency}，仅接收，不作为复活条件
     * @param categoryEnabled     这条线索所属类别的既成开关现状
     */
    static Outcome decide(String selfReport, boolean edge, int requestedChannels,
                          float pan, float distance, float urgency, boolean categoryEnabled) {
        // 数值入参在这里只被声明、不被运算：NaN 因此不可能抛异常，也不可能被冒充成视野部位。
        boolean promote = TIER_PERIPHERAL_LOSS.equals(selfReport)
                && edge && categoryEnabled && requestedChannels != 0;
        if (!promote) {
            return new Outcome(requestedChannels, false, false);
        }
        return new Outcome(requestedChannels | PROMOTION_CHANNEL, true, false);
    }
}
