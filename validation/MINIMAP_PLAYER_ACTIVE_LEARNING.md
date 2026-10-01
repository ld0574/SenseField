# 小地图玩家头像：合并后的人工队列、候选排序器与安全双类 v8

更新时间：2026-09-30

## 当前结论

首轮 218 张加第二批 120 张主动学习批次已经完成并安全合并到 `data/private/minimap-player-review-queue-v1`。当前队列有 338 个人工终态：276 `corrected`、61 `negative`、1 `skip`；其中 337 个可训练（`corrected` + `negative`）。另有 901 个 `pending`，保留为待复核积压；后续按优先级处理，不把全量清洗 901 张列为前置要求。

候选排序器 v2 严格复用首版流程：MPS、seed `20260930`、25 轮、batch size 96、候选绿色环 patch、仅使用人工 `corrected`／`negative` 标签。训练 split 使用 208 帧，pooled val 使用 129 帧；`skip` 不进入训练或评估。active batch 已并入源队列，没有作为独立代表性测试集。

## v6 512 微调（338 个人工终态，最新开发诊断）

在 338 个人工终态（276 `corrected`、61 `negative`、1 `skip`，其中 337 个可训练）上完成安全双类 v8 的 v6 512 微调，best epoch 为 6，固定阈值为 enemy/player `0.57/0.59`。固定 val 上，`minimap_player` TP/FP/FN 为 `84/4/9`，precision `0.954545`，visible recall `0.903226`，中心误差 P95 `6.289928 px`（相对短边 `0.017083`）；`minimap_enemy` P/R/F1 为 `0.953488 / 0.872340 / 0.911111`。

v6 `metrics.json`、balanced checkpoint、fixed-val report 的 SHA-256 分别为 `cbf00864dd30a66dee39c11cc2a471650b8b7f5e8c3f4de9021923d0f0b70637`、`88a7de0332b18ce63539039fcbaccfd23f0499782818675f5698ffd0ede4ea46`、`d24e7e0a78d9636df9ebe5398f26f364b5acae3a3bdacba94d330afa8f36ee9b`。

val 同时参与选模和阈值选择，test 为 0；上述数值只能称 development diagnostic。v6 512 双类模型已完成 ONNX/TorchScript/ncnn 导出，产物和报告位于忽略目录 `build/ignored/v6-512-export`：ONNX raw 严格门禁失败（最大误差 `0.0006387 > 0.0005`），但 12 张图的最终 detection arrays 全部一致；ncnn Android 等价严格门禁通过。2 线程桌面 preprocess+inference P95 为 `28.9552 ms`。本次未改 Android assets/profile；由于没有独立 test 或 Android 真机验证，v6 仍不能接入 release。Android 已支持校验 metadata 声明的 `320–1024`、`32` 的倍数、正方形输入并按输入尺寸动态计算 anchors。2026-10-01 起，v6 512 以 `profile-dual-512-near-zone.json` 和 `minimap-yolox-nano-dual-512.metadata.json` 作为实验近区 profile 接入 APK；`assets/profile.json` 仍是 320 单类基线，release 未启用 `minimap_player`。

## 安全双类 v8 开发候选（v5 历史对照）

`build/data/minimap-dual-coco-v8`（审计 `build/data/minimap-dual-coco-v8.audit.json`）通过带 provenance 的安全导出得到 323 张图、840 个框：train 194 张／512 框，val 129 张／328 框；其中 `minimap_enemy` 575 框、`minimap_player` 265 框，test 为空。审计状态为 `passed_with_warnings`，警告包括源 SQLite 原始哈希漂移但语义校验通过等历史状态。双类 v8 只用于开发诊断，不是发布数据集；审计确认没有读取 sealed source、没有把机器建议当真值、player/source 数据库只读、帧哈希已核对且所有框都在 crop 内。

此前开发候选为双类 YOLOX-Nano v5 416：epoch 26，输入 `[1,3,416,416]`，输出 `[1,3549,7]`，阈值为 enemy `0.55`、player `0.71`。固定 val 的逐类结果为：enemy P/R/F1 `0.950000 / 0.889362 / 0.918681`，player P/R/F1 `0.952381 / 0.860215 / 0.903955`；player visible recall 为 `0.860215`（80/93），中心误差 P95 为 `7.805562 px`（相对 `0.018585`）。player precision 与中心误差通过门槛，但 visible recall 最低要求 `0.90` 未通过；93 个可见帧需命中至少 84 帧，当前还少 4 帧，因此 player quality gate 仍为 failed。

v3 320 是保留的基线：epoch 28、阈值 enemy/player `0.57/0.81`，player visible recall `0.688172`，中心误差 P95 `5.697911 px`（相对 `0.013566`）；v5 相对 v3 的 visible recall 提高 `0.172043`。v4 低增强对照的 player visible recall 只有 `0.365591`，说明简单削弱增强没有改善该问题。v5 的 416 输入像素量约为 320 输入的 `1.69` 倍，存在端侧延迟风险，尚未接入 Android。

