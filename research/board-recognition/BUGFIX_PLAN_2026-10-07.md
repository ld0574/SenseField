# BUGFIX PLAN 2026-10-07：真机「启动实时识别后一直不播报」四修

证据来源：领导 10-06 23:57 在一台 Redmi（Android 16／HyperOS，1080x2400）上抓的系统 bugreport。
本机文件名与设备标识只记在案卷里（不进本公开仓），全部读数的复跑命令与逐条对应关系
见案卷 `PROGRESS.md` 同日「真机 bugreport 复盘」段，本文件只写「怎么修、修完凭什么算好」。

取证时进程：pid 4850 / uid 10346 / 采集线程 tid 23703；日志窗右端 10-07 00:00:32。

> 落地口径说明：本文 §修复一 与 §修复三 的设计条目已按**批次一／批次二 实际落地的代码**回写
> （原稿里设想的 `CLOUD_HARD_BUDGET_MS` 看门狗、`MIN_CELL_PX = 60` 常量都没按原样实现，理由写在各条里）。
> 各条目末的「实测」行是改完当轮的量，不是计划值。

---

## 症状与因果链（一句话）

玩家给完录屏授权、切进游戏，之后一句话都没听到。原因是四件事叠在一起：
存的标定框太小（读出来全是未识别）→ 本地未知率 >25% 把云端兜底叫起来 → 云端同步跑在唯一采集线程上、
把整条流水线堵死 → 而「认不出棋盘」那条提示排在首播双重确认门之后，永远执行不到，于是坏盘等于永久静音。

四修不是四个独立 bug，是一条链的四个环节。只修第一环（框太小）仍然会静默（因为门序错），
只修门序则会变成「一直报认不出」。所以批次一同时做「离线程」和「门序」，批次二再收根上的标定质量。

---

## 修复一（P0）：云端 VLM 调用彻底离开采集线程

### 现状证据

- `Match3LiveService.handler()` 懒建唯一一个 `HandlerThread("m3live-capture")`，
  `ImageReader` 出帧回调与 800ms `tick` 都挂在它上面。
- 原云端块在 `processFrame` 里**同步**调 `CloudVision.readBoardFromServer` / `readBoard`；
  改前 `CloudVision` 的 socket 超时是 OpenRouter connect 20000ms / read 45000ms、
  自托管 connect 5000ms / read 15000ms。
- 真机读数：`00:00:03.293 tid 23703 SkJpegEncoder ... fQuality = 80 ... 356x336`（编码完成，请求刚发出）；
  此后 pid 4850 任意线程在 00:00:04～00:00:32 的日志行数实测 **0**；
  全文 `CloudVision` 命中 **0**（该类三条出口日志一条没出）。
  → 既没成功也没失败，请求仍在途中，采集线程被网络占死。

### 设计（已落地）

1. **像素工作留在采集线程，网络工作交给独立线程。**
   `CloudVision` 拆出 `encodeForUpload(Bitmap) -> String`（base64 JPEG，含原有 `scaleForUpload`），
   在采集线程调用一次；`readBoard` / `readBoardFromServer` 改为收 base64 字符串。
   为什么必须这样而不是直接把 `Bitmap crop` 丢给后台线程：
   `onImageAvailable` 在新帧到达时会 `recycle()` 上一帧，
   而 `Bitmap.createBitmap(src, x, y, w, h)` 的子图与源帧共享像素缓冲——
   后台线程最长要等十几秒，这期间必然跨过几十个新帧，等于拿被回收的像素去编码，
   抛 `RuntimeException: Canvas: trying to use a recycled bitmap` 会打死那条线程；
   未捕获异常在子线程会直接崩整个 App（本轮 bugreport 里没有任何崩溃，改完不能新造一个）。
   实测：编码在采集线程 `dispatchCloud` 内完成并 `crop.recycle()`（`Match3LiveService.java:568-576`），
   后台任务只持有 `String imageB64`。
2. **单线程 executor + 在途守卫**：`Executors.newSingleThreadExecutor`（线程名 `m3live-cloud`），
   守卫用 `AtomicBoolean cloudInFlight`：提交前 `compareAndSet(false, true)` 抢不到就不发第二个请求
   （免费档限速与串行排队是两个独立问题），任务 `finally` 里 `set(false)` 放开。
   为什么是 CAS 而不是原稿设想的 `cloudFuture.isDone()` 比对：`compareAndSet` 本身就是提交点，
   不存在「读到旧 Future 又排队一个」的竞态窗口，采集线程也不必持有 Future 引用。
