<p align="center">
  <img src="assets/sensefield-mark.png" width="112" alt="听野 SenseField 标志" align="middle">
  &nbsp;&nbsp;<strong>×</strong>&nbsp;&nbsp;
  <a href="https://www.openkhub.com/">
    <img src="assets/openkhub-logo.png" width="300" alt="K-Hub 罕见病开源社区 Logo" align="middle">
  </a>
</p>

<h1 align="center">听野 · SenseField</h1>

<p align="center"><strong>把视野之外的战局，交给耳朵和手。</strong></p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-22C55E.svg" alt="Apache-2.0 License"></a>
  <img src="https://img.shields.io/badge/platform-Android%2010%2B-3DDC84.svg" alt="Android 10及以上，arm64">
  <img src="https://img.shields.io/badge/status-experimental-F59E0B.svg" alt="Experimental status">
</p>

<p align="center">
  罕见·无界黑客松参赛项目<br>
  赛题：遗传性视网膜色素变性+游戏辅助工具
</p>

---

**听野（SenseField）** 是面向视野狭窄和低视力玩家的 Android 实验原型。它尝试把游戏画面中玩家可能难以持续观察的信息，转译成方向短音、语音和触觉，补充信息而不代替操作。优先邀请管状视野、周边视野缺损的玩家；其他类型低视力、仍能完成主要操作的玩家可探索试用；当前不支持全盲。