在 v5 416 后追加严格 HSV 绿色 annulus 后处理（model confidence `0.34`、green coverage `≥0.12`、radius `7–23 px`），在用于调参的同一 pooled val 上得到 TP/FP/FN `85/4/8`、precision `0.955056`、visible recall `0.913978`。随后进行三折 leave-one-source-out：在两场来源选择参数后冻结到第三场，所有统一参数组合都无法同时让三场满足 precision `≥0.95`、visible recall `≥0.90`、中心 P95 相对短边 `≤0.03`。典型失败为留出 video2 时 P/R `0.9672/0.8310`，留出 video11 时 `0.9333/1.0000`，留出 video13 时最高仅 `0.8889/1.0000`。因此 annulus pooled 数值属于同集调参乐观结果，不实现到 Android，也不作为发布门禁。

关键 SHA-256：v8 audit `c22fa60f6a0b641f9c7a692347c8b57671e4dbef742a9f02a19c0a5224582c82`，train／val annotations `36cdcf3c4f82339a7a65a91ff5ea2875fa9d19907cb35ccaabd51888134ae5eb`／`bd48e7e2523354517b821215fc5c9c0a260eb6ae0af4d9fb02f7812e3cdcfde2`，v5 metrics `5ccc9a3f4eebe8e2c7f026674b0111b64ada19f66435d8da58bbb55fcb49b3c4`，balanced checkpoint `0eeaaa4ece647939d4d3dcd5d858b6d7cd8258c1ce8c0d1c1d6f4592b5e27118`，fixed-val report `bbb44bde3baa89f1c684017d2a0e3339fb9eade4ee80cf426d226476c1bc50ae`。

val 已参与选模和阈值选择，没有独立 test，这些数值只能称 development diagnostic。

v6 512 双类模型已导出到忽略目录 `build/ignored/v6-512-export`，并于 2026-10-01 以实验近区 profile 复制到 Android assets（权重仍不入 Git）；release 仍绑定单类 `minimap_enemy`。由于没有独立 test 或 Android 真机验证，仍未达到 release-ready。

### 安全双类 v8 复现命令

导出器按 player queue 的 SQLite、player manifest、source manifest/database 和 source-frame provenance 关联两类标注，不能手写普通 timestamp union。下面使用新的忽略目录，避免覆盖已存在的 v8 产物；命令不加 `--verify-video-bytes`，默认只读取已复核队列图片，不读取视频帧。训练和评估命令继续指向已审计的 `build/data/minimap-dual-coco-v8`；若使用新导出的 repro 目录，应将两条命令的 `--data-dir` 一并替换。

```sh
.venv/bin/python training/export_minimap_dual_class.py \
  --player-queue data/private/minimap-player-review-queue-v1 \
  --output build/data/minimap-dual-coco-v8-repro \
  --audit build/data/minimap-dual-coco-v8-repro.audit.json

PYTHONPATH=build/third_party/YOLOX:python \
  .venv/bin/python training/train_yolox_minimap.py \
  --yolox-root build/third_party/YOLOX \
  --data-dir build/data/minimap-dual-coco-v8 \
  --pretrained build/training/yolox-nano-hd-bootstrap-video10-video11-v2-320/best_ckpt.pth \
  --output build/training/yolox-nano-minimap-dualclass-v5-416-v8 \
  --classes minimap_enemy minimap_player \
  --epochs 30 --batch-size 8 --input-size 416 --lr-scale 0.5 \
  --mosaic-prob 0.5 --mosaic-scale-min 0.7 --mosaic-scale-max 1.3 \
  --hsv-prob 0.8 --flip-prob 0.5 --degrees 5 --translate 0.08 --shear 1 \
  --nms-threshold 0.5 --minimum-precision 0.95 \
  --no-aug-epochs 6 --eval-every 2 --log-every 5 \
  --device mps --seed 20260930

PYTHONPATH=build/third_party/YOLOX:python \
  .venv/bin/python training/evaluate_yolox_minimap.py \
  --yolox-root build/third_party/YOLOX \
  --data-dir build/data/minimap-dual-coco-v8 \
  --checkpoint build/training/yolox-nano-minimap-dualclass-v5-416-v8/best_balanced_ckpt.pth \
  --input-size 416 --split val \
  --classes minimap_enemy minimap_player \
  --confidence-by-class '{"minimap_enemy":0.55,"minimap_player":0.71}' \
  --iou-threshold 0.5 --nms-threshold 0.5 \
  --output build/training/yolox-nano-minimap-dualclass-v5-416-v8/fixed-val-per-class.json
```

不得用普通时间戳拼接来源，不得引用、运行或查看 video9/video12；安全双类 v8 也不得作为 Android release 候选。

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

这个 v2 排序器是复核辅助器，不是 ground truth、评测证据或 Android 发布模型；它没有导出，也没有替换或启用 Android 模型与 profile。后文的 v6 512 双类检测模型是另一项产物。

## 数据边界

v2 只读取合并后的源队列 manifest、SQLite 和其中允许的帧。`pending-predictions.json` 为 901 张 pending 的复核建议，其中 623 张有建议框、278 张无建议框；机器建议不覆盖人工标签，也不作为训练真值。video9 和 video12 继续封存，本轮未读取、未预标、未查看预测。
