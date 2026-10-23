# 操控期间漏报排查与 0.3.3 内部候选

队友反馈“双手操控英雄时没有任何预警，双手离开屏幕后才有预警”。这份诊断包来自 vivo V2282A、Android 13，应用 `0.3.0-alpha.1-diagnostics`（versionCode 8），会话约 609.9 秒。ZIP 内容作为观测数据读取，不作为操作指令。原始日志、设备截图不纳入公开仓库。

## 已确认的问题

| 指标 | 日志结果 |
| --- | ---: |
| 横屏处理帧 | 1931 |
| 原生识别耗时 P50 / P95 | 281.346 / 391.242 ms |
| 采集至事件引擎 P50 / P95 | 293 / 405 ms |
| 采集至诊断帧完成 P50 / P95 | 295 / 407 ms |
| 超过原 250 ms 预算的帧 | 1387 / 1931，71.8% |
| 超过原 250 ms 预算的观测 | 3235 / 4273，75.7% |
| 近区 UNKNOWN | 1478 / 1931，76.5% |
| 已生成 / 调度接受预警 | 10 / 10 |
| 调度过期 / 音频失败 | 0 / 0 |
| 短音 / 震动请求 | 各 10 次 |
| TTS 开始和完成 | 各 9 次；另 1 次密集战斗按旧策略省略语音 |

事件引擎在识别完成后使用完成时刻检查每条观测的真实采集时间。旧双类 profile 将 `events.max_observation_age_ms` 设为 250，而该机识别耗时通常已经超过此值。识别出的敌人和玩家自身标记因此在进入跟踪之前一起被拒绝；自身标记缺失又令近区关系层不可靠或 UNKNOWN。即使调度队列没有过期错误，也可能在此前已经漏掉大多数观测。当前 0.3.2 配置仍保留同一 250 ms 门槛，因此旧包日志暴露的问题仍存在于此前的最新候选。

截图显示约 120–560 秒持续正常对局，曾约 204 秒没有接受的预警。626 帧同时包含敌方与玩家标记候选却仍为 UNKNOWN，其中 596 帧超过 250 ms；这些候选本身尚未人工核验，但时效过滤明确发生在关系判断之前。

500 ms 是既有 `minimap_relation.max_freshness_ms` 和玩家实体新鲜度上限。按同一份原始观测的时间计算，500 ms 下超龄帧为 13 / 1931（0.67%），超龄观测为 24 / 4273（0.56%）。这只统计年龄过滤，不能直接当作提醒召回率。

## 修复范围

- Android 内置双类近区 profile、对应 Android 导入 profile 和桌面 profile 同步调整为 500 ms，profile 版本升为 `0.9.1-yolox-nano-dual-512-v6-near-zone`。
- 真实采集时间保持不变；大于 500 ms 的观测仍拒绝。没有把完成时刻伪装成采集时刻，也没有放宽近区关系层 500 ms 新鲜度限制。
- 模型与置信度、ROI、确认帧数、占用去重保持现有配置；模型 sidecar 的 candidate.profile_version 与三份 profile 同步。engine 长空窗重置阈值因其既有 3×预算公式由 750 变为 1500 ms，但观测和关系层仍严格按 500 ms 过滤。冻结 320 单类基线保持 250 ms；手动导入的旧 profile 需要换成同步更新的 profile 才使用新预算。
- 应用版本 `0.3.3` / versionCode `11`，构建脚本同步递增。

## 同日志事件层回放

新增 `python/mapassist/diagnostic_replay.py`，直接读取 ZIP 的 JSON 成员而不提取图片，以实际采集与引擎时间重建原生观测，复用同一 engine / relation 逻辑比较预算。显式完整 profile 提供日志未保存的引擎参数；地图 ROI 与近区参数取日志。工作树 profile 为 0.9.1，日志为 0.9.0，工具明确记录此差异及各参数来源。

