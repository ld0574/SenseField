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

**听野（SenseField）** 是一个面向视野狭窄玩家的安卓实验原型。它尝试把玩家视野之外的关键战局转译成空间音频与触觉线索，补充信息而不代替操作。当前原型优先验证小地图识别和简短声音提示，所有游戏决策与操作仍由玩家完成。

## 为什么做听野

对管状视野、低视力等玩家来说，放大屏幕会让可见范围变得更小，通用读屏软件又难以跟上实时对局。他们需要的不是被“代打”，而是在不遮挡仅存视野的前提下，及时知道周边发生了什么。

> **补信息，不添乱；做队友，不做代打。**

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
| 小地图敌方头像识别 | 开发中 | 当前优先识别带红色阵营外圈的头像 |
| 简短声音提示 | 已实现 | 事件合并、冷却与优先级规则可用 |
| 空间音频与触觉编码 | 计划中 | 尚未完成真实玩家验收 |
| 录像回放、多人标注与离线评测 | 已实现 | 支持 COCO 数据导出 |
| 独立留出对局与实体机验收 | 待完成 | 当前开发集召回率尚未达到比赛指标 |

完整指标、证据边界与待验证项见 [当前验证状态](validation/STATUS.md)。

## 本地运行

需要 Python 3.10+ 和 FFmpeg。完整步骤见 [团队协作与本地运行](docs/团队协作与本地运行.md)。

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -e '.[test]'
python -m pytest -q
```

启动统一标注网站（页面内切换新密集队列和已完成队列）：

```sh
PYTHONPATH=python python3 -m mapassist.annotation_server \
  --dataset dense-v1-6=data/private/minimap-review-v4-dense \
  --dataset reviewed-v1-5=data/private/minimap-review-v3 \
  --dataset reviewed-v6=data/private/holdout-video6/blind-review \
  --host 127.0.0.1 --port 8765 --open
```

Android Studio 直接打开 `android/`。调试 APK 的命令行构建方式也记录在[团队协作文档](docs/团队协作与本地运行.md)。

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
- [团队协作与本地运行](docs/团队协作与本地运行.md)
- [当前验证状态](validation/STATUS.md)
- [GitHub 发布检查清单](docs/GITHUB发布检查清单.md)
- [参与开发](CONTRIBUTING.md)

## 品牌素材

- [App / GitHub 高清图标（1024×1024）](assets/sensefield-icon.png)
- [透明底品牌标记](assets/sensefield-mark.png)
- [GitHub 社交预览图（1280×640）](assets/sensefield-social-preview.png)

## 开源许可

本项目的自有代码和自行创作的项目材料按 [Apache License 2.0](LICENSE) 授权。第三方代码、模型、商标、游戏内容及其他引用材料仍遵循各自的许可与权利归属，不因本项目的 Apache-2.0 许可而改变。

公开提交前请执行发布检查，避免上传录像、游戏画面、个人信息、签名密钥和其他未获授权的内容。
