# 小地图 YOLOX-Nano 开发训练

原 dense baseline 使用 `video1` 至 `video6`。600 张预测无关密集队列已经完成复核；与旧标注合并后共有 749 张有效帧、1,143 个敌人框，按整场对局划分为 `video1–5` 训练 597 张／920 框、`video6` 验证 152 张／223 框。`video6` 已经用于失败分析、模型选择和阈值选择，因此六场都不能再作为比赛留出成绩。后续纳入 video7 扩展标签的微调结果和数据划分见下文；video1–7 均属于开发数据。

下文所有基于旧固定 ROI 的 video6 检测指标都是 crop-relative 内部开发对照。video8 的边界复核和 video7 的 ROI 量化显示旧裁剪存在边缘覆盖缺口；这些分数不证明完整小地图的目标覆盖或召回。旧 ROI 与旧权重暂不改动，纠正裁剪后须重做标注、训练和评估。

小地图区域定位器使用另一套全屏单类数据：video1–8 每场 16 张，共 128 张 `minimap_region` 边界。video8 的 16 个初始左边界后来发现有误，v1 的相关 video8 覆盖率／IoU 已作废；修正标签已用于 v2 重训。v2 按录像分组为 112 张 train（含 video8 的 16 张）和 16 张 video6 开发验证。v2 video6 val 的 IoU 中位数为 0.8369，2.5% 扩边后 16/16 帧覆盖真值；另 120 张 video8 布局评测帧未参与训练，但同场的 16 张 video8 帧参与训练，因此仅为 same-match development diagnostic，不能称独立留出。描述符定位器在 video8 的对照另行报告为与 reviewed crop 的一致性，不是独立 HUD 边界精度。定位器实验不表示敌人检测已修好或发布门禁通过；完整证据和哈希见 [`validation/MINIMAP_LOCATOR.md`](../validation/MINIMAP_LOCATOR.md)。

## 先解决数据分辨率和覆盖

现有 `720×324` 录像的小地图裁剪只有约 `104×108`，敌人头像中位数约 `18×18`，最小目标宽度约 6 像素。把裁剪放大到 320 或 640 输入不会恢复录屏已经丢掉的细节。新增录像应使用设备原生 1080p/1440p 和高码率，训练与安卓端都应先从原始整帧裁出小地图，再缩放到模型输入；不要先缩小整屏。

密集数据的 320 输入实验在第 20 轮达到最好结果：precision 90.50%、recall 72.65%、F1 80.60%，几何方位正确率 96.10%；连续四个验证点没有刷新后于第 40 轮早停。相较旧数据最佳点，召回提高 13.56 个百分点。两个对照均较差：低 Mosaic 为 92.02% / 67.26% / 77.72%，416 输入为 90.63% / 65.02% / 75.72%。当时冻结选择为 Nano、320 输入、默认 Mosaic 的第 20 轮权重，开发阈值 0.29；当前本机 Android 开发候选已更新为下述 hard-FP checkpoint 和 confidence `0.19`。

`video7` 是 2712×1220、30 FPS、约 8 Mbps 的人机局。历史盲测抽样中的 111 张有效帧含 189 框；冻结模型在固定阈值下得到 precision 65.57%、recall 63.49%、F1 64.52%，未达门禁。它比历史模型分别高 3.62、3.17、3.39 个百分点。之后 video7 标签被用于开发训练和复核，因此这组历史结果不再是独立留出成绩；高分辨率本身也没有消除跨录像域差异。

`video8` 曾登记为独立 test，后来发现旧 fixed ROI 截掉右侧头像。旧 119 帧／215 框指标仍只作受截断污染的历史记录。v3 安全 ROI 现已完成复核并导出 119 帧／211 框；其边框中心均在实测 widget 内、完整框均在安全 crop 内。旧冻结、当前 hard-FP 与 Android 等价 ncnn 回放都已在新真值上做 same-match development diagnostic：固定阈值的框级 Precision 约 47–49%、Recall 约 90–91%；confidence sweep 的 best F1 和高 precision/低 recall 取舍见 [`validation/VIDEO8.md`](../validation/VIDEO8.md)。这些值不能当独立留出或部署成绩，因为 video8 同场素材参与过定位器开发，且现有权重按旧 crop 训练。当前 Android ROI／权重不变，检测器默认关闭。

