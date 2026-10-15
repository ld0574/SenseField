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

---

## 任务书七开工回执（2026-10-15 19:20 左右，分支 feature/jev-l2-judgment，HEAD c168e9c）
- 目标：把「只读不写」这条对外声明固化成陌生人可复跑的凭证 —— 一个纯 JVM 只读守卫类 + 一条全仓扫描 + 一次红→绿反向验证。不改任何运行时行为。
- 顺序：任务 0 前提核验 → 任务 1 守卫类与单测 → 任务 2 扫描凭证与探针。
- 当轮自数读数：`android/app/src/main/java` 下 `.java` 156（管理者当轮 155，差 1 的成因见 BLOCKED：并行会话在 19:01 新建未跟踪的 `HapticSemantics.java`）；`res/xml` 6；`AndroidManifest.xml` 1。
- 门禁基线复跑（`--offline --rerun-tasks`）：classes 83 / tests 644 / failures 8 / errors 0 / skipped 0。任务书点名的 7 个红逐名命中；第 8 个红是并行会话 19:02 刚落盘的 `HapticSemanticsTest#unknownAndNullTagsFallBackToTheSameDefaultWaveform`，不属本轮地界、不修不跳，已写进 BLOCKED 留裁。
- 五条读数复跑全部一致：`Match3HintOverlay.java:41` FLAG_NOT_TOUCHABLE、`:50-52` InputManager 仅取 getMaximumObscuringOpacityForTouch（无注入调用）、`accessibility_service_config.xml:6-7` 无 canPerformGestures、清单无 INJECT_EVENTS、三处 performClick 全点自己界面。
- 最大风险：这棵树正被别的会话并发写入（工作树 19:00 后从 2 条未跟踪变 3~4 条），扫描面总数与 java 计数可能在我两次跑之间 ±1。对策：每次扫描都打印实数并附成因，不拿管理者的数当自己的证据。

## 任务书七 交付凭证（分支 feature/jev-l2-judgment，HEAD c168e9c）

### 任务 0：五条读数逐条复跑（原文，未改一个字节）
- `Match3HintOverlay.java:41-45`：TYPE_APPLICATION_OVERLAY 窗口的 flags 为 FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS，PixelFormat.TRANSLUCENT。
- `Match3HintOverlay.java:10/50/51`：全仓只有这一处 `InputManager`（`grep -rn InputManager android/app/src/main/java/` 只回 import 行与 50 行），50 行取服务、51 行取 `getMaximumObscuringOpacityForTouch()`。**无任何注入类调用**，守卫前提成立。
- `res/xml/accessibility_service_config.xml:6-7`：只有 `android:accessibilityFlags="flagIncludeNotImportantViews|flagReportViewIds"` 与 `android:canRetrieveWindowContent="true"`，没有 canPerformGestures。
- `AndroidManifest.xml:3-11`：FOREGROUND_SERVICE、FOREGROUND_SERVICE_MEDIA_PROJECTION、FOREGROUND_SERVICE_MICROPHONE、RECORD_AUDIO、INTERNET、POST_NOTIFICATIONS、SYSTEM_ALERT_WINDOW、VIBRATE、REQUEST_INSTALL_PACKAGES。清单里没有注入事件权限。
- `performClick` 三处：`AssistantOverlay.java:124 view.performClick()`、`Match3BoardPreview.java:126 performClick()`、`:130 @Override public boolean performClick() { super.performClick(); return true; }`，全是点自己的界面。
- 开工预扫（七个黑名单词，范围 main/java + res/xml + AndroidManifest.xml）：grep 退出码 1，即 0 命中。

### 任务 1：纯 JVM 只读守卫
新增 `android/app/src/main/java/com/openkhub/sensefield/ReadOnlyGuard.java`（不 import 任何 Android 类；黑名单 7 条写死为 `INJECTION_APIS` 数据，白名单写死为 `READ_ONLY_INPUT_MANAGER_CALLS = {getMaximumObscuringOpacityForTouch}` 且只对「通过 InputManager 类名直达的成员」生效，永不豁免黑名单、永不整文件豁免；命中按行不按文件，注释行照抓；大小写与空格先折叠再判，不改结论）。
配套 `android/app/src/test/java/com/openkhub/sensefield/ReadOnlyGuardTest.java`：19 个 `@Test`，期望值全部手写常量。覆盖七个黑名单 API 各自被抓、文件名与 1 基行号、多条命中按行序、白名单那条不误报（含 import / getSystemService / 变量接收 / 类名直调四种写法）、`InputManager` 配 `injectInputEvent` 要报、`InputManager.vibrate` 要报、`InputManager.inject` 不被去重吃掉、大写与夹空格不改判、注释行照抓、`performGlobalAction(GLOBAL_ACTION_HOME)` 报两条、空输入（空表/空串/空白/null）返回空表、scanText 行号对齐、黑名单与白名单数据表本身被断言。

