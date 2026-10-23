# video7 扩展 AI 辅助标注与交叉审计记录

更新于 2026-09-27。该数据属于开发集，不是独立留出成绩。本记录描述的是 AI 辅助初标和 AI 交叉审计，不是人工真值；仍建议队友抽查。

本文所有 video6 检测指标均来自 legacy fixed-ROI crop，只能作内部开发比较。video7 的边界量化已确认旧裁剪会切掉右侧目标；完整小地图覆盖和漏检率尚未通过修正裁剪重评。

## 抽样边界

- 来源：`video7.mp4`，2712×1220、30 FPS、高码率人机局。
- 有效区间：60,000–1,045,000 ms。
- 新增 240 个系统均匀抽样时间点，全部属于 `train`。
- 抽样过程不读取检测结果，清单记录 `predictions_used_for_selection=false`。
- 排除旧 video7 111 个标签时间点前后 1,000 ms；新旧时间点实际最近间隔 2,552 ms。
- 原始队列清单 SHA-256：`93841144694fa06f38b8836909ba3638737a567a473b4672a692e01f2de05231`。

全部 240 张抽帧已核对为实际横屏像素 2712×1220，且没有 EXIF 旋转标记。

## 抽帧后生成的辅助建议

建议框在抽样完成后生成，不参与选择帧：

| 项目 | 值 |
| --- | --- |
| ONNX SHA-256 | `5072feba5a33dfa18c98ddf3bdf0b0d12bbd67453a599c690b7ef805c6288e32` |
| 输入 | 320×320 小地图裁剪 |
| confidence / NMS | 0.10 / 0.5 |
| 预标注 SHA-256 | `bad70fe6cd6fbe48c0a19cab83e31dc8e709ec4c2cc44b7f16687b620c23a0ba` |
| 有建议的图片 | 213 / 240 |
| 建议框 | 442 |
| 每图建议框中位数／最大值 | 2 / 7 |
| 附加建议后清单 SHA-256 | `94363bb38528e54d835a1e519d662eade9a25be6c54445b3749f5c90a2e69508` |

预标注导出时已修复裁剪 `floor/ceil` 造成的不足 1 像素 ROI 越界，所有建议框均严格位于标注 ROI 内。

上述检查只验证建议框坐标与 ROI 的包含关系，不验证 ROI 是否覆盖完整头像。后来发现同一固定 ROI 可能切掉右侧目标；video7 扩展队列没有做可扩展边界触碰审计，因此这些标签的裁剪完整性仍未知，不能以“0 框越 ROI”证明裁剪有效。

## AI 辅助初标与交叉审计结果

三路 Luna Max 按帧段分工完成初标。随后进行两路独立、分层的 Luna Max 交叉审计：

- `1–120`：检查 `88/120` 帧；
- `121–240`：检查 `99/120` 帧。

交叉审计覆盖全部 `negative`、`excluded`、`skip` 帧，所有高风险边缘框和重叠框，以及固定抽样；审计没有修改任何标签。自动检查确认数据库与 review manifest 一致，且没有框越出标注 ROI。

| 最终状态 | 数量 |
| --- | ---: |
| `corrected` 图片 | 186 |
| `corrected` 框 | 334 |
| `negative` | 33 |
| `excluded` | 16 |
| `skip` | 5 |
| `pending` | 0 |

最终 review manifest SHA-256：`d05eade78eea2721773d06c4288e338770d8c46810175eda9d7f7b1296547e8a`。

这些计数记录 AI 辅助初标后的队列状态，不代表 240 张都经过人工逐帧确认。仍建议队友抽查这些标签，尤其是边缘和重叠目标。新模型不得再把 video8 当成可重复调参的独立测试集；最终成绩需要另一场预先冻结、从未参与开发的真人对局。

## 导出与开发集微调

`finalize_review` 纳入 219 张／334 框：186 张 `corrected` 和 33 张 `negative`；`excluded` 与 `skip` 不进入训练。与既有数据合并后，7 场开发录像共 1,079 张／1,666 框。按整场录像分组，train 使用 video1–5 与 video7，共 927 张／1,443 框（video7 合计 330 张／523 框）；val 只使用 video6，共 152 张／223 框。整个流程未读取 video8。

