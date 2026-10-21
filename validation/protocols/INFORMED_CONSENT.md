# 听野应用内知情同意书（可对着代码核对的版本）

**版本与基线：** 2026-10-21，代码基线 `b19b198`。本文只描述这一版代码里真实存在的行为；代码变了，本文和 `InformedConsentContractTest` 应一起改。

**性质声明：** 这是黑客松参赛项目，非官方工具，不替代法律意见。对外使用或据此作合规判断前，需律师复核。

**与试用授权的关系：** `validation/protocols/PLAYER_TRIAL_KIT.md:17` 的「试用前说明与授权」管的是线下试用时的录像、诊断日志保存与对外分享授权；本文管的是应用内语音提问、画面理解与提示通道的知情同意。两者交叉引用，不互相替代。

**读法：** 下文未写目录的 Java 文件名，均指 `android/app/src/main/java/com/openkhub/sensefield/` 下的同名文件；其余文件写仓库相对路径。每个 `文件:行号` 都由测试回读核验；标「现状没有／待建」的句子，是本文明确承认的缺口，不是能力承诺。

## 一、这是什么与适用范围

本文适用于听野 Android 应用里的可选实验 AI 助手，以及声音、语音、触觉、视觉四条提示通道。AI 助手默认关闭，主开关在 `AssistantSettingsActivity.java:38`，界面首屏也写明「AI助手默认关闭，需要时再开启」（`AssistantSettingsActivity.java:32`）。只有语音提问或「画面理解且服务已配置」二者至少一个成立时，助手才进入可用状态（`AssistantSettings.java:48`）；关闭助手不影响本地敌人提醒（`AssistantSettingsActivity.java:34`）。本文不覆盖线下试用录像与分享（见 `PLAYER_TRIAL_KIT.md:17`），也不覆盖诊断截图的独立设置。听野不是医疗器械，不做诊断或治疗，不承诺识别全部信息，也不替代玩家自己的判断。本文只写应用内知情同意的现状与待建项，不写法律结论；涉及权利义务的表述需律师复核。

## 二、我们读什么、不读什么

**四条提示通道。** 声音（提示音）由 `CuePlayer.java:282` 的 `SoundPool` 播放，通道名见 `SettingHelpContent.java:37`；语音由 `CuePlayer.java:1376` 的 `TextToSpeech.speak` 播报，通道名见 `SettingHelpContent.java:38`；触觉由 `CuePlayer.java:1213` 的 `vibrate` 驱动，通道名见 `SettingHelpContent.java:39`；视觉由 `AssistantOverlay.java:34` 的文本视图呈现，通道名见 `SettingHelpContent.java:36`，高亮窗还带 `FLAG_NOT_TOUCHABLE`（`Match3HintOverlay.java:45`），叠在游戏上也点不到东西。

**两条 AI 专项各自独立同意。** 语音提问与画面理解是两个独立开关、两次独立弹窗：语音提问在 `AssistantSettingsActivity.java:48` 绑定 `assistant_audio_consent`（`AssistantSettings.java:14`），未同意时语音不启用（`AssistantSettings.java:44`）；画面理解在 `AssistantSettingsActivity.java:55` 绑定 `assistant_image_consent`（`AssistantSettings.java:15`），未同意时画面不启用（`AssistantSettings.java:25`）。弹窗只有点「同意并开启」才写入同意标记与功能开关（`AssistantSettingsActivity.java:117`），点「取消」只把复选框退回未选（`AssistantSettingsActivity.java:121`）。

**不读聊天记录。** 应用清单只申请前台服务、录屏、麦克风、网络、通知、悬浮窗、振动与安装包这几类权限（`AndroidManifest.xml:3`），没有通知读取、短信、联系人等权限；读屏服务只订阅窗口状态与窗口内容变化（`SenseFieldReaderService.java:67`），不建立聊天数据库，也不回看历史消息。但画面理解发送的是你当前共享的屏幕，若屏幕上正好有游戏聊天或系统通知，可能随画面一起发送（`AssistantSettingsActivity.java:56`）——这一点弹窗已明示，不属于「读聊天记录」。

**不读游戏内存。** 仓库规则逐字写着「不注入游戏、不读游戏内存、不模拟触控」（`CONTRIBUTING.md:23`）。画面来自系统录屏授权的一次性令牌（`CaptureService.java:485` 的 `MediaProjection`），界面文字来自系统无障碍事件（`SenseFieldReaderService.java:67`），不读取游戏进程内存，也不接入游戏服务器。