### 任务 2：全仓扫描凭证 + 反向验证
脚本 `_scratch_diag/_tmp_readonly_scan.py`（该目录不进 git），逐文件打印命中行或 `0 hits`，末尾打印扫描面总数。

扫描面自数：`sensefield` 包 `.java` **158** + `res/xml` `.xml` **6** + `AndroidManifest.xml` **1** = **165**（管理者当轮读数 155／156，差 2 的成因点名：并行会话在 19:01／19:0x 新建未跟踪的 `HapticSemantics.java` 与 `WordingGuard.java`，再叠加我自己新增的 `ReadOnlyGuard.java`）。分支：feature/jev-l2-judgment。

反向验证 红（探针 `TmpInjectionProbe.java` 在位，内含两行 dispatchGesture 死代码）：

```
$ ls android/app/src/main/java/com/openkhub/sensefield/ | grep -i probe
AssistantAudioRouteProbe.java
TmpInjectionProbe.java
$ python _scratch_diag/_tmp_readonly_scan.py | grep -E "^HIT|scanned_files|total_hits"
HIT  ReadOnlyGuard.java:36 dispatchGesture
HIT  ReadOnlyGuard.java:37 GestureDescription
HIT  ReadOnlyGuard.java:38 injectInputEvent
HIT  ReadOnlyGuard.java:39 performGlobalAction
HIT  ReadOnlyGuard.java:40 GLOBAL_ACTION_
HIT  ReadOnlyGuard.java:41 enqueueInputEvent
HIT  ReadOnlyGuard.java:42 MotionEvent.obtain
HIT  TmpInjectionProbe.java:9 dispatchGesture
HIT  TmpInjectionProbe.java:10 dispatchGesture
scanned_files 166
total_hits 9
```

反向验证 绿（删除探针后，`find . -name 'TmpInjectionProbe*'` 复认 0 条，仓库里再无该文件）：

```
$ rm android/app/src/main/java/com/openkhub/sensefield/TmpInjectionProbe.java
$ find . -name 'TmpInjectionProbe*' -not -path './android/app/build/*' | wc -l
0
$ python _scratch_diag/_tmp_readonly_scan.py | grep -E "^HIT|scanned_files|total_hits"
HIT  ReadOnlyGuard.java:36 dispatchGesture
HIT  ReadOnlyGuard.java:37 GestureDescription
HIT  ReadOnlyGuard.java:38 injectInputEvent
HIT  ReadOnlyGuard.java:39 performGlobalAction
HIT  ReadOnlyGuard.java:40 GLOBAL_ACTION_
HIT  ReadOnlyGuard.java:41 enqueueInputEvent
HIT  ReadOnlyGuard.java:42 MotionEvent.obtain
scanned_files 165
total_hits 7
```

绿态剩下的 7 条命中全部是守卫自身黑名单声明行（自指），**生产代码路径 0 命中、探针贡献 0 命中**；这 7 行按禁区要求逐行点名写进 BLOCKED.md 第 3 条留裁，扫描面一格未缩。

### 门禁原始读数
任务 0 开工基线（`cd android && ./gradlew.bat :app:testDebugUnitTest --offline --rerun-tasks`，回根目录 `python _scratch_diag/_tmp_gate_tally.py`）：

```
644 tests completed, 8 failed
> Task :app:testDebugUnitTest FAILED
BUILD FAILED in 49s
25 actionable tasks: 25 executed
classes 83
tests 644
failures 8
errors 0
skipped 0
bad_names 8
   com.openkhub.sensefield.AsrModelStoreTest#ignoredRangeRestartsInsteadOfAppending
   com.openkhub.sensefield.AsrModelStoreTest#interruptedDownloadResumesThenReusesVerifiedCache
   com.openkhub.sensefield.AsrModelStoreTest#oldSingleFilePrefixAndVerifiedModelCacheRemainReusable
   com.openkhub.sensefield.AsrModelStoreTest#shortEofPreservesPrefixForRetry
   com.openkhub.sensefield.AsrModelStoreTest#splitDownloadResumesCurrentPartAndIgnoresRangeWithoutLosingFirstPart
   com.openkhub.sensefield.AsrModelStoreTest#corruptSecondPartPreservesFirstAndRetriesOnlySecond
   com.openkhub.sensefield.AssistantTtsCacheTest#sweepNeverFollowsAnOwnedLookingSymlinkOutsideCache
   com.openkhub.sensefield.HapticSemanticsTest#unknownAndNullTagsFallBackToTheSameDefaultWaveform
```

