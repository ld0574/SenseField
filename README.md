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

最新公开交付为 `0.3.8`（Android `versionCode 16`），公开下载为上面的 `0.3.8`。这一版提前温控降载，并说明热状态下提醒可能变慢及通知标记入口；已有发热体感改善反馈，量化温升、长时稳定性与提醒时效仍需真机验证。下一阶段版本目标为 `0.4.0 / versionCode 17`，尚无对应交付记录。版本与使用说明见[发布索引](docs/releases/README.md)，证据见[0.3.8记录](validation/NEXT_VERSION_0.3.8_2026-10-03.md)、[评审补强Goal](validation/SCORE_RECOVERY_GOAL_2026-10-03.md)。

## 为什么做听野

对管状视野、低视力等玩家来说，放大屏幕会让同一时刻可见的范围更小，通用读屏软件又难以跟上实时对局。玩家需要的是在不遮挡仅存视野的前提下，及时感知屏幕边缘和小地图上已经出现的信息。

> **补信息，不添乱；做队友，不做代打。**

听野是一层无障碍信息转换：重新表达玩家本来能够看见、却可能无法持续观察或记住的事件。它不提供兵线计时、路线推荐、打龙建议等战术指挥，游戏判断和操作始终由玩家完成。

## 工作方式

```text
用户授权截屏 → 端侧识别关键事件 → 筛选、排序与去重 → 声音、触觉或视觉线索 → 玩家自主决策
```

- 屏幕画面只在用户授权后获取，并优先在设备本地处理。
- 只提示游戏界面中已经呈现、但可能处于玩家有效视野之外的信息。
- 通过事件确认、合并、冷却和优先级控制提示频率，减少信息过载。
- 工具不产生任何游戏输入，不帮玩家点击、走位或攻击。

## 使用边界

- 仅通过 Android `MediaProjection` 获取用户明确授权的屏幕画面；不读取游戏进程或内存。
- 不注入游戏、不模拟触控、不替玩家作战术判断。
- 私有录像、抽帧、标注数据和实验产物不纳入公开仓库。
- 这是尚未完成最终实体机与玩家验收的研究原型；实际使用前请核实游戏条款和赛事规则。

## 开发版本边界

- 当前 HD 小地图模型尚未通过独立留出验收。主画面红色候选不能直接代表附近的敌方英雄，该分支默认关闭且不进入本次发布；原因和后续门控见[主画面边缘复核记录](validation/MAIN_EDGE_REVIEW.md)。
- Android APK 当前只构建 `arm64-v8a`，最低 Android API 为 29，目标 API 为 35。没有 arm64-v8a 的设备不在本候选支持范围内。
- `MediaProjection` 用于在用户每次明确授权后取得整屏帧；悬浮窗权限（`SYSTEM_ALERT_WINDOW`）只用于显示可选的非交互提示层；通知权限用于前台截屏服务的运行状态和健康提示。识别、事件筛选和模型推理在设备本地完成，不上传屏幕画面。
- 没有完整签名环境变量时，发布脚本只生成名称含 `debug-candidate` 的 Debug APK，并沿用 Android Gradle 的标准 debug signing；签名材料必须由发布者通过环境变量提供，脚本不会生成或上传发布 keystore。候选构建和 GitHub Release 发布按[发布检查清单](docs/releases/GITHUB发布检查清单.md)复核，发布说明必须对应实际构建版本。

历史 2026-10-01 团队测试版已完成 Release 构建：方位语音、无障碍震动、统一授权与底部大按钮，定位状态仅日志。[历史预发布下载](https://github.com/ld0574/SenseField/releases/tag/v0.3.0-alpha.1)；[真机反馈](validation/ANDROID_LIVE_SMOKE_2026-10-01.md)和[发布说明](docs/releases/0.3.0-alpha.1/RELEASE_NOTES.md)保留实验模型与验收边界。

## 当前进度

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| Android 屏幕采集 | 已实现实验链路 | Android 13/14 模拟器流程已验证；Android 14 首轮真机已跑通授权、横屏采集和提示播放，长时会话验收仍待完成。截屏服务以前台通知运行，画面只在本地处理。 |
| 小地图识别 | 首装默认启用 HD 实验模型与新头像提醒 | 本机 APK 内置启用检测器的匹配 profile；模型权重仍不纳入 Git，干净克隆需提供匹配权重才能运行。实验候选尚未通过严格跨运行时一致性和独立留出验证，可在设置中关闭。 |
| 主画面边缘候选分支 | 候选诊断完成、分类器未接入、默认关闭 | 现有标签只能作为红色候选／困难负样本诊断，不能作为敌方英雄真值；见[复核记录](validation/MAIN_EDGE_REVIEW.md)。 |
| 小地图近区提醒 | 已实现实验链路，待真机与玩家验证 | 双类模型识别自身小地图标记；敌方标记进入附近时按 8 向方位提示一次，持续占用不重复。开发录像离线回放覆盖率约 91%、约 2.3 次／分钟；近区半径尚未标定，见[实施路线](docs/plans/黑客松方案收敛与实施路线.md)。 |
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
| 实施路线与当前Goal | [黑客松方案收敛与实施路线](docs/plans/黑客松方案收敛与实施路线.md)、[本阶段补强记录](validation/SCORE_RECOVERY_GOAL_2026-10-03.md) |
| 端侧事件处理 | [事件感知与可靠性方案](docs/design/端侧事件感知与可靠性增强技术方案.md) |
| 开发与本地运行 | [团队协作与本地运行](docs/development/团队协作与本地运行.md)、[贡献指南](CONTRIBUTING.md) |
| 当前验证结论 | [验证状态](validation/STATUS.md) |
| 第三方依赖和许可 | [第三方声明](THIRD_PARTY_NOTICES.md) |

逐场录像、标注与历史实验文档保留在 `validation/` 中供需要时查阅，不逐项放在项目首页。

## 开源许可

本项目自有代码和材料按 [Apache License 2.0](LICENSE) 授权。第三方代码、模型、商标、游戏内容及其他引用材料遵循各自的权利和许可，详见[第三方声明](THIRD_PARTY_NOTICES.md)。
