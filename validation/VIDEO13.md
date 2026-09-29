# video13 高清对局模型辅助人工复核与跨来源开发诊断

更新时间：2026-09-29

video13 是 YouTube 来源的完整《王者荣耀》玩家 POV。本轮 130/130 个均匀抽样任务已经人工复核，并完成 Codex temporal audit。它最初形成 test-only COCO 输出；Android 14 首轮真机反馈出现误报后，2026-09-29 对它检查了 `0.49–0.67` 多档 confidence，因此它从现在起属于模型辅助的跨来源开发诊断，不能再称为独立 test 或用于最终门禁。它不进入当前 bootstrap v2 train／val；video9 和 video12 继续封存，未读取、未抽帧、未运行模型。

## 来源与画面

- 文件：本机私有 `video/video13.mp4`
- SHA-256：`af05d1b0bf597a2cfb96a3b78fc9e32a89c2458faf28171fc46e6cc96fd719f1`
- H.264，1920×1080，30 FPS，1478.934 秒；含 Opus 音频
- 约 `0–15 s` 为加载／欢迎动画，约 `15–1439 s` 有完整 HUD，约 `1439 s` 后进入结束动画与结算
- 底部双语字幕不遮挡小地图；短时商店、设置或战绩面板遮挡由标注员按 `excluded` 处理

视频编码为 1920×1080，但实际游戏画面带上下黑边。分辨率归一化无法单独消除这种纵向偏移，因此没有直接套用 video10/11 的 1920×860 ROI。人工抽样核对后冻结了 video13 专用三层区域：

| 区域 | 像素 `[x,y,w,h]` | 归一化 `[x,y,w,h]` |
| --- | --- | --- |
| safe crop | `[55,55,365,335]` | `[0.0286458333,0.0509259259,0.1901041667,0.3101851852]` |
| label center | `[72,65,325,300]` | `[0.0375,0.0601851852,0.1692708333,0.2777777778]` |
| widget / direction | `[80,70,310,280]` | `[0.0416666667,0.0648148148,0.1614583333,0.2592592593]` |

`safe` 必须包含完整头像框，`label` 限制目标中心，`widget` 只表示地图主体和方向参考。该人工校准只用于建立可信标注；它不证明 Android 自动定位器已经适配此画幅，也不能因为本轮结果修改 ROI。

## 队列、人工复核与审计

正式私有队列位于 `data/private/minimap-video13-holdout-v1/queue`，由 `[15000,1439000)` ms 内的 130 个均匀中点样本组成。模型建议框只作为可编辑起点，人工逐帧检查了全部任务，并删除错框、重复框、补齐漏框，再对商店／战绩面板遮挡帧标为 `excluded`。收口状态为：

| 项目 | 结果 |
| --- | --- |
| 任务 | 130/130 已人工复核 |
| `corrected` | 110 |
| `negative` | 11 |
| `excluded` | 9 |
| `pending`／活动 lease | 0 |
| 可评测标注图 | 121 |
| 人工真值框 | 211 |

Codex temporal audit 已确认抽样时间轴、队列状态、帧尺寸、来源视频哈希、三层 ROI、完整框和中心门禁一致。此前生成的 test-only COCO audit 没有 blocker，`roi_crop_completeness=provenance_clear`，无缺图、重复 split、非法框或 crop-edge 阻断；之后的多档阈值检查使 video13 成为开发数据。

关键证据哈希：

- review manifest：`3a58495d21c9aa5c8c26577d95355e11256d9d361cd9a3ed4190301cc0e1ab22`
- detection manifest：`46aced94adbfa0b96f16f59aa6245b6a5e97bf3238feda0fa70d990f1617f2b4`
- 原 test-only 导出 annotations：`8166b6080b9adc4cc1b68a4dcd81111576895ba2514032da70999f672cbde0eb`
- 原 test-only COCO audit：`9b330d8773ecd6484db212b599af37990be04dd0754e0354b9ec9129360bf83e`

## v2 跨来源开发诊断

使用冻结 v2 checkpoint，在 CPU、输入 `320×320`、历史训练阶段 confidence `0.49`、NMS `0.5`、匹配 IoU `0.5` 下评估 121 张可评测标注图。该报告生成时使用了原 test-only 导出；后续多档阈值检查后，video13 只能作为开发诊断。checkpoint SHA-256 为 `a11b560c6507f51f3e239b358445fb2acc95694edf80bc86c5cc207cc2a12f72`，ONNX SHA-256 为 `517f296a99c79fe57b44746f9bdc33fb1cb564cffe0456e8f4fcaee8b0c58dea`，固定评测 JSON SHA-256 为 `7ce303cfabdfbc7b60b8d4f0177362c6d15d20298d451ed27a32a8b58a601b92`。

| 评测项 | TP / FP / FN | Precision / Recall / F1 |
| --- | --- | --- |
| 检测框，IoU `0.5` | `204 / 28 / 7` | `87.9310% / 96.6825% / 92.0993%` |
| direction event set | `130 / 14 / 0` | `90.2778% / 100% / 94.8905%` |
| direction event set，IoU-gated | `127 / 17 / 3` | `88.1944% / 97.6923% / 92.7007%` |

方向可判定匹配为 `171/171=100%`。按当前 `90% precision / 80% recall / 90% direction` 门槛，检测准确率（precision）未过门槛；检测召回已过，方向已过。direction event set 的未门控 precision 达到门槛，但 IoU-gated 结果仍低于 90% precision，因此不能把本轮写成整体检测门禁通过。

这 121 张图来自 130 个稀疏抽样任务，结果是模型辅助、跨来源的开发诊断；稀疏帧指标不等于连续事件级指标，也不等于 Android 真机验收或实际发声延迟验收。bootstrap v2 train（664 图／1211 框）和 val（230 图／400 框）保持不变，其自身 test 仍为空。

## 真机反馈后的保守阈值

Android 14 首轮真机冒烟出现提示过密和至少一次用户感知的方向误报。为优先减少错误打扰，对冻结 checkpoint 检查 `0.49 / 0.55 / 0.59 / 0.61 / 0.63 / 0.65 / 0.67`。`0.67` 在本数据上的检测框结果为：

| confidence | TP / FP / FN | Precision / Recall / F1 |
| ---: | --- | --- |
| `0.49` | `204 / 28 / 7` | `87.9310% / 96.6825% / 92.0993%` |
| `0.67` | `192 / 16 / 19` | `92.3077% / 90.9953% / 91.6468%` |

相对 `0.49`，误报从 28 降到 16，召回仍高于 80% 门槛。因为本次已经查看多档阈值，以上结果只能支持开发决策，不能再作为最终测试成绩。对应真机问题、代码修复和下一轮条件见 [Android 14 首轮真人操作冒烟记录](ANDROID_LIVE_SMOKE_2026-09-29.md)。
