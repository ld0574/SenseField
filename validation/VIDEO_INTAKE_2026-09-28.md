# 新增录像接收审计（2026-09-28）

本记录只做文件、画面和数据划分审计。没有对 `video9` 至 `video12` 或 `hero.mp4`
运行现有检测器，也没有用它们调模型、阈值或后处理规则。

## 高清替代录像

| 对应场次 | 旧录像 | 高清录像 | 关系与处理 |
| --- | --- | --- | --- |
| video1 | 旧低清文件已被替换 | `video/video1.mp4`，2400×1080 | 当前路径为高清重导出，队列 ID 为 `video1-hd`；100 个任务已复核，95 帧／163 框可训练并纳入 train。不存在 `video1hd.mp4`。 |
| video2 | 720×324，1086.033 s，约 1.04 Mbps | 2400×1080，1086.031 s，约 17.56 Mbps | 同一场、同一时间轴。当前 HD 队列 100 帧已人工复核：79 `corrected`、21 `negative`，共 197 框，进入 bootstrap val；旧低清迁移建议已归档。 |
| video3 | 960×432，831.917 s，约 0.72 Mbps | 2400×1080，1452.633 s，约 17.60 Mbps | 不是同一场；阵容、流程和时长均不一致。不得迁移旧标签，必须使用新 match id 重新标注。 |
| video4 | 720×324，974.533 s，约 1.04 Mbps | 2400×1080，974.509 s，约 17.66 Mbps | 同一场、同一时间轴；旧迁移建议已归档，当前用纯 HD 队列从零标注。 |
| video5 | 720×324，927.900 s，约 1.04 Mbps | 2400×1080，927.882 s，约 17.69 Mbps | 同一场、同一时间轴；旧迁移建议已归档，当前用纯 HD 队列从零标注。 |

文件 SHA-256：

- `video2.mp4`：`bf37667fe2438b47c31da23e50a04a66598b95d65fcc79942052b4c2ae3d9ab5`
- `video2hd.mp4`：`6e2f63276f4037f9802f75f57329b956392697e9bfd96697bdfb39b43a0855e9`
- `video3.mp4`：`7b9f429533982176352960ba2946bca34fe4ccc42e38cb3dfc1485e1df7e6448`
- `video3hd.mp4`：`257b90a579828d61c903ea12e90f53f6f47739ad8173b81d88dc2162dad456cd`
- `video4.mp4`：`d82d2487b1be98493ab972fdc8190bf6ac82e0d799eae220a17997b1ff11ff84`
- `video4hd.mp4`：`357e88c30ac284e27f350377f503d96fe73b930e1974cf5f91eb5209d10cc736`
- `video5.mp4`：`7d92a64009a56c002a7ffad1b7dfb1cd4d46f7bee759b2a1fc7e4c6a184e9c62`
- `video5hd.mp4`：`0a35fce37826366bda6e00e75d53ab017fdf78f0ac1b913fcd6fd865faf22f27`

HD 文件使用 Display Matrix 旋转 90°，编码尺寸为 1080×2400，显示尺寸为
2400×1080。迁移时先按显示方向解码，再按时间戳取邻近帧；不能按帧号搬运，因为旧录像约
24–30 FPS，高清录像约 57–59 FPS 且可能是 VFR。训练集中不能同时保留同一场的低清和高清
副本。不存在 `video6hd.mp4`。低清 video2–6、以及由这些录像产生的模型与指标，全部退役为历史记录；不得进入当前导出、训练、验证或阈值选择。video6 不属于当前获准数据集。

## 新增完整对局

| 文件 | 媒体信息 | 有效对局区间（约） | 画面边界 | 当前建议 |
| --- | --- | --- | --- | --- |
| `video9.mp4` | AV1，1920×864，29.68 FPS，849.16 s，1.45 Mbps | 5–823 s | 开头加载、结尾胜利／结算；约 120 s 商店短时遮地图，约 540 s 信号覆盖局部。右上 B 站水印不遮地图。加载和结算提供排位局证据。 | cross-source sealed holdout；不得运行模型或查看预测。 |
| `video10.mp4` | H.264，1920×860，30 FPS，1001.04 s，3.34 Mbps | 0–990 s | 小地图完整；短时商店／快捷交流帧需排除。 | train/dev 开发来源。 |
| `video11.mp4` | AV1，1920×860，22.47 FPS，1010.08 s，1.86 Mbps | 18.753–999 s | 排除开局商店 `[0,15000)` ms，以及 719–724 s、725–728 s 战绩面板遮挡；帧率低。 | HD dev-val。 |
| `video12.mp4` | H.264，1920×860，30 FPS，1008.40 s，3.53 Mbps | 0–1001 s | 开头约 5 s 中央隐私提示，不遮小地图；之后未见长期遮挡。 | primary sealed holdout；不得运行模型或查看预测。 |

