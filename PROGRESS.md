# PROGRESS

## 通关价值排序轮 · 开工回执（2026-10-09 19:20:17 实测）

- 目标：让消消乐推荐先看本关要收集哪几种动物，能推进目标的交换排到最前；读不到目标就整体弃权，绝不把看不清的补成零。
- 顺序：任务 0 核基线（已过）→ 任务 1 目标条读数类 + 6 用例 → 任务 2 目标接进排序 + 两处接线 + 反向验证 → 末轮门禁。
- 任务 0 实测：`cd android && ./gradlew :app:testDebugUnitTest --offline --rerun-tasks` → BUILD FAILED（7 条既存红），`25 actionable tasks: 25 executed` 零 UP-TO-DATE；聚合 `files=61 tests=402 failures=7 skipped=0 errors=0`；7 条红全名与任务书一致（AsrModelStoreTest 6 条 + AssistantTtsCacheTest.sweepNeverFollowsAnOwnedLookingSymlinkOutsideCache）。
- 最大风险一：主树没有真机像素标定，挂牌 ROI 只能按计划书「左上角挂牌」给未标定常量；接线在标定前会走整体弃权，等价旧行为，不做「看起来能读」的假绿灯。
- 最大风险二：`android.graphics.*` 在 JVM 单测里拿不到真 Bitmap（`unitTests.returnDefaultValues = true`，全仓 0 个测试引用过 Bitmap），所以新逻辑一律吃 `char[]`，像素侧只有接线那一行。
- 边界自查：`_scratch_diag/` 在主树未被 gitignore 覆盖（`git check-ignore` 退出 1），一次性脚本留在该目录、不 commit，靠「不提交」保证不进 git。
- 判卷冻结面：`docs/plans/消消乐目标价值排序.md`、既有 Match3 测试类、`build.gradle` 一字不改；`Match3Board.java` 只读，故「这步消掉哪几种动物」在排序侧自己算，不改 `Swap` 结构。

## 任务 1 · 目标条读数（19:23:08 实测绿）

- 新增 `Match3GoalStrip.java`：输入顶栏逐格颜色分类 `char[]`，输出目标种类去重集合；任一格不是基础动物（一律走 `Match3Sampler.isMovable`，本类不另立名单）就整体弃权，弃权原因带格号，如 `slot2=unknown`、`slot2=ice`、`slot2=learned_label`、`slot1=empty`。
- 新增 `Match3GoalStripTest.java` 9 个用例（要求 ≥6）：空条与缺条、中间格认不出要整体弃权而不是忽略那格、单目标、双目标按扫描顺序、重复同种去重但保留格数、冰块格、学过的外观标签加空格格、诊断串两态、无采点工厂。
- 单跑读数：`--tests com.openkhub.sensefield.Match3GoalStripTest` → `BUILD SUCCESSFUL`，`files=1 tests=9 failures=0 skipped=0 errors=0`。
- 挂牌采点坐标 `CARD_Y_PCT=5`、`CARD_X_PCT={7,14,21}` 写在类里并注释「未标定」，理由见 BLOCKED 第 1 条。

## 任务 2 · 目标接进排序（19:32:36 单跑绿，19:35:41 末轮全量绿）

- `Match3MoveRanker` 加 `rankedSwaps(board, goals)` 重载：目标可信时第一位键是「这一步消掉的动物是否为本关目标」，其后仍是 冰邻接→长连→消除格数→连数；不可信时直接走 `rankLocally`，与旧签名同一条代码路径。旧签名 `rankedSwaps(board)` 与 `evidence(swap)` 的输出口径一字未动（`legacyEvidenceStringIsUnchanged` 锁住）。
- 「这步消掉哪种动物」在排序侧自己算：克隆棋盘、按 `Match3Board.findRuns` 只认涉及被交换两格的新增三连，与 `Match3Board.addIfForms` 同口径。`Match3Board.java` 是只读面，所以没有把颜色记进 `Swap`。
- `evidence` 新增带目标上下文的重载：可信时 `goal_cleared=yes/no` 加 `goal_strip=trusted kinds=B slots=1` 加 `scope=local_one_move_plus_level_goal_kinds`；不可信时继续 `scope=local_one_move_level_goal_unread`，后面追加弃权原因。
- 接线两处：`Match3LiveService` 在棋盘变化分支里采点读数并按新重载排序，`BoardRecognized` 与 `Match3Hint` 两条诊断都带上读数；棋盘失效时把读数清成 `untrusted("board_invalidated")`。`Match3ToolsActivity` 截图识别路径同口径，并把 `goal_strip=...` 打进输出文本，实时与截图共用局部排序这条既有约定没破。
- 新增 `Match3GoalRankingTest.java` 12 个用例，三块手写棋盘（横向四连加横向三连、同盘下方垫两格冰、竖向四连）期望顺序全手写：换目标首位改变 4 例、目标不可信逐位同旧 2 例（一块盘跑三糊输入，另加糊条兜底专例）、诊断串 3 例、候选集核对 1 例、走法不增不减 1 例、目标命中最强走法时顺序不变 1 例。
- 单跑读数：`--tests com.openkhub.sensefield.Match3GoalRankingTest` → `files=1 tests=12 failures=0 skipped=0 errors=0`。

