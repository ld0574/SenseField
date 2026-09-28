<p align="center">
  <img src="assets/sensefield-icon.png" width="160" height="160" alt="听野 SenseField 图标">
</p>

<h1 align="center">听野 · SenseField</h1>

<p align="center"><strong>把视野之外的战局，交给耳朵和手。</strong></p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-22C55E.svg" alt="Apache-2.0 License"></a>
  <img src="https://img.shields.io/badge/platform-Android%2013%20%7C%2014-3DDC84.svg" alt="Android 13 and 14">
  <img src="https://img.shields.io/badge/status-experimental-F59E0B.svg" alt="Experimental status">
</p>

<p align="center">
  罕见·无界黑客松参赛项目<br>
  赛题：为视力障碍玩家打造识别全屏地图的工具
</p>

---

**听野（SenseField）** 是一个面向视野狭窄玩家的安卓实验原型。它尝试把玩家视野之外的关键战局转译成空间音频、触觉与高对比视觉线索，补充信息而不代替操作。当前原型优先验证小地图识别和“视野记忆”，所有游戏决策与操作仍由玩家完成。

## 为什么做听野

对管状视野、低视力等玩家来说，放大屏幕会让可见范围变得更小，通用读屏软件又难以跟上实时对局。他们需要的不是被“代打”，而是在不遮挡仅存视野的前提下，及时知道周边发生了什么。

> **补信息，不添乱；做队友，不做代打。**

听野不是“告诉玩家该怎么打”的竞技助手，而是一层无障碍信息转换：重新表达玩家本来能够看见、却可能无法持续观察或记住的事件。兵线计时、路线推荐、打龙决策等战术指挥不属于当前 MVP。

## 工作方式

```text
用户授权截屏 → 端侧识别关键事件 → 优先级排序与去重 → 声音/触觉线索 → 玩家自主决策
```

- 屏幕画面只在用户授权后获取，并在设备本地处理。
- 优先识别已呈现在游戏界面中、但处于玩家有效视野之外的信息。
- 只提示必要事件，通过合并、冷却和优先级减少信息过载。
- 工具不产生任何游戏输入，不帮玩家点击、走位或攻击。

本项目是参赛团队独立制作的非官方作品，与腾讯、天美工作室群及《王者荣耀》官方无隶属、合作或认可关系。“王者荣耀”等名称、商标及游戏内容归相应权利人所有。本仓库不提供游戏客户端、游戏素材或玩家录像。

## 使用边界

- 仅通过 Android `MediaProjection` 获取用户明确授权的屏幕画面，并在设备本地处理。
- 不注入游戏、不读取游戏内存、不模拟触控。
- 私有录像、抽帧、标注数据和实验产物均由 `.gitignore` 排除。
- 这是尚未完成实体机验收的研究原型；实际使用前需自行核实游戏条款和赛事规则。

## 当前进度

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| Android 13/14 截屏与横屏取帧 | 已实现 | 已完成模拟器链路验证 |
| 小地图敌方头像识别 | HD-only bootstrap 本机开发候选，默认关闭 | 当前人工复核 train 为 video1+8+3+4+5 共 534 图／981 框，val 为 video2-HD 100 图／197 框；test 为空。video2-HD 用于选模和阈值，因此指标只是同场开发诊断；video9/12 继续封存，候选 `verified=false`，APK 默认关闭检测器 |
| 小地图自适应定位 | 旧版定位器实验留档，尚未接入 Android | v2 使用旧低清 video6 val；其定位指标仅作历史，不属于当前 HD-only 验证，也不代表独立发布门禁通过。video8 布局帧为同场诊断；见[定位器记录](validation/MINIMAP_LOCATOR.md) |
| 简短声音提示 | 已实现 | 事件合并、冷却与优先级规则可用 |
| 视野记忆 | 已实现实验版 | APPEAR / TRACK / DISAPPEAR / LAST_DIRECTION；连续命中后才显示，消失需多帧确认，DISAPPEAR 后最后位置与移动方向保留 4 秒 |
| 空间音频与触觉编码 | 已接入实验版 | 左右声道增益和方向震动已实现；尚未完成真实玩家验收 |
| 录像回放、多人标注与离线评测 | 已实现 | 支持 COCO 数据导出 |
| 独立留出对局与实体机验收 | 待测 | video12 与 video9 已封存，当前不得运行模型或查看预测；Android 13/14 实体机仍待验收 |

完整指标、证据边界与待验证项见 [当前验证状态](validation/STATUS.md)。

## 本地运行