敌人重标队列 `data/private/minimap-video8-holdout-v1/blind-review-v3-safe-roi` 的 120 条任务已收口：102 `corrected`／211 框、17 `negative`、1 `excluded`、0 `pending`／活动租约。源录像显示尺寸 2376×1080；小地图控件 `[106,0,454,344]` 外扩 27 px 后得到安全 crop `[79,0,481,371]`（402×371）。task59 的蓝圈误标已删除；每个真值框中心均在 widget 内，完整框均在安全 crop 内；另有一个框触及物理屏幕顶边，不触及可扩展 crop 边。COCO test 导出有 119 张／211 框，crop/provenance audit 无阻断项，但 train 和 val 为空，且所有帧来自同一对局。v2 因 crop 与全屏 manifest 坐标错配仍属无效产物。v3 尚未用于安全 ROI 权重重训。

在 video8 v3 的 119 个有效帧上，队列“初始建议”是 v1 人工复核框，不是模型输出，和新真值比较为 178/37/33、P/R/F1 82.79%／84.36%／83.57%。旧冻结 checkpoint（0.29）为 190/212/21、47.26%／90.05%／61.99%；hard-FP checkpoint（0.19）为 191/201/20、48.72%／90.52%／63.35%；同一 hard-FP 权重的 Android 等价 ncnn exact-frame 回放为 191/202/20、48.60%／90.52%／63.25%。Confidence sweep 的 F1 最佳点为 0.647382（P/R/F1 63.14%／76.30%／69.10%）；在 P≥90% 的 cutoff 中最高 recall 为 19.91%（confidence 0.738952）。全属 same-match development diagnostics，不参与部署阈值选择，也不能声称独立泛化。完整方法和 hashes 见 [`validation/VIDEO8.md`](../validation/VIDEO8.md)。

安全裁剪负责检测覆盖，方向中心仍由真实控件框决定。标注清单用 `widget_roi` 保存真实控件；裁剪 COCO 数据集时，该框会映射到每张图像的局部 `direction_roi`，框级方向和方向事件指标都读取它。新 Android profile 应在 `rois.minimap_direction` 配置同一显示布局的方向参考；旧 profile 没有此项时仍使用 `rois.minimap`，以保持现有 profile 的运行结果。当前 hard-FP profile／权重仍是 legacy crop，不能直接用于扩大后的裁剪。

使用 video1–5 和 video7 的 708 张 train 帧做困难误报加权实验。原冻结模型在这些人工复核帧上产生 115 个 FP，其中 video7 占 63 个。重复 98 张含 FP 的完整图片后低学习率微调，video6 开发验证从 90.50% precision / 72.65% recall / 80.60% F1 变为 90.27% / 74.89% / 81.86%。这些 video6 数值只描述 legacy fixed-ROI crop 内部开发对照；目标边界完整性未验证，不能代表完整小地图覆盖或门槛表现。checkpoint `f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb` 已接入本机固定 ROI Android 开发候选；事件级 `set` 与 `iou_gated` 的旧 crop 指标曾达到 video6 开发门槛，但框级 recall 仍低于 80%。APK 公共默认仍关闭检测器，候选还需修正 ROI、按新裁剪重训，并用新独立真人对局和实体机验收。详细边界见 [`validation/MODEL_PIPELINE.md`](../validation/MODEL_PIPELINE.md) 和 [`validation/HARD_NEGATIVES.md`](../validation/HARD_NEGATIVES.md)。

video7 另有 240 张与旧标签至少间隔 2.552 秒的预测无关均匀抽样帧。抽样完成后才用冻结 ONNX 模型在 `0.10` 低阈值下生成 442 个可编辑建议框。队列现已由三路 Luna Max 完成分段 AI 辅助初标，并经两路分层交叉审计；状态为 186 张 `corrected`／334 框、33 `negative`、16 `excluded`、5 `skip`、0 `pending`。数据库与 review manifest 一致，框坐标在 ROI 内；这项检查没有证明目标未碰裁剪边界或头像完整。交叉审计 0 修改，review manifest SHA-256 为 `d05eade78eea2721773d06c4288e338770d8c46810175eda9d7f7b1296547e8a`。这些结果不是人工真值，建议队友抽查。详细审计范围见 [`validation/VIDEO7_EXPANDED.md`](../validation/VIDEO7_EXPANDED.md)。

