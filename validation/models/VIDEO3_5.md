# 五场录像数据扩充记录（2026-09-26）

## 素材清单

录像只在本地处理，原文件和派生产物均被 `.gitignore` 排除。

| 录像 | 分辨率 | 时长 | 12 FPS 回放帧 | SHA-256 |
| --- | ---: | ---: | ---: | --- |
| `video1.mp4` | 720×324 | 927.866 秒 | 11,134 | `215e81c55de31ec94e83ddc95e7f109c8704cdffaa245f94f1576acc9f081e72` |
| `video2.mp4` | 720×324 | 1086.033 秒 | 13,032 | `bf37667fe2438b47c31da23e50a04a66598b95d65fcc79942052b4c2ae3d9ab5` |
| `video3.mp4` | 960×432 | 831.917 秒 | 9,983 | `7b9f429533982176352960ba2946bca34fe4ccc42e38cb3dfc1485e1df7e6448` |
| `video4.mp4` | 720×324 | 974.533 秒 | 11,694 | `d82d2487b1be98493ab972fdc8190bf6ac82e0d799eae220a17997b1ff11ff84` |
| `video5.mp4` | 720×324 | 927.900 秒 | 11,135 | `7d92a64009a56c002a7ffad1b7dfb1cd4d46f7bee759b2a1fc7e4c6a184e9c62` |

内容抽查确认五段是不同对局。`video5` 与 `video1` 的时长接近，但画面、英雄和阵容不同。

## 新数据发现与修正

冻结的 video1/video2 红环规则首次回放新增录像时，video3、video4、video5 分别输出 112、140、144 条限频提示。放大查看候选后发现英雄选择和加载画面也可能在小地图 ROI 内同时出现红色头像边框与青色 UI，旧的青色像素总量判断会误以为地图已显示。

共享 C++ 检测器现将小地图 ROI 分成 4×4 网格，要求青色地图标记覆盖至少 12 格、暗色地形覆盖至少 11 格，同时保留像素总量条件。回归测试覆盖了暗色英雄卡、亮色英雄卡、分散卡片标记和左右分栏界面。人工确认的赛前误报时刻包括 video2 的 42.417 秒、video3 的 66.333 秒、video4 的 37.417／47.333／52.333／57.333 秒和 video5 的 11.583 秒；最终规则均保持静默。

最终完整回放结果如下。提示数受五秒限频和空间跟踪影响，不能直接当作敌人出现次数。

| 录像 | 观察 | 小地图提示 | 首条提示时间 |
| --- | ---: | ---: | ---: |
| video1 | 10,890 | 102 | 115.667 秒 |
| video2 | 16,968 | 141 | 108.417 秒 |
| video3 | 7,578 | 105 | 81.000 秒 |
| video4 | 13,958 | 126 | 113.083 秒 |
| video5 | 13,051 | 123 | 86.833 秒 |

最终预测位于 `data/private/expanded/video*-minimap-ring-map-gated-v3.jsonl`。规则倾向于静默；放大复核图也显示部分无观察帧中可能仍有红方头像，所以当前修正降低了赛前误报，但可能增加漏检。没有逐框真值前不能计算准确率或召回率。

## 人工真值与导出数据集

新增 `mapassist.review_dataset`，从每场完整回放中分层选择提示帧和无观察背景帧，导出原图、全屏框图、小地图放大联系表、录像哈希及复核清单。建议框始终写成 `review_status: pending` 和 `reviewed_boxes: null`，不会自动成为训练真值。

每场录像可在抽样源中填写 `active_intervals_ms: [[开始毫秒, 结束毫秒], ...]`。生成器只在完整 HUD 和小地图可见的对局时间段内选择提示和背景帧，并拒绝无效、重叠或乱序区间。逐秒检查录像边界后，抽样源记录为：

| 录像 | 有效区间（ms） |
| --- | ---: |
| video1 | 92,000–904,000 |
| video2 | 105,000–1,063,000 |
| video3 | 70,000–831,000 |
| video4 | 105,000–962,000 |
| video5 | 88,000–914,000 |

video3 的原始分辨率为 960×432，HUD 小地图右边界比 720×324 录像按比例换算后更靠右。复核清单因此为 video3 单独记录归一化 ROI `[0.0375, 0, 0.1552083333, 0.3333333333]`；其他录像继续使用清单顶层 ROI。标注网站、框范围校验、时序裁剪和方向评测均读取每场覆盖值，避免裁掉右侧约 10 个像素。

现有队列在有效区间功能加入前生成，没有重新生成，以免覆盖协作标注进度。经完整画面核对，最终有 29 张准备、选人、加载、商店覆盖、展开战术地图、赛后和结算过渡帧标为 `excluded`；其中 video5 的 0、31、62 和 927.167 秒由用户确认，其余保留具体复核者信息。

