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

**听野（SenseField）** 是面向视野狭窄和低视力玩家的 Android 游戏辅助工具。它把玩家难以持续观察的游戏信息转成声音、语音和触觉，并为开心消消乐提供交换位置高亮。游戏判断和操作始终由玩家完成。

**当前版本：0.4.5／code22 · Android 10 及以上 · arm64 · APK 约 24.36 MB**

[下载安卓安装包](https://gitee.com/leda/SenseField/releases/download/0.4.5/sensefieldv0.4.5.apk) · [Gitee 发行版](https://gitee.com/leda/SenseField/releases/tag/0.4.5) · [安装与使用说明](docs/releases/0.4.5/RELEASE_NOTES.md) · [历史版本](docs/releases/README.md)

两款游戏代码已合入 `main`；AI 助手默认关闭。2026-10-10 已核对公开 APK 与本地验证包的大小、SHA-256，以及匹配的线上更新清单。消消乐仍为体验版，工程检查和公开发布不代表玩家体验或温升验收通过，详见[当前验证状态](validation/STATUS.md)。

## 谁适合试用

- **优先人群**：管状视野、周边视野缺损的玩家。
- **探索人群**：其他类型低视力，仍能完成游戏主要操作的玩家。
- **当前不支持**：全盲玩家。

放大屏幕可能让同一时刻可见的范围更小，通用读屏也难以跟上实时对局。听野尝试补充屏幕边缘和小地图中已经出现的信息，让玩家少一些反复寻找。

> **补信息，不添乱；做队友，不做代打。**

## 目前能做什么

| 功能 | 当前行为 |
| --- | --- |
| 王者荣耀辅助 | 在手机本地识别小地图中可见的敌方信息，筛选、去重后提供附近敌人与方位提醒；核心预警不依赖网络。 |
| 离线提醒与说明 | 高频固定提示内置「游戏解说 · 男声」和「游戏向导 · 女声」，默认男声；固定提示和完整说明无需手机 TTS，可调整语速与音量。 |
| 开心消消乐辅助（体验版） | 自动确认棋盘，播报行列并高亮需要交换的两颗棋子；依据已确认的任务贡献排序，可靠时提供靠近目标的准备建议。动态提示使用玩家选择的手机语音引擎。 |
| 大字与学习 | 页面遵循系统字号，分组说明按需展开；完整说明支持按段听、暂停继续和跳过。 |
| 语音与画面助手（实验） | 默认关闭，可在设置中开启；语音在手机识别，画面问答通过线上服务处理，上传范围需单独授权。响应速度、回答质量与可听性仍待改进。 |
| 更新与反馈 | 从固定网站读取版本清单、从 Gitee 下载 APK，校验后交给系统安装器；诊断包和试用记录由玩家或测试负责人导出。 |

消消乐仍有目标漏识别、建议收益不足和提示取消的反馈；复杂障碍、新元素与多步策略尚未完成验证，不保证最优交换或通关。[最新试用与整改记录](validation/match3/MATCH3_TASK_FEEDBACK_0_4_5_2026-10-10.md)保留已知问题及工程证据。王者提醒的独立准确率、范围标定、跨设备声音和受控温升也继续验证，不用开发回放替代实际体验。

## 开始使用

1. 安装 APK，选择「王者荣耀」或「开心消消乐」。
2. 在设置中试听声音，确认音量、语速与提醒偏好。消消乐动态语音需手机提供可用的中文 TTS 引擎；是否联网取决于所选引擎。
3. 点击「开始辅助」，完成屏幕录制授权后自动打开对应游戏。首次说明可按段听，也可跳过。
4. 消消乐交换高亮首次需要悬浮显示权限；拒绝后仍可使用语音。结束时停止辅助，按需要导出测试记录与反馈。

AI 助手需另行开启。连续语音首次使用会下载约 153 MiB 的识别资源，校验后在手机离线运行；不开启助手时无需下载这些资源。画面问答默认连接听野线上服务。

## 隐私与使用边界

```text
授权屏幕采集 → 本地识别 → 筛选、排序与去重 → 提醒或高亮 → 玩家自主操作
```

- 屏幕采集使用 Android `MediaProjection`，每次由玩家授权；本地预警不上传画面，不读取游戏进程或内存。
- 可选助手的语音音频在手机内存中处理，不上传。开启画面理解后，问题文字、当前截图和最多两张近期缩图经 HTTPS 发送到助手网关与视觉模型；低频主动观察还需额外开启。
- 上传画面可能包含聊天或通知；服务端与模型供应商的日志及留存以实际部署和账户条款为准。模型 API 密钥留在服务端。
- 工具不注入游戏、不模拟触控、不代替玩家操作。原始录像、诊断截图、标注和私有实验材料不纳入公开仓库。

本项目由参赛团队独立开发，是非官方作品，与腾讯、天美工作室群、《王者荣耀》及《开心消消乐》官方无隶属、合作或认可关系。相关名称、商标和游戏内容归各自权利人所有；试用时请核实游戏条款和赛事规则。

## 开发与构建

完整配置见[团队协作与本地运行](docs/development/团队协作与本地运行.md)。Android 使用 JDK 17 或 Android Studio 内置 JDK，并准备 Android SDK／NDK；可在 Android Studio 中打开 `android/`。首次原生构建会下载并校验项目锁定的 ncnn 依赖。

```sh
cd android
./gradlew assembleDebug
```

上面生成开发用 Debug 包。模型权重未纳入 Git；需按 `android/app/src/main/assets/` 中对应 metadata 提供匹配文件，才能运行相应识别。预合成语音的准备与校验见[离线语音开发说明](docs/development/offline-speech.md)。干净克隆不等于已具备全部运行素材。

正式交付在仓库根目录运行：

```sh
bash scripts/build_android_preview.sh
```

必须同时配置 `SENSEFIELD_KEYSTORE_PATH`、`SENSEFIELD_KEY_ALIAS`、`SENSEFIELD_KEYSTORE_PASSWORD` 和 `SENSEFIELD_KEY_PASSWORD`，沿用已有签名证书。脚本构建并校验正式 Release；缺少签名或完整内置语音时停止，不会回退 Debug，也不会自动上传。

当前唯一交付文件：

```text
output/releases/0.4.5/
├── gitee-upload/sensefieldv0.4.5.apk
└── cdn-upload/latest.json
```

负责人先上传 Gitee APK 并校验，再更新固定网站清单；同版本修订以 SHA-256 区分。模型分片继续复用，步骤见[Gitee 分发与后续发布](deploy/assistant/CDN发布.md)。

Python 诊断、回放和评测工具需要 Python 3.10 及以上，录像处理另需 FFmpeg：

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -e '.[test]'
```

## 项目与文档入口

| 目录 | 内容 |
| --- | --- |
| `android/` | Android 界面、屏幕采集、声音、助手和更新流程 |
| `native/` | C++ 小地图识别与事件逻辑 |
| `python/mapassist/` | 诊断、数据处理、回放、标注与评测 |
| `training/`、`profiles/` | 模型工具、识别区域与配置 |
| `deploy/assistant/` | 助手部署与分发脚本、反代模板和说明 |
| `docs/` | 产品、设计、开发与发布文档 |
| `validation/` | 按主题整理的验证与排查记录 |

[完整文档索引](docs/README.md) · [贡献指南](CONTRIBUTING.md)

| 主题 | 入口 |
| --- | --- |
| 产品与设计 | [赛题背景](docs/design/赛题背景.md) · [技术方案](docs/design/技术方案.md) · [视野记忆设计](docs/design/视野记忆.md) |
| 实施与符合度 | [实施路线](docs/plans/黑客松方案收敛与实施路线.md) · [符合度改造方案](docs/plans/符合度改造方案.md) |
| 消消乐改进 | [目标价值排序](docs/plans/消消乐目标价值排序.md) · [新元素可信识别](docs/plans/消消乐新元素可信识别.md) · [自动回归](docs/development/match3-regression.md) |
| 安装与发布 | [0.4.5 使用说明](docs/releases/0.4.5/RELEASE_NOTES.md) · [发布索引](docs/releases/README.md) · [Gitee 分发步骤](deploy/assistant/CDN发布.md) |
| 助手与更新 | [助手部署](deploy/assistant/README.md) · [Android 自动更新](docs/development/app-update.md) |
| 测试与反馈 | [当前验证状态](validation/STATUS.md) · [验证索引](validation/README.md) · [离线诊断](validation/protocols/DIAGNOSTIC_REPORT.md) · [玩家试用套件](validation/protocols/PLAYER_TRIAL_KIT.md) |

历史模型指标和逐局记录保留在对应验证文档中，不作为当前版本的独立验收结果。`docs/` 只保存文档，APK 与交付文件留在 `output/releases/`。

## 开源许可

本项目自有代码和材料按 [Apache License 2.0](LICENSE) 授权。第三方代码、模型、音频、商标及游戏内容遵循各自的权利和许可，详见[第三方声明](THIRD_PARTY_NOTICES.md)。