## 小地图裁剪完整性门禁

标注服务是边界校验的权威入口：敌人目标框距可扩展 ROI 边缘 1 像素内时，`corrected` 和 `accepted` 保存会被拒绝，模糊目标应使用 `skip`；`negative` 可用于拒绝一个边缘建议框。碰到原始屏幕边缘的目标单独记录，不自动判成 ROI 错误；若目标本身被屏幕边缘切掉或无法确认，应跳过该帧。`minimap_region` 的边界标注不受目标框门禁限制，小地图可自然贴着屏幕顶边。

`mapassist.detection_dataset --crop-roi` 导出时，任何真值框超出实际 crop 都会报错，不再静默 clip。目标框接触可扩展裁剪边缘会写入 COCO `info.roi_boundary_audit`，并将数据标为不可用于训练或评估；训练器和固定阈值评估器都读取同一个 provenance gate。`mapassist.audit_coco_dataset` 对带本项目 provenance 的数据会阻断这类 split；没有 provenance 的通用 COCO 只报告图像边缘接触并将 crop 完整性标为 unknown，避免把物理屏幕边缘误判为裁剪缺陷。

修正边缘截断需要在源清单中先设置能完整包含目标的训练数据 ROI，再按该 ROI 重做标注、导出、训练和评估。只有新 checkpoint 对应的 Android 推理 ROI 一并更新并经过验证后，才可部署新裁剪；当前已接入的 Android ROI／权重不会自动跟随数据集变化。

### video7 扩展标注后的微调结果

`finalize_review` 纳入 219 张／334 框；合并为 7 场开发数据后共有 1,079 张／1,666 框。train 使用 video1–5 与 video7，共 927 张／1,443 框，其中 video7 合计 330 张／523 框；val 仅使用 video6 的 152 张／223 框。训练与评估均未读取 video8。

数据哈希：combined manifest `67e4894df081660453228a2435f478b5aeaebe90c2f902cd4a288eb249092a09`；train annotations `75767b5a33d3fceff95937bb1f06bd0898ac8f55ea53f94faf3e300e33ed0303`；val annotations `954c5687978012f280c2f83b6dedf71e4e9568062cc4fd999aca96227f64b981`。

以 dense baseline checkpoint `68b86a7a10c97d9b2c738f72b1f49a0b1dcc76d10751ee9f4e61380ee4fb93bc` 初始化，在 Apple MPS 上使用 seed `20260926`、输入 320、batch size 16、`lr_scale=0.1`，最多 40 轮，第 30 轮早停。最佳 epoch 10 的 checkpoint SHA-256 为 `49d8d21900603f78a595e08362895d70011201a9b26457c5fa388f915a80ae99`；confidence 为 `0.43`。video6 legacy fixed-ROI crop 开发对照 TP/FP/FN 为 `158/17/65`，precision / recall / F1 为 `90.2857% / 70.8520% / 79.3970%`，几何方向正确率为 `97.3856%`；数值不表示完整小地图覆盖。

相对 dense baseline，precision / recall / F1 变化为 `−0.2171 / −1.7937 / −1.2000` 个百分点；相对 hard-FP 候选，变化为 `+0.0154 / −4.0359 / −2.4657` 个百分点。此处 checkpoint `49d8d21900603f78a595e08362895d70011201a9b26457c5fa388f915a80ae99` 是另一项 video7 标签微调对照，不是当前接入本机开发资产的 f717 hard-FP 候选。该结果只是 video6 开发集比较，不是独立留出成绩。metrics SHA-256 为 `fd7349e74a4c4772682217bebe51633c9668dec7be31b6798129ad06155899f5`，固定评估 SHA-256 为 `b4dc2d50b77d9df1d443135d2413eab8374171485d3b2a155b735ca6eee968e9`。video7 标签来自 AI 辅助初标和交叉审计，不是人工真值，仍建议队友抽查。