3. **超时收紧代替应用侧看门狗**（此处**偏离原稿**，原稿要加 `CLOUD_HARD_BUDGET_MS = 12000` 与 `cancel(true)`）：
   `CloudVision` 两条 HTTP 的 socket 超时统一改成 connect 4000ms / read 12000ms，看门狗不加。理由两条：
   - `cancel(true)` 中断的是 executor 线程的中断位，对已经陷进 native socket 读的 `HttpURLConnection`
     不保证立刻返回；要在应用侧强加一个上界，就得从采集线程去 `disconnect()` 那条连接，
     等于把「网络对象跨线程操作」这个新交叉点引进来——而本修复的目标恰恰是消除跨线程共享。
     socket 超时是这条链路上唯一真正生效的界。
   - 在途位被慢请求长期占住的后果是**有界降级**，不是静默：`cloudInFlight` 为 true 期间只是不再发新请求，
     本地播报完全照常（这条流水线是本地优先、云端兜底）。
   OpenRouter 读超时从 45s 降到 12s 的取舍：拿「VLM 慢于 12 秒时不回」换「采集流水线永不被网络占死」，
   失败照旧回退本地读数并置 60s 冷却（`CloudVision.cooldownUntil`，实测三条失败支路都在，`:87`/`:95`/`:102`）。
4. **结果异步落地，只走原播报出口**：后台任务跑完用 `replyHandler.post(...)`（handler 在派发前、
   即 `compareAndSet` 之前捕获）把矩阵投回采集线程，存进 `cloudMatrix` 并记落地时刻 `cloudMatrixAt`。
   `processFrame` 在多数票与自我修复之后、去重之前调 `takeCloudResult`：
   取用即清空、行列与本地一致、且结果龄期 ≤ `CLOUD_RESULT_TTL_MS`（**12000ms，原稿写 10000**，
   与 read 超时同界，避免出现「永远取不到的合法结果」）才覆盖本地读数，过期或错行列丢弃并记日志。
   绝不从后台线程直接 `announce()`——播报节拍、去重、`MIN_ANNOUNCE_GAP_MS` 全在采集线程的状态上。
5. `teardownMedia()` 里 `shutdownNow()` 并把 `cloudExecutor` / `cloudInFlight` / `cloudMatrix` 清零：
   会话重启不能带上旧结果，也不能让旧的在途位永远卡住。

### 验收

- 静态：采集线程路径（`tick` / `processFrame` / `dispatchCloud` 主体）无任何 HTTP，
  `CloudVision.readBoard*` 的命中只在 `dispatchCloud` 的后台任务体里（实测 `:592` 自托管、`:594` OpenRouter）。
- 在途守卫与网络替身**不写本地单测**：`dispatchCloud` 依赖 `SharedPreferences`、`Bitmap`、`Handler`
  三个 framework 类，而本仓单测口径是 plain JUnit 无 Robolectric（对照 `Match3GateTest`、
  `Match3MajorityVoteTest`），造假替身只能测自己写的假件。这条以真机验收为准。
- 真机：授权→切进游戏→**必须**听到第一句播报（本地读数或「认不出棋盘」二者之一），
  不允许出现「有帧、无声音」的状态。

---

## 修复二（P0）：门序重排——「认不出」必须排在首播双重确认之前

### 现状证据

原 `processFrame` 的 return 顺序实测：去重 → 首播双重确认（`if (pendingStable < 2) return;`）
→ 变化幅度门槛 → 未知率 >40% 自我修复（修复仍失败才可能提示）；
而 ABSTAIN 只在 `sampler == null` 且从没标定过时才走。

本轮实际路径：格子 44 像素落在缝隙 → 每个稳定窗都在漂 → `pendingStable` 永远 < 2 → 在双重确认 return
→ 40% 自我修复和它的提示一次没执行。全文 `自我修复` 命中 0，播报文本日志命中 0。

另一处口径死结：原提示条件是「未弃权播报过」且 `!match3_calibrated` —— 本轮 `match3_calibrated` 是 **true**
（存了小框），所以即使走到那一行也不会说话。「标定过但标定坏了」恰恰是最需要提示重标的状态。

### 设计（已落地）

1. 未知率判定提到云端取用之后、去重之前，新顺序实测为：
   多数票 → **未知率 >40% 自我修复（原逻辑整块搬来，不改内部判据）** → 云端派发/取用 →
   **未知率 >40% 出声闸门** → 去重 → 双重确认 → 幅度门槛 → 播报。
   自我修复放在云端之前：修复动作是本地重标定（快、同步、只跑在坏盘上），
   先给它一次机会，读得出来就不必再叫网络。
2. 搬过来之后仍要防「关卡开场动画」假报（双重确认当初就是为它加的，诊断包 diag3/diag4）：
   新增 `unreadableStreak` 计数，未知率超标窗 `streak++`，**只有 streak ≥ 2 才播提示**，
   读到能认的盘立即归零。两个稳定窗约 2.4 秒（每 800ms 一 tick、三帧成窗），
   配合「同一状态只播一次」的 `unreadableAnnounced`，不会变成刷屏。
