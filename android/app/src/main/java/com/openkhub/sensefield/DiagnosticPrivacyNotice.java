package com.openkhub.sensefield;

/**
 * The single copy of the privacy statement carried inside every exported
 * diagnostic package.
 *
 * <p>Why a separate class: the same promises are shown on the settings screen
 * ({@code DiagnosticsActivity}) and shipped beside the exported files. Keeping
 * two hand-written copies is how a notice drifts away from what the recorder
 * actually writes. The screen and the archive both read this constant, so a
 * change has to be made once.</p>
 *
 * <p>Pure JVM: no {@code android.*} type is imported, which lets
 * {@code DiagnosticFieldContractTest} assert the wording without a device.</p>
 */
final class DiagnosticPrivacyNotice {

    /** Name of the entry written into the exported zip. */
    static final String FILE_NAME = "PRIVACY.txt";

    /** Statement shipped with every package. Plain text, UTF-8, no markup. */
    static final String TEXT = String.join("\n",
            "听野诊断包 · 隐私说明",
            "",
            "一、本包包含什么",
            "1. metadata.json：机型、厂商、品牌、产品名、Android 版本、SDK、屏幕尺寸、DPI、",
            "   应用版本、会话编号（随机 UUID）、精确开始时间，以及识别参数与上限值。",
            "2. events.jsonl / summary.json / checkpoint.json：识别与播报过程的结构化事件，",
            "   含棋盘识别结果、交换推荐、播报文本（系统生成的提示语）、播放与投递状态。",
            "3. images/：可选的屏幕截图与地图裁剪（JPEG）。约每 10 秒一张，标记问题时另存",
            "   前后短时采样。是否采集由「保存游戏画面」开关决定，默认关闭。",
            "4. elements/：可选的 64×64 棋盘元素缩略图（PNG），仅用于本地核对，不进入规则库。",
            "5. feedback.txt：导出时你填写的补充说明。",
            "",
            "二、本包不包含什么",
            "1. 不含录音、音频文件或麦克风采集内容。",
            "2. 不含语音识别的原始文字或转写结果。",
            "3. 不含 ANDROID_ID、IMEI、序列号、MAC 地址、手机号等唯一设备标识。",
            "",
            "三、存储与外发",
            "1. 诊断数据只保存在本机应用私有目录，不会自动上传到任何服务器。",
            "2. 只有你主动点「导出并分享」或「保存诊断包到文件」时，文件才会离开应用。",
            "3. 若你自行转发，接收方将看到本包内的全部内容。",
            "",
            "四、分享前请确认",
            "1. 屏幕截图可能包含游戏昵称、聊天内容、通知或输入法候选。",
            "2. 请在分享前打开 images/ 检查一遍，确认愿意提供这些内容。",
            "3. 不需要画面时，可先关闭「保存游戏画面」再复现问题，仅导出文字记录。",
            "");

    private DiagnosticPrivacyNotice() {
    }
}