**下载与使用：[听野 0.3.8 公开体验版](https://github.com/ld0574/SenseField/releases/tag/v0.3.8) · [安装与使用说明](docs/releases/0.3.8/RELEASE_NOTES.md)**

本项目由参赛团队独立开发，是非官方作品，与腾讯、天美工作室群及《王者荣耀》官方无隶属、合作或认可关系。相关名称、商标和游戏内容归各自权利人所有。

GitHub公开体验版仍为`0.3.8`（Android `versionCode 16`）；用户已提供Gitee 0.4.1下载地址，迁移修订需负责人覆盖上传。当前源码版本为 `0.4.1 / versionCode 18`，当前按用户要求改为Gitee Release分发APK与语音模型，固定更新清单保留`https://888413.xyz/apk/latest.json`（仅几百字节）。同版本修订以SHA-256区分，不新增0.4.2；迁移包先手动安装一次，后续可自动下载Gitee附件。语音模型拆成90MB和70.3MB两份，后续版本共用、缓存继续复用，见[Gitee分发记录](validation/GITEE_DISTRIBUTION_2026-10-06.md)。整体 `verified` 与 `release_ready` 仍为 false。0.4.0 合并树当时记录的语音/画面助手、消消乐与相关测试证据保留在[0.4.0 历史说明](docs/releases/0.4.0/RELEASE_NOTES.md)，不与 0.4.1 更新专项测试混为一谈。Linux 单次合成 ASR/GLM 延迟数据见[0.4.0 网关部署记录](docs/development/assistant-gateway-test-deployment.md)，不是实体麦克风或 P95 测量。外部实声时延、三组热负载对照和玩家验收仍未完成。0.4.1 当前验证进度见[自动更新验证记录](validation/APP_UPDATE_0.4.1_2026-10-04.md)，版本与历史交付见[发布索引](docs/releases/README.md)、[0.3.8记录](validation/NEXT_VERSION_0.3.8_2026-10-03.md)和[评审补强Goal](validation/SCORE_RECOVERY_GOAL_2026-10-03.md)。

## 为什么做听野

对管状视野、低视力等玩家来说，放大屏幕会让同一时刻可见的范围更小，通用读屏软件又难以跟上实时对局。玩家需要的是在不遮挡仅存视野的前提下，及时感知屏幕边缘和小地图上已经出现的信息。

> **补信息，不添乱；做队友，不做代打。**

听野是一层无障碍信息转换：重新表达玩家本来能够看见、却可能无法持续观察或记住的事件。本地预警提供事实位置线索；0.4.1源码中的可选画面助手可按玩家提问，结合可见截图给出装备、选人和对战建议，效果尚待验证。游戏判断和操作始终由玩家完成。

## 工作方式

```text
用户授权截屏 → 端侧识别关键事件 → 筛选、排序与去重 → 声音、触觉或视觉线索 → 玩家自主决策
```

- 小地图检测、事件筛选和核心预警在设备本地完成，不依赖网络。
- 0.4.1源码中的语音识别在手机本地处理，音频只在内存中流转，不上传。画面理解开启后，问题文字、当前截图和最多两张近期缩图经HTTPS网关发送至配置的视觉模型；低频主动观察只发当前画面，还需额外开启。此前0.4.0的服务器ASR路径见历史说明。
- 实验 AI 助手总开关默认关闭，旧版测试中开启的分项设置也不会自动启动助手；可在“设置 → 语音与画面助手（实验）”中主动开启，再分别授权语音和画面。关闭时不启动助手录音、语音资源准备、助手画面请求或小圆点，本地预警独立运行。当前玩家反馈仍有回复慢、回答偏长及播放不完整的问题，助手效果尚未通过验收。
- 画面可能包含游戏聊天、通知或其他敏感内容；网关和模型供应商的日志/留存条款需按实际部署与账户核对，不能据此承诺整条链路零留存。
- 只提示游戏界面中已经呈现、但可能处于玩家有效视野之外的信息。
- 通过事件确认、合并、冷却和优先级控制提示频率，减少信息过载。
- 工具不产生任何游戏输入，不帮玩家点击、走位或攻击。

## 使用边界

- 仅通过 Android `MediaProjection` 获取用户明确授权的屏幕画面；不读取游戏进程或内存。
- 连续语音首次开启从CDN下载约153 MiB固定语音资源，校验后在手机离线识别，旧缓存继续复用；画面问答默认使用听野线上服务，无需填写地址或连接码，仍需单独授权上传范围。0.4.1可选助手依据可见截图与一般游戏知识回答，可能不确定或错误；不读取隐藏敌情，也不等同于本地OCR或准确战术判断。
- 当前轻量网关部署关闭服务器ASR，仅转发视觉请求；模型API密钥保留在服务端，不进入APK或仓库，模型之间不自动切换。生产服务由部署者自行配置与验证。
- 不注入游戏、不模拟触控、不替玩家作战术判断。
- 私有录像、抽帧、标注数据和实验产物不纳入公开仓库。
- 这是尚未完成最终实体机与玩家验收的研究原型；实际使用前请核实游戏条款和赛事规则。

## 开发版本边界

- 当前 HD 小地图模型尚未通过独立留出验收。主画面红色候选不能直接代表附近的敌方英雄，该分支默认关闭且不进入本次发布；原因和后续门控见[主画面边缘复核记录](validation/MAIN_EDGE_REVIEW.md)。
- Android APK 当前只构建 `arm64-v8a`，最低 Android API 为 29，目标 API 为 35。没有 arm64-v8a 的设备不在本候选支持范围内。
- `MediaProjection` 用于在用户每次明确授权后取得整屏帧；悬浮窗权限（`SYSTEM_ALERT_WINDOW`）用于可选视觉提示与助手小圆点；通知权限用于前台截屏服务的状态和操作入口。小地图识别、筛选与本地预警在设备上处理。0.4.1的语音由手机识别，画面上传需单独开启；助手服务断开不影响本地预警。
- 没有完整签名环境变量时，发布脚本只生成名称含 `debug-candidate` 的 Debug APK，并沿用 Android Gradle 的标准 debug signing；签名材料必须由发布者通过环境变量提供，脚本不会生成或上传发布 keystore。候选构建和 GitHub Release 发布按[发布检查清单](docs/releases/GITHUB发布检查清单.md)复核，发布说明必须对应实际构建版本。

历史 2026-10-01 团队测试版已完成 Release 构建：方位语音、无障碍震动、统一授权与底部大按钮，定位状态仅日志。[历史预发布下载](https://github.com/ld0574/SenseField/releases/tag/v0.3.0-alpha.1)；[真机反馈](validation/ANDROID_LIVE_SMOKE_2026-10-01.md)和[发布说明](docs/releases/0.3.0-alpha.1/RELEASE_NOTES.md)保留实验模型与验收边界。

## 当前进度

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 0.4.1 学习与设置 | 本地工程候选，未发布 | 配置页合并提醒与振动测试；试听目录显示音效名称，方位语音保留一个左上示例。完整提醒说明原文保留，支持单段收听、播放全文、暂停后从当前句继续，播放控件固定在底部。帮助仍为分组面板，长说明正文最多150%系统字号。285项JVM回归、最终构建/lint及同11个模拟器场景在普通／200%字号下通过；最新候选已无线覆盖手机并核对摘要。真实声音／震感、TalkBack、患者理解及折叠屏游戏适配待测，见[患者反馈Goal](validation/PATIENT_FEEDBACK_GOAL_2026-10-05.md)。 |
| Android 屏幕采集 | 已实现实验链路 | Android 13/14 模拟器流程已验证；Android 14 首轮真机已跑通授权、横屏采集和提示播放，长时会话验收仍待完成。截屏服务以前台通知运行，画面只在本地处理。 |
| 小地图识别 | 首装默认启用 HD 实验模型与新头像提醒 | 本机 APK 内置启用检测器的匹配 profile；模型权重仍不纳入 Git，干净克隆需提供匹配权重才能运行。实验候选尚未通过严格跨运行时一致性和独立留出验证，可在设置中关闭。 |
| 主画面边缘候选分支 | 候选诊断完成、分类器未接入、默认关闭 | 现有标签只能作为红色候选／困难负样本诊断，不能作为敌方英雄真值；见[复核记录](validation/MAIN_EDGE_REVIEW.md)。 |
| 小地图近区提醒 | 已实现实验链路，频率修订待新对局验证 | 双类模型识别自身小地图标记；持续可见只提醒一次，进出范围不重播。短漏检/邻近换号保持已提醒状态，连续可靠缺席3秒才确认消失，之后返回仍需新2/3识别。其他目标仍在附近也可触发。半径暂从0.20缩到0.16，尚未独立标定；同一组日志实体回放90→30次不代表真机效果，见[频率修订](validation/NEAR_ZONE_FREQUENCY_REPAIR_2026-10-06.md)。 |
| 0.4.0 语音与画面助手（历史能力） | 历史工程候选，未发布 | 语音、画面理解及低频主动观察默认关闭；连续语音由用户选择。此前 0.4.0 合并树的 JVM、Android instrumentation、HTTPS/WSS 合成输入、native、Python、构建与 lint 结果见[历史记录](docs/releases/0.4.0/RELEASE_NOTES.md)，不作为 0.4.1 的测试证据。Android 10/API 29 起的静音 `USAGE_GAME` 探测轨道不能证明游戏自身轨道走耳机；耳机路由、外部实声时延、热负载与玩家验收仍待验证。 |
| 0.4.1 CDN 修订 | 同版本工程验证通过，待手动上传 | 从项目 CDN 读取清单并下载 APK；版本名称和序号相同但 SHA-256 不同时提示修订更新。用户手动覆盖上传 APK 和自动生成的清单，保留签名验证与系统安装确认。详见[本轮记录](validation/APP_UPDATE_CDN_0.4.1_2026-10-04.md)。 |
| 0.4.1 首次自动更新（历史） | 自动更新专项验证通过，未发布 | 默认检查 GitHub Latest 普通稳定 Release；每次冷启动最多检查一次，可关闭并可手动检查。用户选择后才下载，校验 APK 大小、SHA-256、包名、版本和相同签名，再交给 Android 系统安装器确认。游戏辅助运行中不下载或安装；更新服务失败不影响本地辅助。更新流程不传输画面、语音或助手凭据。JVM 全套 217 项（含 updater 新增 13 项）、Python 543 项通过/1 项跳过、4 项 Android updater instrumentation、arm64 build/lint 通过；Linux fixture 两次下载共 42,685,346 bytes。真实 UI 流程已通过系统 UPDATE 安装隔离的 0.4.2/code19 测试包。冷启动/旋转频率为代码复核项，没有专门的生命周期 instrumentation。详见[验证记录](validation/APP_UPDATE_0.4.1_2026-10-04.md)。 |
| 开心消消乐伴随体验 | 主线已合并，实验功能 | 截图标定或授权后约 1 Hz 采样棋盘，播报可形成三连的相邻交换；Jev 仅有单独配置的示例判定。识别依赖手动标定和颜色启发式，实局准确率、特殊棋子完整规则和玩家效果未验证；不会替玩家操作。 |
| 视野记忆与提示 | 实验版，需玩家验证 | 已接入多帧确认、事件跟踪、优先级、密集模式及多通道提示；0.3.5队友反馈双手操控正常。约04:35方向反馈可能与之后目标移动有关，仍待同一时刻证据。 |
| 诊断与反馈闭环 | 本地工具 | ZIP可生成cue/事件/热量/截图报告；匿名试用表可记录开关对照、理解和干扰，并导出JSON。见下方工具入口。 |
| 最终验收 | 进行中 | 仍需独立对局、实体机长时运行、实际发声测量和目标玩家评估；公开Release不代表最终验收通过。 |

历史320单类基线的开发评估如下。置信度为 `0.67`、NMS 为 `0.5`，框匹配 IoU 阈值为 `0.5`；它不是当前512双类近区模型的质量表。

| 评估集 | TP / FP / FN | 精确率 | 召回率 | F1 |
| --- | ---: | ---: | ---: | ---: |
| Android 同款 ncnn 开发验证集（230 张图、400 个真值框） | 353 / 15 / 47 | 95.92% | 88.25% | 91.93% |
| 跨来源开发诊断集（121 张图、211 个真值框） | 192 / 16 / 19 | 92.31% | 91.00% | 91.65% |

当前v6 512双类开发集：自身标记precision/visible recall为95.45%/90.32%，中心误差P95为短边1.71%；敌方precision/recall/F1为95.35%/87.23%/91.11%。这些数据参与选模和阈值选择，test为空；ONNX raw仍未通过原0.0005门槛。详见[模型接入记录](validation/MODEL_PIPELINE.md)。0.3.5真机处理约2.08 FPS、电池温度38.2→40.8°C，只有约8分24秒，不能证明15分钟稳定性或受控降热。完整证据边界见[当前验证状态](validation/STATUS.md)。

## 诊断与玩家试用

- [离线诊断报告](validation/DIAGNOSTIC_REPORT.md)：从应用导出的ZIP生成本地HTML/JSON，逐条检查提示、事件截图和运行负载。播放回调与实际听到分开记录。
- [离线诊断对照](validation/DIAGNOSTIC_COMPARE.md)：并列两局的处理、复制、队列、截图覆盖和温度指标，保留条件差异、中断及重复输入等证据缺口。
- [0.3.7两局热反馈](validation/HEAT_RETEST_0.3.7_2026-10-03.md) · [0.3.8修正记录](validation/NEXT_VERSION_0.3.8_2026-10-03.md)：提前降载并说明提示延迟代价，真机降温仍待验证。
- [匿名玩家试用套件](validation/PLAYER_TRIAL_KIT.md) · [打开离线记录表](validation/player-trial.html)：记录辅助开关对照、提示理解/感知、帮助和干扰；不自动上传，导出后再由负责人保管。
- [助手外部音频测量](validation/GOAL_0.4.0_2026-10-03.md)：用外部录像标记实际说话、停播和第一段有用回答；`python -m mapassist.measure_assistant_latency` 只接受外部录音标注，不以 ASR、TTS 或播放回调代替物理声音。
- [Android 自动更新开发说明](docs/development/app-update.md) · [0.4.1 CDN 同版本修订记录](validation/APP_UPDATE_CDN_0.4.1_2026-10-04.md) · [首次 GitHub 更新器历史记录](validation/APP_UPDATE_0.4.1_2026-10-04.md)：当前清单默认指向项目 CDN；首次 GitHub 更新器证据保留为历史。
- [评审补强与复现入口](validation/SCORE_RECOVERY_GOAL_2026-10-03.md)：历史需求缺口、现有实现、验证边界与下一轮验收。

## 本地运行

Python 工具需要 Python 3.10 或更新版本；录像处理需要 FFmpeg。完整环境配置见[团队协作与本地运行](docs/development/团队协作与本地运行.md)。

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -e '.[test]'
```

Android 应用可用 Android Studio 打开 `android/`，也可以运行：

```sh
cd android
./gradlew assembleDebug
```

预发布候选使用 `scripts/build_android_preview.sh`。不提供完整签名环境变量时，它只生成并核验名称含 `debug-candidate` 的 Debug APK，并沿用 Android Gradle 的标准 debug signing；签名构建需要同时设置 `SENSEFIELD_KEYSTORE_PATH`、`SENSEFIELD_KEY_ALIAS`、`SENSEFIELD_KEYSTORE_PASSWORD` 和 `SENSEFIELD_KEY_PASSWORD`。脚本只读取已有发布 keystore，不创建或提交签名材料。

首次原生构建会下载并校验项目锁定版本的 ncnn Android 依赖。
模型权重未纳入 Git；需要将与 `android/app/src/main/assets/minimap-yolox-nano-320.metadata.json` 匹配的 `.param` 和 `.bin` 放入 assets，才能运行默认开启的 HD 实验识别。没有本地权重时仍可构建 APK，但开始截屏识别会提示模型文件缺失。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `android/` | Android 屏幕采集、事件处理与提示 |
| `deploy/assistant/` | 助手部署脚本、反代模板与[部署说明](deploy/assistant/README.md) |
| `native/` | C++ 视觉识别和事件逻辑 |
| `python/mapassist/` | 数据处理、回放、标注与评测工具 |
| `training/` | 模型训练与评估工具 |
| `profiles/` | 识别区域和参数配置 |
| `validation/` | 验证状态与实验记录 |

## 文档

完整目录见[文档索引](docs/README.md)，逐版本交付见[发布索引](docs/releases/README.md)。`docs/` 只保存文档，APK 和体验包留在 `output/releases/`。

| 主题 | 文档 |
| --- | --- |
| 赛题背景与用户问题 | [赛题背景](docs/design/赛题背景.md) |
| 产品和系统方案 | [技术方案](docs/design/技术方案.md)、[视野记忆设计](docs/design/视野记忆.md) |
| 实施路线与当前Goal | [黑客松方案收敛与实施路线](docs/plans/黑客松方案收敛与实施路线.md)、[历史评审核对](validation/SCORE_RECOVERY_GOAL_2026-10-03.md)、[0.4.0助手验收](validation/GOAL_0.4.0_2026-10-03.md)、[0.4.1自动更新验证](validation/APP_UPDATE_0.4.1_2026-10-04.md) |
| 端侧事件处理 | [事件感知与可靠性方案](docs/design/端侧事件感知与可靠性增强技术方案.md) |
| 助手服务部署 | [直接启动与部署步骤](deploy/assistant/README.md) |
| APK 与语音资源分发 | [Gitee 上传、同版本覆盖与后续发布](deploy/assistant/CDN发布.md) |
| 开发与本地运行 | [团队协作与本地运行](docs/development/团队协作与本地运行.md)、[Android 自动更新](docs/development/app-update.md)、[贡献指南](CONTRIBUTING.md) |
| 当前验证结论 | [验证状态](validation/STATUS.md) |
| 第三方依赖和许可 | [第三方声明](THIRD_PARTY_NOTICES.md) |

逐场录像、标注与历史实验文档保留在 `validation/` 中供需要时查阅，不逐项放在项目首页。

## 开源许可

本项目自有代码和材料按 [Apache License 2.0](LICENSE) 授权。第三方代码、模型、商标、游戏内容及其他引用材料遵循各自的权利和许可，详见[第三方声明](THIRD_PARTY_NOTICES.md)。
