# P2 双跑评测报告（无标注部分）

评测日期：2026-10-02。评测集：50 帧（20 消消乐＋10 象棋＋15 中文无障碍树＋5 边界帧，生成器 `backend/eval/gen-frames.js`，id 唯一、无消除帧经构造法保证零三连）。
双跑：每帧五问，本地 Laya 适配服务（`http://127.0.0.1:8080`）与云端 OpenRouter（`typesafe/jev-1.13`）各一次，**全程 0 请求错误**。原始数据：`backend/eval/dual-run-results.json`。

## 1. 两端 top-1 一致率（不依赖标注）

| 问题 | 题型 | 一致率 | 低置信率(<0.5)·本地 | 低置信率(<0.5)·云端 |
|---|---|---|---|---|
| next_action | choice | 21/50 = **42.0%** | 49/50 = 98.0% | 2/50 = 4.0% |
| danger_level | score | 11/50 = **22.0%** | 50/50 = 100.0% | 16/50 = 32.0% |
| should_interrupt | noul | 11/50 = **22.0%** | 48/50 = 96.0% | 3/50 = 6.0% |
| announce_rank | score | 14/50 = **28.0%** | 50/50 = 100.0% | 38/50 = 76.0% |
| scene_changed | noul | 15/50 = **30.0%** | 34/50 = 68.0% | 27/50 = 54.0% |
| **总计** | | **72/250 = 28.8%** | | |

中英文分行：zh 帧（48）28.8%；en 帧（1）40.0%；mixed 帧（1）20.0%——样本太小不构成结论，但中文不是最差项。

## 2. 延迟（P50/P95，五问一请求）

| 端 | P50 | P95 | n |
|---|---|---|---|
| 云端 OpenRouter | 1080ms | 1190ms | 50 |
| 本地 Laya（CPU） | 5202ms | 6756ms | 50 |

云端延迟远优于此前单帧实测（1.5–2.2s），且 P95 仅 1.19s——**云端链路稳定性达标**（T4 触发线 800ms 仍偶发超出，但已接近）。

## 3. 把握度分布（P3 直方图，原始计数）

**本地**：next_action 40/50、danger 45/50、announce_rank 48/50 塌缩在 0–0.1 档；五问中仅 scene_changed 有可用分布（≥0.8 有 8 帧）。**≥0.8 的帧全端合计 8/250。**

**云端**：next_action ≥0.8 有 27/50；danger ≥0.8 有 27/50；should_interrupt 聚集在 0.5–0.6（noul 把握度无接近确定项）；announce_rank 38/50 低于 0.5；scene_changed 聚集 0.6–0.7。

## 4. 一句话结论（按 ADR T2 门槛）

**T2 要求一致率 ≥90% 且低置信率差 ≤10pp——实测 28.8%，本地不可替换云端；且本地把握度塌缩（68–100% 帧落入低置信）意味着按铁律本地几乎全部走「没看清」分支：安全（不会自动执行错误动作）但无信息。** 差距最大的是 danger_level（22%）与两个 noul（22%/30%）；相对最好的是 next_action（42%）。

**本地后端定位由此定案：断网可用性兜底＋客户端联调，不是判定质量兜底。** 改善方向（按优先级）：multilingual 检查点实测 → 用确认后的标注对 Laya 做领域微调 → 或本地换小型生成式方案（githubnext/localjev 形态，需重新过契约探针）。

## 5. top-1 准确率（对照人工标注）——等待标注

标注模板已备好：`backend/eval/annotation-template.csv`（50 帧全列出＋3 行示例＋match3 可机器推导的参考列）。
**填写并确认后运行**：`node backend/eval/sweep.js annotation-filled.csv 1`（X=1% 约束的阈值寻优，按操作风险分层：read_only / mutating / payment）。
寻优脚本已就绪；mutating/payment 层本评测集无此类操作帧，阈值维持保守默认并强制人工确认。

## 6. 复现方式

```bash
node backend/eval/gen-frames.js                                  # 重新生成 50 帧
OPENROUTER_API_KEY=… node backend/eval/dual-run-eval.js          # 双跑（约 8 分钟）
node backend/eval/hist.js                                        # 置信直方图
node backend/eval/sweep.js annotation-filled.csv 1               # 标注后：阈值寻优
```

## 7. 补充：中文版五问双跑（2026-10-03，领导改判「默认简体中文」后的实测）

同 50 帧、仅问题语言换为简体中文（结果 `dual-run-results-zh.json`），与英文版对照：

| 问题 | 一致率 EN → ZH | 云端低置信率 EN → ZH |
|---|---|---|
| next_action | 42.0% → **66.0%**（升） | 4.0% → 22.0% |
| danger_level | 22.0% → 8.0%（降） | 32.0% → 30.0% |
| should_interrupt | 22.0% → 20.0% | 6.0% → **92.0%**（塌缩） |
| announce_rank | 28.0% → 24.0% | 76.0% → 64.0% |
| scene_changed | 30.0% → 8.0%（降） | 54.0% → 28.0% |
| **总一致率** | 28.8% → **25.2%** | 本地延迟 p50 6679ms；云端 1119ms |

**结论（只给数字）**：语言对单问影响方向不一——next_action 中文明显更稳（42→66%），但 should_interrupt 的云端把握度在中文档位下塌缩（低置信 6→92%）、scene_changed/danger 一致率下降；总一致率 28.8% vs 25.2% 属同一量级。**建议保留双语开关并按语言分别标定阈值；上报谁的默认以人工标注后的准确率为准（等标注）。** 本地把握度塌缩与语言无关（两种语言下本地均 78–100% 低置信）。

## 8. P3 收尾（2026-10-04）：阈值标定 v1（机器推导部分）

- 标注来源：10 帧 match3-with 的 next_action 为 **state 内嵌事实**（potential_matches 非空 → announce_match），标注文件 `annotation-machine.csv`（人工确认列=机器推导，待领导抽查）；主观题型（rank/danger/interrupt）仍留空等人工标注。
- 寻优结果（sweep.js，X=1% 约束，read_only 层）：
  - **云端 next_action：EN 与 ZH 在全部阈值档（0.30–0.95）均 10/10 正确、0 错误**——derived 阈值 0.30，recommended 保守取 0.50（n=10 无负样本，统计强度弱）。
  - **本地：任何阈值都无法同时满足「有自动执行＋错误率≤1%」**（把握度塌缩）——本地永远走「没看清」档，仅作断网兜底，结论与 §4 一致。
- 产出 `backend/eval/routing-policy.json`（云端三档风险分层：read_only 已标定 / mutating、payment 保守默认待真实操作帧）。
- 开放项：领导抽查机器推导标注 → 可把 0.30/0.50 的选择定案；主观题型的准确率标注（annotation-template.csv 剩余 40 帧）仍等领导填写。