**不注入、不模拟点击。** 只读守卫把 `dispatchGesture`、`injectInputEvent`、`performGlobalAction` 等注入类 API 列入不可豁免的黑名单（`ReadOnlyGuard.java:44`），读屏服务也自述「不消费、不注入任何事件」（`SenseFieldReaderService.java:18`）；点击类动作常量 `ACTION_CLICK` 同样在黑名单里（`ReadOnlyGuard.java:53`），无障碍配置只声明读取窗口内容、没有手势能力（`accessibility_service_config.xml:2` 的注释与 `accessibility_service_config.xml:7` 的唯一能力位）。读屏服务确实会读前台窗口的无障碍树（`SenseFieldReaderService.java:16`），这是「只读可见节点」，不是「什么都不看」。

## 三、风险告知

这是实验功能，回复可能较慢、较长或被中断（`AssistantSettingsActivity.java:33`）；画面理解的云端回答可能等待 5～10 秒，依据的是截取时的画面，不能确认视野外或回答到达时的敌人状态（`AssistantSettingsActivity.java:56`、`SettingHelpContent.java:244`）。语音提问五秒未检测到说话会退出、单次最长十五秒；外放时游戏人声可能被误识别，蓝牙音箱不适合连续提问，普通蓝牙耳机连接时识别可能使用手机麦克风，游戏开麦、系统静音或其他录音活动时输入会暂停（`AssistantSettingsActivity.java:49`）。画面理解可能把画面里的游戏聊天或系统通知一并发送（`AssistantSettingsActivity.java:56`）。服务设有请求频率和每日体验额度，超过额度时只能稍后再试（`SettingHelpContent.java:234`）。以上功能都可能出错或漏报，不能替代玩家的观察与操作；本地敌人提醒可独立运行（`AssistantSettingsActivity.java:34`）。本文不承诺账号安全、游戏内结果或任何特定效果；涉及消费者权益与责任的表述需律师复核。

## 四、你的权利与撤回

参与是自愿的：你可以不开启任何 AI 开关，也可以随时关闭总开关；关闭后助手停止录音、画面请求和回复播放，本地敌人提醒继续运行（`SettingHelpContent.java:238`）。**现状的撤回方式**是到设置里关掉对应开关：语音提问关语音开关，画面理解关画面开关，两个开关分别写在 `AssistantSettingsActivity.java:48` 与 `AssistantSettingsActivity.java:55`。**关闭不会清除同意标记**：`AssistantSettingsActivity.java:114` 在取消勾选时只写 `putBoolean(key, false)`，把功能开关写回 false，已存的 `assistant_audio_consent`／`assistant_image_consent` 仍是 true；再次打开开关会重新弹同意框（`AssistantSettingsActivity.java:115`），但旧标记并没有被删除。**目前没有一键清除入口**，应用内也没有删除同意记录的按钮，这一项列为待建。现在能做的替代路径是：在系统设置里清除本应用数据或卸载应用，从而清掉本地偏好；清单关闭了应用备份（`AndroidManifest.xml:34`）。麦克风权限只在点「同意并开启」后才申请（`AssistantSettingsActivity.java:119`），你也可以在系统设置里随时撤销。以上权利说明不构成法律意见，需律师复核。

## 五、第三方与数据留存

本地保存的内容包括：两个同意布尔值与功能开关（`AssistantSettings.java:14`、`AssistantSettings.java:15`）、一个安装标识（`AssistantSettings.java:19`，首次使用时生成，见 `AssistantSettings.java:35`）。安装标识会作为 `X-SenseField-Installation` 请求头发给画面服务（`AssistantGatewayClient.java:177`）。语音提问的原始语音只在手机内存里临时处理，不上传、不保存音频文件（`AssistantSettingsActivity.java:49`）；首次开启会从听野 CDN 下载约 153 MiB 语音资源，之后离线识别（`AssistantSettingsActivity.java:49`）。画面理解会把问题文字、当前画面（Base64）与会话/帧等元数据发到配置的画面服务（`AssistantGatewayClient.java:164`、`AssistantGatewayClient.java:176`）；明确要求比较近期变化时，最多再加两张近期缩图（`AssistantSettingsActivity.java:56`），缩图走 `context_frames` 字段（`AssistantGatewayClient.java:169`）。近期画面只缓存在内存（`AssistantSettingsActivity.java:56`），诊断截图由独立设置管理（`AssistantSettingsActivity.java:56`）。服务端点必须是 HTTPS（`AssistantSettings.java:49`）。本地偏好会保留到你在系统里清除应用数据或卸载应用；服务端保存多久、是否用于训练，代码里没有答案，需向服务提供方确认并请律师复核。第三方服务按其条款处理收到的数据（`AssistantSettingsActivity.java:56`）。

