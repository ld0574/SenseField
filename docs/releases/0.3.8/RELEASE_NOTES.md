# 听野 0.3.8 公开体验版

构建交付日期：2026-10-03；公开发布日期：2026-10-04。版本 `0.3.8 / versionCode 16`，已发布为 [GitHub 公开体验版](https://github.com/ld0574/SenseField/releases/tag/v0.3.8)（普通 Release，Latest）。公开发布不代表最终稳定性验收通过。

## 本版变化

- 更早减少手机发热时的识别负载。温度较高时降低识别频率，提醒可能变慢；实际降温效果仍需体验验证。
- 明确通知展开后的「标记问题」入口。

## 安装与使用

支持 Android 10 及以上、arm64-v8a，可覆盖安装已有同签名版本。

下载：[APK](https://github.com/ld0574/SenseField/releases/download/v0.3.8/sensefield-0.3.8-arm64-v8a-debug-candidate.apk) · [安装 ZIP](https://github.com/ld0574/SenseField/releases/download/v0.3.8/sensefield-0.3.8-install.zip) · [使用说明](https://github.com/ld0574/SenseField/releases/download/v0.3.8/installation-guide.txt) · [SHA-256 校验文件](https://github.com/ld0574/SenseField/releases/download/v0.3.8/sensefield-0.3.8-arm64-v8a-debug-candidate.apk.sha256)。

屏幕采集每次需要用户明确授权；悬浮窗权限用于可选提示层；通知用于运行状态以及标记、暂停和停止。识别在本机完成，记录不会自动上传，画面保存由设置控制。

1. 开始辅助前，先试听并确认媒体音量与语音。短音区分左右，上下方位请听语音，震动提醒附近情况。
2. 遇到漏报或误报，下拉通知栏，点听野通知右侧箭头或向下展开，再点「标记问题」。按钮在展开后显示。
3. 结束后停止辅助，到「测试记录与反馈」导出日志，无需额外在这台手机上录屏。
4. 手机明显发烫时先停止辅助和连续测试，待冷却后再体验；测试时记录热点、充电和游戏设置，便于比较。

## 验证与体验反馈

交付时已通过代码及 Android 13 模拟器检查，实际温升、双手操作和提醒时效仍需真机复测。交付后收到「整体没明显问题、发热体感好一点」的主观反馈；没有随附新诊断日志或测试时长，不能推导量化温降、实际发声延迟或稳定性门禁通过。

## 交付文件与来源

公开附件从 `output/releases/0.3.8/` 上传；APK、ZIP 和校验文件仍保存在该目录，`docs/` 只保存说明。安装 ZIP 仅含同一 APK、校验文件及更新后的使用说明，未上传诊断或截图。

| 项目 | 记录 |
| --- | --- |
| 公开 APK | `sensefield-0.3.8-arm64-v8a-debug-candidate.apk`（与中文本地安装包字节相同） |
| Android versionCode | `16` |
| APK 大小 | 19,780,113 bytes |
| APK SHA-256 | `86f026fe253f0d2c6ce37787cfc7e7d75d443785d9005bb46b29a2e823c58e6f` |

说明来源：`output/releases/0.3.8/听野v0.3.8 使用说明.txt`，公开使用说明为同目录 `installation-guide.txt`。构建元数据：`handoff.json`；发布元数据：`github-release.json`、`release-asset-manifest.json`。

标签 `v0.3.8` 指向 `408f32a44a7ff58c5d69c5be8efa3d8d352afa9f`。APK 未重建；实际 Manifest、v2 签名、大小及 SHA-256 已复核，采用与既有体验版相同的 Android Debug 签名。

后续验证与证据边界见[0.3.8 改造、验证与交付后反馈](../../../validation/NEXT_VERSION_0.3.8_2026-10-03.md)。