此前困难误报加权候选在 video6 开发集以 confidence `0.19`、NMS `0.5` 评估，方向事件 `set` TP/FP/FN 为 `148/10/26`、P/R/F1 为 `93.6709% / 85.0575% / 89.1566%`；`iou_gated` 为 `144/14/30`、`91.1392% / 82.7586% / 86.7470%`。这些是 legacy fixed-ROI crop-relative 内部开发数值，不代表完整地图覆盖；两种事件算法的旧 crop 指标曾达到 video6 开发门槛 P≥90%、R≥80%。候选 checkpoint SHA-256 为 `f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb`，评估 JSON SHA-256 为 `117561be1bb634cb2caf327bbb106bd27820716ccbde74adde5114504506c95e`。video6 已用于选模和选阈值，该结果不是独立留出成绩或最终验收结果；该候选现已接入本机 Android 开发资产，APK 公共默认 profile 仍关闭检测器。ncnn parity 和冒烟边界见 [`validation/MODEL_PIPELINE.md`](../validation/MODEL_PIPELINE.md)；完整事件计数见 [`validation/VIDEO7_EXPANDED.md`](../validation/VIDEO7_EXPANDED.md)。

### 扩大 hard-FP 后续实验：失败

沿用相同的 train/val 划分：来源 train 为 927 张／1,443 框，video6 val 为 152 张／223 框。confidence 固定为 `0.29` 的来源训练集基线报告 TP/FP/FN 为 `1304/173/139`，precision / recall / F1 为 `88.2871% / 90.3673% / 89.3151%`。抽取 150 个 hard sources（含 20 个纯负样本），将既有已标注样本重复加入训练集 188 条记录、对应 283 个已有框；扩展后 train 为 1,115 条记录／1,726 个框，val 未变。没有新增标注。

从 dense checkpoint 初始化，沿用前述训练参数；第 25 轮早停，最佳 epoch 5，confidence `0.47`。video6 legacy fixed-ROI crop 开发验证 TP/FP/FN 为 `155/17/68`，precision / recall / F1 为 `90.1163% / 69.5067% / 78.4810%`，几何方向正确率 `96.6667%`；这些数值不表示完整小地图覆盖。F1 低于 dense `80.60%`、此前 hard-FP 候选 `81.86%` 和 video7 扩展标签微调 `79.40%`。本轮失败 checkpoint 未接入本机开发 profile；当前接入的是 f717 hard-FP 候选，APK 公共默认仍关闭检测器。这是 video6 crop-relative 开发指标；video7 扩展标签为 AI 辅助而非人工真值，权重和数据不提交。

关键产物 SHA-256：best checkpoint `fb2721f64eccf9226834d7c85537b7d76b8d1c45050975adb74cc024b1f7aa55`；fixed evaluation JSON `d55849c6ed43ffdac32524c812c40717193ee1d698256e10671432786a51c664`；来源 train annotations `75767b5a33d3fceff95937bb1f06bd0898ac8f55ea53f94faf3e300e33ed0303`；重复训练集 annotations `5151ef2ee6a3e72e362889fc0b84c6703898d83b94aaa2dc87ddd4cfac643e75`；metrics `f591b39d893757e690c866ae7c6a9da5ec5c6bfc29b1bcddcb44292b505c5116`；baseline report `278bb41deb2f3c9bb356a9b8d60c3aee392353f6fbf814eb9548d809fc836d31`；provenance `a0a221a573d244f5036e5b2ee10675747ae2b20a6f973340706d97690ead486c`。完整过程见 [`validation/VIDEO7_EXPANDED.md`](../validation/VIDEO7_EXPANDED.md)。

### FP+FN hard-errors 对照：失败

使用旧 v5 train（708 张／1,109 框，不含 219 张 AI 辅助标签）和 video6 val（152 张／223 框）。`--include-false-negatives` 纳入 142 个唯一 union hard sources，其中 98 张含 FP、95 张含 FN（类别有重叠），7 张为纯负样本；从这些既有样本重复加入 167 条训练记录、对应 320 个已有框后，train 为 875 条记录／1,429 个框。没有新增标注。从 dense baseline 按旧 hard-FP 参数训练，第 25 轮早停；best epoch 5、confidence `0.29`。video6 legacy fixed-ROI crop 开发验证 TP/FP/FN `158/15/65`，precision / recall / F1 `91.3295% / 70.8520% / 79.7980%`，几何方向正确率 `96.7105%`；这些数值只用于旧裁剪内的开发比较，不代表完整地图覆盖。precision 达到 90%，但 F1 未超过替换门槛 `81.8627%`。本轮失败 checkpoint 未接入本机开发 profile；当前接入的是 f717 hard-FP 候选，APK 公共默认仍关闭检测器。数据和权重不提交。