## 反向验证 · 两轮都有红有绿

- 第一轮（读数侧）：把「认不出就整体弃权」偷改成「忽略那一格」→ `BUILD FAILED`，7 条红，含 `unrecognizableGoalCardKeepsEveryPositionOfTheLocalOrder` 报 `expected:<[1,2:2,2, 1,2:1,3, 4,2:5,2]> but was:<[4,2:5,2, 1,2:2,2, 1,2:1,3]>`、`iceCardIsNotAnAnimalSoTheStripAbstains` 报 `ice rules are not read yet, so a goal card showing ice abstains`。还原后转绿。
- 第二轮（排序侧）：把比较器里的目标键摘成常量 0 → `BUILD FAILED`，5 条红：`collectBearTurnsTheThreeRunAheadOfTheUncollectedFourRun`、`goalKeyOutranksIceNeighboursOnTheSameBoard`、`verticalStrongMoveYieldsToTheHorizontalGoalRun`、`twoGoalCardsWhereOnlyTheSecondOneIsClearableStillReorder`、`evidenceNamesGoalKindsWhenTheStripWasReadable`。还原后转绿。
- 末轮全量（19:35:41 实测）：`25 actionable tasks: 25 executed` 零 UP-TO-DATE，`files=63 tests=423 failures=7 skipped=0 errors=0`；红的仍是开工那 7 条同名的 Windows 文件系统红。测试数 402→423（新增 9 加 12），结果文件 61→63。

## 过程噪音与偏差留痕

- 一次自写错断言：`goalRelevanceDoesNotDropOrInventAnyMove` 首次把「未排序的旧顺序」与「排序后的新顺序」相比，尺子错、红了；改成两侧同序比较（多重集相等），业务断言没放宽。这条红是我自己的尺子问题，不是被测代码缺陷。
- 走了一步与建议不同的路：任务书说 Bitmap 只在接线处出现，所以「不是新增类」而是把 8 行采点循环分别写进 `Match3LiveService.goalCards` 与 `Match3ToolsActivity.goalCards` 两个接线文件，共享的只有百分比常量。代价是两份相似循环；换来的是新类保持零 Android 依赖、可 JVM 直测。
- 边界自查（末轮 `git diff --stat`）：只有 `Match3LiveService.java`、`Match3MoveRanker.java`、`Match3ToolsActivity.java` 三个既有文件被改（加 113 行、删 14 行）；新增 3 个文件加本卷与 `BLOCKED.md`，`_scratch_diag/` 未跟踪。冻结面 `docs/plans/消消乐目标价值排序.md`、`android/app/build.gradle`、既有测试目录的 `git diff` 实测为空。未 commit、未 push、未 tag。

## 追加轮 · 2026-10-09 当轮实测 2026-10-09 20:23:39

- 应领导「按顺序执行」第①件：把待裁第 1 条的执行面写成标定操作卡，追加进 `BLOCKED.md`（读数两处位置、`reason` 五种尾巴各对应改哪个常量、一次只动一个量、复跑命令与 423/7/0 口径、机器判据是 `abstain` 转 `trusted`）。源码一字未动。
- 第②件实测：`:8777` 监听数 0、原进程 35884 已不在、无 http.server 进程，局域网服务已是停的状态；三个常见落点（下载目录、本工作区、主树）找 10-08 之后新到的 `sensefield-diag-*.zip` 为 0 个；下载日志最后一条是 09 日 18:38:33 他机 HEAD 探链、无对应 GET 完成记录（08 日 08:56 那次 GET 是装包本身），也就是说 cand2 的装机读数还没回来 —— 判不了，不是判过了。
- 第③件：未 commit、未 push、未 tag（任务书死规矩，且 cand-ag 那 21 个文件的合并策略还挂着待裁）。
- 留档读数复扫：files=63 tests=423 failures=7 skipped=0 errors=0，红的仍是同名 7 条。
