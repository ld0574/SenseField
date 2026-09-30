# 听野 SenseField 0.3.0-alpha.1

> GitHub pre-release developer preview 草稿。发布前请由团队逐项核对[检查清单](RELEASE_CHECKLIST_0.3.0-alpha.1.md)，并在 GitHub 上勾选 **pre-release**。

## 这是什么

`0.3.0-alpha.1` 是用于审阅端侧链路、提示交互和公开仓库边界的 Android developer preview，版本号为 `versionCode 7`。它仍是实验原型，不是最终验收版本，也不代表对目标游戏的官方支持。

## 本候选包含

- 用户主动授权后的 Android `MediaProjection` 整屏采集。
- 设备本地的小地图实验识别、事件筛选以及声音、触觉和可选视觉提示链路。
- 运行状态通知和可选的非交互悬浮提示层。
- 当前 APK 只构建 `arm64-v8a`，最低 Android API 29，目标 API 35。

## 权限用途和数据边界

- `MediaProjection`：每次截屏会话由用户在系统对话框明确授权，用于获取整屏帧；停止共享后服务应停止处理。
- `SYSTEM_ALERT_WINDOW`：仅用于获得用户允许后显示非交互的悬浮提示层；它不接收触控，也不代替游戏输入。
- `POST_NOTIFICATIONS`：用于前台截屏服务的运行状态、暂停和健康提示，不用于上传内容。
- 屏幕帧、模型推理和事件筛选在设备本地完成；项目不读取游戏进程或内存，也不上传屏幕画面。

## 已知限制

- 内置 HD 小地图模型是实验候选，尚未通过独立留出对局验收。当前开发评估不能写成独立测试成绩。v6 512 双类模型已完成 ONNX/TorchScript/ncnn 导出，产物和报告位于忽略目录 `build/ignored/v6-512-export`；ONNX raw 严格门禁失败（最大误差 `0.0006387 > 0.0005`），但 12 张图的最终 detection arrays 全部一致；ncnn Android 等价严格门禁通过。2 线程桌面 preprocess+inference P95 为 `28.9552 ms`。本次未改 Android assets/profile，当前 release APK 仍绑定单类 `minimap_enemy`；test 为空且尚无独立测试对局或 Android 真机验证，仍未达到 release-ready。
- 主画面边缘复核批次已完成人工复核：160 张中 47 张 `corrected`、113 张 `negative`，共 114 个框；完成审计见 `data/private/main-edge-review-v1/review-batch-v1/review-completion-audit.json`。这些红色候选和困难负样本只用于诊断，不能作为 `enemy hero` 真值，因为小兵、野怪也可能有相似血条，且镜头会漂移。主画面边缘分支默认关闭，发布版不启用。后续必须先做上下文英雄分类和玩家相关性门；详见[主画面边缘复核记录](../validation/MAIN_EDGE_REVIEW.md)。
- 当前仅支持 `arm64-v8a`。发布页面必须同时写明这一限制，并提供 APK SHA-256。
- Android 14 首轮实体机只证明了部分授权、横屏采集、ncnn 和提示播放链路；端到端 P95、连续 15 分钟会话和目标玩家体验尚未完成最终验收。
- 首装实验开关和新头像提醒的默认状态仍可在应用设置中关闭；实验模型不应被表述为已验证的游戏识别能力。

## 构建和签名

从仓库根目录运行：

```sh
bash scripts/build_android_preview.sh
```

未提供完整签名环境变量时，脚本只构建并核验 Debug candidate，输出名称为：

```text
sensefield-0.3.0-alpha.1-arm64-v8a-debug-candidate.apk
```

需要签名的 release candidate 时，由发布者在构建环境中提供以下四个变量：

```text
SENSEFIELD_KEYSTORE_PATH
SENSEFIELD_KEY_ALIAS
SENSEFIELD_KEYSTORE_PASSWORD
SENSEFIELD_KEY_PASSWORD
```

四个变量必须同时存在；脚本只读取已有发布 keystore，不生成、复制或提交发布 keystore，不把密码写入仓库，也不会调用 GitHub Release。没有签名变量时，Debug candidate 沿用 Android Gradle 的标准 debug signing。签名 candidate 应使用：

```text
sensefield-0.3.0-alpha.1-arm64-v8a-signed-candidate.apk
```

脚本会在 APK 旁生成同名 `.sha256` 文件，并输出文件大小、SHA-256 和签名证书摘要。发布附件前重新核对这些值，并在 GitHub Release body 中保留上述限制。

## 当前已核验候选

2026-09-30 在本地构建的 Debug candidate：

```text
android/app/build/outputs/preview/sensefield-0.3.0-alpha.1-arm64-v8a-debug-candidate.apk
17,156,242 bytes
SHA-256 49043142158a360abb7bb3c5d8c9c7ed24828374f67bdb888e9eedcb445a6b0a
```

`apksigner` 确认 v2 签名有效，signer 为 Android Debug，证书 SHA-256 为 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`。`aapt` 确认包名 `com.openkhub.sensefield`、versionCode 7、versionName `0.3.0-alpha.1`、minSdk 29 和 targetSdk 35；APK 仅包含 `arm64-v8a`。如重新构建，必须以新产物的大小、哈希和签名输出替换这组值。

## 发布文案建议

可以把下面这段作为 GitHub pre-release 摘要：

> 听野 SenseField `0.3.0-alpha.1` 是 Android arm64-v8a developer preview，用于审阅用户授权的整屏采集、端侧实验识别和提示链路。实验小地图模型尚未通过独立留出验收，公共默认 profile 关闭主画面边缘候选分支且上下文分类器尚未接入；MediaProjection、悬浮窗和通知权限的用途见 README。请把它视为研究原型，先阅读已知限制并核对 APK SHA-256。
