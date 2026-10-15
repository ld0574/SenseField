# BLOCKED — 任务书七（只读守卫与扫描凭证）

分支 feature/jev-l2-judgment，HEAD c168e9c。本轮代码交付已完成并通过自己的单测；以下四项是数字/口径层面的对不上，按书里「卡住就写 BLOCKED.md 并如实报告」留裁，未为了变绿改任何判卷标准。

## 1. 任务 0 门禁基线与当轮实测对不上（并发会话所致，已按书以自数为准）
书里写基线 classes=82 / tests=636 / failures=7。我 19:0x 开工复跑实测 classes=83 / tests=644 / failures=8 / errors=0 / skipped=0。
多出的那 1 个红不是环境问题也不是我的地界：`HapticSemanticsTest#unknownAndNullTagsFallBackToTheSameDefaultWaveform`，由并行会话在 19:02 落盘（未跟踪文件，与 `HapticSemantics.java` 同批）。我未修、未 skip、未缩面。
书里的基线漂移条款已预告此事（"这些数字都只是管理者当轮读数，不是常量…以你的读数为准"），因此判定为「非阻断，按自数报」，未停工。

## 2. 终态门禁复跑被并行会话在飞的测试文件挡住（连败 3 次后收手）
本轮两个新文件落盘后的完整执行读数（19:09）：classes 84 / tests 663 / failures 7 / errors 0 / skipped 0，7 个红与书里点名逐名一致；我的 `ReadOnlyGuardTest` 在该次真实执行里 tests="19" failures="0" errors="0" skipped="0"。
此后我又跑了 3 次 `:app:testDebugUnitTest --offline --rerun-tasks`，三次都在测试执行前就失败：

```
> Task :app:compileDebugUnitTestJavaWithJavac FAILED
* What went wrong:
Execution failed for task ':app:compileDebugUnitTestJavaWithJavac'.
> Compilation failed; see the compiler output below.
  WordingGuardTest.java:55: 需要')'   （:56 :57 :58 :59 :60 同）
BUILD FAILED in 6s~11s / 24 actionable tasks: 24 executed
```

报错文件是并行会话（另一份任务书）当时正在写的 `android/app/src/test/java/com/openkhub/sensefield/WordingGuardTest.java`，不在我的白名单，我不改它也不删它。
后果：19:09 之后没有新的完整门禁读数，`android/app/build/test-results/testDebugUnitTest/` 目录时间戳仍是 19:09，我引用的就是那次。原始日志：`_scratch_diag/_tmp_gate_brief7_run.log`、`_scratch_diag/_tmp_gate_brief7_final.log`。
需要裁的：等那位的文件能编译后，任意一份后续任务书重跑一次门禁即可复核我的读数（我的两个文件 19:09 后未再改动，mtime 见 PROGRESS.md 交接段）。

## 3. 完成条件 2「当前源码 0 命中」的口径：守卫自身黑名单声明行 7 条自指命中
按禁区要求逐行点名（文件＋行＋API），未加 exclude、未把注释改判成跳过、未删黑名单条目：

```
ReadOnlyGuard.java:36 dispatchGesture
ReadOnlyGuard.java:37 GestureDescription
ReadOnlyGuard.java:38 injectInputEvent
ReadOnlyGuard.java:39 performGlobalAction
ReadOnlyGuard.java:40 GLOBAL_ACTION_
ReadOnlyGuard.java:41 enqueueInputEvent
ReadOnlyGuard.java:42 MotionEvent.obtain
```

这 7 行是守卫的数据表本身（`INJECTION_APIS` 的字面量，一条一行），是"把黑名单写死成数据、别留成一句注释"这条要求与"当前源码 0 命中"这条验收的固有冲突：任何一个复跑人打开扫描面，都必然扫到守卫自己的词表。
本轮处置：照原样打印在扫描输出里，并单独说明 **除这 7 行外，扫描面 165 个文件全部 0 命中（生产代码路径 0 命中）**；反向验证的绿态也是按这个口径读的（探针贡献 0 命中）。
需要裁的：是接受"自指声明行"作为凭证的一部分（默认建议），还是允许把守卫自身的词表行改写成拆分拼接形式——后者我不做，因为那是给扫描面打洞。

## 4. 扫描面自数与管理者读数差 2（已点名成因）
我数到：`sensefield` 包 `.java` 158、`res/xml` `.xml` 6、`AndroidManifest.xml` 1，合计 165；管理者当轮 155／156 与 162／163。
差值成因逐个点名：`HapticSemantics.java`（并行会话 19:01，未跟踪）、`WordingGuard.java`（并行会话，未跟踪）各 +1，本轮我自己新增的 `ReadOnlyGuard.java` +1；反向验证用的 `TmpInjectionProbe.java` 只在红态那一次让 scanned_files 临时变 166，删除后回到 165（`find` 复认 0 条）。
探针删除之后 `android/app/src/main/java` 下 `.java` 总数：158（开工时 156）。