3. 提示条件去掉 `!match3_calibrated` 这半边：标定过的人更该被告知「你框的这块认不出来，请重新框选」。
   措辞按状态分两支（未标定＝先框选；已标定＝标定过的区域已不对，重新框选）。
4. `abstainAnnounced`（` sampler == null` 那条支路）的语义与复位点不变；
   新支路的「只播一次」由独立的 `unreadableAnnounced` 管，两者不复用同一个标志，
   免得一次会话里两种失效模式互相吃掉提示。
5. 判据抽成两个静态方法，让「门序」这件事能在本地 JVM 被复现：
   `Match3LiveService.isUnreadableBoard(char[][])`、
   `Match3LiveService.shouldAnnounceUnreadable(streak, alreadyAnnounced, sinceLastAnnounceMs)`。
   抽出来的原因是原稿那条验收（喂垃圾矩阵序列断言 2 窗内出声）在生产代码里没法直接调——
   `processFrame` 要 `Bitmap`。抽完之后测试调的就是线上那几个字，不是测试自己重写的一份。

### 验收

- 单测：`isUnreadableBoard` 用 8x8=64 格钉住那条 40% 线——26 格未识别（40.6%）为真、
  25 格（39.06%）为假、0 格为假；空格 `' '` 与未识别 `'.'` 两种符号都要计入（`isUnreadable` 口径）。
  `shouldAnnounceUnreadable` 断言 streak=1 假、streak=2 且未播过且已过 6s 真、已播过假、未过最小间隔假。
  实测落地位置 `Match3UnreadableGateTest`。
- 单测：喂「每窗都漂的垃圾矩阵」序列，按上面的静态判据逐窗模拟门序，断言第 2 窗产出一句「认不出」，
  第 3、4 窗不重复播。
- 真机：故意把标定框填成极小值（比如 30/40/45/48），进游戏后应听到「认不出棋盘，请重新框选」，
  而不是这条链改之前的全静默。

---

## 修复三（P1）：标定合理性闸门——拒绝把一个不可能的框当真

### 现状证据

- `00:00:03.293 ... Dimensions.width = 356, Dimensions.height = 336`；
  `CloudVision.scaleForUpload` 的 `max = 896` 对 ≤896 的入参照原样返回，所以 356x336 就是裁剪框真实像素，
  即存机框为 33% 宽 x 14% 高。
- 仓内真机实测正确框 `14/38/87/71`（`REAL_VIDEO_FINDINGS.md:35`）≈ 788x792，本轮框只有它约 1/8 面积。
- 改前唯一的形状检查是 `r - l > 40 && b - t > 40`——44 像素格子的框轻松通过。
- 格数自检 `detectGridCount` 在小框里返回 -1（其自身单格 <60 像素就弃权，`Match3Sampler.java:427` 附近），
  于是回退存值硬读，等于「自检弃权 → 照用坏标定」。

### 设计（已落地，第 1 条**偏离原稿**）

1. 下限**不是**原稿定的常量 `MIN_CELL_PX = 60`，而是随屏宽走：
   `Match3Sampler.minPlausibleCell(screenWidthPx) = max(40, screenWidthPx / 18)`。
   改的原因：1080 宽下 `1080/18 = 60`，与原稿的 60 完全重合（真机这条判据不变），
   但常量 60 在 480 宽的老年机／分屏上是**永远达不到**的下限，会把有效标定全拒掉——
   无障碍工具的用户机屏幕分布比一般 App 更散，不能拿一台机器的实测值当全局常量。
   `screenWidthPx / 18` 的来历：8 格棋盘横向占屏宽约 80% 时，单格约为屏宽的 1/10；
   取 1/18 是把它放宽到「框只正确划到一半宽」也仍算可信，即闸门只拦几何上不可能的框。
   与 `detectGridCount` 的 60 像素弃权线**同源但不同用**：那条是「自检能不能数」，这条是「标定该不该信」。
2. `Match3Sampler.plausibleCalibration(screenW, screenH, l, t, r, b, rows, cols, cellOut)`：
   `cellW = screenW*(r-l)/100/cols`，`cellH = screenH*(b-t)/100/rows`；任一边 < 下限即判不可信，
   `cellOut` 回填实测单格宽高，供提示语与日志把真实数字念出来（电话里能复述）。
3. 闸门同时挂两处：手动标定沿用支（拒绝**使用**，不建 sampler、直接出声）与
   设置页保存支（`Match3AssistActivity.saveCalibration`，拒绝**写入** SharedPreferences）。
   只堵一头，另一头还能把坏值塞回来。
4. 自动适配支不加此闸门：它已有 40% 试采验证，且几何由棋盘检测给出，
   再加闸门会误伤小棋盘关卡。

### 验收