source annotations SHA-256 `eeff2b1fff5ae1b2bb365fe5651f144477d2e2b977ad6140eddef992d046220c`，baseline evaluation `47b483e2af8036dfe1ff76d0fc2fb3be2b9a9c8dac2a25ccc30d09847685c71f`，重复训练集 annotations `efa54d5b4d40a26af2242f5f3b4a7ab7999ca0a894af7ac8e1a7018e006648bb`，provenance `caf2acc439b2ec1aa05b6df5caeaee2e561a9216bc31e22cbaa252feed542978`，best checkpoint `71570279b32983671dde8e5189e8fdc744dfbe0a69e272e105c2e44030d812e2`，metrics `d31d381468732f9db2c3e57b6069f8faad9642bbc91ca97b18bb4c46d933f947`，fixed evaluation JSON `12dbed95cf2001641abdeab5fa36ca83fcf00f64d10c69e72fc7b992c97dc3bc`。最终真机验收结果待完成；详情见 [`validation/VIDEO7_EXPANDED.md`](../validation/VIDEO7_EXPANDED.md)。

改进顺序为：

1. 优先人工复核 hard-FP 来源，检查并去除重复样本，避免继续盲目重复加权；
2. 评估尺度鲁棒性，并补充高分辨率真人开发录像，人工复核无敌人画面以及塔、兵线、友方头像和重叠图标等困难背景；
3. 继续按整场录像分组，使用新的开发验证对局比较模型，避免依赖已经反复用于选模的 video6；
4. video8 旧敌人检测数据因裁剪缺陷不再是有效留出证据。v3 安全 ROI 已有 119 个有效帧／211 框并完成 provenance audit，可用于后续开发训练；当前权重仍绑定旧 crop，尚未按新裁剪重训。video8 模型评测和 confidence sweep 只作 same-match development diagnostic。独立成绩必须使用另一场从未参与定位器开发、训练或调参的真人排位。布局定位器 v2 使用修正后的 16 张 video8 边界训练并在同场 120 张布局帧诊断，不等于敌人检测成绩或独立留出。

密集队列和多人标注命令见 [`docs/团队协作与本地运行.md`](../docs/团队协作与本地运行.md)。

同一批低分辨率数据还做了 640×640 输入微调对照：从 320 最佳权重继续训练 60 轮，最佳点为 epoch 30，precision 91.49%、recall 48.86%、F1 63.70%。它低于 320 基线，说明单纯放大已经只有 `104×108` 的小地图裁剪没有解决问题。该实验只排除“直接增大输入即可修复”的假设，不代表高分辨率原始录像或重新设计过的 640 训练一定无效。

## 外部小地图数据对照

Roboflow Honor of Kings Minimap v1 已完成 COCO 审计：train 2,989 张／16,346 框、valid 4 张／20 框、无 test；共 128 个数字类，图片为 `Stretch640`，未发现精确重复。通用头像预训练把 128 类合并为单类 `minimap_hero`，未使用 `heroes` 父类标签。两次相同自录开发集微调的 v1、v2 分别为 90.30% / 66.82% / 76.80% 和 90.51% / 64.13% / 75.07%，均未超过自录基线 90.50% / 72.65% / 80.60%，故不替换基线。

另以保守头像边缘颜色规则构造红圈伪标签：红环阈值 0.04、对次高颜色分数的优势至少 1.5 倍，遇到任一歧义头像即排除整图。train 保留 986 张和 1,373 个敌人框；valid 仅保留 1 张、4 个框。伪标签只用于开发预训练，正式 holdout 录像、标签和冻结预测不变。使用红圈预训练初始化、在自录 video1–6 开发集微调后，best epoch 20、confidence `0.63`，precision 90.36%、recall 67.26%、F1 77.12%；相对自录基线分别低 0.14、5.39、3.48 个百分点，故不替换基线。这是开发验证结果，不是留出成绩。详细对照、数据哈希和 checkpoint SHA-256 见[外部数据验证记录](../validation/EXTERNAL_PRETRAINING.md)；原始图片、转换数据、权重和训练产物不提交。审计、许可证和方法细节也见该记录。

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
  pycocotools onnx onnxruntime pnnx ncnn
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