## 六、无障碍呈现

同意弹窗是系统 `AlertDialog`，有标题、完整说明和两个按钮（`AssistantSettingsActivity.java:115`、`AssistantSettingsActivity.java:116`、`AssistantSettingsActivity.java:121`），复选框文字为 18sp（`AssistantSettingsActivity.java:103`），弹窗按钮做了统一填充样式（`AssistantSettingsActivity.java:123`）。语音资源准备状态用 polite 级无障碍实时区域播报（`AssistantSettingsActivity.java:51`）。助手小圆点面板用文本视图显示状态与历史（`AssistantOverlay.java:34`、`AssistantOverlay.java:265`），高亮提示窗另带只给读屏用的标签（`Match3HintOverlay.java:80`）。**现状的不足**：没有首屏分层要点，所有说明塞在一个长弹窗里；没有字号缩放、焦点顺序或读屏听感的真机验证；弹窗用「取消」而不是「不同意」，两个按钮是否同样显著也没有真机确认。本文档本身可按标题分节阅读，但读屏把八节念出来是否听得懂，本机无设备，未验。

## 七、理解度自检

下面是纸面自检，不是应用内强制测验；应用内目前没有理解度校验（见第八节）。请先自己回答，再看答案。

**题 1：语音提问和画面理解是同一个同意吗？** 正确答案：不是，两个独立开关、两次独立弹窗，分别对应 `assistant_audio_consent` 与 `assistant_image_consent`。答错怎么办：回到第二节，并在设置页分别找到「语音提问」和「画面理解」两个开关重读弹窗。

**题 2：关闭语音提问后，之前点过的同意会被清除吗？** 正确答案：不会；关闭只把功能开关写回 false，同意标记仍保留，见 `AssistantSettingsActivity.java:114`。答错怎么办：重读第四节，并记住再次打开开关会重新弹同意框，但旧标记仍在。

**题 3：开启画面理解后，什么会被发到云端？** 正确答案：问题文字与当前共享画面，明确比较近期变化时最多再加两张近期缩图；画面里若有聊天或通知，也可能一起发送。答错怎么办：先关闭画面理解，再重读第二节与第五节。

**题 4：现在应用内有一次清除全部同意记录的入口吗？** 正确答案：不能，目前没有一键清除入口；只能关闭开关，或在系统设置里清除应用数据/卸载应用。答错怎么办：重读第四节与第八节，不要把「关闭开关」当成「删除记录」。

**题 5：不开 AI 助手，本地敌人提醒还能用吗？** 正确答案：能，本地敌人提醒独立运行，关闭助手不影响它，见 `AssistantSettingsActivity.java:34`。答错怎么办：重读第一节与第三节。

**题 6：听野是医疗器械吗？** 正确答案：不是；它不做诊断或治疗，也不承诺识别全部信息。答错怎么办：重读第一节与第三节，并把本文的「需律师复核」一并记住。

## 八、现状与待建（如实）

**已知缺口（至少六条）。** ① 同意记录只有两个布尔值（`AssistantSettings.java:14`、`AssistantSettings.java:15`），没有时间戳、没有文档版本号、没有同意范围。② 没有首屏分层要点，说明是一整段长弹窗（`AssistantSettingsActivity.java:115`）。③ 没有理解度强制校验；第七节只是纸面自检，应用内没有答题或拦截。④ 弹窗用「取消」而不是「不同意」（`AssistantSettingsActivity.java:121`），两个按钮是否同样显著未经真机确认（样式统一见 `AssistantSettingsActivity.java:123`）。⑤ 没有应用内清除或删除同意记录的入口；关闭开关不会清除同意标记（`AssistantSettingsActivity.java:114`）。⑥ 没有同意审计日志、导出或撤回记录；何时同意、同意的是哪一版，都查不到。⑦ 读屏实际听感、字号缩放与焦点顺序未在真机验证。⑧ 服务端留存期限与第三方条款在代码里没有答案。

**待建清单（不少于四项）。** A. 同意记录增加时间戳、版本与范围；B. 首屏分层要点，把「读什么、发什么、怎么撤回」拆成可读的三段；C. 理解度强制校验，答错不得开启；D. 把「取消」改成「不同意」，并做两个按钮同等显著的真机验证；E. 增加应用内清除同意记录与撤回审计；F. 与服务提供方确认留存期限并请律师复核条款。

**一句话口径：** 能验的都在本文写着的 `文件:行号` 里；没有的，本文写着「现状没有」或「待建」。这是黑客松参赛项目，非官方工具，不替代法律意见。