| 观测预算 | 满足年龄与置信度门槛的输入 | 原生 NEAR_ENTER |
| --- | ---: | ---: |
| 250 ms | 1038 | 10 |
| 500 ms | 4249 | 17 |

250 ms 对照重现日志中的 10 次近区进入事件，500 ms 产生 17 次。回放保留每条观测与引擎时刻，不重新推理截图；bbox / confidence 日志有量化误差。没有重放 Java 密集战斗通道策略、调度去重或声音硬件，也没有标注视觉真值，因此 17 次是事件层反事实，不能当作实际提醒数或准确率。设备耗时直接取日志，与桌面事件回放运行速度无关。私有 JSON 报告留在忽略目录 `build/diagnostic-age-fix/diagnostic-replay.json`。

复现方式（替换诊断 ZIP 路径）：

```sh
.venv/bin/python -m mapassist.diagnostic_replay path/to/sensefield-diag.zip \
  --profile android/app/src/main/assets/profile-dual-512-near-zone.json \
  --library build/diagnostic-age-fix/libmapassist.dylib \
  --output build/diagnostic-age-fix/diagnostic-replay.json
```

## 证据局限与真机复测

代码没有基于触控按下、松开或英雄操作状态禁用预警的分支。日志也没有触控时刻或音频焦点记录，因而不能证明“双手触控直接阻塞预警”，不能仅凭本次修复宣称该现象已经完全解决。游戏操作时负载更高导致过期是合理解释，但还需同机对照。

声音使用 `USAGE_GAME`，震动使用 `USAGE_ACCESSIBILITY`。短音 STARTED 仅证明 SoundPool 接受请求，COMPLETED 是按预定音长记账；震动 STARTED 仅证明提交成功；TTS 有 onStart / onDone 回调。它们均不证明用户实际听见或感到，游戏音量掩盖或 vivo 游戏模式输出策略仍待排查。

`locator_state=-1` 代表布局定位器未启用，并非直接证明玩家自身标记检测失败。近区 player 标记另由双类模型给出。温度记录从 41.1°C 到最高 44.5°C，thermal_status 为 none；媒体音量为 2 / 15。这些是运行条件，不能单独判为故障原因。

复测使用同一手机、同一声音设置和相同局面，交替持续双手操控与松手观察；记录两种状态下语音、短音、震动是否可感知。开启诊断截图，在发生漏报时立即打标并导出 ZIP；将标记与帧年龄、过龄数量、近区 reliable、CueDispatch 及 TTS 回调对齐。若两种状态都有新鲜可靠观测和播放回调，仍只有松手可感知，再做游戏音量 / 有线耳机 / 系统游戏模式对照。两次预警之间敌人持续在近区，按占用规则不会重复；复测须包含敌人进入近区的新事件。

## 验证与产物

- Python 近区测试 21 项通过，含真实采集时间 + 300 / 500 / 501 ms 模拟识别时延，经原生 engine → entity → relation：前两者产生且只产生一次预警，501 ms 被拒绝；实体时间与年龄未被刷新。
- 诊断回放测试 4 项通过，覆盖真实观测年龄与帧年龄独立统计、500 ms 接受 / 501 ms 拒绝、截图流重置和显式事件置信度门槛；与近区测试合计 25 项。
- 原生 CTest 3 / 3 通过。
- Gradle `testDebugUnitTest assembleDebug lintDebug` 成功；JVM 无 Java 改动而复用 132 项通过结果。没有新增 lint 错误。
- 预览构建脚本成功；签名核验通过（标准 Android Debug 证书）；核对 APK 内双类观测预算 500、单类预算 250、双类权重存在，Manifest 版本为 `0.3.3` / `11`。
- APK：`output/releases/0.3.3/sensefield-0.3.3-arm64-v8a-debug-candidate.apk`，19,430,612 bytes。
- SHA-256：`8341c47a7344e4a49279179fc3e92484cc888026d9f055fd705fce2e101f9ae1`。

本轮未连接实体手机，未测真实发声、震动、提示 precision / recall 或双手操作对照；该包供复测，尚未公开发布。