需要 Python 3.10+ 和 FFmpeg。完整步骤见 [团队协作与本地运行](docs/团队协作与本地运行.md)。

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -e '.[test]'
python -m pytest -q
```

启动当前 HD 人工复核队列（共用 `8765` 端口）：

```sh
PYTHONPATH=python python3 -m mapassist.annotation_server \
  --dataset video1-hd=data/private/minimap-review-video1-hd-v1 \
  --dataset video2-hd=data/private/minimap-review-video2-hd-v1 \
  --dataset video3-hd=data/private/minimap-video3hd-new-match-20260928-a/blind-review-v1 \
  --dataset video4-hd=data/private/minimap-review-video4-hd-v1 \
  --dataset video5-hd=data/private/minimap-review-video5-hd-v1 \
  --dataset video7-edge=data/private/minimap-review-video7-edge-recheck-v1 \
  --dataset video8-hd=data/private/minimap-video8-holdout-v1/blind-review-v3-safe-roi \
  --dataset video10-11-hd=data/private/minimap-video10-11-hd-development-v1/queue \
  --host 127.0.0.1 --port 8765 --open
```

当前 bootstrap 使用 video1+8+3+4+5 train（534 图／981 框）和 video2-HD val（100 图／197 框）；test 为空，不能报告最终成绩。video2-HD 用于选模和阈值，因此当前候选指标只是同场开发诊断，不是独立成绩。固定 `c=0.51` 下，PyTorch TP/FP/FN 为 `174/13/23`（P/R/F1 `93.0481% / 88.3249% / 90.6250%`），Android 等价 ncnn 为 `175/14/22`（`92.5926% / 88.8325% / 90.6736%`）。ONNX/ncnn 严格 raw parity 和 Android 等价检测门禁均失败；候选 `verified=false`，仅供本机调试，公共默认 detector 仍关闭。video9/12 没有读取或运行模型，继续封存；队列状态见[验证状态](validation/STATUS.md)与[录像接收记录](validation/VIDEO_INTAKE_2026-09-28.md)。

Android Studio 直接打开 `android/`。首次原生构建会下载并校验固定版本的 ncnn Android 依赖；调试 APK 的命令行构建方式记录在[团队协作文档](docs/团队协作与本地运行.md)。

公开源码不包含 Roboflow 图片、模型权重或训练产物，默认 profile 关闭小地图识别器；获准在本机复现实验的步骤见[团队运行文档](docs/团队协作与本地运行.md)。当前公开历史仍含旧提交中的模型文件；历史清理决定见[发布清单](docs/GITHUB发布检查清单.md)。

“视野记忆”是可选实验功能。用户需在首页主动开启并授予“显示在其他应用上层”权限；提示层不接收触控，并请求系统用安全窗口将其排除在截屏内容之外。系统只在 APPEAR / DISAPPEAR 状态切换时产生触觉反馈；只有消失目标具有可靠移动向量时才播报方向，并设置语音冷却，避免把持续识别变成持续打扰。该功能仍依赖实验识别器的准确度，安全窗口行为也需实体机确认；目前未完成实体机与目标玩家验收，默认关闭。

## 仓库结构

| 目录 | 内容 |
| --- | --- |
| `android/` | 安卓截屏与声音提示 |
| `native/` | C++ 检测和事件规则 |
| `python/mapassist/` | 回放、标注、评测与数据导出 |
| `profiles/` | 画面区域和检测参数 |
| `validation/` | 测试结果与验收记录 |

## 文档

- [赛题背景（公开脱敏版）](docs/赛题背景.md)
- [参赛技术方案](docs/技术方案.md)
- [视野记忆产品与事件设计](docs/视野记忆.md)
- [团队协作与本地运行](docs/团队协作与本地运行.md)
- [外部数据引入与预训练](docs/外部数据引入与预训练.md)
- [当前验证状态](validation/STATUS.md)
- [外部数据审计与预训练验证](validation/EXTERNAL_PRETRAINING.md)
- [小地图自动定位器](validation/MINIMAP_LOCATOR.md)
- [video7 扩展人工标注](validation/VIDEO7_EXPANDED.md)
- [video1 高清重导出与标签迁移](validation/VIDEO1_HD.md)
- [困难误报加权实验](validation/HARD_NEGATIVES.md)
- [video8 真人排位冻结盲测](validation/VIDEO8.md)
- [GitHub 发布检查清单](docs/GITHUB发布检查清单.md)
- [参与开发](CONTRIBUTING.md)

## 品牌素材

- [App / GitHub 高清图标（1024×1024）](assets/sensefield-icon.png)
- [透明底品牌标记](assets/sensefield-mark.png)
- [GitHub 社交预览图（1280×640）](assets/sensefield-social-preview.png)

## 开源许可

本项目的自有代码和自行创作的项目材料按 [Apache License 2.0](LICENSE) 授权。第三方代码、模型、商标、游戏内容及其他引用材料仍遵循各自的许可与权利归属，不因本项目的 Apache-2.0 许可而改变。

ncnn、YOLOX 与 pnnx 的版本、用途和许可证见 [第三方声明](THIRD_PARTY_NOTICES.md)。

公开提交前请执行发布检查，避免上传录像、游戏画面、个人信息、签名密钥和其他未获授权的内容。
