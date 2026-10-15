# PROGRESS

## 开工回执（2026-10-15，feature/jev-l2-judgment，任务书十：跨游戏通用触觉语义表）
- 理解的目标：把触觉从"每个游戏一套震动含义"升级成跨游戏可复用的身体语言。只定义语义表与波形映射（ATTENTION 短促双震 / DANGER 长单震 / APPROACH 渐强），**不接入生产路径**，让"换个游戏不用重新学"变成可单测的事实。
- 顺序：任务 0 门禁核验 ✓ → 任务 1 纯 JVM 语义表类 → 任务 2 单测。
- 最大风险：把语义表接进 `HapticPolicy.create()` 就越界（本轮只许加新文件）；对策是完成条件里用 `git diff --stat` 硬卡"只有两个新增文件、零 modified"。
- 任务 0 结果：门禁读数 **classes 82 / tests 636 / failures 7 / errors 0 / skipped 0**，与任务书预期逐项一致。7 个红全是既存环境问题（6 个 AsrModelStoreTest + AssistantTtsCacheTest#sweepNeverFollowsAnOwnedLookingSymlinkOutsideCache），未修未跳。已读 `feature/sensefield-dev:PROGRESS.md` 前 40 行了解任务书一~九 背景，未切分支。
- 备注：本分支原先没有 PROGRESS.md，本文件为新建。

## 完成记录（2026-10-15，任务书十 两个任务全做完）
- 任务 1 ✓ `android/app/src/main/java/com/openkhub/sensefield/HapticSemantics.java`（100 行，纯 JVM 零 Android import）：
  `enum Semantic { ATTENTION, DANGER, APPROACH }` + `Waveform` + `waveform()` + `audit()` + `withinBudget()`。
  ATTENTION=`{0,45,55,45}` 两短震（190ms）；DANGER=`{0,420}` 单长震（420ms，长于 ATTENTION 任一段 45ms）；
  APPROACH=`{0,60,70,60}` 配 `{0,70,0,150}` 渐强；null/未知回落 `{0,45}` 中性单震。
- 任务 2 ✓ `HapticSemanticsTest.java` 8 个测试，覆盖两两互异、渐强严格递增、震段计数、回落确定性、
  预算边界、audit 非空、null tag 可读。

### 最终门禁
`classes 83 / tests 644 / failures 7 / errors 0 / skipped 0`
（基线 82/636/7/0/0 → classes +1、tests +8，failures 恒 7 且全是既存环境红）。

### 改动范围（红线自证）
相对本轮起点 `c168e9c`：**3 个新增、0 修改、0 删除**。三个禁改文件
`HapticPolicy.java` / `CuePlayer.java` / `CueRequest.java` 全部未动（逐个 `git diff --name-only` 核过）。
第 3 个新增是本 PROGRESS.md，代码文件正好两个。

### 过程中修的自己引入的一个失败
首跑时 `unknownAndNullTagsFallBackToTheSameDefaultWaveform` 报红：我用 `values()[0]`（=ATTENTION，
四元素波形）当"未知 tag 的对照"，而 fallback 是两元素，本就不该相等——是**我的测试写错了**，
不是实现错。改为断言"null 两次调用结果一致 + 不等于三条真实语义中任何一条"。

### 暗卷（3 条，自留抽查）
① 把 APPROACH 振幅改成递减 → `approachSwellsWithStrictlyRisingAmplitudes` 立刻红；
② 把 fallback 改成空数组 → `unknownAndNullTags...` 立刻红；
③ 把 `audit()` 掏空 → 两条 audit 断言同时红。三条都翻转，证明断言非恒真。注入已全部清除并复核。

### 下一步（未做）
语义表尚未接入任何播放路径（本轮红线明确禁止）。接入需另开任务书，接点应是
`HapticPolicy.create()` 之前的语义判定，或 `CuePlayer` 选波形处；接入后现有
`HapticPolicyTest` 的全部期望值必须保持绿（原方向词汇不许被语义表改写）。

## 任务书七开工回执（2026-10-15 19:20 左右，分支 feature/jev-l2-judgment，HEAD c168e9c）
- 目标：把「只读不写」这条对外声明固化成陌生人可复跑的凭证 —— 一个纯 JVM 只读守卫类 + 一条全仓扫描 + 一次红→绿反向验证。不改任何运行时行为。
- 顺序：任务 0 前提核验 → 任务 1 守卫类与单测 → 任务 2 扫描凭证与探针。
- 当轮自数读数：`android/app/src/main/java` 下 `.java` 156（管理者当轮 155，差 1 的成因见 BLOCKED：并行会话在 19:01 新建未跟踪的 `HapticSemantics.java`）；`res/xml` 6；`AndroidManifest.xml` 1。
- 门禁基线复跑（`--offline --rerun-tasks`）：classes 83 / tests 644 / failures 8 / errors 0 / skipped 0。任务书点名的 7 个红逐名命中；第 8 个红是并行会话 19:02 刚落盘的 `HapticSemanticsTest#unknownAndNullTagsFallBackToTheSameDefaultWaveform`，不属本轮地界、不修不跳，已写进 BLOCKED 留裁。
- 五条读数复跑全部一致：`Match3HintOverlay.java:41` FLAG_NOT_TOUCHABLE、`:50-52` InputManager 仅取 getMaximumObscuringOpacityForTouch（无注入调用）、`accessibility_service_config.xml:6-7` 无 canPerformGestures、清单无 INJECT_EVENTS、三处 performClick 全点自己界面。
- 最大风险：这棵树正被别的会话并发写入（工作树 19:00 后从 2 条未跟踪变 3~4 条），扫描面总数与 java 计数可能在我两次跑之间 ±1。对策：每次扫描都打印实数并附成因，不拿管理者的数当自己的证据。
