# 小地图玩家头像：合并后的人工队列与候选排序器 v2

更新时间：2026-09-30

## 当前结论

首轮主动学习批次的人工结果已经合并到 `data/private/minimap-player-review-queue-v1`。当前队列有 338 个人工终态：276 `corrected`、61 `negative`、1 `skip`；其中 337 个可训练（`corrected` + `negative`）。另有 901 个 `pending`，保留为待复核积压；后续按优先级处理，不把全量清洗 901 张列为前置要求。

候选排序器 v2 严格复用首版流程：MPS、seed `20260930`、25 轮、batch size 96、候选绿色环 patch、仅使用人工 `corrected`／`negative` 标签。训练 split 使用 208 帧，pooled val 使用 129 帧；`skip` 不进入训练或评估。active batch 已并入源队列，没有作为独立代表性测试集。

## v2 开发诊断

阈值 `0.81` 在 pooled val 上按 precision ≥95% 后最大化 recall 选择。所有逐视频结果使用这一共同阈值，没有按视频单独调阈值。中心误差只统计命中的人工框，单位为像素。

| 开发来源 | TP/FP/FN/TN | Precision | Recall | F1 | 中心误差均值 / P95 |
| --- | --- | ---: | ---: | ---: | ---: |
| pooled val（video2/11/13） | 84/4/9/32 | 95.4545% | 90.3226% | 92.8177% | 4.3656 / 12.8733 px |
| video2-HD | 66/3/5/26 | 95.6522% | 92.9577% | 94.2857% | 4.5348 / 12.6490 px |
| video11-HD | 13/0/1/1 | 100.0000% | 92.8571% | 96.2963% | 3.4698 / 14.2699 px |
| video13 | 5/1/3/5 | 83.3333% | 62.5000% | 71.4286% | 4.4619 / 13.2544 px |

video13 是跨来源开发诊断，precision、recall 和 F1 明显低于 video2／video11；它已经参与模型与阈值判断，不能称为独立 test 或最终门禁成绩。上述结果全部是开发集诊断，不代表 Android 能力或发布能力。

## 复现命令与产物

```bash
PYTHONPATH=. .venv/bin/python training/learn_minimap_player_suggestions.py \
  --source data/private/minimap-player-review-queue-v1 \
  --output build/player-label-audit/candidate-ranker-v2-338 \
  --seed 20260930 --epochs 25 --batch-size 96 \
  --pending-batch-size 8 --device auto
```

源 manifest SHA-256：`ff8d9a35647456391a9a8cd63683b0ec59744aae38121cd585fd7126daf33a27`。

v2 产物位于忽略目录 `build/player-label-audit/candidate-ranker-v2-338`，均标记为 `review_aid_non_release`：

- `checkpoint.pt`：`eab10db1176ebd90c16a4f919fb346c21266b4e8d31f5fbf939e783f4ef32793`
- `report.json`：`32fb10ed766c5d19aab39e94251e016b3c6213013b8ad4b224e931ad991f41c4`
- `pending-predictions.json`：`7f7be016ee996d3dd48bbe6c1bc77110b0b53372c25d6219e34dfd729cef5873`

模型为复核排序辅助器，不是 ground truth、评测证据或 Android 发布模型；没有导出、替换或启用 Android 模型与 profile。

## 数据边界

v2 只读取合并后的源队列 manifest、SQLite 和其中允许的帧。`pending-predictions.json` 为 901 张 pending 的复核建议，其中 623 张有建议框、278 张无建议框；机器建议不覆盖人工标签，也不作为训练真值。video9 和 video12 继续封存，本轮未读取、未预标、未查看预测。
