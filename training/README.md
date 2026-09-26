# 小地图 YOLOX-Nano 开发训练

当前开发训练使用 `video1` 至 `video6`。600 张预测无关密集队列已经完成复核；与旧标注合并后共有 749 张有效帧、1,143 个敌人框，按整场对局划分为 `video1–5` 训练 597 张／920 框、`video6` 验证 152 张／223 框。`video6` 已经用于失败分析、模型选择和阈值选择，因此六场都不能再作为比赛留出成绩。

## 先解决数据分辨率和覆盖

现有 `720×324` 录像的小地图裁剪只有约 `104×108`，敌人头像中位数约 `18×18`，最小目标宽度约 6 像素。把裁剪放大到 320 或 640 输入不会恢复录屏已经丢掉的细节。新增录像应使用设备原生 1080p/1440p 和高码率，训练与安卓端都应先从原始整帧裁出小地图，再缩放到模型输入；不要先缩小整屏。

密集数据的 320 输入实验在第 20 轮达到最好结果：precision 90.50%、recall 72.65%、F1 80.60%，几何方位正确率 96.10%；连续四个验证点没有刷新后于第 40 轮早停。相较旧数据最佳点，召回提高 13.56 个百分点。两个对照均较差：低 Mosaic 为 92.02% / 67.26% / 77.72%，416 输入为 90.63% / 65.02% / 75.72%。因此当前冻结选择为 Nano、320 输入、默认 Mosaic 的第 20 轮权重，开发阈值 0.29。

`video7` 是 2712×1220、30 FPS、约 8 Mbps 的人机局，先保持为预测无关高分辨率测试。111 张有效盲测帧含 189 框；冻结模型在固定阈值下得到 precision 65.57%、recall 63.49%、F1 64.52%，未达门禁。它比历史模型分别高 3.62、3.17、3.39 个百分点，说明密集标注有效，但高分辨率本身没有消除跨录像域差异。

改进顺序为：

1. 录制另一场原生高分辨率真实匹配对局并先冻结为 test；
2. 再把 `video7` 的高分辨率人机数据加入训练，补充无敌人画面以及塔、兵线、技能光效等难负样本；
3. 继续按整场录像分组，禁止从同一场拆帧到训练与验证／测试；
4. 只有数据覆盖稳定后再比较 Nano/Tiny，并以验证集 recall 和真机延迟共同选择，而不是按训练 loss 选择。

密集队列和多人标注命令见 [`docs/团队协作与本地运行.md`](../docs/团队协作与本地运行.md)。

同一批低分辨率数据还做了 640×640 输入微调对照：从 320 最佳权重继续训练 60 轮，最佳点为 epoch 30，precision 91.49%、recall 48.86%、F1 63.70%。它低于 320 基线，说明单纯放大已经只有 `104×108` 的小地图裁剪没有解决问题。该实验只排除“直接增大输入即可修复”的假设，不代表高分辨率原始录像或重新设计过的 640 训练一定无效。

## 准备 YOLOX

从仓库根目录运行：

```sh
mkdir -p build/third_party
git clone https://github.com/Megvii-BaseDetection/YOLOX.git build/third_party/YOLOX
git -C build/third_party/YOLOX checkout 6ddff4824372906469a7fae2dc3206c7aa4bbaee
git -C build/third_party/YOLOX apply ../../../training/patches/yolox-modern-pytorch-mps.patch

uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python -e '.[test]' torch torchvision \
  opencv-python loguru tqdm thop ninja tabulate psutil tensorboard \
  pycocotools onnx onnxruntime
```

补丁将旧式张量类型转换改成当前 PyTorch 支持的设备无关写法，不改变网络结构或权重。它让同一训练脚本可在 CUDA、Apple MPS 和 CPU 上运行。

下载官方 COCO 预训练权重：

```sh
mkdir -p build/models
curl -L --fail -o build/models/yolox_nano.pth \
  https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.pth
shasum -a 256 build/models/yolox_nano.pth
```

期望 SHA-256：`cd28f55fbbc1829f99d9ac9b38a16d259a22889739c8728ea877610201feff7b`。

## 导出裁剪数据并训练

```sh
PYTHONPATH=python .venv/bin/python -m mapassist.finalize_review \
  data/private/minimap-review-v4-dense/review-manifest.json \
  --output data/private/minimap-review-v4-dense/detection-manifest.json

PYTHONPATH=python .venv/bin/python -m mapassist.combine_detection_manifests \
  data/private/minimap-review-v3/detection-manifest-video1-6-development.json \
  data/private/minimap-review-v4-dense/detection-manifest.json \
  --output data/private/minimap-review-v4-dense/detection-manifest-combined-video1-6-development.json

PYTHONPATH=python .venv/bin/python -m mapassist.detection_dataset \
  data/private/minimap-review-v4-dense/detection-manifest-combined-video1-6-development.json \
  --crop-roi \
  --output data/private/minimap-review-v4-dense/coco-minimap-combined-video1-6

PYTHONPATH=build/third_party/YOLOX:python \
  .venv/bin/python training/train_yolox_minimap.py \
  --yolox-root build/third_party/YOLOX \
  --data-dir data/private/minimap-review-v4-dense/coco-minimap-combined-video1-6 \
  --pretrained build/models/yolox_nano.pth \
  --epochs 120 --batch-size 16 --input-size 320 --eval-every 5 \
  --early-stop-patience 4 --early-stop-min-epoch 40 \
  --output build/training/yolox-nano-minimap-dense-320
```

`metrics.json` 保存数据、实验配置和预训练权重哈希、YOLOX 提交号、逐轮损失及验证阈值。`best_ckpt.pth` 是开发验证 F1 最好的权重。只有新录、未参与调参的连续对局可以用于最终成绩。

## 导出与一致性检查

```sh
PYTHONPATH=build/third_party/YOLOX:python \
  .venv/bin/python build/third_party/YOLOX/tools/export_onnx.py \
  -f training/yolox_nano_minimap.py \
  -c build/training/yolox-nano-minimap-dense-320/best_ckpt.pth \
  --output-name build/models/minimap-yolox-nano-dense-320/model.onnx \
  --no-onnxsim \
  test_size '(320,320)' input_size '(320,320)'

PYTHONPATH=build/third_party/YOLOX:python \
  .venv/bin/python training/verify_yolox_onnx.py \
  --yolox-root build/third_party/YOLOX \
  --data-dir data/private/minimap-review-v4-dense/coco-minimap-combined-video1-6 \
  --checkpoint build/training/yolox-nano-minimap-dense-320/best_ckpt.pth \
  --onnx build/models/minimap-yolox-nano-dense-320/model.onnx \
  --confidence 0.29 \
  --output build/models/minimap-yolox-nano-dense-320/onnx-parity.json

PYTHONPATH=build/third_party/YOLOX:python \
  .venv/bin/python training/evaluate_yolox_minimap.py \
  --yolox-root build/third_party/YOLOX \
  --data-dir data/private/minimap-review-v4-dense/coco-minimap-combined-video1-6 \
  --checkpoint build/training/yolox-nano-minimap-dense-320/best_ckpt.pth \
  --input-size 320 --split val \
  --output build/training/yolox-nano-minimap-dense-320/fixed-val-evaluation.json
```
