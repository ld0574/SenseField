# 真机与玩家测试记录

> **当前只用于开发试装记录。** 当前 debug APK 内置 profile 默认关闭检测器；开发试装脚本会把 `hok_minimap_hd_bootstrap.android.json` 推送到 Download，供测试者手动导入并开启实验识别器。候选仍未通过严格 parity，也未达到 `release_ready`，因此使用导入 profile 的会话不能作为最终门禁证据。
>
> **最终门禁暂不执行。** 只有未来构建并冻结一个将已验证 profile、匹配模型和 native library 内置的 APK，且其候选 metadata 标记可发布、parity 门禁通过后，才按本页最终采集流程操作。该最终会话必须使用 APK 内置 profile，不导入外部配置；当前开发安装脚本不适用于最终门禁。

- 日期／测试人：
- 手机型号／Android 版本：
- 游戏版本／界面缩放／屏幕分辨率：
- GameProfile 名称与文件 SHA-256：
- 场景（练习场／其他获允许场景）：
- 截屏授权：整屏／单应用；截屏是否正常：
- 连续运行时长（至少 15 分钟）：
- `SessionStart` 中的 session ID：
- Android logcat 原始文件与 SHA-256：
- 本次实际安装的 APK 文件与 SHA-256：
- 是否使用 APK 内置 profile，且未导入其他配置：
- 外部录像是否未剪辑，视频和音频是否都覆盖整段会话：
- Android 日志与外部录像的对齐方法：
- 实际声音的收录方法（外放／耳机及所用设备）：

| 项目 | 记录 |
| --- | --- |
| 三类事件中实际启用的识别器 | |
| 有效提示／误报／漏报 | |
| 方位正确／错误／无法判断 | |
| 证据首次可见到实际音效起点的 P95（同一段外部录像测量） | |
| 外部记录的原始文件、时间标注 CSV、统计报告路径 | |
| 非过期 `CueEvent` 数／CSV 行数／cue ID 是否完全一致 | |
| 横屏实际处理时长／平均采样 FPS／最大帧间隔 | |
| 游戏帧率、卡顿及测量方法 | |
| 电量、温度或明显发热 | |
| 游戏原声是否被遮盖 | |
| 黑屏、授权中断、横屏切换问题 | |
| 最终关闭的事件类型及原因 | |

## Hero 试玩反馈

- 哪些提示帮助她注意到原本漏看的画面：
- 哪些提示太频繁、太晚、方位不清或妨碍判断：
- 她建议保留／关闭／改词的提示：
- 下一轮需要改动：

记录只存本地。分享演示前取得录像和反馈当事人的授权。

## 最终门禁采集（仅冻结 APK 准备完成后）

开始前确认已有通过模型和 parity 门禁的发布候选，并确认它把本轮冻结 profile、param/bin 与 native library 一并打包。当前开发 APK 不满足这些条件；不要用开发 APK、导入 profile 或开发安装脚本提交最终门禁。准备好最终 APK 后，先复制并固定待测文件，再安装这一个文件；测试结束前不要重新构建或覆盖它：

```sh
mkdir -p validation/private
cp android/app/build/outputs/apk/debug/app-debug.apk \
  validation/private/mapassist-tested.apk
shasum -a 256 validation/private/mapassist-tested.apk
adb install -r validation/private/mapassist-tested.apk
```

最终门禁要求本次会话使用该 APK 内置的 `profile.json`，并确认实际安装 APK、profile、param/bin 和离线留出预测的哈希完全一致。若手机曾导入过开发配置，先清除应用数据，再安装冻结 APK；不要再导入其他 profile。当前 APK 的内置 profile 默认关闭检测器，单独勾选实验开关不能替代未来的冻结 APK 构建。

连接手机后，先清空旧日志，再在电脑上持续保存本次会话的 logcat：

```sh
adb logcat -c
adb logcat -v time 'MapAssistCapture:I' '*:S' \
  > validation/private/physical-device-logcat.txt
```

同时用另一台设备录制手机屏幕和实际听到的声音。录像保持原始连续时间轴，至少运行 15 分钟，并在结束前下拉通知栏、点“停止”结束助手；确认持续通知消失。之后在保存 logcat 的终端按 Ctrl+C。日志应恰好包含目标 UUID 的一条 `SessionStart`、若干 `CueEvent` 和一条 `SessionSummary`。不要在计入 15 分钟处理时长的会话中暂停；需要验证暂停／恢复时另开开发会话。

先解析该 UUID，检查横屏处理区间、平均采样率、最大帧间隔和音频排队结果：

```sh
PYTHONPATH=python python3 -m mapassist.android_session_log \
  validation/private/physical-device-logcat.txt \
  --session-id '从 SessionStart 复制的 UUID' \
  --output validation/private/android-session-report.json
```

复制 `LATENCY.example.csv` 为私有记录。外部录像中每一个**非过期** `CueEvent` 都写一行：`event_id` 自行编号，`cue_id` 原样复制日志值，`kind` 与日志事件一致，`evidence_ms` 和 `audio_ms` 都以外部录像开头为 0 毫秒。听不到对应提示时将 `audio_ms` 留空并说明原因，不用别的声音补齐。CSV 不得添加日志中不存在的 cue ID，也不能遗漏非过期 cue。

```sh
PYTHONPATH=python python3 -m mapassist.measure_latency \
  validation/private/latency.csv \
  --output validation/private/latency-report.json
```

最终每个启用事件至少需要 5 个实际发声样本，总计至少 20 个；任何漏发声都会使验收失败。Android 日志还需证明横屏处理跨度至少 15 分钟、平均至少 8 FPS、最大处理帧间隔不超过 2 秒。

完成记录后，将同一证据整理为 `FINAL_EVIDENCE.example.json` 的 schema 3 格式。除冻结 profile、ncnn param/bin、native library、实际安装 APK、`device_log`、`session_id`、原始外部录像和 CSV 的 SHA-256 外，还要填写 train/val/test COCO 标注、匹配录像 manifest 和候选元数据的 SHA-256。COCO 文件名须保留导出器的 `<match_id>_<9 位毫秒时间戳>.png` 格式；门禁会从中重建来源对局分组，检查 test 非空、train/val/test 不交叉，并将 test 的 `match_id` 和录像 SHA-256 绑定到本次留出录像。

```sh
PYTHONPATH=python python3 -m mapassist.validation_gate \
  validation/private/final-evidence.json \
  --output validation/private/final-report.json
```

门禁会重新读取外部录像的音视频 packet 时间轴、Android 会话日志、延迟 CSV、留出标签、逐帧预测时间轴、预测 provenance 和所有证据文件哈希；还会解包 APK，确认其中 profile 和 ncnn param/bin 与离线留出预测使用的冻结文件完全一致。本页文字记录用于补充说明，不能代替机器验收报告。