按新测得的控件边界准备训练数据时，先用布局 v2 重建 video2–7 的安全 ROI，并排除几何已变化的旧 `video1`。等 `video1-hd` 完成人工复核后，将它和 video8-v3 合并，再导出裁剪数据：

```sh
PYTHONPATH=python .venv/bin/python -m mapassist.safe_roi_manifest \
  data/private/minimap-review-v6-video7-expanded/detection-manifest-video1-7-expanded-video6-dev.json \
  data/private/minimap-layout-combined-v1/detection-manifest-v2.json \
  --output data/private/minimap-review-v6-video7-expanded/detection-manifest-video2-7-safe-roi.json \
  --padding-short-side-fraction 0.035 --exclude-match video1

# video1-hd review 完成后，导出新 detection manifest，不改 review-manifest.json。
PYTHONPATH=python .venv/bin/python -m mapassist.finalize_review \
  data/private/minimap-review-video1-hd-v1/review-manifest.json \
  --output data/private/minimap-review-video1-hd-v1/detection-manifest.json

PYTHONPATH=python .venv/bin/python -m mapassist.combine_detection_manifests \
  data/private/minimap-review-v6-video7-expanded/detection-manifest-video2-7-safe-roi.json \
  data/private/minimap-review-video1-hd-v1/detection-manifest.json \
  data/private/minimap-video8-holdout-v1/blind-review-v3-safe-roi/detection-manifest-video8-v3.json \
  --split video8=train \
  --output data/private/minimap-review-v6-video7-expanded/detection-manifest-video2-7-video1-hd-video8-safe-roi.json

PYTHONPATH=python .venv/bin/python -m mapassist.detection_dataset \
  data/private/minimap-review-v6-video7-expanded/detection-manifest-video2-7-video1-hd-video8-safe-roi.json \
  --crop-roi \
  --output data/private/minimap-review-v6-video7-expanded/coco-video2-7-video1-hd-video8-safe-roi

PYTHONPATH=python .venv/bin/python -m mapassist.coco_dataset_audit \
  data/private/minimap-review-v6-video7-expanded/coco-video2-7-video1-hd-video8-safe-roi \
  --output data/private/minimap-review-v6-video7-expanded/coco-video2-7-video1-hd-video8-safe-roi/audit.json
```

这些 manifest、裁剪图片和 audit 都是私有数据，只保存在 `data/private/`，不要提交或公开。这一轮把 video8 改为 train，因此 audit 会明确报告 `No populated test split`；这条表示当前不能引用独立测试成绩，其余 `training_blockers` 必须为空。每个有数据的 split 都必须显示 `roi_crop_completeness: provenance_clear`，确认没有 crop boundary 阻断后才能训练。布局帧不一致、ID／录像不匹配或已有视频 SHA-256 不一致时，ROI 工具会报错并停止输出。最终成绩必须使用另一场从未参与定位、训练、调参或失败分析的真人对局。

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

## 用冻结 ncnn 模型回放完整录像

桌面端的完整录像回放使用冻结的 CFR 媒体时间轴：按 `ffmpeg fps` 采样，时间戳为 `round(frame_index * 1000 / fps)`，并以零排队延迟把该时间戳传给 C++ `ma_engine`。若 profile 含 `layout.minimap_locator`，回放会先运行与 Android 相同的原生定位器；搜索帧不运行 YOLOX，锁定或短时保持帧使用动态 ROI。它用于可复现的媒体时间事件评测；实际处理耗时只记录为桌面性能信息，不代表 Android 端到端时延。输出 JSONL 同时包含逐帧 `layout`、`observations`、YOLOX `detections` 和事件层 `cues`，可交给 `mapassist.evaluate` 或 validation gate：

```sh
PYTHONPATH=python .venv/bin/python training/replay_yolox_ncnn.py path/to/match.mp4 \
  --profile android/app/src/main/assets/profile.json \
  --param android/app/src/main/assets/minimap-yolox-nano-320.param \
  --bin android/app/src/main/assets/minimap-yolox-nano-320.bin \
  --output build/replay/match.predictions.jsonl \
  --metadata build/replay/match.predictions.meta.json \
  --fps 12
```

