# 原型验证状态（更新于 2026-10-04）

## 2026-10-04 公开发布 0.3.8

已按用户要求发布为 [GitHub 0.3.8 公开体验版](https://github.com/ld0574/SenseField/releases/tag/v0.3.8)（普通 Release，Latest）。标签 `v0.3.8` 指向 `408f32a44a7ff58c5d69c5be8efa3d8d352afa9f`；APK 沿用 2026-10-03 的交付，未重新构建。实际版本为 0.3.8 / versionCode 16、minSdk29、target35、arm64-v8a；v2 签名有效，与既有体验包同为 Android Debug 证书。

附件包含 APK、安装 ZIP、使用说明和 APK SHA-256 校验文件。APK 为 19,780,113 bytes，SHA-256 `86f026fe253f0d2c6ce37787cfc7e7d75d443785d9005bb46b29a2e823c58e6f`；GitHub 附件大小和摘要已与本地核对。安装 ZIP 仅含同一 APK、校验文件与更新后的玩家使用说明；未上传原始诊断或游戏截图。公开发布不改变 `verified=false` / `release_ready=false`，本版真机温升、实际发声时延和长时稳定性仍待验证。

## 2026-10-04 0.4.1 CDN 同版本修订（工程验证完成，待手动上传）

按用户要求保持 0.4.1/code18，改从 `https://888413.xyz/apk/latest.json` 读取版本清单，直接下载同源 CDN APK；不再使用 GitHub 更新源。同版本名称和安装序号的包以 SHA-256 区分修订，避免相同文件反复提示。CDN 原 APK 的真实下载核对成功，清单尚返回 404；本轮包由用户手动覆盖上传。JVM 212 项、相关 Python 28 项及 Android 专项 7 项通过；应用内系统 UPDATE 已完成同 code18 覆盖安装，更新后指纹/no-op 另 1 项通过。新包摘要为 `88992f7ef9323ad58558fcf5b4ee8ff8ad384f8e7405a567beaeb2389be8539d`，与原始 `b2a4...` 构建分开记录。测试范围和最终制品见[CDN 修订记录](APP_UPDATE_CDN_0.4.1_2026-10-04.md)。整体 `verified=false`、`release_ready=false`，玩家、温控和实声门禁仍待完成。

## 2026-10-04 0.4.1 首次 GitHub 更新器构建（历史记录）

当时 GitHub `Latest` 为 0.3.8 普通 Release 体验版；该轮源码配置为 0.4.1 / Android `versionCode 18`。0.4.1 自动更新专项验证已完成，本地 arm64 Debug 候选 APK、说明和 SHA-256 已生成并核对，但 0.4.1 尚未发布，整体 `verified=false`、`release_ready=false`。更新源默认是 GitHub `/repos/ld0574/SenseField/releases/latest`；每次冷启动最多自动检查一次，用户可关闭自动检查或手动重试。用户确认后才下载到应用私有目录，完成字节数、SHA-256、包名、版本和同签名检查后交给 Android 系统安装器；首次安装可能需要来源安装权限。游戏辅助运行中不开始下载或安装，更新失败不阻断本地辅助。更新元数据不包含画面、语音或助手凭据。

JVM 全套 217 项（含 updater 新增 13 项）、Python fixture 子集 13 项、全 Python 套件 543 项通过/1 项跳过、Android updater instrumentation 4 项通过；arm64 Debug build 和 lint 通过。GitHub stable Release 解析器使用合成 stable metadata 验证；工作站对 GitHub Latest 的直接 API 请求遇到匿名 API 限流，应用显示可重试状态且游戏入口仍可用，因此没有验证该工作站上的实时 GitHub 检查或下载。Linux loopback TLS fixture 经受限 SSH 本地转发收到 3 次清单请求与 2 次 APK 下载，共传输 42,685,346 bytes，0 次 404；下载内容的字节数、SHA-256 和包信息核对通过。UI 实测从“下载并安装”开始，完成来源安装权限设置后返回应用并进入系统 UPDATE 流程；隔离的未来测试 APK 实际安装为 0.4.2 / `versionCode 19`，不是用 `adb install` 替代升级。之后 Android 14 arm64 模拟器恢复干净的 0.4.1/code18 APK 并成功完成冷启动。包内扫描确认默认 GitHub 更新源存在，未发现 fixture CA、测试端口 URL 或测试凭据。没有公开自托管更新站点，正式更新源仍是 GitHub。冷启动检查与旋转/权限设置返回行为经代码审查确认，没有专门的生命周期 instrumentation。候选状态保持 `verified=false`、`release_ready=false`。脱敏测试方法、证据及本地候选 APK 信息见[0.4.1 自动更新验证记录](APP_UPDATE_0.4.1_2026-10-04.md)，更新接口与复现步骤见[开发说明](../docs/development/app-update.md)。

## 2026-10-04 0.4.0 助手工程状态（历史候选记录）

当时 GitHub `Latest` 为 0.3.8 普通 Release 体验版（2026-10-04 只读核对），0.4.0 的源码版本为 Android `versionCode 17`。0.4.0 未公开发布，以下测试数据仅记录该历史候选，不是 0.4.1 的验证证据。合并后通过 JVM 204 项、Python 530 项（1 项跳过）、native CTest 4/4、arm64/x86_64 Debug 构建与 lint。Android 14 arm64 模拟器 12 项本地 instrumentation 通过；额外 2 项正式客户端真实 HTTPS/WSS 合成输入测试通过，未配置 fixture 的普通设备套件会跳过它们。

ASR 后端为 CPU Python FunASR；Paraformer streaming 固定快照的 Apache-2.0 许可、revision 和模型文件 SHA-256 已核对。一次合成输入“请读出当前比分”的 smoke 在 2,510 ms 后得到 final，是单一样本服务管线结果，不是 P95、识别质量或外部录像端到端延迟。外部录像 ASR final-recognition P95（目标 ≤1,000 ms）仍待测，不能判定 SLA 通过。真实 GLM 合成截图读取正确，单次 API 请求 1,089 ms；Android 正式客户端已经 HTTPS/WSS 跑通真实 GLM、固定 CPU ASR 与 generation reset。Linux 隔离 HTTPS/WSS 合成测试也通过，单次音频结束至 final 为 199 ms、GLM 网关请求为 1,804 ms，见[部署记录](../docs/development/assistant-gateway-test-deployment.md)；未测 Android→Linux 或实体麦克风，均非 P95。目标设备麦克风并发、SpeexDSP AEC 实际效果、外部实声延迟、3 组 15 分钟热/负载对照和玩家验收仍待完成。

合并的开心消消乐体验入口提供截图标定与约 1 Hz 的授权实时采样，使用颜色矩阵枚举可形成三连的相邻交换并播报坐标；另有需单独配置渠道凭据的 Jev 示例判定。识别依赖手动棋盘标定与启发式颜色采样，未证明实局准确率或特殊棋子规则完整性，也不会替玩家操作。合并时修复了消消乐绕过声音设置、旧投影关闭新会话、节流前未排空 ImageReader 与截图位图未回收问题，两款游戏采集互斥。王者荣耀端的实验性空间声像、距离触觉与两字方位选项默认关闭。连续语音路由门槛为 Android 10/API 29+ 的本应用静音 `USAGE_GAME` 探测轨道；BLE 路由识别从 API 31 起可用。用户须先确认游戏声在耳机中，其他录音活动或未知/失败路由时暂停上传；耳机并发、回声与静音探测负载仍待真机验证。详细边界见[0.4.0 工程候选说明](../docs/releases/0.4.0/RELEASE_NOTES.md)与[0.4.0 Goal](GOAL_0.4.0_2026-10-03.md)。

## 2026-10-03 本地候选0.3.8：两局热反馈后的降载

当前源码0.3.8/code16，公开Release仍为0.3.5。0.3.7同机两局分别6:05/8:25，仅间隔约21秒，电池温度37.4→41.3及42.2→43.9°C；系统thermal均为none、均未充电。缩略复制平均约4ms，但native帧处理平均296/317ms。第二局270.980秒才进入旧WARM，用户反馈烫手。见[两局反馈](HEAT_RETEST_0.3.7_2026-10-03.md)。

本轮电池WARM/HOT介入提前到40/42°C、退出39/41°C，加大热状态完成后休息；识别重置保留同会话冷却时钟和设备样本水位。padding沿用推理ncnn Option，通知明确展开后标记，并直接显示热状态提示。163项JVM、构建/lint、Android13 arm64模拟器4项instrumentation及96组合成预处理一致性验证通过。模型、识别阈值、提示规则、500ms观察预算和事件截图窗口未改。采样变慢可能增加提醒延迟、漏掉短时事件，实际温升和提醒效果仍需真机验证；没有宣称降温通过。交付及证据边界见[0.3.8记录](NEXT_VERSION_0.3.8_2026-10-03.md)。

交付后用户反馈“这次感觉没啥问题，发热情况体感是好一点了”，记为0.3.8本次整体体验无明显问题、发热体感改善。本次未附新日志或测试时长，尚不能量化温降、确认实际发声延迟或长时稳定性通过。保留0.3.8作为下一轮体验基线，继续观察连续几局与视障玩家真实体验。

## 2026-10-03 本地候选0.3.7：复制性能续阶段

当前源码0.3.7/code15，公开下载仍为0.3.5。direct buffer诊断复制改为单次JNI填充，保留Java fallback；YOLOX通过stride-aware ROI resize省去临时RGBA crop。9个模型/profile等assets与0.3.6逐字节一致，native库仅JNI库变化；模型、阈值、采样窗口、提示频率和热档策略未改。

160项JVM、构建/lint、4项Android14 arm64模拟器instrumentation通过；96组合成ncnn预处理tensor bit-identical。新的host复制测试含5,000组随机对照，ASAN/UBSAN通过。最终基准断言对应尺寸JNI复制成功，模拟器480/960px全屏缩略图P50为0.364/1.406ms，Java对照为2.731/10.678ms；仅是合成复制微基准，不证明真机降温或游戏FPS。诊断对照工具保留重复来源、条件差异、中断和缺失图片等信息，不能代替完整独立实测。交付与复现见[0.3.7记录](NEXT_VERSION_0.3.7_2026-10-03.md)。

## 2026-10-03 本地候选0.3.6与评审补强

该轮源码 `versionName=0.3.6`、`versionCode=14`，公开下载仍为0.3.5。本轮优化诊断像素读取、增加开始前左右短音试听；模型、profile、native、近区提示规则、500 ms观测预算与热档策略保持0.3.5。新增离线ZIP报告及匿名玩家试用记录工具，修正QUEUE_REPLACED审计词表与近区实声CSV测量类型。模型接入记录已补齐v6 512，35项历史需求对照见[Goal记录](SCORE_RECOVERY_GOAL_2026-10-03.md)。

Android JVM 160项全部通过，`assembleDebug`、`lintDebug`通过。APK实际版本14/0.3.6、minSdk29、target35、arm64-v8a已核验；v2签名有效，证书与0.3.5一致；11个assets/native条目逐字节一致。候选：`output/releases/0.3.6/sensefield-0.3.6-arm64-v8a-debug-candidate.apk`，SHA-256 `ad6baabf4856ae3d80442c29ddaa2f4d3177b43f008f6828dd3bec681c685662`，19,445,501 bytes。本轮没有连接实体设备，因此没有新的手机温升、真实声音、震动或患者效果结论。复制host微基准差异小，不能当作真机降热依据。

工具的集成验证与交付索引见[0.3.6记录](NEXT_VERSION_0.3.6_2026-10-03.md)。严格最终门槛未降低，`verified`、`release_ready`不变；video9/video12继续封存。500 ms试用实声目标、500 ms观测预算与严格250 ms/8 FPS最终门槛分开；近区报告也不代替旧schema的完整最终验收。

## 2026-10-02 当前公开体验版 0.3.5

当前 `versionName=0.3.5`、`versionCode=13`。0.3.4 的单帧补拍与 10 秒背景截图不足以还原瞬时视觉根因，本版添加有界事件前后采样：常规缓存最多约 2 组／秒、前 3 秒／7 组，后窗约 2 秒，重叠延长最多 5 秒；自动新片段起点间隔至少 10 秒。保留清晰当帧并优先排队，通知加入「标记问题」。跨会话图片任务上限 8 组／16 MiB，沿用 60 MiB／20 分钟／3 局保存上限；关闭画面保存、暂停／旋转／结束清除缓存，取消与缺失状态可查。

模型、500 ms 观测预算、近区范围／提示频率和 0.3.4 的热档处理间歇保持现有行为。JVM 153 项、`assembleDebug`、`lintDebug` 已通过；交付后已取得首份真机复测，长时发热与真实提醒时效仍待同机验证，不能把缓存有界当作不发烫的结论。0.3.4 局内 03:07 的误报仅匹配到触发依据，尚未确认根因，本版没有宣称修复。具体策略、链路核验与交付见[0.3.5 记录](DIAGNOSTIC_CONTEXT_0.3.5_2026-10-02.md)。下一次含新改动的交付为 `0.3.6`。

本地 Debug candidate：`output/releases/0.3.5/sensefield-0.3.5-arm64-v8a-debug-candidate.apk`；SHA-256 `475b5cccf6102a339e7c2da4f84298e703711459102bd2d36b6b0976bddb3239`。v2 签名有效，与旧包同签名；模型、profile 和原生库与保留的 0.3.4 逐字节一致。已于 2026-10-02 按用户要求发布为 [GitHub 公开体验版](https://github.com/ld0574/SenseField/releases/tag/v0.3.5)（普通 Release，并设为 Latest），标签 `v0.3.5` 指向 `b40449c8df04abd37a99a63cbe75002a4d2e635d`。APK 未重新构建，GitHub 的大小和 SHA-256 与本地一致；公开安装 ZIP 仅含同一 APK、校验文件和玩家使用说明。原始诊断和游戏截图未上传，公开发布不代表稳定性验收通过。

交付前 Android 14 arm64 模拟器已覆盖升级并通过悬浮窗／截屏授权，采集服务已启动；AVD 未提供横屏帧，当时事件前后图片未核验。随后首份 0.3.5 真机日志确认 9 个自动窗口、2 个额外窗口限流，11 个触发当帧及全部 107 个排队图片任务均成对保存，无队列丢弃。电池温度 38.2→40.8°C；同步缩略复制 658 次累计 23.003 秒，平均 35 ms，完整处理墙钟窗口占比 74.2%，起始条件不同，不能推导版本降温。用户最初报告约 04:35 左上／实际右上，后补充可能是敌人移动后才看到，因此记为方向体验待核实；最接近左上记录截图为 04:31，同一候选随后约 2 秒内经上方变右上。敌方平滑坐标与即时自身位置跨扇区的滞后线索已记录，尚不据此调参或新交付 APK。人工标记、图片关闭和 WARM/HOT 新事件仍待复测。详见[0.3.5 首份复测](HEAT_RETEST_0.3.5_2026-10-02.md)。

## 2026-10-02 0.3.4 降负载与首份复测（历史）

当前 `versionName=0.3.4`、`versionCode=12`，用于严重发热的降负载复测。队友反馈 0.3.3 操控预警明显改善；新日志约 8 分 26 秒内电池温度 42.6→45.7°C，native 同步墙钟覆盖率约 92.1%，另保存 225 对截图。当前处理完成后按耗时与热档留出间歇，定期诊断截图 2→10 秒，补充充电与负载诊断字段，保持模型、500 ms 观测预算与提示规则。

JVM 142 项、`assembleDebug`、`lintDebug` 和 `git diff --check` 通过。随后取得首份 0.3.4 同机日志：约 8 分 9 秒，未充电，电池温度 39.2→43.1°C，native 墙钟占比 92.1%→71.7%，截图 225→49 对；11 次近区提示全部被接受，摘要无播放失败。队友反馈发热明显改善、双手操作正常，另一次「附近没有敌人却提示」已确认是局内 03:07 的「右上有敌人」，对应 cue 5 / frame 620。该帧主画面未见敌方英雄，小地图有对应红圈；未发现陈旧 track 或长时语音排队参与，但自身／敌方图标身份与暂定近区范围仍需核实，不能把新鲜头像像素当作活敌真值。起始温度比上一局低 3.4°C，不能把最高温差当作版本降温幅度；11 次提示均在进入 WARM 前，热档提醒时效、真实感知和至少 15 分钟持续体验仍需补齐。本轮保留 0.3.4，不再调整处理间歇或提示频率，补充误报证据与范围标定步骤。详见[首份复测分析](HEAT_RETEST_0.3.4_2026-10-02.md)、[构建与策略记录](HEAT_LOAD_0.3.4_2026-10-02.md)和[首批试用计划](PLAYER_PILOT_PLAN_2026-10-02.md)。下一次含新改动的交付为 `0.3.5`。

本地 Debug candidate：`output/releases/0.3.4/sensefield-0.3.4-arm64-v8a-debug-candidate.apk`；SHA-256 `53b5f66ea59caff0f5f51d739a50b6da158be43311d13f82c9d734c2b7a9ddec`。APK 实际版本、v2 签名、500 ms profile 与模型哈希已核验；未对外发布。

## 2026-10-02 0.3.3 操控预警修复（历史）

当前 `versionName=0.3.3`、`versionCode=11`，用于操控期间漏报排查。队友日志中识别耗时 P50 281 ms，原 250 ms 预算过滤了 75.7% 原始观测；双类近区 profile 改为 500 ms，与近区新鲜度上限一致，仍保留采集时间并拒绝更旧结果。近区回归 21 项与原生 CTest 3/3 通过，APK 构建和 lint 成功。后续队友反馈双手操控预警明显改善；新 0.3.3 ZIP 有 12 次提示被接受，无播放失败。日志没有触控时刻，播放请求也不证明实际可感知，仍需量化事件与真实发声对照。证据与 `0.3.3` APK 哈希见[漏报排查记录](TOUCH_ALERT_DIAG_2026-10-02.md)。下一次新交付为 `0.3.4`。

## 2026-10-02 0.3.2 诊断恢复（历史）

当前 `versionName=0.3.2`、`versionCode=10`：周期检查点已独立于处理帧和识别锁，竖屏准备／暂停期间仍保存设备与最后状态；新进程会恢复异常会话摘要并清除旧运行标志，保留原检查点，不编造结束时间。密集战斗只比较实际近区事件，约 800 ms 窗口到期后清除旧赢家，避免旧高分持续压制新语音。自动 profile、250 ms 画面过期预算、标准语音／短音／双震动和提醒释义保持现有行为。

JVM 132 项通过，`assembleDebug`、`lintDebug` 通过，仅剩既有 mipmap 警告；Android 14 arm64 模拟器验证了周期检查点、通知暂停／继续、横屏截图、强制停止恢复及 ZIP 保存。导出包包含 152 条帧事件、66 张关联截图、42 个检查点和异常摘要，均为应用页面链路证据。相关 Python 测试 6 项与公开仓库检查通过，详情见[0.3.2 改造记录](NEXT_VERSION_0.3.2_2026-10-02.md)。下一次含新改动的交付为 `0.3.3`，共用[版本命名规范](../docs/releases/版本命名规范.md)。

本地 APK：`output/releases/0.3.2/sensefield-0.3.2-arm64-v8a-debug-candidate.apk`；SHA-256 `00441e8a51c8e8751e6f8ac97b55bdf8cc991fe2fdffce7c6728c93357dee957`。这是 Debug candidate，未发布 Release、未创建 tag。本轮未连接实体手机；漏报／误报、实际听感、震动感知、实际发声延迟和发热门禁仍待独立验证。

## 2026-10-02 0.3.1 提醒说明（历史）

已提交上一轮近区调度与诊断改造，新增开始前 TTS 提醒说明：解释当前开启的短音、方向语句和震动含义，穿插输出示例；可跳过、在设置重听或关闭。页面采用大字和可滚动的大按钮，停止／离开会清理播放，说明不启动截屏或识别。详细流程和证据见[提醒说明与试听记录](REMINDER_GUIDE_2026-10-02.md)。

当时 `versionName=0.3.1`、`versionCode=9`，内部和外部版本统一遵守[版本命名规范](../docs/releases/版本命名规范.md)。JVM 113 项通过，`assembleDebug`、`lintDebug` 通过；Android 14 模拟器验证了开始说明、跳过授权、设置重听与 150% 字体布局。模拟器缺少中文 TTS，未验证真实发声、耳机或震动感知。

该版本 APK 已留存于 `output/releases/0.3.1/`；SHA-256 `3656c70ce14fda17bbe2a5b514c589d4c9a94b2e434cea8f19a9046d260c3537`。这是 Debug candidate，未对外发布；证据与哈希保留原样。

## 2026-10-01 最新真机与团队测试版

诊断版 `0.3.0-alpha.1-diagnostics` 已加入应用内“测试记录与反馈”：队友无需另开系统录屏即可导出每局 ZIP。记录字段和截图关联见[Android 诊断记录说明](ANDROID_DIAGNOSTICS_2026-10-01.md)；新的诊断 APK、校验和与队友操作步骤位于 `output/releases/0.3.0-alpha.1-diagnostics-20261001/`。

完整一局记录已取得（含准备约 11 分 34 秒）：18 次方位语音完成，测试者实际听到并反馈方向正确、好用；报告 1～2 次疑似误报及没有震动。系统证实 18 次震动被按触摸反馈设置忽略。最新包改用无障碍震动、两次短震动，增加测试按钮；定位状态不再发声，关闭方位语音时附近敌人用双音提醒。

Release 构建和 lint、JVM 93/93 已通过；最新包已安装。按用户要求以 0.3.0-alpha.1 GitHub pre-release 交给队友继续测试，沿用既有 Android Debug 证书并明确标识。原始日志不公开。平均横屏处理 5.13 FPS、最大处理间隔 24.964 秒，尚未达到最终连续运行门槛；R_enter 标定、误报／漏报逐条真值、实际发声延迟、发热对照和目标玩家试玩仍未完成。[真机记录](ANDROID_LIVE_SMOKE_2026-10-01.md)与[发布说明](../docs/releases/0.3.0-alpha.1/RELEASE_NOTES.md)保留各包证据边界。

## 0.3.0-alpha.1 GitHub developer preview 边界

发布目标已切换为 `versionName 0.3.0-alpha.1`、`versionCode 7`。这是可审查的 pre-release developer preview，不是最终验收或稳定发布版本。内置 HD 小地图模型仍是实验候选，尚未通过独立留出对局验收；开发集结果和严格跨运行时差异不得写成独立成绩。主画面边缘复核批次已完成人工复核（160 张：47 `corrected`、113 `negative`、114 框），完成审计见 `data/private/main-edge-review-v1/review-batch-v1/review-completion-audit.json`。这些红色候选和困难负样本只用于诊断，不能作为 `enemy hero` 真值；主画面边缘候选分支在公共默认 profile 中关闭，上下文分类器尚未接入，发布版不启用。

当前 APK 只构建 `arm64-v8a`，最低 Android API 为 29、目标 API 为 35。`MediaProjection` 只在用户明确授权后获取整屏帧；`SYSTEM_ALERT_WINDOW` 只用于可选的非交互悬浮提示层；`POST_NOTIFICATIONS` 用于前台截屏服务的运行状态和健康提示。屏幕帧、模型推理和事件筛选均在设备本地完成，不上传画面，也不读取游戏进程或内存。`assets/profile.json` 仍是冻结的 320 单类 `minimap_enemy` 基线；2026-10-01 起 APK 另含双类 v6 512 近区实验 profile（`profile-dual-512-near-zone.json`），本地权重存在时作为实验默认，缺失时自动回退基线，候选均未通过最终验收，当前预发布仅供团队测试。

发布准备脚本为 `scripts/build_android_preview.sh`：四个签名环境变量全部提供时才构建签名 candidate，缺少签名参数时只构建并核验文件名含 `debug-candidate` 的 Debug APK，并沿用 Android Gradle 的标准 debug signing。脚本只读取已有发布 keystore，不生成或上传发布 keystore，不发布 GitHub Release。候选文件、权限文案和发布限制见 [Release notes 草稿](../docs/releases/0.3.0-alpha.1/RELEASE_NOTES.md) 与 [检查清单](../docs/releases/0.3.0-alpha.1/CHECKLIST.md)。

> **当前权威规则（HD-only）**：只使用人工复核后的 HD 框作为真值。当前 bootstrap v2 split 为 video1+8+3+4+5+10 train（664 图／1211 框）、video2-HD+11 val（230 图／400 框）；bootstrap test 仍为空，不能作为 bootstrap 最终成绩。当前部署 confidence 为 `0.67`；训练阶段使用的 `0.49` 只保留为历史结果。video13 的 130/130 个任务已人工复核（110 `corrected`、11 `negative`、9 `excluded`、0 pending/lease），并完成 Codex temporal audit；此前的 COCO audit 无 blocker，形成 121 个可评测图／211 框。Android 14 首轮真机反馈后查看了 video13 的多档 confidence，因此它现在属于模型辅助的跨来源开发诊断，不能再称为独立 test 或进入最终门禁。video4-HD、video5-HD、video10/11 已完成复核并通过 ROI/provenance 审计；video7-edge 仍待复核。`video11` 是 HD dev-val，`video10` 已进入 train；`video12` primary sealed holdout、`video9` cross-source sealed holdout，继续封存且未读取。Hero 只用于 UX／事件故事。低清 video2–6 及其队列、模型、指标和旧 checkpoint 均已退役；未审核机器框不得作为真值。

下表保留旧实验结果供审计；凡依赖低清 video2–6 的队列、模型和指标均已退役。下列 video6 检测 P/R/F1 与方向事件数值来自 legacy fixed-ROI crop，只是历史内部开发对照；已发现的边界缺口意味着它们不代表完整小地图覆盖或召回。

## 已验证

| 项目 | 结果 |
| --- | --- |
| 桌面共享 C++ 引擎与录像回放 | 2026-09-30 全量 `.venv/bin/python -m pytest -q`：431 项通过、1 项条件跳过；标注网页 Node 测试 12 项通过。覆盖完整 GameProfile 定位读取、小地图自动定位、显示方向抽帧、三层 ROI 传递与门禁、多类别标注与数据导出、YOLOX 多类别解码与 metadata contract、红环几何过滤、空间跟踪、冷却与抢占、主动学习批次保护、冻结模型与 Android 资产哈希、会话日志、最终门禁及 ncnn 回放约束。跳过项需要专用合成 YOLOX profile。 |
| 小地图检测数据准备 | 当前 HD bootstrap v2 split 为人工复核 train 664 图／1211 框（video1+8+3+4+5+10）和 val 230 图／400 框（video2-HD+11）；bootstrap test 仍为空，不能报告 bootstrap 最终成绩。video13 已完成 130/130 个任务人工复核和 Codex temporal audit，121 图／211 框保留为跨来源开发诊断。首轮真机反馈后查看多档阈值，c=`0.67` 为 TP/FP/FN `192/16/19`、P/R/F1 `92.3077% / 90.9953% / 91.6468%`；因此 video13 已被开发使用，不能进入独立门禁。video9/12 继续封存且未读取。 |
| HD bootstrap v2 训练候选（仅开发） | 从 video4/5 扩充候选初始化，在 MPS、320 输入、batch 16、seed `20260930`、`lr_scale=0.25` 下最多训练 12 轮；best epoch 8，约 337 秒。首轮真机反馈后改用 confidence `0.67`；Android 同款 ncnn 开发 val 的 TP/FP/FN 为 `353/15/47`，P/R/F1 为 `95.9239% / 88.2500% / 91.9271%`。video2-HD、video11 和 video13 均已参与开发判断，不是独立成绩。ONNX/TorchScript/ncnn 已导出；230/230 张图的检测数量一致，但严格 raw／坐标 parity 仍失败。实验 Android assets/profiles 已绑定 c067，候选保持 `verified=false`、`release_ready=false`；内置 HD profile 在首装默认启用识别与新头像提醒，用户可关闭。 |
| 端到端延迟统计工具 | 已能从外部记录的证据／实际发声配对时间计算逐类及总体 P95，并单列漏提示；首轮真机冒烟有帧处理和播放日志，但尚无外部屏幕／实际声音配对的延迟样本 |
| Android 0.3.0-alpha.1 Debug candidate | 2026-09-30 使用 Android Studio JBR 与本机 SDK 运行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`：65 项 JVM 单元测试、Debug APK 构建和 lint 全部通过；native CTest 2/2 通过。随后用 `scripts/build_android_preview.sh` 生成 `sensefield-0.3.0-alpha.1-arm64-v8a-debug-candidate.apk`，大小 17,156,242 bytes，SHA-256 `49043142158a360abb7bb3c5d8c9c7ed24828374f67bdb888e9eedcb445a6b0a`。这是 Debug candidate，不是正式 release 签名包。 |
| APK 结构（0.3.0-alpha.1 候选） | `aapt dump badging` 确认包名 `com.openkhub.sensefield`、versionCode 7、versionName `0.3.0-alpha.1`、minSdk 29、targetSdk 35；APK 仅含 `lib/arm64-v8a`，并包含与 tracked metadata 哈希匹配的本地 YOLOX `.param`/`.bin`。 |
| APK 签名（0.3.0-alpha.1 候选） | `apksigner verify --verbose --print-certs` 通过：APK Signature Scheme v2 = true，1 个 Android Debug signer，certificate SHA-256 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`。 |
| Android 会话健壮性 | Android 14 首轮实体机（应用 0.2.1 debug）已跑通 MediaProjection、2712×1220 横屏、ncnn 与真实播放。观测到 8 次 35–1084 ms 的短暂断流且均自动恢复；恢复语音造成额外干扰，现已改为仅日志与通知。该日志缺少最终 `SessionSummary`，不能作为正式会话验收。当前开发候选为 0.3.0-alpha.1（versionCode 7），仍需重新跑实体机会话。 |
| 小地图新目标提示 | 首轮实体机暴露 `vision_memory=false` 时 kind 2 仍走旧直通路径的错误：44 条提示在约 234.6 秒内播放，P50 间隔 5.058 秒，用户确认过密且至少一次方向误报。测试后 shared preferences 显示 `vision_memory=false`、`cue_preset=standard`、`allow_experimental=true`，与日志中的旧直通路径一致。修复版禁止 kind 2 绕过轨迹层；关闭时完全静默，开启后仅对稳定 `APPEAR` 事件使用中性短提示，会话级最短间隔为 15 秒，不再播报地图中心方位或 `DISAPPEAR`。修复版待真机复测。详见 [首轮真机冒烟](ANDROID_LIVE_SMOKE_2026-09-29.md)。 |
| 玩家自身小地图标记探索与安全双类 v8（v6 512 导出候选，未发布） | 首轮 218 张与第二批 120 张已完成并安全合并：338 个 terminal，其中 276 `corrected`、61 `negative`、1 `skip`；337 个可训练，901 个 `pending`。v2 仍是 `review_aid_non_release` 复核辅助器，MPS、seed `20260930`、25 轮、batch 96，阈值 `0.81` 的 pooled val（video2/11/13）为 TP/FP/FN/TN `84/4/9/32`，P/R/F1 `95.4545% / 90.3226% / 92.8177%`，中心误差均值/P95 `4.3656/12.8733 px`。安全双类 v8 为 323 图／840 框：train 194/512、val 129/328，enemy 575、player 265，test 为空。当前最好 v6 512 微调候选为 epoch 6、阈值 enemy/player `0.57/0.59`；player TP/FP/FN `84/4/9`，P/R/F1 `0.954545/0.903226/0.928177`，visible recall `0.903226`（84/93），中心 P95 `6.289928 px`（相对 `0.017083`），三项 player development gate 均通过；enemy P/R/F1 为 `0.953488/0.872340/0.911111`。v5 416 对照的 player visible recall 为 `0.860215`。绿色 annulus 在 pooled val 虽达到 `0.913978`，但三折留出来源验证不稳定，因此不接入 Android。v6 512 双类已完成 ONNX/TorchScript/ncnn 导出，产物和报告位于忽略目录 `build/ignored/v6-512-export`：ONNX 严格 raw gate 失败（最大误差 `0.0006387` > `0.0005`），但 12 张图的最终 detection arrays 全部一致；ncnn Android 等价预处理的严格 gate 通过（raw 最大误差 `0.0004534`、检测值最大误差 `0.0056153`、计数全部一致）。2 线程桌面 preprocess+inference P95 为 `28.9552 ms`。导出当天未改 Android assets/profile；2026-10-01 以实验近区 profile 接入 APK（见下方近区关系层条目），由于 test 为空且尚无独立测试对局或 Android 真机验证，候选仍未达到 release-ready。val 参与选模且无独立 test，只能称 development diagnostic；video9/12 未读取。详见[主动学习记录](MINIMAP_PLAYER_ACTIVE_LEARNING.md)。 |
| 小地图近区关系层（已上机诊断，完整验收未过） | 2026-10-01 按 `docs/plans/黑客松方案收敛与实施路线.md` 实现：原生 `native/src/minimap_relation.cpp`（`MA_API_VERSION` 9）由 Android JNI 与离线回放共用；以自身小地图标记为原点，按占用时段去重，用 REARM 窗口控制重复，不叠加 15 秒全局冷却；8 向扇区带 ±7.5° 滞回；UNKNOWN 持续 2 秒响低音，恢复响柔和音，低音至少间隔 10 秒且被间隔挡下时延后而不丢弃。JNI packet 升为 version 2，Java 新增 `NEAR_ZONE` 类别、连续声像和三种新提示音。双类 v6 512 以 `profile-dual-512-near-zone.json` 作为实验默认，`events.min_confidence=0.57` 与逐类最低阈值一致，避免二次过滤；`assets/profile.json` 仍是冻结的 320 单类基线，双类权重缺失时自动回退，设置中也可切回。测试：原生 CTest 3/3（其中近区 18 个合成场景）、pytest 448 通过 1 跳过、JVM 单元测试 84 项、`assembleDebug` 与 `lintDebug` 通过，APK 内 arm64 库导出新符号。video11（HD dev-val，已参与选模）桌面离线回放 16.8 分钟：证据覆盖率 91.17%，近区提示 38 次（2.26 次／分钟），其中 12 次距上一次不足 15 秒，雷达暂停 8 次，抑制 111 次（short_gap 69、rearm_pending 42），桌面每帧处理 P50/P95 36.7/45.8 ms。以上只是开发诊断：R_enter=0.20 是未标定的暂定值，提示 precision／recall 尚未人工复核，初始实现时尚无真机结果；10 月 1 日真机诊断及修复短测见本页顶部，端到端延迟与目标玩家结果仍未完成。video9、video12 未读取。 |
| 合成录像演示 | 重新构建桌面共享库并按 README 回放：84 帧、3 条提示、3/3 合成事件匹配；`build/synthetic/fixture.mkv`、`predictions.jsonl`、`report.json` 已更新，可用相同命令重现 |
| Android 13/14 模拟器 | 0.2.0 固定 ROI 回归中，两种 API 均以同一 APK 全新安装，完成 `1/5` YOLOX、整屏授权、2400×1080 横屏采集，以及暂停／恢复／停止；均观察到实际 ncnn `CueEvent`，统计 `queued=4`、`audioFailures=0`，无崩溃。可导入的自适应 profile 尚未在模拟器复测；模拟器也无实际扬声器音频。详情见 [模拟器验证](EMULATOR.md) |
| video1 低清历史开发回放（只读） | 旧低清版（720×324、约 15 分 28 秒）仅开启从同场截取的“撤退”模板，以 12 帧／秒处理 11,134 帧，得到 3 条提示，对应肉眼核对的 3 段“撤退”横幅；见 [video1 开发记录](VIDEO1.md)。旧完整 MP4 已由高清重导出版替换，不能用当前同名文件重放此历史实验；这是同场调参结果，不是留出准确率。 |
| video1 高清复核与训练纳入（开发数据） | 高清重导出 SHA-256 `bab46071fae13f9f4d63424f5492cdb931d5bc1da1384c8ed7558791200a39f8`；100 个任务已完成复核：83 `corrected`／163 框、12 `negative`、5 `excluded`、0 `pending`。95 张 trainable 帧／163 框已纳入当前 HD bootstrap train。视频参与过开发，只用于训练和回归诊断，不能作为独立留出成绩；详见 [video1 高清记录](VIDEO1_HD.md)。 |
| HD bootstrap v2 标签与 split | train 为 video1（95 帧／163 框）+video8（119／211）+video3-HD（120／268）+video4-HD（100／150）+video5-HD（100／189）+video10（130／230），合计 664 图／1211 框；val 为 video2-HD（100／197）+video11（130／203），合计 230 图／400 框。bootstrap test 为空，只能作开发辅助；video13 不进入该 split，此前的 test-only 诊断已因多档阈值检查改归开发数据。video10 为 110 `corrected`／20 `negative`，video11 为 107 `corrected`／23 `negative`；详情见[训练记录](../training/README.md)。 |
| HD 复核队列状态 | video3-HD、video4-HD、video5-HD、video10/11 均已完成复核；video10/11 共 260/260 帧、217 `corrected`、43 `negative`、433 框，SQLite 与导出 manifest 一致，没有 pending 或活动 lease，ROI／尺寸／来源录像 hash 审计通过且无可扩展 crop-edge contact，已纳入 v2 split。video7-edge 仍待复核。video13 已完成 130/130：110 `corrected`、11 `negative`、9 `excluded`、0 pending/活动 lease，121 个可评测图／211 框；review manifest、detection manifest、annotations 和 audit 哈希已冻结，最初的 test-only COCO audit 无 blocker。其后因多档阈值检查成为跨来源开发诊断，不能再作为独立 test 或进入最终门禁。video9/12 继续封存且未读取。 |
| 小地图红方头像环 | 新增不依赖具体英雄头像的 `minimap_red_ring` 检测和空间跟踪。最初 video1/video2 基线及抽查见 [video2 小地图记录](VIDEO2.md)；加入新录像后的当前结果见 [五场录像记录](VIDEO3_5.md)。所有结果都是开发数据，不是留出准确率。 |
| 组合配置 | `android-combined-development.json` 同时启用小地图红环和“撤退”模板；最新回放中 video1 为 102＋3、video2 为 141＋3 条提示。危险信号可抢占较低优先级提示，video1 三次撤退时间保持不变，主画面检测保持关闭。 |
| 五场录像扩充（低清历史数据） | 五场共处理 56,978 帧；地图结构门控移除了已确认的英雄选择／加载界面误报，最终分别输出 102/141/105/126/123 条小地图提示。150 张抽样帧已全部复核；排除 29 张非对局画面后，121 张有效帧含 190 个敌方头像框。COCO 已按整场对局导出为 train 76 张／111 框、val 45 张／79 框。所有五场均参与过规则复核，不再算独立留出数据。见 [五场录像记录](VIDEO3_5.md)。 |
| video6 历史逐帧盲测 | 60 帧已全部盲标并在解封预测时重新核对全部承诺哈希。当时 IoU 0.5 下准确率 57.58%、召回率 21.59%、方位正确率 94.44%，前两项未达门禁。后续已查看失败案例并用它选择 YOLOX 权重和阈值，所以 video6 及该轮模型、指标现仅作历史，不属于当前获准 train/dev；历史过程见 [video6 盲测记录](VIDEO6.md)。 |
| YOLOX-Nano 小地图模型（低清历史，已退役） | 600 张密集队列完成独立复核后，与旧标注合并为 749 张／1,143 框，仍按 video1–5 train、video6 val。Nano 320 最佳点为 epoch 20：precision 90.50%、recall 72.65%、F1 80.60%、几何方位正确率 96.10%；第 40 轮早停。低 Mosaic 对照为 92.02% / 67.26% / 77.72%，416 输入为 90.63% / 65.02% / 75.72%，均较差。冻结选择已导出 ONNX，12 张真实验证裁剪的最大原始输出误差 0.000223，小于 0.0005 门限。开发召回仍未达到 80%。 |
| 困难误报加权候选（低清历史，已退役） | 只用 video1–7 开发数据。旧 crop 指标与 checkpoint `f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb` 均已退役，仅留档审计；video6 曾用于选模和选阈值，不能作为独立留出。APK 公共默认 profile 关闭检测器；历史 ncnn 对照见[模型接入记录](MODEL_PIPELINE.md)。 |
| Safe-ROI 合并训练集与三轮训练（低清混合历史，已退役） | COCO 合并集覆盖 8 场：train 为 video1-hd、video2–5、video7、video8，共 1,020 张／1,650 框；video6 仅作开发 val，152 张／223 框；test 为空。video8 v3 的 119 张／211 框现已进入 train。train/val 的 COCO crop provenance audit 均为 `provenance_clear`；空 test 表示不能报告独立测试结果。低清 video2–5 占 train 476/1,020 张（46.7%）。v1 从 legacy hard-FP 权重以 `lr_scale=0.1` 微调，best epoch 10、confidence `0.75`，TP/FP/FN `84/9/139`，P/R/F1 `90.3226% / 37.6682% / 53.1646%`，第 30 轮早停。v2 从 v1 best checkpoint 以 `lr_scale=0.3` 微调，best epoch 10、confidence `0.79`，TP/FP/FN `98/9/125`，固定验证 P/R/F1 `91.5888% / 43.9462% / 59.3939%`，第 30 轮早停；未达到 80% recall 门槛。COCO full fine-tune 从官方 COCO 权重开始，第 80 轮早停、best epoch 60、confidence `0.81`；video6 固定验证 TP/FP/FN `101/11/122`，P/R/F1 `90.1786% / 45.2915% / 60.2985%`。相对 v2，precision `−1.4102`、recall `+1.3453`、F1 `+0.9046` 个百分点，recall 仍低于 80% 门槛。checkpoint SHA-256 `de276061fda434f5a480bbad6023a2ef4280568d3cee91e448a99c540142fe53`；metrics SHA-256 `4f29fff582d808f4de1af79efab898ba3d6893168b619e9992694cf909d9cddb`；固定 video6 val SHA-256 `5a3977338bbd893ce4d6196f7189017575a6d5e941bb2ad31f1dd134690d0bfd`。所有轮次均使用 video6 开发集进行模型和置信度选择，不是独立成绩；数据集 test 为空。该 checkpoint 尚未导出 ONNX/ncnn 或集成到 Android；Android ROI／权重未更新，公共 APK 默认仍关闭检测器。 |
| Safe-ROI NMS 同阈值诊断（低清历史，已退役） | 冻结 checkpoint 在 video6 development val 上对 NMS `0.4/0.5/0.6/0.7/0.8` 各做 941 点 confidence 扫描。P≥90% 的最大 recall 点五档完全相同（c=`0.807`，TP/FP/FN `103/11/120`，P/R/F1 `90.3509% / 46.1883% / 61.1276%`），门槛内无 NMS 收益。低 precision max-F1 同 confidence `c=0.419` 下，NMS `0.4` 对 `0.5` 为 P/R/F1 `79.5699% / 66.3677% / 72.3716%` 对 `79.1444% / 66.3677% / 72.1951%`，全局 `148/38/75` 对 `148/39/75`；17 个 3+ 目标帧均为 `30/3/24`、recall `55.5556%`；右侧均命中 `28/53`（recall `52.8302%`）、方向正确 `26/53`（`49.0566%`）。固定 NMS `0.5` 仅降低 confidence（`0.807→0.419`）就使密集帧 recall `27.7778%→55.5556%`、右侧 recall `32.0755%→52.8302%`；提升来自 confidence 降低，不是 NMS。完整曲线 JSON SHA-256 `8ca4df76050062fb4c831d36fe5189a37fcdd075bd6a83aa3a2edf9ee55face2`；video6 已用于模型和阈值选择，属于 development，不是独立留出；Android 不变。 |
| YOLOX + red-ring 固定规则离线原型（低清历史，已退役） | safe-ROI video6 val 152 帧／223 框；同坐标读取 frozen YOLOX crop 预测。high `.807` 直通，low `.45` 候选要求严格红 `R≥90`、dominance `1.25`、band ratio `.032`／min band `2`、至少三边且含对边，每边 3 像素、ring score `≥188`。baseline TP/FP/FN `103/11/120`、P/R/F1 `90.3509% / 46.1883% / 61.1276%`；hybrid `111/12/112`、`90.2439% / 49.7758% / 64.1618%`。密集帧（真值≥3 框）由 `15/3/39` 变为 `19/3/35`；右侧真值由 `16/9/43` 变为 `17/10/42`。工具不搜索阈值；参数此前已在同一 video6 development val 探索，因此所有分数仅为开发诊断，recall 仍远低于 80%。未接 Android、未改模型资产；没有 video9 全新真人对局结果、Android 集成／真机验证或独立门禁成绩。checkpoint `de276061fda434f5a480bbad6023a2ef4280568d3cee91e448a99c540142fe53`；annotations `6d1d4ad35e150533de85b965d4f2096ebc2c9b80442fdcb4ef249a140ef7754e`；评测脚本 `418b29ec69589982631455849cd83e045e6cfa57315ec63526d6f9f107e56122`；报告 JSON `aaa2acc7cf8dd70930a3057e782d6c9aa58fa4962b87aff10063f7900fd8387c`。复现命令和限制见 [训练记录](../training/README.md)。 |
| Safe-ROI 均衡困难样本 v1（低清历史，已淘汰） | 在上一轮 safe-ROI full fine-tune checkpoint 上继续训练：train 1,166 张／1,989 框，video6 val 152 张／223 框，test 为空；best epoch 5，25 轮早停，confidence `0.85`。固定 val TP/FP/FN `72/8/151`，P/R/F1 `90.0000% / 32.2870% / 47.5248%`。在同一 video6 development val 上追加 confidence `0.01–0.95`、step `0.001` 的 941 点细扫：P≥0.90 最大 recall 点 c=`0.848`，TP/FP/FN `74/8/149`，P/R/F1 `90.2439% / 33.1839% / 48.5246%`；max-F1 点 c=`0.348`，TP/FP/FN `154/48/69`，P/R/F1 `76.2376% / 69.0583% / 72.4706%`。同一 video6 val 的 full fine-tune 细扫基线门槛点 c=`0.807`、P/R/F1 `90.3509% / 46.1883% / 61.1276%`，max-F1 点 c=`0.419`、P/R/F1 `79.1444% / 66.3677% / 72.1951%`。相对该细扫基线，balanced-hard 门槛点 P/R/F1 变化 `−0.1070 / −13.0044 / −12.6030` 个百分点；max-F1 点变化 `−2.9068 / +2.6906 / +0.2755` 个百分点。固定结果和扫描均为 development video6 选模／选阈值诊断，不是独立成绩；实验已淘汰，不导出 Android 模型，不接入 Android。checkpoint SHA-256 `877fd5e5e451744227d82ace7644d16a0e2dd57d079418a57831ea7f6145c47b`；metrics SHA-256 `d2d87812f6d5e3a3dfd28e755a945319879f4c49287eb45a4412591d1e846905`；固定 video6 val 评测报告 SHA-256 `fd8981caf6c64f770de80a08097a1e3cd6eacdf10591d5cfe4ec1d5fd0ef877f`；细扫 JSON SHA-256 `c46ac2fdfa8f4aabc7073fa148979e7ba166c8214ac43904e40ce6f016f33a03`；底层 val annotations SHA-256 `6d1d4ad35e150533de85b965d4f2096ebc2c9b80442fdcb4ef249a140ef7754e`。 |
| Safe-ROI hard-frame-only v1（低清历史，已淘汰） | 来源 train 1,020 张／1,650 框；按 full fine-tune checkpoint 的 train 误差（c=`0.81`、IoU `0.5`）选 46 个 hard sources，仅重复一次，新增 46 条 COCO 记录／126 框。扩展 train 1,066 条记录／1,776 框，video6 val 152 张／223 框，test 为空。train/val provenance 均 `provenance_clear`；无缺图、ID 冲突或跨 split 重复。审计 47 组 train 精确重复中，46 组是有意重复，1 组为源数据中相邻 video4 帧；唯一 blocker 是无 populated test。MPS 训练 30 轮未早停，best epoch 15、confidence `0.89`；固定 video6 val TP/FP/FN `42/4/181`，P/R/F1 `91.3043% / 18.8341% / 31.2268%`。941 点细扫的 P≥90% 最大 recall 点 c=`0.861`，TP/FP/FN `85/9/138`，P/R/F1 `90.4255% / 38.1166% / 53.6278%`；safe-ROI full fine-tune 基线 c=`0.807` 为 `90.3509% / 46.1883% / 61.1276%`，差值 `+0.0746 / −8.0717 / −7.4998` 个百分点，未达到保留标准。属于 video6 开发集选模／选阈值诊断，不是独立成绩；实验淘汰，不导出 Android 模型或更新 profile／ROI／权重，公共 APK 仍默认关闭检测器。完整配置见 [训练记录](../training/README.md)。train annotations SHA-256 `6dd10f468e24bf40491893a8c0aa9208394ef0484f3e803025aa9a343130eeea`；audit `438f3ce75d45b4a483c598acf84b2f49c6333c59119dc54288f75db43ff4c053`；hard-example provenance `8cbc0d57f08b0ed0bed497b4c130dd80652742d01559ea9cb5f332c9ab0eda1d`；checkpoint `f1e3b0f08986ced267ba65be294876631e3cad333766d4accf1438292cb0cbdb`；metrics `db6e51c1c10cd556d41e000b4271f30951d331749a9c23606a14367550eafd65`；fixed val `b15875996850f0e69a02f7f4ef04b87721de170ca6dc509b894a3d3d0ade83d7`；sweep `8f92ddfa12dec4932efbe076213d65d4f5f86fe42605d6897ef4acec013f5105`。 |
| video7 扩展数据微调对照（旧低清 video6 指标，仅历史） | 这是另一项 video7 标签微调，使用 video6 开发验证：TP/FP/FN `158/17/65`，P/R/F1 `90.2857% / 70.8520% / 79.3970%`，confidence `0.43`。该 checkpoint 未接入本机开发资产；当时的本机 hard-FP 候选 `f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb` 现已退役，默认 profile 仍关闭检测。video7 AI 辅助标签仍建议抽查，详情见[video7 扩展记录](VIDEO7_EXPANDED.md)。 |
| 扩大 hard-FP 后续实验（低清历史，失败） | 从 dense checkpoint 沿用相同参数训练；150 个 hard sources（20 个纯负样本）通过重复既有已标注样本加入 188 条训练记录／283 个既有框，train 为 1,115 条记录／1,726 个框。video6 confidence `0.47` 下 P/R/F1 `90.1163% / 69.5067% / 78.4810%`；该失败 checkpoint 未接入本机开发 profile，当时的候选为 `f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb`，现已退役；默认关闭。详情见 [video7 扩展记录](VIDEO7_EXPANDED.md)。 |
| FP+FN hard-errors 对照（低清历史，失败） | 重复加入既有 hard sources 后 train 为 875 条记录／1,429 个框。video6 confidence `0.29` 下 P/R/F1 `91.3295% / 70.8520% / 79.7980%`；该失败 checkpoint 未接入本机开发 profile，当时的候选为 `f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb`，现已退役；默认关闭。最终验收和更换为独立真人对局仍待完成，详情见 [video7 扩展记录](VIDEO7_EXPANDED.md)。 |
| YOLOX Android 本机开发候选（低清历史模型，已退役） | checkpoint `f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb`、confidence `0.19`、NMS `0.5` 曾用于固定 ROI 开发；checkpoint 与指标均已退役，仅保留 parity 历史。APK 默认 profile `detectors.minimap_yolox=false`；详情见[模型接入记录](MODEL_PIPELINE.md)。 |
| video7 开发冒烟（低清旧模型结果，仅历史） | 负样本段 61.551–72.312 s：5 个人工抽样点均无真值框，新候选在 122 帧回放中 0 检测；同一 122 帧片段中旧 fixed／adaptive profile 分别产生 6／31 个检测，提示开发误报减少。正样本段 74–92 s：216 帧、501 detections/observations、4 cues，桌面处理 P95 29.938 ms；7 个人工抽样点的预测／真值框数逐点一致。均为开发冒烟，不代表独立精度或真机时延。详见[模型接入记录](MODEL_PIPELINE.md)。 |
| 小地图自动定位（旧低清定位器实验，仅历史） | 定位器搜索、确认、保持与重定位仍属于独立开发功能；旧自适应 profile 绑定已退役的 dense baseline checkpoint，不可直接导入或与其他旧权重混用。定位器 v2 的 video6/video8 指标只作历史开发诊断，不属于当前 HD 验证或独立留出。详情见[定位器记录](MINIMAP_LOCATOR.md)。 |
| video7 高分辨率历史盲测（低清模型／阈值，仅历史） | 2712×1220、30 FPS、约 8 Mbps 的人机局；预测无关均匀抽取 120 张并由两人分片盲标、三层复核，最终 111 张有效帧／189 框。模型和阈值曾在低清 video6 冻结，现已退役。历史模型固定阈值结果为 precision 61.96%、recall 60.32%、F1 61.13%；密集 320 模型为 65.57% / 63.49% / 64.52%，几何方位正确率 99.11%。有改善但未过 90% / 80% 门禁；video7 后续进入开发训练和复核，以上是历史盲测结果，不再是独立留出成绩。人机逐帧结果也不能代替真实匹配事件级和真机验收。 |
| video8 旧冻结运行（低清旧模型结果，仅历史） | 旧流程在 119 帧／215 框上的 precision 58.84%、recall 80.47%、F1 67.98%、方位率 96.86% 是受截断污染的历史 crop-relative 数值，不能作为完整地图效果或独立留出结论。旧 fixed ROI 截掉右侧头像；v2 recalibrated 队列因 crop 与全屏 manifest 坐标错配无效。v3 安全 ROI 现已完成复核和 provenance audit；独立 video8 COCO 导出仍是 119 张／211 框的 test-only 归档，但同一批图像已在合并 safe-ROI 数据中改作 train，因此 video8 不再是检测器留出集。旧冻结、hard-FP 与 Android 回放只作 same-match diagnostic；相关权重已退役，检测器默认关闭。详见 [video8 记录](VIDEO8.md) 和上面的 safe-ROI 实验记录。 |
| 多人标注网站 | 已实现原图／小地图切换、框新增／移动／缩放／删除、筛选、团队进度和自动下一张。当前全部 HD 队列在同一个 `8765` 网站顶部切换，数据库各自独立；低清历史队列不由默认命令加载。主画面边缘批次 160 张已完成人工复核（47 `corrected`、113 `negative`、114 框），完成审计见 `data/private/main-edge-review-v1/review-batch-v1/review-completion-audit.json`；这些红色候选／困难负样本只作诊断，不能作为 `enemy hero` 真值，分支默认关闭且发布版不启用。玩家主动学习首轮 218 张加第二批 120 张已合并为 338 个 terminal，剩余 901 张 `pending`。玩家队列强制一帧恰好一个 corrected 框，重新绘制时自动替换旧建议，避免把错建议和正确框一起保存。页面显示当前批次剩余数并明确机器建议不是答案。SQLite WAL、15 分钟租约和乐观锁保护多人写入。 |
| 主画面边缘复核批次 | 160 张已完成人工复核：47 `corrected`、113 `negative`、114 框；完成审计文件为 `data/private/main-edge-review-v1/review-batch-v1/review-completion-audit.json`。这是红色候选／困难负样本诊断，不能作为 `enemy hero` 真值；小兵、野怪相似血条和镜头漂移仍需上下文分类处理。主画面边缘分支默认关闭，发布版不启用。 |
| 小地图逐框诊断 | 150 张开发样本已全部复核并以 IoU 0.5 评测。原始 v3 建议框在 121 张有效帧上为准确率 58.43%、召回率 54.74%；加入小组件过滤和相邻头像分框后，用当前原生库重新推理同一批帧得到准确率 71.07%、召回率 59.47%、匹配框平均 IoU 76.98%、可判断方向正确率 97.80%。该分层抽样覆盖的五场录像全部参与过开发，只用于回归诊断，不能代替独立留出事件评测。 |
| 最终验收门禁 | `mapassist.validation_gate` 从独立留出预测／标签和真机延迟 CSV 重算指标，验证逐帧 CFR 时间轴、回放 provenance、冻结 ncnn param/bin/native library 与所有证据文件 SHA-256；门禁还解包实际安装 APK，要求其中 profile 和模型与离线预测完全一致。`ffprobe` 验证外部真机录像的音视频流各自连续至少 15 分钟。另强制 test 分组、未参与调参、逐类事件支持、90%/80%/90% 指标、实体 Android 13/14 声明、实际发声样本及 P95 ≤250 ms。通过、语义失败、模型错配、时间轴错配和哈希篡改测试均已覆盖。 |

历史 2026-09-30 Debug candidate（不作为当前上传附件）：`android/app/build/outputs/preview/sensefield-0.3.0-alpha.1-arm64-v8a-debug-candidate.apk`
版本：`0.3.0-alpha.1`（versionCode 7）
文件大小：`17,156,242 bytes`
SHA-256：`49043142158a360abb7bb3c5d8c9c7ed24828374f67bdb888e9eedcb445a6b0a`
签名：Android Debug signer，v2 验证通过；候选类型必须在 GitHub Release 中保持 `debug-candidate`标识。

公开仓库检查通过；模型文件仍不纳入 Git，内置 profile 默认启用 `minimap_yolox`，运行识别需要本地 APK 包含匹配权重。旧 Git 历史仍含模型文件；历史清理的 force-push 尚未执行，仍待明确授权。

合成录像的 3 个事件均被评测脚本匹配，**仅证明数据管线和规则工作，不能代表真实游戏准确率或真机延迟**。模拟器验证也不能替代实体手机测试。

## 新真机会话审计日志与 schema 3 门禁

最终验收使用 `FINAL_EVIDENCE` schema 3，并以同一 `session_id` 关联 Android 审计日志、外部屏幕与实际声音录像、延迟 CSV。除原有真机验收外，门禁现在会读取哈希固定的 train/val/test COCO 标注和 match manifest，从导出的帧名重建对局分组，要求 test 有实际图像、三组来源对局不交叉，并验证 test 对局 ID 与本次留出录像 SHA-256 一致。它也要求 profile `verified=true`、候选元数据声明 `release_ready`、模型 bin/param 与候选和 profile 哈希一致、阈值一致且 ONNX/ncnn/Android 预处理 parity 均通过。

门禁仍需同时复核：

- 外部录像的音频和视频 packet 时间轴连续，两个流各自最大 packet gap 均 `<=2s`，并检查音视频起止偏移；
- 留出预测逐帧符合 12 FPS CFR 时间公式；prediction metadata 的 profile、ncnn param/bin 和 native library 哈希与冻结证据一致，实际安装 APK 内的 profile 与模型也必须一致；
- 每一个非过期 Android `CueEvent` 的 `cue_id` 在 latency CSV 中恰好出现一次，CSV 不得出现日志之外的 ID，且 `kind` 一一对应；
- Android 日志证明至少 15 分钟横屏处理，平均处理速率 `>=8 FPS`，最大处理帧间隔 `<=2s`；
- 真机实际发声、每类至少 5 个配对样本且总计至少 20 个，P95 `<=250 ms`，并通过实体 Android 13/14 与授权、场景和录像完整性检查。

当前新真机会话的机器审计报告仍待最终复核；在 schema 3 报告和原始证据完成前，不将模拟器的 `queued=4` 视为实际发声通过，也不宣称最终验收已通过。

`physical_device`、实际听到声音、录像是否未剪辑、场景是否获允许等字段仍是测试人员声明，机器门禁只能检查文件、时间轴和内部一致性。最终报告需要人工观看原始外部录像并核对这些声明。

## 当前待办

### 2026-10-02 端侧收敛记录

上一轮已完成代码回归：标准近区提示的语音／短音／震动联动、短话术与 1.8 倍默认语速、单个最新近区 TTS 待播项、密集战斗优先级与降级、周期性异常会话 checkpoint，以及 locator/实际 ROI 诊断字段。当时 `testDebugUnitTest` 100 项通过，`lintDebug` 与 `assembleDebug` 通过。该轮 debug candidate 文件名为 `sensefield-0.3.0-alpha.1-diagnostics-arm64-v8a-debug-candidate.apk`，SHA-256 `49f03ed1e0046e943c527b15f90cba1670ed344d5af7b9c8e349aa6115da231a`；保留原始命名和哈希作为历史证据。本轮当前包见页面顶部。真机实际听感、震动可感知性、端到端延迟、漏报／误报和热量仍未通过独立门禁。

患者代表提出的新手需求已先落实为开始前的语音释义与试听；完整游戏操作教学和游戏内新手任务识别继续列为后续产品项。本次不以播放回调代替患者实际理解与感知，也不改变实时识别链路和 release 门禁。

1. 完成 video7-edge 的人工复核，并核对队列 ROI 与 provenance；尚未复核的数据不得加入训练或验证。video10/11 已完成并纳入 v2 split。
2. 当前 HD bootstrap v2 仅供开发辅助：train 为 video1+8+3+4+5+10 的 664 图／1211 框，val 为 video2-HD+11 的 230 图／400 框，bootstrap test 仍为空，不能报告 bootstrap 最终成绩。video13 已作为跨来源开发诊断（121 图／211 框）；此前训练阶段 confidence `0.49` 的准确率门槛未过、召回与方向已过。首轮真机反馈后曾检查多档阈值，当前部署 confidence 为 `0.67`，video13 不能用于独立门禁。低清 video2–6 与其旧模型、checkpoint、指标均已退役。
3. video12 primary holdout 与 video9 cross-source holdout 继续封存；当前不得运行模型、预标注或查看预测。任何未来正式盲评需另行遵循冻结门禁。
4. `minimap-player` 首轮 218 张与第二批 120 张已完成并合并为 338 个人工终态；剩余 901 张 `pending` 按优先级处理，不把全量清洗列为要求。v2 候选排序器和安全双类 v8 都只作开发／复核辅助；v6 512 在 pooled val 的 player precision/visible recall/中心 P95 相对短边为 `0.954545/0.903226/0.017083`，三项开发门槛通过。v6 512 双类已完成 ONNX/TorchScript/ncnn 导出，产物和报告位于忽略目录 `build/ignored/v6-512-export`：ONNX raw 严格门禁失败（最大误差 `0.0006387 > 0.0005`），但最终 detection arrays 全部一致；ncnn Android 等价严格门禁通过。2 线程桌面 preprocess+inference P95 为 `28.9552 ms`。2026-10-01 已作为实验近区 profile 接入 APK，但 val 参与选模和阈值选择、test 为空，无独立测试对局；2026-10-01 已取得有用的方位语音反馈，但最终持续运行与准确性验收未完成，仍不得表述为已验证的稳定发布。绿色 annulus 的三折留出来源验证不稳定，不进入端侧。模型和绿色外圈建议只用于复核候选，不能直接驱动提示；近区关系层只在自身标记可见、新鲜度 ≤500 ms 且位于地图主体内时计算敌人相对距离；小地图关系与主画面镜头无关，不需要镜头跟随门。自身标记缺失、玩家死亡或遮挡超过 2 秒进入 UNKNOWN，只记录状态变化；2026-10-01 玩家反馈后取消暂停／恢复声音。
5. **Android 13/14 测试手机**：Android 14 首轮 0.2.1 实体机冒烟已跑通整屏授权、横屏采集、ncnn 和实际播放；Android 14 设备现已通过无线 ADB 连接。0.3.0-alpha.1（versionCode 7）的 512 近区链路已完成一局诊断，但静音、TTS 失败和 AutoPowerKill 导致本轮未通过；修复后已确认讯飞语记实际播报，52 秒竖屏准备会话无断流恢复；完整游戏、持续运行与最新布局仍待复测。`FLAG_SECURE` 场景、坐标映射、震动、提示与游戏音频关系，以及黑屏／授权终止处理仍需验证。
6. **端到端验收**：需要至少 15 分钟真机运行，并用同时拍到屏幕、录到声音的外部录像对齐证据首次可见与提示发声时间，测量 P95、帧率及发热。桌面回放时间轴不能替代该测量。
7. **近区提醒**：按 `docs/plans/黑客松方案收敛与实施路线.md` WP-A 做“人肉雷达”并确认话术；用 `python -m mapassist.calibrate_near_zone` 从约 20 个“敌方英雄刚进入主画面边缘”的人工标记时刻标定 R_enter；真机测 512 输入耗时并做同录像一致性抽查；人工逐条复核 video11 回放中的 38 次近区提示，估计 precision 与漏报。

后续流程按 [HD-only 训练记录](../training/README.md)和[团队运行文档](../docs/development/团队协作与本地运行.md)执行；未达标的识别器保持关闭。