- 356x336 这一实测值必须被闸门拦下（1080x2400、8x8 时 cellW=44、cellH=42，下限 60，两边都不足）。
- 正确框 788x792 按 7x7（cellW≈112）必须放行；按 8x8（cellW≈98）也必须放行——
  闸门不能紧到把有效标定拒掉，这条边界要在测试里钉住。
- 低分屏不被误伤：下限随屏宽走，`minPlausibleCell(1080)=60`、`(720)=40`、`(480)=40`（480/18=26 被 40 兜住）。
  480x800 屏、单格 40 像素的标定必须放行——按原稿的常量 60 这条会被拒掉，是本次偏离原稿的实际后果。
- 设置页填一个 33%x14% 的框点保存，应弹提示且不写 SharedPreferences
  （读回 `match3_calibrated` 仍为原值）。
- 前四条落在 `Match3CalibrationGateTest`（纯算术，无需 framework）。

---

## 修复四（P1）：让下一份 bugreport 单独就能看出标定漂移

现状只能靠 `SkJpegEncoder` 的副作用读数反推裁剪尺寸——那是 skia 的调试日志，不是我们打的，
换台机或关掉 skia-debug 就没这条线。要自己打。

1. `沿用手动标定` 那行扩成带几何读数（`describeCalibration()` 统一产出
   `标定=l/t/r/b% 裁剪=WxHpx 单格≈宽x高px 格数=行x列`）；格数自检弃权时明写「格数自检弃权，按存值读」，
   不再让「没后缀」当成一种需要外部推理才能读懂的信号（实测 `Match3LiveService.java:347-349`）。
2. 云端派发打一条 `云端兜底派发 NxN 裁剪=WxHpx`，取用打 `云端识别接管`／丢弃原因，
   让「为什么打云端」「有没有卡住」「结果用没用上」在同一 tag 下自证。
3. 播报行旁边补一条机器读数 `播报读数 … 未知=u/t 变化=d 格 …`，
   与诊断包 `diagnostics.audit` 的 `unknown=` 并行（那是包，这是 logcat，两者都要有）。
4. 无障碍服务未启用导致功能静默跳过时（`SenseFieldReaderService.showRowNumbers` 里 `instance == null`；
   本轮 `Enabled services:{}` 实测命中）改为返回布尔值，调用方在 false 时播一次
   「读屏辅助未开启，行号与点读用不了」（`hintIfReaderOff`，一次会话只播一次）——
   覆盖安装会重置无障碍授权，这是每个装新版的人都会踩的状态，不能继续静默。

---

## 批次与门禁

- 批次一（P0）：修复一＋修复二。改动面 `Match3LiveService.java`（CRLF 制式）、`CloudVision.java`（纯 LF）。
- 批次二（P1）：修复三＋修复四。改动面 `Match3LiveService.java`、`Match3Sampler.java`（CRLF）、
  `Match3AssistActivity.java`（纯 LF）、`SenseFieldReaderService.java`（CRLF）。
- 每批改完跑：`bash research/board-recognition/java-bench/run-tests.sh`、
  `gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline`、
  `python scripts/check_public_repo.py`、改动文件身份痕迹扫描（`wxid|xwechat|用户名|邮箱|sk-or-v1|Users 路径`）。
- 行尾纪律：CRLF 文件改完断言「裸 LF 数 = 0」，纯 LF 文件断言「CR 总数 = 0」。本仓两种制式混存，改前先量。
- 对外动作（commit／push／tag／Release）不在本方案默认范围内，见案卷 BLOCKED §11 请命。

## 已知遗留（本轮不修，记清楚免得当成已修）

- `onImageAvailable` 与 `tick` 之间存在帧回收竞态：两帧在同一 tick 间隔内到达时，
  回调会 `recycle()` tick 正在处理的那一帧。800ms tick 下窗口很窄，本轮日志没有对应崩溃，
  但它决定了「Bitmap 不能跨线程用」这条约束，所以修复一按「采集线程只编码、后台只传字节」设计。
  真要根治需把帧的所有权改成「tick 取走即独占，回调不回收正在处理的帧」。
- `match3_cloud_escalate` 全仓只有读侧、没有任何写入点（`grep -rn` 实测两处命中皆读侧），
  是设置页缺一个开关而不是逻辑坏；补开关属产品决定，不在四修内。
  本轮之后云端仍只由「本地未知率 >25%」这条自动支路叫醒。
- `Match3AssistActivity.java:303-306` 的注释被真机推翻：API 35 用
  `MediaProjectionConfig.createConfigForDefaultDisplay()` 之后，23:59:56.493 系统应用选择器照旧弹出。
  代码意图（整屏投影）仍然成立且必要（切到游戏后单应用投影会停帧），本条只改注释口径，
  选择器仍需靠 TalkBack 焦点＋「允许」按钮的可访问性来通过——那是另一件事。