## 5. 终态门禁已补上（19:17 第 4 次复跑，解除第 2 条）
并行会话 19:14 把 `WordingGuardTest.java` 改到能编译后，我重跑成功：classes 85 / tests 675 / failures 7 / errors 0 / skipped 0，7 个红逐名为书里点名的那批；较 19:09 的 84／663 多出的 1 类 12 测试全属并行会话的 WordingGuardTest。我的 ReadOnlyGuardTest 19 条全绿。同轮扫描复认 scanned_files 165、total_hits 7（仅守卫自身声明行）。
第 2 条保留作过程记录：那份文件在 19:0x-19:14 之间确实让门禁编译失败三次，任何后续任务书若再遇到同一挡路文件，等它编译通过再取数即可，不要去动它。

---

## 6.（v1.1 储备）Adaptive Audio Context / 环境底噪自适应调节

**状态**：BLOCKED，不进当前 MVP。设计决策与实测前提记录于此，供后续启动时直接引用，避免重复调研。

**要解决的真问题**：视障/注意力受限用户「听不清提示」比「没有提示」更危险——它制造虚假的安全感。真实物理环境（地铁、街道、嘈杂室内）会淹没语音提示。

**2026-10-15 任务 0 代码库梳理结论（重要，改变了原方案的性质）**：

原方案的技术主张**大部分已实现**，真正的缺口只有「输出映射层」：

| 层 | 状态 | 位置 |
|---|---|---|
| 感知：RMS 能量 | 已实现 | `VoiceActivityGate.accept()` |
| 感知：噪声底噪自适应 | 已实现 | `VoiceActivityGate` 的 `noise` EMA + 阈值 `max(350, noise*3.2)` |
| 感知：滞后防抖 | 已实现 | 进入 `voicedFrames>=15` / 退出 `quietFrames>=45` |
| 隐私：不留历史音频 | 已实现 | `VoiceInputMetrics`（never retains samples）+ pre-roll 有界 30 帧 |
| 权限：RECORD_AUDIO | 已落地 | `AndroidManifest.xml`，助手语音输入已在使用 |
| 开关：默认关闭先例 | 已有 | `AssistantSettings.ENABLED` + ASSISTANT_DEFAULT_OFF 记录 |
| **输出：音量增益曲线** | **缺口** | 挂点已有：`CuePlayer.volume()` 读 `GameProfile` 的 `volume` 键（默认 45%） |
| **输出：触觉兜底降级** | **缺口** | 接缝已有：`HapticSemantics`（旁挂，未接入播放路径） |
| **输出：Toast 环境提示** | **缺口** | 仓库已有 Toast 用法先例 |
| 高频增强 1k–4kHz +3dB | 缺口（真新增） | 全仓无频谱/EQ 代码 |

**原 BLOCKED 三条理由的复核**：
1. ~~权限敏感性增加用户信任成本~~ — **已不成立**：RECORD_AUDIO 已在用，助手语音输入是既有能力。
2. ~~需大量真实数据校准阈值~~ — **部分成立但对象变了**：噪声底噪 EMA 已在生产运行，要校准的不再是「从零定阈值」，而是「复用已有 EMA 定档位边界（Quiet/Moderate/Noisy）」。
3. 优先级让步（「能跑通」优先） — **仍成立**。

**原方案中与仓库实际不符的参数（照做会错）**：
- 采样率：方案写 `~23ms @44.1kHz`；实际 **16000 Hz、10ms/帧**，且 `VoiceActivityGate.accept()` 对 `samples.length != 160` 直接抛异常。任何新消费者必须按 16k/10ms 对齐。
- 音源：方案建议 `VOICE_COMMUNICATION`；实际用 `VOICE_RECOGNITION`（ASR 取向，带系统降噪）。

**启动前必须先做的前置项**：`VoiceActivityGate` **没有单测**（只有 `VoiceInputMetricsTest`）。AAC 要复用它的噪声底噪读数，应先补 `VoiceActivityGateTest`，否则复用的是一个无回归保护的生产组件。

**两项接口预留的实际情况（原方案指定动作无法按原文执行）**：
1. `noise_fallback` 字段：全仓**没有用户模板 Schema**，「任务 1 的 Schema」不存在，无处可加。已作为设计约束记录于此，待模板系统立项时作为需求带入。
2. `trigger_source` 参数：不能加在 `HapticPolicy.create()`——该签名被 `HapticPolicyTest` 冻结，且任务书十红线禁止改动。干净接缝是旁挂的 `HapticSemantics`（纯 JVM、未接入播放路径）。**是否在 HapticSemantics 上加 trigger_source，待领导裁定。**

**隐私与安全边界（沿用原方案，实测已满足的标 ★）**：
- ★ 不用 MediaRecorder 生成音频文件；仅 AudioRecord 取 PCM，处理完即覆盖
- ★ 内存中不留历史音频（`VoiceInputMetrics` 明示 never retains samples）
- 非语义化：只算声学特征，不做 ASR / 关键词检测
- 全局开关，默认关闭，首次开启二次确认
- 权限文案：「仅用于检测环境噪音大小以调节提示音量，不会录制或上传任何声音内容。」
- 触觉兜底：音频通道信噪比低于阈值时，关键提示降级到触觉通道