本轮终态（两个新文件落盘后的完整执行，19:09；我的 19 条测试全绿，`ReadOnlyGuardTest` 结果体 tests="19" failures="0" errors="0" skipped="0"）：

```
BUILD FAILED in 16s
25 actionable tasks: 25 executed
classes 84
tests 663
failures 7
errors 0
skipped 0
bad_names 7
   （即上面前 7 条同名，未修未跳）
```

19:09 之后我又复跑 3 次终态，全部 `BUILD FAILED in 6~11s / 24 actionable tasks`，卡在 `:app:compileDebugUnitTestJavaWithJavac`，报错文件是并行会话当时正在写的 `WordingGuardTest.java:55-60`（需要 ')'），测试任务未执行，结果目录时间戳仍是 19:09。按「同一条验收连败 3 次换下一项」收手，原始日志留 `_scratch_diag/_tmp_gate_brief7_run.log`，详见 BLOCKED.md 第 4 条。

### 交接（本轮改了什么、什么留给下一份）
- 本轮进 git 地界的只有：`android/app/src/main/java/com/openkhub/sensefield/ReadOnlyGuard.java`（新增）、`android/app/src/test/java/com/openkhub/sensefield/ReadOnlyGuardTest.java`（新增）、`PROGRESS.md`（追加）、`BLOCKED.md`（新增）。脚本 `_scratch_diag/_tmp_readonly_scan.py` 在不进 git 的目录。
- 未 commit、未 push、未 tag、未发 Release、未改远端；未改任何运行时行为（窗口 flag / 无障碍配置 / 清单权限一字节未动）、未改 build.gradle 与 CMake 钉值、未新增依赖权限流程；判卷文件与既有测试类一字未改。
- 留待下一份：守卫未接 CI（书里拍的板「本轮只做扫描＋守卫」）；守卫规则（Java）与扫描脚本（Python）是同一套口径的两份实现，任何一方改动黑名单或白名单必须同步另一方，否则凭证与单测会各说各话；`_tmp_readonly_scan.py` 每次复跑都会因并行会话新增未跟踪类而改变 scanned_files，读数必须与当轮 `ls | wc -l` 同框报出。

### 终态门禁补记（19:17，第 4 次复跑成功）
挡路的 `WordingGuardTest.java` 由并行会话在 19:14 改到能编译后，我重跑了一次完整门禁（仍带 `--offline --rerun-tasks`），这次是终态读数：

```
BUILD FAILED in 16s
25 actionable tasks: 25 executed
classes 85
tests 675
failures 7
errors 0
skipped 0
bad_names 7
   com.openkhub.sensefield.AsrModelStoreTest#ignoredRangeRestartsInsteadOfAppending
   com.openkhub.sensefield.AsrModelStoreTest#interruptedDownloadResumesThenReusesVerifiedCache
   com.openkhub.sensefield.AsrModelStoreTest#oldSingleFilePrefixAndVerifiedModelCacheRemainReusable
   com.openkhub.sensefield.AsrModelStoreTest#shortEofPreservesPrefixForRetry
   com.openkhub.sensefield.AsrModelStoreTest#splitDownloadResumesCurrentPartAndIgnoresRangeWithoutLosingFirstPart
   com.openkhub.sensefield.AsrModelStoreTest#corruptSecondPartPreservesFirstAndRetriesOnlySecond
   com.openkhub.sensefield.AssistantTtsCacheTest#sweepNeverFollowsAnOwnedLookingSymlinkOutsideCache
```

classes 85 / tests 675 比 19:09 那次的 84／663 高的 1 个类与 12 条测试全是并行会话的 `WordingGuardTest`，不是我的地界；我的 `ReadOnlyGuardTest` 仍是 19 条全绿。errors=0、skipped=0、failures 恰为书里点名的那 7 个名字。同一轮扫描复认：scanned_files 165、total_hits 7（仍只有守卫自身声明行）。BLOCKED.md 第 2 条的「无终态读数」由此解除。

### 并发状态补记（19:19，交给下一份任务书的基线）
本轮期间 HEAD 被并行会话推进了一次：开工时 c168e9c，现在 2af4964（`feat(haptics)` 那笔提交，含 PROGRESS.md 一起进了版本库，所以 PROGRESS.md 从 `??` 变成 `M`）。那不是我的操作：本轮全程未 commit、未 push、未 tag。
同一棵树上当前的非我地界脏项：`WordingGuard.java`／`WordingGuardTest.java`（另一份任务书）、`JudgmentSelfTestActivity.java`／`Match3ToolsActivity.java`（被改过，我没碰）。下一份开工时请以 2af4964 为基线自数，别沿用本节任何数字。
