<p align="center">
  <img src="assets/sensefield-icon.png" width="160" height="160" alt="听野 SenseField 图标">
</p>

<h1 align="center">听野 · SenseField</h1>

<p align="center"><strong>把视野之外的战局，交给耳朵和手。</strong></p>

<p align="center">
  罕见·无界黑客松参赛项目<br>
  赛题：为视力障碍玩家打造识别全屏地图的工具
</p>

---

**听野（SenseField）** 是一个面向视野狭窄玩家的安卓实验原型。玩家授权系统截屏后，工具识别画面中已经可见的小地图敌人、主画面边缘敌人和队友危险信号，再将关键信息转译成简短声音与触觉提示；所有游戏决策和操作仍由玩家完成。

本项目是参赛团队独立制作的非官方作品，与腾讯、天美工作室群及《王者荣耀》官方无隶属、合作或认可关系。“王者荣耀”等名称、商标及游戏内容归相应权利人所有。本仓库不提供游戏客户端、游戏素材或玩家录像。

## 使用边界

- 仅通过 Android `MediaProjection` 获取用户明确授权的屏幕画面，并在设备本地处理。
- 不注入游戏、不读取游戏内存、不模拟触控。
- 私有录像、抽帧、标注数据和实验产物均由 `.gitignore` 排除。
- 这是尚未完成实体机验收的研究原型；实际使用前需自行核实游戏条款和赛事规则。

## 当前进度

- 已实现 Android 13/14 截屏服务、横屏取帧和音频提示，并完成模拟器验证。
- 已实现录像回放、多人标注网站、离线评测和 COCO 数据导出。
- 当前优先识别小地图中带红色阵营外圈的敌方英雄头像。
- 当前开发集召回率尚未达到比赛指标，新的独立留出对局仍待录制，结果见 [validation/STATUS.md](validation/STATUS.md)。

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

- [团队协作与本地运行](docs/团队协作与本地运行.md)
- [当前验证状态](validation/STATUS.md)
- [GitHub 发布检查清单](docs/GITHUB发布检查清单.md)
- [参与开发](CONTRIBUTING.md)

## 品牌素材

- [App / GitHub 高清图标（1024×1024）](assets/sensefield-icon.png)
- [透明底品牌标记](assets/sensefield-mark.png)
- [GitHub 社交预览图（1280×640）](assets/sensefield-social-preview.png)

公开仓库前请执行发布检查，避免提交录像、游戏画面、个人信息、签名密钥和其他无权公开的内容。项目目前尚未确定开源许可证；团队确定许可前，代码仅供查看，不代表已授予复制、修改或再分发权限。