`*.meta.json` 会记录录像、profile、ncnn param/bin、native library 的路径、大小和 SHA-256，以及 sampling、timeline、event_now_policy、帧数、事件数、回放吞吐量和桌面逐帧处理耗时。回放要求 `detectors.minimap_yolox=true` 且输入尺寸为 320。输入 profile 的 confidence、NMS 和事件参数保持冻结；不要用回放录像结果反向调整 profile 后再把同一录像当作独立验证。集成测试在缺少构建产物时会 skip；正式留出前必须实际构建并运行该测试，skip 不能视为通过。

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

### 转换为 ncnn

将固定 320 输入、输出未解码 `1×2100×6` 张量的 TorchScript 模型转换为
ncnn：

```sh
.venv/bin/python training/convert_yolox_ncnn.py \
  --torchscript build/models/minimap-yolox-nano-dense-320/model.torchscript.pt \
  --output-param build/models/minimap-yolox-nano-dense-320/model.ncnn.param \
  --output-bin build/models/minimap-yolox-nano-dense-320/model.ncnn.bin \
  --metadata build/models/minimap-yolox-nano-dense-320/ncnn-conversion.json \
  --input-size 320
```

转换固定使用 pnnx 的 `fp16=0`、`optlevel=2` 和 CPU 路径。当前 pnnx 会把
YOLOX Focus 中步长为 2 的四个切片写成不受支持的 Crop 层。脚本先在 pnnx
中间图中核对 `dims=(2,3)`、`steps=(2,2)`、四组起点和 channel 拼接顺序，再核对
ncnn 参数确实以 `Input + Split + 4 Crop + Concat` 开头，然后替换为一个
`YoloV5Focus`。任一结构不匹配时转换直接失败。发布前会暂存 param、bin 和
metadata；普通文件写入错误会恢复上一组文件。`ncnn-conversion.json` 保存输入、
原始 pnnx 输出、最终 param/bin、pnnx 启动器和实际后端程序的 SHA-256，以及
改写前后的 layer/blob 数。

在真实验证图片上比较 TorchScript 与 ncnn 的原始输出：

```sh
.venv/bin/python training/verify_yolox_ncnn.py \
  --torchscript build/models/minimap-yolox-nano-dense-320/model.torchscript.pt \
  --param build/models/minimap-yolox-nano-dense-320/model.ncnn.param \
  --bin build/models/minimap-yolox-nano-dense-320/model.ncnn.bin \
  --data-dir data/private/minimap-review-v4-dense/coco-minimap-combined-video1-6 \
  --image-count 30 --input-size 320 \
  --max-raw-error 5e-4 --confidence 0.29 --nms-threshold 0.5 \
  --output build/models/minimap-yolox-nano-dense-320/ncnn-parity-30.json
```

一致性检查在 Python ncnn 中注册同一个 `YoloV5Focus`，并关闭 fp16、bf16 和
packing，以隔离转换误差。默认还会运行与 Android 等价的 ncnn
`Mat.from_pixels_resize(PIXEL_BGR)` 和右／下方 114 padding，再按置信度 `0.29`、
NMS `0.5`、步长 8／16／32 解码最终检测。两个门禁分别记录，runtime 检查不会
放宽 raw 输出的 `0.0005` 门槛。

旧 dense baseline 的 30 张 `video6` 验证图片最大 raw 输出绝对误差为 `0.0004493`；该通过结果不适用于当前 hard-FP checkpoint `f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb`。当前候选的 ncnn parity 门槛结果见[模型接入记录](../validation/MODEL_PIPELINE.md)。ncnn 与
OpenCV 输入的最大像素差为 `1`，每张图的最终检测数量一致，框／置信度最大值差
为 `0.0030708`，通过默认 `0.01` 门槛。也可省略 `--data-dir`，用
`--images image-a.png image-b.png` 检查指定原图；报告会记录每张图片及三个模型
文件的 SHA-256。只排查模型转换时可传 `--no-runtime-preprocess-check`。Android
真机仍需单独验收截屏格式、精度、耗时和发热。
