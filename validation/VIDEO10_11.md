# video10/11 HD 人工复核与开发训练记录

更新时间：2026-09-29

这两场录像用于 HD 检测器的开发数据。所有统计均按完整录像分组，不能把相邻帧随机拆到不同 split，也不能把下面的开发指标当作最终比赛成绩。

## 人工复核结果

| 场次 | split | 帧任务 | 状态 | 框数 | ROI 审计 |
| --- | --- | ---: | --- | ---: | --- |
| video10 | train | 130/130 | 110 `corrected`、20 `negative` | 230 | 通过 |
| video11-HD | dev-val | 130/130 | 107 `corrected`、23 `negative` | 203 | 通过 |
| 合计 | — | 260/260 | 217 `corrected`、43 `negative` | 433 | 通过 |

video10 和 video11 的源帧均为 `1920×860`。队列 SQLite `integrity_check=ok`，导出 manifest 与最终复核结果一致，没有 pending 任务或活动 lease；所有帧和 overlay 均存在且可解码。三层坐标为：

- `safe`：`[44,0,408,334)`
- `label`：`[64,0,373,303)`
- `widget`：`[96,0,372,277)`

最终框没有可扩展 crop-edge contact；有 60 个框接触源画面的物理顶边（train 46、val 14），这是录屏画面边界，单独记录，不作为 ROI 裁剪缺陷。video11 已排除开局商店 `[0,15000)` ms，以及约 719–724 s、725–728 s 的战绩面板遮挡。

来源录像 SHA-256：

- video10：`8337d4116c47dd1a82fce2ea69f7d4f0e56f047c6f331110d1a7be1b3d501c48`
- video11：`1b16bcb846cd7bbd8e79cfb48d052b3bec0a080b5d5abd483c1e97ee8e9958cd`

证据文件 SHA-256：

- fresh final manifest：`d56ca36ee2a35d53a2414a1802d70e762363b418cdbda34337c6f4b05da38d83`
- review manifest：`2e4f73d25692fb58f452f442af60624c428b63771e1484758b715609d676fe44`
- SQLite：`483fdf4975cfbbffa5f479e8f051d63ad3322f23fa2388a27ae2545015646f16`

## v2 开发 split

合并后当前 HD bootstrap v2 为：

| split | 来源 | 图像 | 框 |
| --- | --- | ---: | ---: |
| train | video1、video3-HD、video4-HD、video5-HD、video8、video10 | 664 | 1211 |
| val | video2-HD、video11 | 230 | 400 |
| test | — | 0 | 0 |

COCO train/val 均通过 provenance 审计，跨 split 没有精确重复；空 test 是当前独立成绩门禁的 blocker。video10 已进入 train，所以不能用它的训练集回放结果证明泛化；video11 是开发 val，已经参与当前模型和阈值选择。

## 新训练候选

从 video4/5 扩充候选初始化，使用 YOLOX-Nano、320 输入、batch 16、Apple MPS、seed `20260930`、`lr_scale=0.25`，最多 12 个 epoch；best epoch 为 8，实际训练约 337 秒。开发 val 选择 confidence `0.49` 时：

```text
TP/FP/FN: 366/35/34
precision: 91.2718%
recall:    91.5000%
F1:        91.3858%
```

这些结果来自 video2-HD+video11 开发 val，且 video11 参与了选模或阈值选择，因此只是开发诊断，不能称为独立留出成绩。新候选已经导出 ONNX/TorchScript/ncnn 并绑定本机 Android 实验 assets，但严格 raw／坐标 parity 未通过，仍为 `verified=false`、`release_ready=false`；公共默认 profile 继续关闭 detector，实体 Android 也尚未验收。

## 证据边界

video9 和 video12 仍为封存留出录像，本记录没有对它们运行模型、预标注或查看预测。Hero 片段只用于 UX／事件故事。下一步应先完成 video7-edge 的人工复核和审计，再建立真正未参与选模的 test split，随后才可报告独立检测门禁。