四个文件都没有音频流。`video10` 至 `video12` 主控英雄和画面布局一致，可能来自同一玩家和
录屏源，因此必须按完整录像分组，不能按帧随机拆分。video12 固定为 primary sealed holdout，
video9 固定为 cross-source sealed holdout；模型冻结前，两场均不得运行模型或查看预测。

## Hero 实战片段

`hero.mp4` 为 852×388、约 30 FPS、134.33 s、约 0.93 Mbps 的实战摘录。0–134.3 s
全部处于对局中，没有选人、加载或结算。小地图、计分、血条、摇杆和技能 HUD 均可见，但
小地图只有约 120×95 像素，画面较软，不适合作为高清检测训练主数据。

Hero 只用于 UX／事件故事，不运行检测模型，也不进入训练、精度指标、验证或阈值选择。可展示的片段包括：

- 0–12 s：完整 HUD 与小地图；
- 34–44 s：敌人接近并交战；
- 56–64 s：多人交战；
- 76–84 s：接近结构与技能范围；
- 92–104 s：近距离遭遇；
- 128–134 s：交战中突然结束。

录像能够展示玩家需要同时关注主画面和小地图的场景，但没有视线、触控或口述证据，不能据此
断言 Hero 在某一时刻漏看了敌人。该片段优先用于用户故事、提示节奏和事件级设计，不用于报告
高清检测精度。

## HD-only 数据划分与当前队列

未来训练／开发数据仅允许：已替换成高清内容的 `video/video1.mp4`、`video2hd`、`video3hd`、`video4hd`、`video5hd`、`video7`、`video8`、`video10`。video11 是单独 HD dev-val。`video12` 是 primary sealed holdout，`video9` 是 cross-source sealed holdout；两场继续封存，不得运行模型或查看预测。Hero 只用于 UX／事件故事。

低清 video2–6 及其旧模型、指标、队列，只保留作历史记录，不得进入当前导出、训练、验证或阈值选择。

| 复核队列 | 当前状态 | 小地图坐标 |
| --- | --- | --- |
| `data/private/minimap-review-video1-hd-v1` | 100 已复核，95 可训练／163 框（83 `corrected`、12 `negative`、5 `excluded`） | safe/label/widget 约 `[55,0,510,420)` / `[80,0,466,380)` / `[126,0,464,334)` |
| `data/private/minimap-review-video2-hd-v1` | 100 已复核，79 `corrected`、21 `negative`，197 框 | safe/label/widget `[55,0,510,420)` / `[80,0,466,380)` / `[120,0,465,347)` |
| `data/private/minimap-review-video4-hd-v1` | 人工复核中；因存在活动租约，未热替换最新建议框 | 同 video2hd |
| `data/private/minimap-review-video5-hd-v1` | 100 张待复核；已挂载最新 HD 模型的 169 个可编辑建议框 | 同 video2hd |
| `data/private/minimap-video3hd-new-match-20260928-a/blind-review-v1` | 120/120 已复核：102 `corrected`、18 `negative`、268 人工框；0 `accepted`／`pending`／活动租约。SQLite 与 manifest 一致，三层 ROI、尺寸与框审计通过，0 crop-edge contact；可用于 train。有效区间 `[138000,1439000)` ms | safe/label/widget `[55,0,510,420)` / `[80,0,466,380)` / `[94,0,466,352)` |
| `data/private/minimap-review-video7-edge-recheck-v1` | 240 pending，333 个起始框仅来自 HD 人工结果 | safe/label/widget `[55,0,600,470)` / `[90,0,550,420)` / `[143,0,524,378)` |
| `data/private/minimap-video8-holdout-v1/blind-review-v3-safe-roi` | 120 已复核，119 可训练／211 框（102 `corrected`、17 `negative`、1 `excluded`） | safe/label/widget `[65,0,500,400)` / `[90,0,470,365)` / `[106,0,454,344)` |
| `data/private/minimap-video10-11-hd-development-v1/queue` | 共 260 张待复核；已挂载最新 HD 模型的 330 个可编辑建议框 | safe/label/widget `[44,0,408,334)` / `[64,0,373,303)` / `[96,0,372,277)` |

video11 排除开局商店 `[0,15000)` ms 和 719–724 s、725–728 s 遮挡，最早有效任务为 18.753 s。`safe` 限制完整框，`label` 限制目标中心，`widget` 只用于地图主体与方向。video5 与 video10/11 的新建议框由当前 HD bootstrap ONNX 在 confidence `0.33` 生成，并经过 label ROI 与安全边缘过滤；所有未审核机器建议都只是待复核提示，不能作为真值。当前按完整录像分组的 HD bootstrap split 为 video1+8+3 train（334 图／642 框）和 video2-HD val（100 图／197 框），test 为空，不能报告最终成绩。其余待标队列完成复核及 ROI/provenance 检查后才能加入；video9/12 绝不用于预标注或查看预测。详细启动方式见[团队协作与本地运行](../docs/团队协作与本地运行.md)。