| 产物 | SHA-256 |
| --- | --- |
| combined manifest | `67e4894df081660453228a2435f478b5aeaebe90c2f902cd4a288eb249092a09` |
| train annotations | `75767b5a33d3fceff95937bb1f06bd0898ac8f55ea53f94faf3e300e33ed0303` |
| val annotations | `954c5687978012f280c2f83b6dedf71e4e9568062cc4fd999aca96227f64b981` |
| dense baseline checkpoint | `68b86a7a10c97d9b2c738f72b1f49a0b1dcc76d10751ee9f4e61380ee4fb93bc` |
| best checkpoint (epoch 10) | `49d8d21900603f78a595e08362895d70011201a9b26457c5fa388f915a80ae99` |
| metrics | `fd7349e74a4c4772682217bebe51633c9668dec7be31b6798129ad06155899f5` |
| fixed evaluation | `b4dc2d50b77d9df1d443135d2413eab8374171485d3b2a155b735ca6eee968e9` |

微调使用 Apple MPS、seed `20260926`、输入 320、batch size 16、`lr_scale=0.1`，最多 40 轮，第 30 轮早停；最佳 checkpoint 为 epoch 10，评估 confidence 为 `0.43`。在 video6 开发验证集上 TP/FP/FN 为 `158/17/65`，precision / recall / F1 为 `90.2857% / 70.8520% / 79.3970%`，几何方向正确率为 `97.3856%`。

相对 dense baseline，precision / recall / F1 分别变化 `−0.2171 / −1.7937 / −1.2000` 个百分点；相对 hard-FP 候选，分别变化 `+0.0154 / −4.0359 / −2.4657` 个百分点。这个 video7 扩展标签微调 checkpoint 是独立对照，没有接入本机开发资产；另一 hard-FP checkpoint `f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb` 及旧候选现均已退役，APK 默认检测仍关闭。该结果只是 video6 开发集比较，不是独立留出成绩；video7 的 AI 辅助标签仍建议队友抽查。

## 既有 hard-FP 候选的方向事件补充

补充记录此前困难误报加权候选在 video6 开发验证集上的方向事件评估：输入 320、IoU `0.5`、confidence `0.19`（阈值在 video6 开发集上选择）、NMS `0.5`。检测框 TP/FP/FN 为 `167/18/56`，precision / recall / F1 为 `90.2703% / 74.8879% / 81.8627%`；方向评估涉及 223 个真值框，其中 214 个可判方向、9 个方向模糊、0 个中心框，合并为 174 个方向事件。

| 方向事件算法 | TP/FP/FN | Precision / Recall / F1 |
| --- | --- | --- |
| `set` | `148/10/26` | `93.6709% / 85.0575% / 89.1566%` |
| `iou_gated` | `144/14/30` | `91.1392% / 82.7586% / 86.7470%` |

候选 checkpoint SHA-256：`f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb`（epoch 10）；方向事件评估 JSON SHA-256：`117561be1bb634cb2caf327bbb106bd27820716ccbde74adde5114504506c95e`。`set` 与 `iou_gated` 的 precision 均不低于 90%、recall 均不低于 80%，当时通过 video6 开发门槛；video6 已用于 checkpoint 与阈值选择，所以不是独立留出成绩或最终验收。该 checkpoint 和相关指标现已退役，仅作审计记录。APK 公共默认 profile 仍关闭检测器。ncnn 严格 raw-output parity 的失败值和 Android 等价检测一致性见[模型接入记录](../MODEL_PIPELINE.md)。

## 扩大 hard-FP 后续实验：负结果

本轮继续基于同一开发划分：train 为 video1–5 与 video7 的 927 张／1,443 框，val 仅为 video6 的 152 张／223 框。固定 confidence `0.29` 的来源训练集基线报告为 TP/FP/FN `1304/173/139`，precision / recall / F1 `88.2871% / 90.3673% / 89.3151%`。从误报来源中选取 150 个 hard sources（其中 20 个为纯负样本）；把既有已标注样本重复加入训练集 188 条记录、对应 283 个已有框，train 变为 1,115 条记录／1,726 个框，val 不变。没有新增标注。

从 dense checkpoint 开始，并沿用前述微调参数。训练在第 25 轮早停，最佳权重为 epoch 5；video6 开发验证使用 confidence `0.47`，TP/FP/FN 为 `155/17/68`，precision / recall / F1 为 `90.1163% / 69.5067% / 78.4810%`，几何方向正确率 `96.6667%`。F1 低于 dense 基线 `80.60%`、hard-FP 候选 `81.86%` 和 video7 扩展标签微调 `79.40%`；本轮失败 checkpoint 未接入本机开发 profile，f717 hard-FP 候选及相关旧指标现已退役，APK 默认仍关闭。

这是 video6 开发集指标；video7 新增标签为 AI 辅助标注，不是人工真值。本实验未读取 video8，也不提交权重或数据。

