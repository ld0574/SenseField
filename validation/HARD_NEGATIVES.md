# video1–7 困难误报加权实验

## 目的与数据边界

video8 真人排位冻结评测显示 precision 只有 58.84%。该录像继续保持只读，本实验不读取其逐帧预测或失败案例，只使用已经降级为开发数据的 video1–7。

开发集仍按完整录像分组：video1–5 和 video7 为 train，video6 为 val。训练集有 708 张人工复核帧／1,109 个框，其中 97 张为纯负样本；验证集保持 152 张／223 个框不变。

先用原冻结 checkpoint 和固定 confidence `0.29` 在 train 上运行一次：

```sh
PYTHONPATH=build/third_party/YOLOX:python .venv/bin/python \
  training/evaluate_yolox_minimap.py \
  --yolox-root build/third_party/YOLOX \
  --data-dir data/private/minimap-review-v5-video1-7-train-video6-val/coco-minimap \
  --checkpoint build/training/yolox-nano-minimap-dense-video1-6-320px-earlystop40e/best_ckpt.pth \
  --input-size 320 --split train --confidence 0.29 \
  --iou-threshold 0.5 --nms-threshold 0.5 --device mps \
  --output build/training/hard-negative-baseline-v1-5-v7-train.json
```

结果为 TP/FP/FN `998/115/111`。115 个 FP 分布在 98 张帧中，其中 video7 占 63 个；7 张纯负样本出现 8 个 FP，另外 107 个 FP 出现在同时含有真值目标的画面。说明问题不能只靠增加空地图帧解决，还需要提高塔、兵线、友方头像和重叠图标等同画面背景的权重。

## 可复现的加权数据

`training/reweight_coco_hard_examples.py` 只接受覆盖完整 train split、且 annotation SHA-256 与源 COCO 一致的固定阈值评测。它重复含 FP 的完整人工复核图片及其全部真值框，不裁取模型预测区域，也不改变 val/test：

```sh
PYTHONPATH=.:python .venv/bin/python training/reweight_coco_hard_examples.py \
  --data-dir data/private/minimap-review-v5-video1-7-train-video6-val/coco-minimap \
  --evaluation build/training/hard-negative-baseline-v1-5-v7-train.json \
  --output data/private/minimap-review-v5-video1-7-train-video6-val/coco-minimap-hard-fp \
  --max-extra-copies 2 --negative-bonus 1
```

训练图片由 708 张增加到 826 张，框由 1,109 个增加到 1,316 个；98 张困难源图中 78 张增加一份、20 张增加两份。重复完整图片能同时强化真值定位和误报背景，避免把包含真敌人的帧误当作纯负样本。

## 微调结果

从原 epoch 20 checkpoint 以 `lr_scale=0.1` 微调，仍用 video6 选择 threshold 和 checkpoint；第 10 轮为最佳点，第 30 轮因连续四次验证未改善而早停：

| 指标 | 原冻结模型 | 困难样本候选 |
| --- | ---: | ---: |
| Precision | 90.50% | 90.27% |
| Recall | 72.65% | 74.89% |
| F1 | 80.60% | 81.86% |
| 方向正确率 | 96.10% | 97.50% |
| 开发阈值 | 0.29 | 0.19 |

召回提高 2.24 个百分点，F1 提高 1.26 个百分点，但仍未达到 80% recall，因此暂不替换 APK 中的冻结模型。后续需要新增更多已人工复核的高分辨率真人开发对局，尤其是困难背景；再用另一场从未参与开发的真人排位做独立盲测。

## 证据哈希

```text
源 train annotations：eeff2b1fff5ae1b2bb365fe5651f144477d2e2b977ad6140eddef992d046220c
冻结 train 评测：47b483e2af8036dfe1ff76d0fc2fb3be2b9a9c8dac2a25ccc30d09847685c71f
加权 train annotations：3b13d6c03a2d4b60bf92e5e609c7ff6714d6150a2ee15087a297bdc9f09ca776
候选 checkpoint：f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb
训练 metrics：ccb1d035d4ce09373f94dbe07486d9203dd1e88fa99937a64bd817363eb7bdcc
固定 video6 开发评测：d6fdfac89eb795e7b99001ca1a25de335f4e595438d735023c0c3cee17e1d148
```

这些是开发指标。video6 已用于 checkpoint 和 threshold 选择，不能当作独立成绩；video8 也没有用新候选重跑。
