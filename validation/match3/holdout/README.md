# 消消乐独立评测格式

真实引擎的逐格判定由 `Match3HoldoutAccuracyInstrumentedTest` 产出，`scripts/evaluate_match3_accuracy.py` 负责评分，不在 Python 中重写识别逻辑。流程见 [图库与评测](../../../docs/development/match3-flywheel.md)。

## 输入与输出

- `manifest.json` 与完整棋盘帧：输入清单，按会话分组，记录实际字节摘要。
- `predictions.json`：真实引擎输出，包含格子身份、颜色、交换权限与冰层。
- `labels.json`：人工真值，按样本 ID 与预测对应，未知权限保留 `null`。

样本 ID 使用 `<file>:r<row>c<col>`。清单中的行列数与边界可省略，省略时由实时同源几何逻辑定位。`examples/` 仅包含合成标签和预测，用于评分器自测，不含真实玩家数据。

## 指标

| 指标 | 含义 |
| --- | --- |
| coverage | 可提供有效身份与权限判断的比例 |
| miss | 真值已知而引擎弃权 |
| misclassify | 引擎给出的身份与真值不同 |
| false-swap | 真值不可交换或未知，引擎却授予交换权限 |
| swap-miss | 真值可交换，引擎未授予交换权限 |

开发基线和严格评测分别记录。正式运行前冻结来源、标签、模型及门槛；Full 模式要求独立与冻结声明、逐条人工审阅及完整 HUD 真值。缺失标签或输入不能作为通过。

真实帧、清单、预测与评分保存在 `validation/private/`。按完整录制会话分组，训练、开发与独立留出不交叉；封存数据不用于调参或开发回归。
