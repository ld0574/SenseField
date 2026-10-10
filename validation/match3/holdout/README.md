# 消消乐识别准确率 Holdout（阶段 0）

把「准确率不行 / 识别不到」变成可复算的数。真实引擎的逐格判定由模拟器上的
`Match3HoldoutAccuracyInstrumentedTest` 产出，`scripts/evaluate_match3_accuracy.py`
只做评分，不复刻识别逻辑。方案全文见 `docs/plans/消消乐识别飞轮改造.md`。

## 三份制品（按样本 id 关联）

- `manifest.json`（+ 帧 PNG）：holdout 输入。
  `{"frames":[{"file":"board.png","rows":9,"cols":9,"bounds":[l,t,r,b]}]}`
  `rows/cols/bounds`（百分比）可省；省略时走 `Match3Sampler.autoDetectGeometry`。
- `predictions.json`：真实引擎产出，样本 `{id,row,col,kind,color,swap_permission,ice}`，
  `id = "<file>:r<row>c<col>"`。
- `labels.json`：人工真值（O(家族) 的唯一人工步），样本
  `{id,family,kind,color,swappable,rule,source}`。`swappable` 为 `null` 表示未知/非普通动物。

`examples/` 下是一对合成 `predictions.json`／`labels.json`，用于离线自测评分器
（`python3 -m unittest tests.test_match3_accuracy_eval`），不含任何真机数据。

## 指标口径

- **coverage 覆盖**（无需真值）：`kind` 非 UNKNOWN/SURFACE/SPECIAL、且动物的 `swap_permission` 非 UNKNOWN 的比例。
- **漏认 miss**：真值已知家族，引擎弃权。
- **认错 misclassify**：引擎给出已知家族但 ≠ 真值。
- **误放 false-swap**：真值不可交换/未知，引擎判可交换（最危险）。
- **漏换 swap-miss**：真值可交换，引擎判不可交换（安全，损失推荐）。

阶段 0 只记录基线，不阻断；`--max-false-swap`／`--min-coverage` 门槛留给阶段 1。

## 数据与隐私

真机帧、manifest、predictions 一律放在被忽略的 `validation/private/` 下，不入库。
标注/评分在本机离线做，不外传游戏画面。真实基线（10 盘 743 格，人工网页标注确认）：
coverage 0.837；misclassify 0、false_swap 0、miss 104（空/背景→SURFACE 46、雪花变体 15、
动物过保守弃权 37、银币 6）、真正新/未知仅 17 格。第 11 张 `feedback-ice-live` 是通关结算弹窗、
本就无棋盘（非几何 bug）。统一根因：固定目录每家族样例太少且近 327KB 上限 → gallery。

命令见方案文末「复现」小节。
