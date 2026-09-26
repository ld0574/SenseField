# video6 独立小地图逐帧盲测记录（2026-09-26）

> 状态更新：以下内容保留当时的冻结盲测过程和历史结果。后续已经查看失败案例并用 video6 选择 YOLOX 权重与阈值，因此 video6 现已降级为开发验证数据，不能再引用为独立留出成绩。

## 素材登记与适用范围

`video/video6.mp4` 为 720×324、约 1022.539 秒、H.264 横屏录像，SHA-256 为：

```text
b8f067e8207bf72a3fe8cbeb692b7a7436fd10177674fce191027445faa8188b
```

按 30 秒和首尾 5 秒抽帧检查，素材全程来自实际对局 HUD。场景变化复核确认在约 464.333、688.067、758.033、958.467 和 960.633 秒存在明显剪辑跳切。它不是连续完整对局，不能用于完整事件召回率、连续 15 分钟稳定性或“可见证据到实际发声”的端到端 P95 验收。

它在任何 detector 输出被查看之前登记为独立小地图**逐帧**留出素材。后续不得依据这份录像修改红色阈值、地图门控、几何过滤或其他识别规则；若发生此类修改，必须把 video6 降级为开发数据并另取新留出素材。

## 冻结承诺

私有登记位于 `data/private/holdout-video6/holdout-register.json`。本次冻结内容：

| 项目 | SHA-256 |
| --- | --- |
| video6 | `b8f067e8207bf72a3fe8cbeb692b7a7436fd10177674fce191027445faa8188b` |
| 小地图 profile | `c1f4dc6207c4e242c332ca585fb7615ff04900bb65e0990da2ab6e928c5bc7a5` |
| 原生识别库 | `e2c51991aa2d5f80adb118bc4116f23894caeb77c963ff31c9b9c3d6e5043ba9` |
| 12 FPS 冻结预测 | `6397880ac0511d7c791debebe9898834d14423c9cbb94c1089e0c5947915af9a` |

冻结预测共 12,270 帧。回放 provenance 位于 `data/private/holdout-video6/predictions-frozen.jsonl.meta.json`，记录录像、profile、原生库与预测文件哈希。上述私有文件均由 `.gitignore` 排除。

## 预测无关的抽样和标注

`mapassist.blind_review_dataset` 只读取冻结 provenance 中的 FPS、帧数和哈希，不读取观察框来选样本。它把 12,270 个预测时间点分成 60 个等宽区间，各取区间中点，最终覆盖 8.500–1013.917 秒。输出清单明确记录：

```json
{
  "review_mode": "blind",
  "split": "test",
  "sampling": {
    "strategy": "uniform_midpoint_frames",
    "predictions_used_for_selection": false,
    "fps": 12,
    "source_frame_count": 12270,
    "sample_count": 60
  }
}
```

复现命令：

```sh
PYTHONPATH=python python3 -m mapassist.blind_review_dataset \
  video/video6.mp4 \
  --prediction-metadata data/private/holdout-video6/predictions-frozen.jsonl.meta.json \
  --output data/private/holdout-video6/blind-review \
  --match-id video6 --sample-count 60 \
  --roi 0.0375 0 0.1444444444 0.3333333333
```

启动盲标网站：

```sh
PYTHONPATH=python python3 -m mapassist.annotation_server \
  --dataset video1-5=data/private/minimap-review-v3 \
  --dataset video6=data/private/holdout-video6/blind-review \
  --host 0.0.0.0 --port 8765
```

打开网站后从顶部数据集选择器进入 `video6`。两套队列共用网站入口，但数据库与复核清单保持独立。

盲标页没有自动建议框，且拒绝 `accepted` 状态。有敌人时人工框选全部敌人并保存为 `corrected`；对局中确认没有敌人时标 `negative`；比分面板、剪辑过渡或非正常 HUD 标 `excluded`；无法判断标 `skip`。前后 0.5 秒画面只用于辨别移动头像、固定塔标和特效。

## 解封与评测

60 张全部完成人工标注后，运行：

```sh
PYTHONPATH=python python3 -m mapassist.detection_evaluate \
  data/private/holdout-video6/blind-review/review-manifest.json \
  --predictions data/private/holdout-video6/predictions-frozen.jsonl \
  --output data/private/holdout-video6/blind-review/detection-report.json
```

评测器重新核对了冻结预测及 provenance 的 SHA-256，再在采样时间点连接预测框与人工真值。60 张已全部盲标完成：48 张正样本、7 张负样本、5 张因严重遮挡或无法可靠判断而跳过；没有 `accepted` 或 `pending`。

IoU 0.5 下的独立逐帧结果：

| 指标 | 结果 | 目标 | 是否通过 |
| --- | ---: | ---: | --- |
| 准确率 | 57.58%（19 TP / 14 FP） | ≥90% | 否 |
| 召回率 | 21.59%（19 TP / 69 FN） | ≥80% | 否 |
| 方位正确率 | 94.44%（17 / 18） | ≥90% | 是 |
| 完全正确帧 | 20.00%（11 / 55） | 仅诊断 | — |
| 匹配框平均 IoU | 0.6513 | 仅诊断 | — |

因此冻结的小地图红环检测器**未通过留出集门禁**，主要问题是严重漏检，准确率也未达标。不得依据 video6 调整当前规则后继续沿用这份结果作为独立测试成绩；若要迭代，video6 必须降级为开发数据，并另录一份新的独立留出素材。私有完整报告位于 `data/private/holdout-video6/blind-review/detection-report.json`。
