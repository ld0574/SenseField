# 0.4.0 选用式语音与画面助手目标、接口和验收记录

2026-10-04追加：[VAD取消修复与手机软件全链](ASSISTANT_VAD_CANCELLATION_REPAIR_2026-10-04.md)。等待画面请求不再因未经语义确认的输入片段被丢弃；插话停播与新问题替换仍保留，原帧龄不延长。合成全链可核对本地识别、真实Qwen、声音首写和正文，实际麦克风/耳机声学P95、真实建议效用与受控温升仍待验证，历史评分及门禁状态不变。

当前0.4.1已改为手机端ASR，服务器只提供视觉。停止/恢复录音、状态保护、缓存回收与正式软件链路的后续证据见[手机端ASR工程复核](ASSISTANT_ONDEVICE_ENGINEERING_2026-10-04.md)；最新正文追踪、问题抢占、首句TTS、测试及装机见[对局后修订](ASSISTANT_POSTMATCH_REPAIR_2026-10-04.md)。本页0.4.0服务器ASR和GLM结果保留为历史，不能替代当前真实语音或热负载验收。

编制日期：2026-10-03；工程状态更新：2026-10-04。版本目标 0.4.0 / versionCode 17 是历史方案；只读核对的最新公开下载为 0.3.8。此前 4218bf94… 快照的0.4.1/code18 APK 已无线安装至小米 Android 14，214121822 bytes，SHA-256 `4218bf94b03153e48e9e313fd28900139c14c0bba000e534289cd4f799df5759`。Android JVM 225 项、instrumentation 10 项、Python 网关 103 项以及 Debug/Test APK 与 lint 通过。纯合成 PCM instrumentation 的比分/出装/选人三例分别为 170/294/352ms，关键词与 `isRequest` 断言通过；不等于真实麦克风、实际发声、P95 或温升验证。服务器 ASR 合成 WSS 的 speech-end 至 final 274ms 是历史比较单点。0.4.1/code18 仍为本地候选、未公开发布；此前安装的 `ba187fa…` 版本与本轮新安装包区分记录。此前 Mac 合成菜单路径使用 `qwen/qwen3.8-27b`。本局旧客户端会话记录 11 个 FINAL、其中 6 个为空；计数器另显示 2 个手动请求、8 个主动请求和 5 个 accepted results，其余状态未审计，不能完整归因。14:27:32 的旧会话软件日志曾显示 ASR FINAL `requestLike=true` 后触发手动 QUESTION 和新帧请求；外部实声、物理时延、热表现或玩家验收仍待取得独立证据。

0.4.0 历史配置使用 GLM-only 视觉后端。后续 0.4.1 服务端增加 `zhipu` / compatible API 提供商与模型配置；按用户顺序先测 MiniMax、再测 Qwen，当前明确选择 `/models` 返回的实际 ID `qwen/qwen3.8-27b`，`max_tokens=256`，无自动 fallback。正确 ID 的 Qwen 直接合成选项读取为 1102ms，Mac→TLS 网关完整合成菜单请求 1350ms（网关 1308ms），文字与所有权通过；先前不带 namespace 的 HTTP 503 仍保留为历史，原因未知。MiniMax 近期 1024 token 与 thinking disabled/256 token 探针均 HTTP 200 空正文（4645/4879ms）；更早成功的合成结果及 Android HTTPS 合成读取保留为历史单点，不代表当前持续可用。详见[0.4.1真机记录](ASSISTANT_LIVE_0.4.1_2026-10-04.md)。

## 目标与完成边界

在既有端侧小地图辅助之外，为明确选择该能力的用户提供语音和画面问答。语音、画面与主动观察分开授权，默认关闭；首次开启必须在游戏外、相关 Activity 可见时完成。连续语音由用户自己选择，不能强制改成按住说话。助手随用户授权的 MediaProjection 前台服务运行；应用切到后台后，已运行的服务仍可继续，系统持续显示前台服务通知。当前没有独立于该服务、静默运行的后台采集路径。