| 产物 | SHA-256 |
| --- | --- |
| 来源 train annotations | `75767b5a33d3fceff95937bb1f06bd0898ac8f55ea53f94faf3e300e33ed0303` |
| hard-FP expanded train annotations (repeated records) | `5151ef2ee6a3e72e362889fc0b84c6703898d83b94aaa2dc87ddd4cfac643e75` |
| best checkpoint | `fb2721f64eccf9226834d7c85537b7d76b8d1c45050975adb74cc024b1f7aa55` |
| metrics | `f591b39d893757e690c866ae7c6a9da5ec5c6bfc29b1bcddcb44292b505c5116` |
| fixed evaluation JSON | `d55849c6ed43ffdac32524c812c40717193ee1d698256e10671432786a51c664` |
| baseline report | `278bb41deb2f3c9bb356a9b8d60c3aee392353f6fbf814eb9548d809fc836d31` |
| provenance | `a0a221a573d244f5036e5b2ee10675747ae2b20a6f973340706d97690ead486c` |

当前结果说明继续盲目重复加权误报会让开发 F1 更差。下一步优先人工复核误报样本、检查并去除重复样本，再评估尺度鲁棒性；不把本轮结果描述为提升。

## FP+FN hard-errors 对照：负结果

本轮使用旧 v5 train/val 数据（train 708 张／1,109 框，不含 219 张 AI 辅助标签；video6 val 152 张／223 框）。`--include-false-negatives` 将正样本重复数扩展为 `min(max_extra_copies, max(fp, fn))`，纯负样本仍按 FP 与 negative bonus 规则处理。142 个唯一 union hard sources 中，98 张含 FP、95 张含 FN（两类有重叠），其中 7 张为纯负样本；从这些既有样本重复加入 167 条训练记录、对应 320 个已有框后，train 为 875 条记录／1,429 个框。没有新增标注。

从 dense baseline 按旧 hard-FP 相同参数训练，第 25 轮早停，最佳 epoch 5、confidence `0.29`。video6 开发验证 TP/FP/FN `158/15/65`，precision / recall / F1 `91.3295% / 70.8520% / 79.7980%`，几何方向正确率 `96.7105%`。虽然 precision 达到 90%，F1 低于替换门槛 `81.8627%`，本轮失败 checkpoint 未接入本机开发 profile；f717 hard-FP 候选及相关旧指标现已退役，APK 默认仍关闭。这是 video6 开发指标；本轮未读取 video8，也不提交权重或数据。最终全量 pytest 已完成（144 passed、1 skipped），APK 集成后 `assembleDebug lintDebug` 清洁构建通过；真机验收尚未完成。

| 产物 | SHA-256 |
| --- | --- |
| source train annotations | `eeff2b1fff5ae1b2bb365fe5651f144477d2e2b977ad6140eddef992d046220c` |
| source baseline evaluation | `47b483e2af8036dfe1ff76d0fc2fb3be2b9a9c8dac2a25ccc30d09847685c71f` |
| FP+FN expanded train annotations (repeated records) | `efa54d5b4d40a26af2242f5f3b4a7ab7999ca0a894af7ac8e1a7018e006648bb` |
| hard-errors provenance | `caf2acc439b2ec1aa05b6df5caeaee2e561a9216bc31e22cbaa252feed542978` |
| best checkpoint | `71570279b32983671dde8e5189e8fdc744dfbe0a69e272e105c2e44030d812e2` |
| metrics | `d31d381468732f9db2c3e57b6069f8faad9642bbc91ca97b18bb4c46d933f947` |
| fixed video6 evaluation JSON | `12dbed95cf2001641abdeab5fa36ca83fcf00f64d10c69e72fc7b992c97dc3bc` |

该对照没有达到 F1 替换门槛；最终真机验收仍待完成。下一步优先人工复核 hard sources、去除重复样本并评估尺度鲁棒性。

## 裁剪 ROI 残差（2026-09-27）

复核 video8 截断问题时一并量化了 video7：小地图控件实测 x∈[143,524]、y∈[0,378]（与人工布局边界框一致，各边差 ≤9 px），而本队列敌人标注与历史盲测使用的固定裁剪为 x∈[102,494]、y∈[0,407]——左含 41 px 杂景、右切 30 px、下含 29 px。已标注的 523 框仍是“裁剪内可见内容”的真值，但覆盖缺口意味着贴边目标被系统性排除；后续重建 video7 开发数据（含 AI 辅助标签复检）时应按标定 ROI 重新导出裁剪。测量与修正过程见 [video8 记录](VIDEO8.md) 与 [定位器记录](MINIMAP_LOCATOR.md)。
