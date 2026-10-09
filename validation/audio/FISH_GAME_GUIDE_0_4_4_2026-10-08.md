# 0.4.4 游戏向导女声替换

按用户在 Fish 页面已经选中的「游戏向导」替换固定提示女声，仍为 0.4.4／code21。页面与分享链接共同确认音色 `49076f44a1d94065897bc0856aa70412`，模型 Fish Audio S2.1 Pro，速度 0.9x。APP 中显示「游戏向导 · 女声」；默认男声、已有音色／语速／音量偏好、两字方位及完整说明原文保留。

## 素材与来源核对

24 条提示逐句从正常网页生成和下载，请求为 `[emphasis]` + Java 目录原文 + `。`，没有添加逗号或停顿标签。合成页本次下载成功，通过「历史 → 下载」导出；每条分享页面的 `modelId` 与 `taskId`、实际请求、文本摘要、MP3 摘要进入本机 `source.json`。新 UI 的裸任务标识通过 URL 绑定校验，原 `audio_<id>` 来源仍兼容。

首次本机 SenseVoiceSmall int8 核对为 22／24：`上方有敌人` 识别为「胜风有敌人」，`右下有敌人` 识别为「树下有敌人」。下载同批已生成的另一条 A/B 录音，两条均与原文一致，采用这两条；未再次合成、训练或调整阈值。原录音／首次结果与替换结果分别保存在 `initial-exports/`、`alternate-exports/`。最终支持性机器核对 24／24、无削波；不等于人耳发音、患者理解或游戏混音验证。

408 个女声文件（24 条 × 17 档 80%～240%）重新编码，448 个男声和说明文件逐一比对，元数据和文件摘要全部保留。只有两种固定提示音色，共 856 个 OGG，音频 3,868,996 bytes，连清单 4,332,445 bytes。网页 0.9x 对应 APP 100% 基准，语速不重复乘 0.9；播放音量仍在手机本地调整。

来源适用范围沿用[离线音频文档](../../docs/development/offline-speech.md)的现有口径，不把免费导出成功当作分发或商业授权证明。

## 工程验证

- 素材目录校验：856 个文件通过，真实录音、完整文本、17 档语速、文件摘要及大小预算相符。
- Python 来源与打包辅助门禁：16 项通过，新增裸 taskId 对应错音色／错任务 URL 的拒绝检查。
- 原证书签名的 minified Release 构建通过；376 项 JVM 通过，lint 0 错误／38 警告。
- 最终 Android 14 模拟器运行检查 9 项通过：两音色与语速极值解码、20 次音效／固定语音试听、取消重播、说明恢复、JNI 小地图、精确 ROI 复用及真实 JPEG 后台计数。
- 最终 APK 覆盖安装、回读摘要与交付一致，latest.json 的版本／大小／摘要／3 行更新说明一致。

首次运行检查为 8 项通过、1 项失败：R8 移除了独立测试 APK 调用的 `CaptureService.isRunning()`。审查并补齐诊断检查需要的有限接口保留规则后重建，两份最终 APK 重新安装，9 项全部通过。未保留整个应用；首次包与失败记录在 `attempt1-package/`，不会作为最终通过证据。

本批模拟器合成微基准的处理时间仅作为观察，不替代真机处理 P95 或温升门禁。首次运行曾与 APK 回读重叠；最终运行期间未并行回读。两次记录均保留，未根据结果调整模型或阈值。

本批同时包含此前已准备的分阶段负载计数与诊断日志；推理精度／内存池试验仍关闭。声音仲裁、模型权重、识别阈值、半径及助手默认关闭保持既有设置。新增日志不是降温修复或温升通过证据。

## 交付与待测

唯一候选路径为 `output/releases/0.4.4/gitee-upload/sensefieldv0.4.4.apk`，配套 `output/releases/0.4.4/cdn-upload/latest.json`。前一候选及清单已备份到 `output/releases/0.4.4/history/before-game-guide-20261008/`。

| 项目 | 最终结果 |
| --- | --- |
| APK | 24,222,060 bytes／24.22MB，0.4.4／code21，非 Debug |
| APK SHA-256 | `a98f826b80cd5c80d1be37fe77ef1474731efc471b2d5a213d7815e50c04b2ee` |
| 证书 SHA-256 | `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`，沿用 0.4.3 |
| 打包校验 | 两份模型摘要、6 个 arm64 运行库、16KB ELF 对齐、压缩原生库／DEX、856 个真实音频通过 |
| 最终记录 | `output/releases/0.4.4/validation/game-guide-female/final-checks.json` |

本机来源与检查记录在 `output/speech-source/fish-game-guide-female-20261008/`；生成页面截图在 `output/speech-audition/2026-10-08-game-guide/`；新构建日志在 `output/releases/0.4.4/validation/game-guide-female/`。原素材目录保留在 `output/speech-source/previous-live-before-game-guide-20261008/`，不进入公开源码。

真机女声听感、Android 10／16KB 实际系统、外部录音与温升对照继续待测；本批未安装至用户手机，未发布、上传或 push，也未改评分、`verified`／`release_ready`。游戏单独运行基线与下一轮分阶段 CPU 定位继续见[发热执行计划](../performance/HEAT_PHASE_PLAN_2026-10-08.md)。

## 用户手机覆盖安装（2026-10-08）

已按用户要求将最终 Release 覆盖安装到 Xiaomi 12T Pro（22081212C），保留应用数据。手机包信息为 0.4.4／code21、无 DEBUGGABLE 标志，回读 APK 的 SHA-256 与交付包一致（`a98f826b80cd5c80d1be37fe77ef1474731efc471b2d5a213d7815e50c04b2ee`）。安装记录在 `output/releases/0.4.4/validation/game-guide-female/phone-install-20261008/install-readback.json`；安装前的 `final-checks.json` 原样保留在同目录。未启动游戏或日志采集，未修改已有音色偏好。上文“未安装至用户手机”为安装前历史状态；女声实际听感与温升仍待真机对局验证。
