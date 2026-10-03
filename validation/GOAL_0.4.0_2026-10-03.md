# 0.4.0 选用式语音与画面助手目标、接口和验收记录

编制日期：2026-10-03；工程状态更新：2026-10-04。版本目标 0.4.0 / versionCode 17。只读核对的最新公开下载为 0.3.8；0.4.0 仍是未发布的工程候选，工程链路已通过合成输入联调；外部时延、热表现或玩家验收仍待取得独立证据。

## 目标与完成边界

在既有端侧小地图辅助之外，为明确选择该能力的用户提供语音和画面问答。语音、画面与主动观察分开授权，默认关闭；首次开启必须在游戏外、相关 Activity 可见时完成。连续语音由用户自己选择，不能强制改成按住说话。助手随用户授权的 MediaProjection 前台服务运行；应用切到后台后，已运行的服务仍可继续，系统持续显示前台服务通知。当前没有独立于该服务、静默运行的后台采集路径。

当前实现没有本地 ASR 模式。用户分别开启语音并同意音频处理后，端侧 WebRTC VAD 2.0.10（aggressiveness mode 3）筛出的语音片段和最多约 300 ms 预滚动音频会流向用户配置的网关/自有服务。连续语音路由门槛为 Android 10/API 29+：本应用使用 `USAGE_GAME`、`MODE_STATIC`、全零 PCM 静态短缓冲并静音无限循环的 `AudioTrack` 探测默认路由，通过其 `getRoutedDevice()` 与 routing callback 白名单确认有线/USB 耳机；BLE 路由识别从 API 31 起可用。探测不申请 audio focus、不调用 `setPreferredDevice()`，也不读取其他 UID 的游戏播放配置。Android 14/15 AOSP 会匿名化提供给普通应用的活动播放配置并清空设备 ID，因此旧的跨 UID 活动路由门禁无法工作；见[Android 14 播放配置匿名化](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/audio/PlaybackActivityMonitor.java#L741)、[Android 15 播放配置匿名化](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/services/core/java/com/android/server/audio/PlaybackActivityMonitor.java#L791)与[匿名配置副本实现](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/media/java/android/media/AudioPlaybackConfiguration.java#L391)。探测只能证明本应用默认 `USAGE_GAME` 声路，无法确认游戏为自己的轨道指定了不同设备，试用前须由用户确认游戏声已在耳机中；外放实验未开放。无耳机、A2DP、SCO、扬声器、未知路由或探测失败时暂停上传。静音探测轨道可能带来额外 native 音频负载，需由热/负载对照测量，不称为零开销。Android 10/11 仍支持本地预警与手动画面问答。应用同时观察自身录音静音与全局其他录音会话；`isClientSilenced()` 为真或任意其他麦克风活动（包括匿名 session）出现时暂停上传，恢复前清空旧轮次；设备实际并发行为仍待真机验证。用户分别开启画面并同意画面处理后，单张用户请求的画面通过该服务交给智谱 GLM-4.6V-Flash。图像和音频路径相互独立，低频主动观察另设开关。应用不持久化完整语音转录；已有诊断截图/报告是另一条本地保留路径，按其现有用户开关与本机保留规则处理，不能拿它推断助手数据被上传或被持久化。

语音或画面被用户关闭、网关不可用、权限撤销时，不影响端侧小地图识别链路。SpeexDSP AEC 已接入耳机条件下的语音链路，并用助手自身的合成 PCM 作回声参考；它不会捕获游戏播放音频，也不依赖游戏提供回声参考。AEC 算法已集成，目标设备上的回声抑制效果、并发采音质量和可懂度仍待实测。

## 合并加入的开心消消乐体验入口

主线还包含独立的开心消消乐体验入口，与本节选用式助手数据通路分开。用户可选一张截图并手动校准棋盘范围及 6–9 行/列网格，程序按颜色采样棋子、枚举能形成三连的相邻交换并用语音播报位置；用户也可明确授权 MediaProjection，按约每秒采样并在棋面变化时播报。另有一个 Jev 示例判定按钮，需在独立的判定自测设置中启用渠道并配置凭据；它请求的是示例局面，不是实时棋盘判定。

这条棋盘路径使用手工标定和启发式颜色区间，特殊棋子模板库仅覆盖用户保存的局部外观，完整规则和模板命中效果没有验证。当前没有证明真实对局识别准确率、提示可感知性或玩家帮助效果；应用不代替用户交换棋子或执行游戏操作。该体验入口不改变既有小地图验收口径，也不计入语音助手 SLA。

## 2026-10-04 工程验证状态

- 合并开心消消乐后的工作树通过 JVM 204 项、Python 530 项（1 项跳过）、native CTest 4/4、arm64 Debug 构建与 lint。Android 14 arm64 模拟器 12 项本地 instrumentation 通过；额外 2 项显式真实 HTTPS/WSS 测试通过（普通套件未配置时跳过）。前序 183 项结果保留为历史，不混入当前计数。
- ASR 使用 CPU Python FunASR 和已校验的 pinned Paraformer streaming Apache-2.0 snapshot。一次合成输入“请读出当前比分”的 smoke 在 2,510 ms 后收到 final；这是单次服务管线耗时，不是语音质量、外部实声时延或 P95。外部录像 final-recognition P95（目标 ≤1,000 ms）仍无样本，尚不能判定通过。
- 真实 GLM-4.6V-Flash 合成图读取正确，单次 API 请求 1,089 ms；Android 正式客户端经已验证 TLS 的 HTTPS/WSS 完成真实 GLM、固定 CPU ASR 和跨 generation reset。复现见[安卓传输测试](../docs/development/assistant-android-transport-test.md)。Linux 隔离网关的 TLS、401 鉴权拒绝、真实 CPU ASR 与免费 GLM 合成测试均通过；单次音频结束至 final 为 199 ms，GLM 网关请求为 1,804 ms。见[Linux 部署记录](../docs/development/assistant-gateway-test-deployment.md)；未测 Android→Linux 或实体麦克风。目标设备耳机/麦克风并发、SpeexDSP AEC 实效、外部录音端到端时延、热负载比较和玩家体验仍待完成；以上均非物理 SLA 或 P95。0.4.0 没有公开发布记录。

该助手不自动提高历史 35 项符合度，也不改变 2026-10-01 评估书的 **52.96% 历史结果**。新问答可作为独立实验能力；#11 是否满足、能否帮助玩家，仍要按对应需求和独立证据复核，不预报分数。

## 已知接口约定

下表是网关实现方提供的接口约定，用来对齐客户端；联调和发布前还须对照仓库中实际运行的服务及其配置逐项核实。公共文档只列路由和字段，不放服务地址、Bearer 值或模型密钥。

| 用途 | 请求与负载 | 响应/边界 |
| --- | --- | --- |
| 健康检查 | GET /health | 服务就绪信息；不得带或回显密钥 |
| 语音流 | WSS /v1/audio，HTTP Authorization: Bearer …；先发 JSON {type:start, session_id, generation, sample_rate:16000}，随后发 speech_start、speech_end、reset 控制消息（含 turn_id、generation），音频为 16 kHz 单声道 PCM16LE 二进制帧 | 服务发 partial、final、status 等带 ID 的事件；包含文本或状态及 asr_ms。应用不把 partial/final 回调当作物理发声时间 |
| 画面请求 | POST /v1/visual，同一网关 Bearer 认证；JSON 包含 session_id、generation、turn_id、frame_id、question、image_base64、frame_age_ms、proactive | JSON 返回关联 ID 与 kind（hud / ui_text / unknown）、answer、uncertain、elapsed_ms。请求仅在用户选择的功能范围内发出 |
| GLM 视觉服务 | 网关后端请求 `/api/paas/v4/chat/completions`，按智谱原生 HTTPS 接口在 `image_url.url` 传原始 base64 JPEG；关闭 thinking，流式输出，`max_tokens=256`，不发送 `response_format` | `ZHIPU_API_KEY` 仅在服务器端。当前代码只有 GLM-4.6V-Flash 视觉后端、没有视觉回退。智谱公开价目页与模型说明在 2026-10-03 标示该模型 API 输入/输出免费（[价格页](https://docs.bigmodel.cn/cn/guide/start/pricing)、[模型说明](https://docs.bigmodel.cn/cn/guide/models/free/glm-4.6v-flash)）；账号额度、并发/速率限制和网关服务器成本仍适用，不能据此称整体服务没有成本。数据用途与留存期限仍须按实际账户与部署条款核对，不推定零留存 |

网关设备 token 的配置名为 `ASSISTANT_GATEWAY_DEVICE_TOKEN`，多 token 可用 `ASSISTANT_GATEWAY_DEVICE_TOKENS`；它们与 `ZHIPU_API_KEY` 分开。Android 侧只使用用户配置的网关 URL 与设备凭据；不得在仓库、APK 源码或日志中放服务端模型密钥。

## 35 项需求证据链中的重点条目

历史评估只给出起点。本表记录可复核的代码和测试名，不能替代真机录像、真实声音、独立数据或玩家判断。

| 条目 | 现有实现可定位处 | 可复核测试/日志 | 未覆盖边界 |
| --- | --- | --- | --- |
| #12 威胁事件聚合 | native/src/minimap_relation.cpp；CaptureService.submitRelationCue()；NearZoneEvent episode/占用/重入/新鲜度 | tests/test_minimap_relation.py::test_far_enemy_walking_in_cues_once_with_bearing；Android 诊断 NearZoneEvent | 属于小地图近区 episode，不是技能、血量或完整威胁融合；需要新对局人工对齐证据 |
| #13 优先级 = 威胁度 × 紧迫度 ÷ 距离 | NearZoneCombatPolicy.score()、priorityForScore()；紧迫度代理为本次近区事件，威胁代理为新鲜敌人数 | NearZoneCombatPolicyTest.scoreRaisesPriorityForFreshCloseThreats；诊断 CueEvent 中的 priority、freshEnemies | 只用于该近区调度路径，是简化评分；未形成全事件通用空间距离优先级；没有行为效果或玩家帮助证据 |
| #15 团战检测与降级 | NearZoneCombatPolicy.observe()；多新鲜目标/短时多次 NEAR_ENTER 作为 dense 代理，抑制部分竞争语音 | NearZoneCombatPolicyTest.twoFreshTargetsEnterDenseAndSuppressLowerSpeech、denseModeExitsAfterQuietWindow；诊断 CombatMode、CombatSuppressed | 不是游戏团战真值或技能命中检测；不声称“即将命中自己的技能”或关闭所有视觉效果 |
| #18 Top1 与 2 字短提示 | CueDispatcher 最多留一个近区 TTS 待播项；标准文案为“左上有敌人”等事实句；“两字方位提示”开关只播方位，如“左上”“右侧”，未知时为“附近” | CueDispatcherTest.latestNearSpeechReplacesOlderPendingNearSpeech；NearZoneRoutingTest.speechNamesEightMinimapSectorsFactually、twoWordStyleNamesEightSectorsAndKeepsUnknownsAmbiguous；诊断 CueDispatch/CuePlayback | 已实现两字事实方位，但 Top1 需求符合度、实际时长和可理解性仍待验证；回调只证明软件事件，不证明外部录音中确实听到声音 |

证据链报告必须逐条包含：需求原文、代码位置和真实测试名、同一 session/cue ID 的应用诊断、外部未剪辑录音/视频时间码、未覆盖项。CuePlayback 是系统回调；真实响声以外部录音为准。

### 历史数据口径

- 52.96% 是 2026-10-01 评估书对当时展示材料的历史判定，不是本版本实测得分或赛事得分。
- “video11 覆盖率 91.17%、近区 2.26 次/分钟”来自已参与选模的桌面离线开发回放，不是实体机或玩家使用频率；未人工核实的近区提示不能称为正确率或体验频率。
- 当前单次 nativeMicros、服务处理墙钟、处理墙钟占会话比例、CuePlayback 回调、外部物理声音分别是不同量，不以墙钟比例称 CPU 利用率，不以回调替代实际声音。
- 当前网关的 ASR 后端是 Python FunASR 1.4.16，在服务器 CPU 上运行；Paraformer streaming 快照固定为 `fd2af606b37d7fb8b3b8a218c5be5b07b53ef6ba`，模型文件按 `python/mapassist/assistant_gateway/model_manifest.json` 中的大小和 SHA-256 校验。此实现不含 Android/C++ 原生 ASR runtime；不要把 Python 包版本称为原生运行时版本。Android 本地 WebRTC VAD 则是独立的 C/C++ 推理组件，按 WebRTC VAD 2.0.10 固定源码构建。

## 实际玩家与设备验收

以下是将来收集证据的验收计划，当前没有参与者计数或通过结果。未获得真实记录之前保持待测。

- 招募 3–5 名目标/探索参与者。热与负载比较须完成 3 组配对的 15 分钟运行；候选相对对照的电池温升增量不超过 1°C，native processing P95 回归不超过 10%。同设备、游戏场景、音量、热点、画质和语音条件应匹配；实施前预先定义起始温度匹配范围，建议配对起始电池温度相差不超过 1°C。组间充分冷却，出现不适或明显发热就停止，不要求完成满时长。目标玩家的功能体验另外报告，不把这些工程门槛当成玩家效用结论。
- 外部设备连续拍摄屏幕并录制房间中的实际提示声音；标记用户实际语音起止、助手当前声音停止、真实静音开始和第一段有用回答的物理时间。保留无声、未回答和录像缺失为 unknown，不填零，不用 TTS/ASR 回调代替物理声音。
- Barge-in：从外部录音中用户开始说话到正在播放的助手声音实际停止，配对样本 P95 目标 ≤300 ms。只有同时确认被打断的输出实际正在发声时才进入该指标。
- ASR final：网关 `asr_ms` 当前是从 utterance start 到 final 事件发出的经过时间，只作服务诊断，不能代替用户说完到最终识别可见的延迟。外部同步录像若能看到最终转写/识别状态，标注 `final_recognition_ms`；从 `voice_end_ms` 到此时刻的配对 P95 目标 ≤1000 ms。没有可见终态时留空，不用回调时间填补。
- 视觉回答：将 `voice_end_ms` 到外部录音中第一段有用回答实际可闻的 `first_useful_answer_ms` 作为端到端时延，针对语音触发的可见 HUD/菜单文字问答，配对 P95 目标 ≤4000 ms。同时报告网关 `elapsed_ms`、unknown、uncertain 和缺答；服务时长不替代实声目标。
- 小地图 near-zone 的 500 ms 观测年龄预算只控制哪些画面可参与关系判断，不是发声延迟；陪同试用的外部实声 P95 ≤500 ms 是单独的试点目标，也不等于最终验收。近区半径仍待依据边缘帧人工标注运行 `python -m mapassist.calibrate_near_zone` 标定，不做阈值扫描。
- 既有小地图最终门槛继续保持：实际声音延迟 P95 ≤250 ms、平均处理 ≥8 FPS、最大处理帧间隔 ≤2 s，以及冻结模型/配置、parity 和独立留出评测。Assistant 指标、近区 500 ms 观测预算与近区 500 ms 试点目标均不修改该门槛，也不证明小地图达到该门槛。
- 另记录第 0/5/10/15 分钟温度、热档、游戏帧率、电量、网络状态、实际请求次数、理解/帮助/干扰反馈以及中断/漏答。受控对照不以单局主观感受推导温升或性能通过。

## 外部音频标注 CSV

measure_assistant_latency 接受外部录像为唯一时间源。所有时刻是同一录像起点后的毫秒数。`final_recognition_ms` 是可选列，应标记画面中首次可见最终识别结果的时刻；如录像无法辨认该状态就留空。其他列为必需表头：

~~~csv
participant_id,session_id,sample_id,turn_id,interrupted_audio_id,answer_audio_id,voice_start_ms,voice_end_ms,assistant_audio_start_ms,assistant_audio_stop_ms,actual_silence_ms,first_useful_answer_ms,final_recognition_ms,timing_source,source_note
~~~

timing_source 必须为 external_recording。Barge-in 行须同时填 interrupted_audio_id、用户说话起点和外部听到的助手播放起止；普通回答行用 answer_audio_id 配 first_useful_answer_ms。`final_recognition_ms` 只用于外部录像可辨认识别终态的样本，计算 voice-end→final-recognition；不接受 app callback 时间。无回答、无清晰静音或没有被打断的播放时，相应 ID 和时间都留空。每个 session_id + turn_id 和音频 ID 只能在本 session 出现一次；用户文字内容不进入 CSV。脚本按 nearest-rank ceil(0.95*n)-1 计算 P95，输出配对与缺失数以及回答/识别早于说话结束或答案早于静音的计数。

## 发布前核对清单

下列工程检查和真人门禁分开记录，不把代码或合成输入联调当作玩家验收。

- [x] 模拟模型 HTTPS/WSS 与 Android 正式客户端真实 GLM/CPU ASR 合成输入 transport 测试通过；覆盖 TLS、设备鉴权、请求/响应 ID 与 generation reset，不覆盖患者数据和物理声学条件。
- [ ] 三个隐私开关分别关闭/开启验证；关闭时用服务端日志证明没有对应音频或图像请求；首开流程在游戏外 Activity 可见时完成；应用切至后台后的前台服务/通知/采集生命周期完成真机核验。
- [ ] 服务端只持有 ZHIPU_API_KEY；APK、Git、诊断日志中无服务端密钥或设备 token；公开仓库扫描无秘密内容输出。
- [ ] 语音临时数据、服务端访问日志和第三方服务数据保留条款均已复核；无完整对话转录被应用持久化；诊断 ZIP 的本机保留按既有诊断设置另行核查。
- [x] ASR 模型 tag/license、Python 包版本和 pinned snapshot 文件 SHA-256 已核验；本机 CPU smoke 完成模型加载与合成音频推理。当前后端为 CPU Python FunASR，不报告不存在的 C++ ASR runtime 版本；真机语音质量及时延仍待测。
- [ ] 真实玩家与设备验收完成且记录可复核；目前无结果，不预填样本量、P95、温度或评分。
- [x] 既有严格小地图门槛和 verified/release_ready 状态未被放宽或改写；未运行封存录像、阈值扫描、训练或上传患者素材。

接口/协议有变动时先更新本文件、客户端和服务端之间的接口证据，再继续记测量结果.

## 工程交付与保留门禁

2026-10-04 完成这阶段的工程交付：同伴消消乐已快进合并至本地 main；统一声音抢占、插话取消与过期回调、呈现层实验、独立隐私开关、小圆点、VAD/AEC、CPU ASR 网关和免费 GLM 接入均已落在候选源码。路由审查发现 Android 14/15 的跨 UID 播放配置匿名化会阻断原耳机判断，已改为本应用静音轨道探测，并用 Android 14 的默认扬声器测试确认闭锁；这不验证真实耳机效果。最终回归为 204 项 JVM、12 项本地设备测试、lint；额外 2 项真实 Android 传输测试和 Linux 网关自身的合成推理另记。

Linux 单次 ASR 的音频结束至 final 为 199 ms，推理为 313 ms，开始至 final 为 1,982 ms（含 1,770 ms 输入）；GLM 网关请求为 1,804 ms，SSH 隧道 HTTPS 往返为 1,868 ms。全部是合成数据的单点服务测量，无 P95、物理发声、Android→Linux 或玩家结论。部署复现与测试清理见[Linux 部署记录](../docs/development/assistant-gateway-test-deployment.md)。后续按上文的独立设备、声音、热负载与玩家门禁收集证据。

本地候选 `output/releases/0.4.0/` 包含 arm64 APK、中文使用说明、SHA-256 和核验 JSON；临时 Android 联调 CA 已移除，实际测试凭据经提交候选与 APK 字节扫描无匹配。新版符合度方案记录真实测试入口，未训练、未扫描阈值、未读取封存录像、未改既有模型/profile、未改 `verified` / `release_ready`。真实玩家、物理声音、受控温升与供应商留存核验仍独立待测，不用工程目标完成替代它们。