当前输出为 `data/private/minimap-review-v3/`：

| 分组 | 对局 | 提示候选 | 背景候选 | 合计 |
| --- | ---: | ---: | ---: | ---: |
| train | video1、video2、video3 | 60 | 30 | 90 |
| val | video4、video5 | 40 | 20 | 60 |
| test | 无 | 0 | 0 | 0 |

共 150 张候选帧，其中 100 张来自提示时刻，50 张来自无观察背景。现已全部复核：24 张直接接受建议框、79 张人工修正、18 张确认没有敌人、29 张排除为非对局界面，没有待处理或跳过帧。运行命令：

```sh
PYTHONPATH=python python3 -m mapassist.review_dataset \
  data/private/expanded/minimap-review-source.json \
  --output data/private/minimap-review-v3 \
  --positive-per-match 20 --negative-per-match 10
```

复核 `review-manifest.json` 时，每张样本必须设置以下状态之一：

- `accepted`：建议框全部正确，直接采用 `suggested_boxes`。
- `corrected`：将完整真值框写入 `reviewed_boxes`；这里既可修正框，也可补漏框。
- `negative`：确认整帧没有该类目标，写出空框。
- `excluded`：准备、选人、加载或结算等非对局画面，不进入数据集。
- `skip`：画面模糊、遮挡或语义不确定，不进入数据集。

只要还有一张 `pending`，收口工具就拒绝导出。全部复核后运行：

推荐通过多人标注网站完成复核。单人使用 `--host 127.0.0.1`；同一可信局域网内协作使用 `--host 0.0.0.0`，终端会打印同伴可访问的地址：

```sh
PYTHONPATH=python python3 -m mapassist.annotation_server \
  --dataset data/private/minimap-review-v3 \
  --host 0.0.0.0 --port 8765
```

每位标注员使用不同名字。服务端以 15 分钟租约分配任务，SQLite 和版本检查会拒绝同伴占用中的任务及旧页面写入；结果同时写入数据库和 `review-manifest.json`。标注页固定显示完整画面；源录像可用时还会按需提取前后 0.5 秒的小地图，辅助判断图标是否移动。网站没有账号认证，只在可信局域网内使用。完成后停止服务，先生成逐框诊断，再执行数据收口：

```sh
PYTHONPATH=python python3 -m mapassist.detection_evaluate \
  data/private/minimap-review-v3/review-manifest.json \
  --output data/private/minimap-review-v3/detection-report.json
cmake -S native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel
PYTHONPATH=python python3 -m mapassist.detection_replay_evaluate \
  data/private/minimap-review-v3/review-manifest.json \
  --profile profiles/hok_minimap_development.json \
  --output data/private/minimap-review-v3/detection-report-current.json
PYTHONPATH=python python3 -m mapassist.finalize_review \
  data/private/minimap-review-v3/review-manifest.json \
  --output data/private/minimap-review-v3/detection-manifest.json
PYTHONPATH=python python3 -m mapassist.detection_dataset \
  data/private/minimap-review-v3/detection-manifest.json \
  --output data/private/minimap-review-v3/coco
```

逐框报告默认使用 IoU 0.5，包含 train／val、逐场和总体指标及失败帧。当前样本由检测结果分层选择，且 video1–5 都参与过开发，因此这些指标用于发现误框、漏框和方向问题，不能作为独立测试成绩。

COCO 导出器保留 `minimap_enemy` 类别和整场 train／val 分组。最终产物已经生成：

| 产物 | 结果 |
| --- | --- |
| `detection-report.json` | 原始 v3 建议框：121 张有效帧；准确率 58.43%、召回率 54.74%、匹配框平均 IoU 80.36%、方向正确率 97.50% |
| `detection-report-current.json` | 当前原生库重新推理同一批帧：准确率 71.07%、召回率 59.47%、匹配框平均 IoU 76.98%、方向正确率 97.80% |
| `detection-manifest.json` | 121 张有效帧、190 个敌方头像框 |
| `coco/` train | 76 张图片、111 个框，其中 12 张负样本 |
| `coco/` val | 45 张图片、79 个框，其中 6 张负样本 |

逐框失败记录显示当前规则最常见的问题是把相邻头像合成一个框，以及在没有候选观察的背景抽样帧中漏掉敌人。以上数据来自参与调参的分层抽样开发集，只用于下一轮改进，不能作为比赛验收成绩。

五场录像都已被查看并用于发现或验证规则失败，不能再作为独立留出测试。下一场新录像应冻结为 `test`，先完成逐框标注和预测，再决定是否调整规则；一旦据其结果调参，就必须把它移回开发数据。