ASR 最终架构已定为 bundled SenseVoiceSmall int8 在手机端运行：Android 10+、不依赖系统 on-device service；经 JNI 使用 sherpa-onnx 1.13.8 / ONNX Runtime 1.28.2 单线程 CPU，约 239 MB 权重作为 APK asset、本地复制后校验哈希。候选已安装并通过纯合成 PCM instrumentation。目标音频不上网、不落盘；只在用户问画面时才将识别出的文本问题和授权截图发给视觉网关。取消时保留单 slot 至 native 调用退出；设备 HOT 时暂停，无云端 fallback。服务器 `vision_only` 路由/health 禁用 ASR，不加载服务器 ASR 模型；Paraformer 与 SenseVoice 是已测服务器比较实验，其中合成 WSS speech-end 至 final 为 274ms 单点，不能据此推断本地 ASR、实体麦克风表现或 P95。模型可随 APK 分发但不入 Git，随包许可需覆盖 FunASR Model License 1.1、Sherpa Apache-2.0 与 ONNX Runtime MIT。连续语音路由门槛为 Android 10/API 29+：本应用使用 `USAGE_GAME`、`MODE_STATIC`、全零 PCM 静态短缓冲并静音无限循环的 `AudioTrack` 探测默认路由，通过其 `getRoutedDevice()` 与 routing callback 白名单确认有线/USB 耳机；BLE 路由识别从 API 31 起可用。探测不申请 audio focus、不调用 `setPreferredDevice()`，也不读取其他 UID 的游戏播放配置。Android 14/15 AOSP 会匿名化提供给普通应用的活动播放配置并清空设备 ID，因此旧的跨 UID 活动路由门禁无法工作；见[Android 14 播放配置匿名化](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/audio/PlaybackActivityMonitor.java#L741)、[Android 15 播放配置匿名化](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/services/core/java/com/android/server/audio/PlaybackActivityMonitor.java#L791)与[匿名配置副本实现](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/media/java/android/media/AudioPlaybackConfiguration.java#L391)。探测只能证明本应用默认 `USAGE_GAME` 声路，无法确认游戏为自己的轨道指定了不同设备，试用前须由用户确认游戏声已在耳机中；外放实验未开放。0.4.1修订增加普通A2DP耳机实验；设备类型无法区分耳机与蓝牙音箱，用户须戴耳机并确认游戏声也在耳机中。无耳机、SCO、扬声器、未知路由或探测失败时暂停本地 ASR 输入。静音探测轨道可能带来额外 native 音频负载，需由热/负载对照测量，不称为零开销。Android 10/11 仍支持本地预警与手动画面问答。应用同时观察自身录音静音与全局其他录音会话；`isClientSilenced()` 为真或任意其他麦克风活动（包括匿名 session）出现时暂停本地 ASR 输入，恢复前清空旧轮次；设备实际并发行为仍待真机验证。0.4.0 历史视觉路径把单张用户请求的画面交给 GLM-4.6V-Flash；当前 0.4.1 服务端支持 `zhipu` 与 compatible API，实际选择 `qwen/qwen3.8-27b`，正确 ID 的合成读选项和 Mac 网关合成菜单读图通过；MiniMax 最新两个探针 HTTP200但正文为空，较早成功单点另记，不自动 fallback。图像和音频路径相互独立，低频主动观察另设开关。应用不持久化完整语音转录；已有诊断截图/报告是另一条本地保留路径，按其现有用户开关与本机保留规则处理，不能拿它推断助手数据被上传或被持久化。

语音或画面被用户关闭、网关不可用、权限撤销时，不影响端侧小地图识别链路。SpeexDSP AEC 已接入耳机条件下的语音链路，并用助手自身的合成 PCM 作回声参考；它不会捕获游戏播放音频，也不依赖游戏提供回声参考。AEC 算法已集成，目标设备上的回声抑制效果、并发采音质量和可懂度仍待实测。

## 合并加入的开心消消乐体验入口

主线还包含独立的开心消消乐体验入口，与本节选用式助手数据通路分开。用户可选一张截图并手动校准棋盘范围及 6–9 行/列网格，程序按颜色采样棋子、枚举能形成三连的相邻交换并用语音播报位置；用户也可明确授权 MediaProjection，按约每秒采样并在棋面变化时播报。另有一个 Jev 示例判定按钮，需在独立的判定自测设置中启用渠道并配置凭据；它请求的是示例局面，不是实时棋盘判定。

这条棋盘路径使用手工标定和启发式颜色区间，特殊棋子模板库仅覆盖用户保存的局部外观，完整规则和模板命中效果没有验证。当前没有证明真实对局识别准确率、提示可感知性或玩家帮助效果；应用不代替用户交换棋子或执行游戏操作。该体验入口不改变既有小地图验收口径，也不计入语音助手 SLA。

## 2026-10-04 工程验证状态

当前工程候选的停止/恢复录音、状态保护、TTS缓存回收和正式本地ASR→Controller→HTTPS→网关软件链路已复核，239项JVM、122项Python、14项Android14模拟器测试（12控制+2本机HTTPS fixture）、Debug/Test APK与lint通过。fixture不启动AudioRecord/录屏/真实TTS，只用合成PCM、合成画面和固定视觉回复。HOT/用户暂停现在调用AudioRecord.stop()，恢复复用实例并清空跨轮缓冲；AOSP flush契约与厂商HAL未测边界见[工程复核](ASSISTANT_ONDEVICE_ENGINEERING_2026-10-04.md)。用户重新提供无线ADB后已安装干净候选，包指纹与手机读回匹配；旧100MiB更新客户端需先人工覆盖。工程Goal不替代真实麦克风、游戏建议、实声P95、温升、患者及独立符合度验收。

- 合并开心消消乐后的工作树通过 JVM 204 项、Python 530 项（1 项跳过）、native CTest 4/4、arm64 Debug 构建与 lint。Android 14 arm64 模拟器 12 项本地 instrumentation 通过；额外 2 项显式真实 HTTPS/WSS 测试通过（普通套件未配置时跳过）。前序 183 项结果保留为历史，不混入当前计数。
- 既有服务器 ASR 为 CPU Python FunASR + pinned Paraformer streaming snapshot（Apache-2.0）；一次合成 smoke 的旧口径在 2,510 ms 后收到 final，仅作历史单点。服务端比较实验还包括 CPU SenseVoiceSmall int8 ONNX（`sherpa-onnx==1.13.8`），模型清单锁定 revision 与文件 SHA-256；模型许可是 FunASR Model Open Source License Agreement 1.1，不是 Apache-2.0，服务端试验权重由服务器缓存提供；最终本地候选允许模型 asset 随 APK 分发但二进制不入 Git，随包需包含 FunASR Model License 1.1、Sherpa Apache-2.0、ONNX Runtime MIT 许可。两种服务器后端都沿用连续监听/VAD 分段，句末一次识别且无 partial；不自动切换。本轮服务器 ASR 合成 WSS speech-end 至 final 单点为 274ms；Mac 同一 1,769 ms 合成 PCM 的旧口径 Paraformer finalization 为 2,324 ms，新协议的 `inference_ms` 为 185 ms；SenseVoice 对 score/build/draft 三类合成样本的直接测量为 85/139/145 ms，预设关键词判断通过。以上均为合成输入单点，直接 inference、WSS 和客户端端到端不是同一计时口径，不代表麦克风表现或 P95。bundled SenseVoiceSmall int8 手机 ASR 已实现并装入新 APK；纯合成 PCM instrumentation 三例（170/294/352ms）及关键词、`isRequest` 断言通过。该测试不覆盖物理麦克风、实际声音、P95 或温升。外部录像 final-recognition P95（目标 ≤1,000 ms）仍无样本，尚不能判定通过。
- **0.4.0 历史 GLM 集成与测试：**真实 GLM-4.6V-Flash 合成图读取正确，单次 API 请求 1,089 ms；Android 正式客户端经已验证 TLS 的 HTTPS/WSS 完成真实 GLM、固定 CPU ASR 和跨 generation reset。复现见[安卓传输测试](../../docs/development/assistant-android-transport-test.md)。Linux 隔离网关的 TLS、401 鉴权拒绝、真实 CPU ASR 与免费 GLM 合成测试均通过；单次音频结束至 final 为 199 ms，GLM 网关请求为 1,804 ms。见[Linux 部署记录](../../docs/development/assistant-gateway-test-deployment.md)；未测 Android→Linux 或实体麦克风。目标设备耳机/麦克风并发、SpeexDSP AEC 实效、外部录音端到端时延、热负载比较和玩家体验仍待完成；以上均非物理 SLA 或 P95。0.4.0 没有公开发布记录。

该助手不自动提高历史 35 项符合度，也不改变 2026-10-01 评估书的 **52.96% 历史结果**。新问答可作为独立实验能力；#11 是否满足、能否帮助玩家，仍要按对应需求和独立证据复核，不预报分数。

## 已知接口约定

下表同时保留历史接口。当前 0.4.1 手机端 ASR 不使用音频 WSS，服务器 `ASR_BACKEND=disabled`；当前远端主链是 `/health` 和 `/v1/visual`，音频接口仅作禁用断言和历史对照。公共文档只列路由和字段，不放服务地址、Bearer 值或模型密钥。

| 用途 | 请求与负载 | 响应/边界 |
| --- | --- | --- |
| 健康检查 | GET /health | 服务就绪信息；不得带或回显密钥 |
| 语音流 | WSS /v1/audio，HTTP Authorization: Bearer …；先发 JSON {type:start, session_id, generation, sample_rate:16000}，随后发 speech_start、speech_end、reset 控制消息（含 turn_id、generation），音频为 16 kHz 单声道 PCM16LE 二进制帧 | 服务发 partial、final、status 等带 ID 的事件；包含文本或状态及 asr_ms。应用不把 partial/final 回调当作物理发声时间 |
| 画面请求 | POST /v1/visual，同一网关 Bearer 认证；JSON 包含 session_id、generation、turn_id、frame_id、question、image_base64、frame_age_ms、proactive | JSON 返回关联 ID 与 kind（hud / ui_text / unknown）、answer、uncertain、elapsed_ms。请求仅在用户选择的功能范围内发出 |
| 0.4.0 历史 GLM 视觉服务 | 网关后端请求 `/api/paas/v4/chat/completions`，按智谱原生 HTTPS 接口在 `image_url.url` 传原始 base64 JPEG；关闭 thinking，流式输出，`max_tokens=256`，不发送 `response_format` | `ZHIPU_API_KEY` 仅在服务器端。此行描述 0.4.0 历史 GLM-only 配置，不代表 0.4.1 当前提供商。智谱公开价目页与模型说明在 2026-10-03 标示该模型 API 输入/输出免费（[价格页](https://docs.bigmodel.cn/cn/guide/start/pricing)、[模型说明](https://docs.bigmodel.cn/cn/guide/models/free/glm-4.6v-flash)）；账号额度、并发/速率限制和网关服务器成本仍适用，不能据此称整体服务没有成本。数据用途与留存期限仍须按实际账户与部署条款核对，不推定零留存 |
| 0.4.1 当前视觉提供商 | 按用户顺序先测 MiniMax、再测 Qwen；当前选择实际模型 ID `qwen/qwen3.8-27b`、`max_tokens=256`。直接合成请求 1102ms，Mac→TLS 网关完整合成菜单请求 1350ms（网关 1308ms），文字与所有权通过 | 当前合成通过不证明 Android 实际发声或 P95。MiniMax 最近两次探针（1024、thinking disabled/256）HTTP200但空正文；更早成功探针保留为历史。先前不带 namespace 的 503 保留为历史，不能推断全部原因；模型间无自动 fallback。手动交接最多等待2秒，再另有8秒模型调用超时；若自定义 provider coroutine 忽略取消，断连清理无独立硬上限并保留slot。详见[真机记录](ASSISTANT_LIVE_0.4.1_2026-10-04.md) |

网关设备 token 的配置名为 `ASSISTANT_GATEWAY_DEVICE_TOKEN`，多 token 可用 `ASSISTANT_GATEWAY_DEVICE_TOKENS`；兼容接口凭据与 `ZHIPU_API_KEY` 均单独保存在服务端环境。Android 侧只使用用户配置的网关 URL 与设备凭据；不得在仓库、APK 源码或日志中放服务端模型密钥。

### 0.4.1 游戏知识助手扩展（源码已实现，工程联调与验收中）

0.4.0 历史助手及当前已测候选的范围是转读可见 HUD/菜单，旧提示选择不推荐与不谈战术；这是当时的版本/团队保守设计，不是用户现在确认的目标。用户已明确要求助手可以结合最近截图讨论游戏各方面，覆盖静态装备页的装备解释和购买建议、选人页英雄推荐、对战画面的策略分析。该实验独立于端侧小地图敌方提醒，也不回写历史 35 项或 52.96% 符合度判定。

当前源码已实现用户手动提问、保留原问题、最多两张最近 640 尺寸缓存图加一张 1280 尺寸主图的三帧上下文，以及回答最多两句且不超过 100 个字符。上下文缓存单帧最长 6 秒；动态 HUD 的建议新鲜度窗口为 5 秒，静态界面为 15 秒；服务端保持现有 `hud` / `ui_text` / `unknown` kind。此前4218bf94…候选的225/10/103回归是历史快照；当前干净候选239/122/14的软件复核与再次装机见工程记录。服务器 ASR 合成 WSS 274ms 是历史比较单点；当前服务为 `vision_only` / `asr_ready=false`。这些工程结果不表示真实游戏截图上的建议质量、物理麦克风端到端时延、P95 或玩家验收。

允许根据授权截图中可见的信息与一般游戏知识给出上述解释和建议；装备强度/属性等可能随版本变化，不承诺当前版本数值。不得猜测截图未显示的敌方位置、隐藏状态或技能冷却，也不得读取游戏内存/API、注入或自动操作。建议由玩家决定是否采纳。原需求“提示不代思考”要求保留玩家操作权，铁律还要求只读、不操作和遵守游戏厂商服务条款，但没有明文禁止所有语言建议。已核对的赛手手册也未见禁止自然语言建议的通用赛规；具体游戏条款和适用赛事规则仍须按目标游戏核对。本扩展不证明准确率、效用、公平性通过或符合度得分提高。

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
- 源码保留服务器 CPU 对照路径：默认 CPU Python FunASR 1.4.16 / `paraformer_streaming`，Paraformer snapshot 固定为 `fd2af606b37d7fb8b3b8a218c5be5b07b53ef6ba` 并按 manifest 校验；另有显式 `sensevoice_int8` 选项，使用 `sherpa-onnx==1.13.8` 与固定的 SenseVoiceSmall int8 ONNX manifest。后者模型许可为 FunASR Model Open Source License Agreement 1.1，不能沿用 Paraformer 的 Apache-2.0 标签。两者均为服务器侧比较实验，不是最终架构。最终本地候选为 Android 10+ bundled SenseVoiceSmall int8，经 JNI sherpa-onnx 1.13.8 / ONNX Runtime 1.28.2 单线程 CPU 运行，约 239 MB asset 本地复制校验；音频不上网、不落盘，仅问画面时发问题文本与截图。已在装机 APK 中实现，取消保留单 slot 至 native 退出、HOT 时暂停本地输入、无云端 fallback；模型可随 APK 但不入 Git，许可随包注明 FunASR Model License 1.1、Sherpa Apache-2.0 与 ONNX Runtime MIT。不要把模型许可、Python 包版本和推理运行时混为一谈。Android 本地 WebRTC VAD 则是独立的 C/C++ 推理组件，按 WebRTC VAD 2.0.10 固定源码构建。

## 实际玩家与设备验收

以下为目标玩家与受控验收计划；队友工程联调另记于[0.4.1真机记录](ASSISTANT_LIVE_0.4.1_2026-10-04.md)，尚无目标参与者独立验收通过结果。未取得对应证据的门禁保持待测。

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

- [x] 0.4.0 历史 GLM/CPU ASR 合成输入 transport 测试通过；覆盖 TLS、设备鉴权、请求/响应 ID 与 generation reset，不覆盖参与者数据和物理声学条件。
- [x] 0.4.1正式本地ASR/Controller/HTTPS客户端/网关在Android14模拟器的本机fixture联调通过：问题与近期图、session/generation/turn/frame关联、取消与迟到丢弃、voice-only无音频/视觉请求。固定回复不验证实际provider；断网/真实停止生命周期及物理声音仍未测。
- [ ] 三个隐私开关分别关闭/开启验证；关闭时用服务端日志证明没有对应音频或图像请求；首开流程在游戏外 Activity 可见时完成；应用切至后台后的前台服务/通知/采集生命周期完成真机核验。
- [x] 当前提供商密钥配置在服务器环境；本轮公开仓库和最终APK无已知真实凭据匹配，临时fixture证书/配置已删除。zhipu使用ZHIPU_API_KEY，compatible使用ASSISTANT_GATEWAY_VISION_API_KEY；当前Qwen无需Zhipu密钥。此项不替代下列主机/上游保留条款与历史日志审计。
- [ ] 语音临时数据、服务端访问日志和第三方服务数据保留条款均已复核；无完整对话转录被应用持久化；诊断 ZIP 的本机保留按既有诊断设置另行核查。
- [x] Paraformer 与 SenseVoice 的模型清单、许可、runtime 版本和 pinned 文件 SHA-256 已核对；服务器 ASR 合成 WSS 对照的 speech-end 至 final 为 274ms 单点，Mac 非隔离环境另完成直接合成 smoke。bundled SenseVoiceSmall int8 手机 ASR 已装入 APK，纯合成 PCM instrumentation 三例及关键词、`isRequest` 断言通过；合成测试不验证真实麦克风质量、端到端物理时延、实际发声、P95 或温升。
- [ ] 真实玩家与设备验收完成且记录可复核；目前无结果，不预填样本量、P95、温度或评分。
- [x] 既有严格小地图门槛和 verified/release_ready 状态未被放宽或改写；未运行封存录像、阈值扫描、训练或上传患者素材。

接口/协议有变动时先更新本文件、客户端和服务端之间的接口证据，再继续记测量结果.

## 工程交付与保留门禁

2026-10-04 完成这阶段工程交付：同伴消消乐已快进合并至本地 main；统一声音抢占、插话取消与过期回调、呈现层实验、独立隐私开关、小圆点、VAD/AEC、ASR 对照网关和 0.4.0 历史 GLM 接入均已落在候选源码。路由审查发现 Android 14/15 的跨 UID 播放配置匿名化会阻断原耳机判断，已改为本应用静音轨道探测，并用 Android 14 默认扬声器测试确认闭锁；这不验证真实耳机效果。此前4218bf94…候选已装至小米 Android 14；其回归为Android JVM 225 项、instrumentation 10 项、Python 网关 103 项及 Debug/Test APK、lint 通过。端侧纯合成 PCM instrumentation 三例为 170/294/352ms；服务器 ASR WSS 274ms 是历史合成对照。

更早 Linux 单次 Paraformer ASR 的音频结束至 final 为 199ms、推理为 313ms、开始至 final 为 1,982ms（含 1,770ms 输入）；0.4.0 历史 GLM 网关请求为 1,804ms，SSH 隧道 HTTPS 往返为 1,868ms。这些均为合成数据单点，不是 P95、物理发声、Android→Linux 或玩家结论。2026-10-04 某次只读主机复查中，SSH 可达，但当时历史部署预期的环境文件、`/opt/mapassist` runtime 与 pinned ASR cache 不存在，回环服务端口拒绝连接；那次没有发起 ASR。此项是时间点快照，不能推断后续合成 WSS 对照所用环境当前状态。部署复现与历史结果见[Linux 部署记录](../../docs/development/assistant-gateway-test-deployment.md)。手机 APK 使用 bundled SenseVoiceSmall int8 本地识别；网关已切换 `vision_only`，health HTTP 200、`asr_ready=false`，服务器不加载 ASR 模型。真实麦克风、发声、P95 与温升仍待独立门禁验证。后续按上文的独立设备、声音、热负载与玩家门禁收集证据。

本地候选 `output/releases/0.4.0/` 包含 arm64 APK、中文使用说明、SHA-256 和核验 JSON；临时 Android 联调 CA 已移除，实际测试凭据经提交候选与 APK 字节扫描无匹配。新版符合度方案记录真实测试入口，未训练、未扫描阈值、未读取封存录像、未改既有模型/profile、未改 `verified` / `release_ready`。真实玩家、物理声音、受控温升与供应商留存核验仍独立待测，不用工程目标完成替代它们。

### 0.4.1 已有交互控制工程进展（候选旧范围）

现有候选中，“选哪个/哪一项？”类自然语音 final 会进入“读取当前画面可见选项”的待处理请求；已测控制提示只要求读出清晰可见的选项名称、不推荐不猜测，普通选择陈述（如“我选桑启”）不触发截图请求。这描述的是此前候选的窄范围测试，不再限制新请求的助手范围。蓝牙或麦克风安全状态处于 blocked 时，服务端 `ready` 不会覆盖本地阻塞状态；状态变化刷新运行通知。安全审计 `INPUT_STATE` 仅记录 flags 与 routed device type，不记录转写正文。

13:37:53 的音频系统历史记录显示 A2DP 断开后路由回到 speaker、SCO 未启用；该记录只说明路由变化，不能证明用户某句话发生于该时刻。14:27:32 的旧真机会话 ASR FINAL `requestLike=true` 后进入手动 QUESTION 和新帧请求，证明此前版本这次触发链有效。本局旧客户端会话记录 11 个 FINAL、6 个为空，计数器显示 2 个手动请求、8 个主动请求和 5 个 accepted results；其余状态未审计，不能完整归因。此前 Android JVM 216 项及 Android 14 模拟器 `AssistantControls` 9/9 是旧候选回归结果；此前4218bf94…候选已装至小米 Android 14；其回归为Android JVM 225 项、instrumentation 10 项、Python 网关 103 项及 Debug/Test APK、lint 通过。新增本地 ASR 纯合成 PCM instrumentation 的比分/出装/选人三例为 170/294/352ms，关键词与 `isRequest` 断言通过。服务器 ASR WSS 对照 274ms 为历史单点；服务器现为 `vision_only` / `asr_ready=false`。端侧合成测试不代表真实麦克风、实际发声、P95 或温升。此前 Mac 合成视觉选择 Qwen。上述都是工程/合成证据，不证明外部可闻声音、物理时延/温升、玩家验收或评分变化；35 项符合度和 52.96% 历史判定、既有严格小地图门槛均保持不变，`verified` 与 `release_ready` 未改。
