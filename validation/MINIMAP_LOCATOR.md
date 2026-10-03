# 小地图自动定位器验证记录

## 2026-09-27 技术决策

现有低分辨率亮度描述符方案不再作为最终方案。旧版在 video8 上能稳定锁定，但将粗略框与候选框取并集后，后级敌人 YOLOX 的输入分布发生变化；旧 ncnn 对照记录了 crop-relative F1 从 67.83% 到 62.71%。这些分数来自后来确认不完整的固定裁剪和旧标签，不能据此断言完整小地图上的敌人模型退化或改进。在 video1–video7 开发清单上将该定位器作为裁剪器还会跳过 27 张图，其中 17 张是正样本，共丢失 28 个敌人框；该诊断也不构成完整地图评测。

新方案使用两个职责分离的轻量模型：

```text
Android 原始整屏
→ 黑边/旋转归一化
→ 全屏低分辨率单类定位器（minimap_region，1–2 Hz）
→ [cx, cy, w, h] 时序滤波与过期静默
→ 按预测边界裁剪
→ 固定容器、固定边距的 320×320 规范化输入
→ 小地图敌人 YOLOX（12 Hz）
→ Temporal Filter → Accessible Event
```

定位器优先复用 YOLOX Nano，只有 `minimap_region` 一类；若真机 CPU 预算不足，再对比 NanoDet-Plus 320。小地图在缩小后仍是大目标，不需要在整屏上识别极小的敌人头像。定位器只在启动、分辨率/方向变化、置信度下降和周期刷新时运行，避免将第二个模型的延迟叠加到每一帧。

后级敌人检测器必须用“定位器预测框→同一规范化函数”生成的裁剪重新训练，并在训练时对边界加入小幅位移、缩放和安全区扰动。这一步直接解决本次“定位成功但敌人检测变差”的训练/推理裁剪不一致。

全屏定位真值使用 `minimap-layout` 标注队列采集：video1–video7 每局均匀取 16 张，共 112 张；另从已经降级为开发数据的 video8 取 16 张。video8 的首版左边界错误，后续已重校；v1 使用的 video8 标签因此属于错误真值，v1 训练与评测只保留为历史记录。修正后的边界用于 v2 重训。每张图只标一个可见小地图外边界。训练按整场录像分组：video1–5、video7、video8 共 112 张用于训练，video6 的 16 张用于开发验证。

定位层单独验收边界 IoU、四边像素误差、粗 ROI 覆盖率和搜索静默率；敌人层在规范化裁剪上验收 precision/recall；最后使用一场从未用于训练和阈值选择的新真人对局做端到端验收。video8 已经用于问题定位，不再声称为独立留出集。

## 单类学习定位器 v1（错误 video8 边界历史）

v1 的 112 张训练帧包含 video8 的 16 张布局帧；这些标签后来发现左缘错画到 x=214（实测约 x=106）。因此 v1 checkpoint 曾按错误 video8 边界训练。下列 v1 数字只记录历史，video8 上以旧边界计算的覆盖率和 IoU 已作废；video6 验证也是模型选择用过的开发数据，不能作为独立成绩，也不能代表 v2。

首版全屏单类 YOLOX Nano 使用 128 张 `minimap_region` 真值，按整场录像分为 112 张训练和 16 张 video6 开发验证，输入为 320×320 等比例填充。训练帧包含后来发现边界错误的 16 张 video8 帧。为避免镜像和随机平移破坏“左上角 HUD”先验，首轮关闭 Mosaic、翻转和仿射，只保留 HSV 扰动。训练在第 40 轮早停，最佳权重来自第 10 轮；video6 开发验证在置信度 `0.11` 下为 16 TP、0 FP、0 FN。该验证对局已参与模型选择，所以只能证明开发链路可行。

普通目标检测指标会隐藏裁剪边界误差。专用评测器按部署策略为每帧选置信度最高的框，结果为：

| 指标 | 原始预测框 | 每边扩展短边 2.5% |
| --- | ---: | ---: |
| 有预测／额外预测 | 16 / 0 | 16 / 0 |
| IoU 中位数／最小值 | 0.8988 / 0.8432 | 0.8730 / 0.8456 |
| IoU ≥ 0.75 | 100% | 100% |
| IoU ≥ 0.90 | 43.75% | 0% |
| 真值区域覆盖率中位数／最小值 | 89.88% / 87.87% | 100% / 100% |
| 最大单边误差中位数／P95 | 5.84 / 7.08 px | 7.01 / 10.91 px |
| 裁剪中属于真值的比例中位数 | 100% | 87.30% |

原始框多数收缩在人工边界内部，直接裁剪会丢掉约 10% 的边缘。后级敌人识别因此采用 2.5% 短边安全扩展作为首个开发候选，并必须用同样扩展后的裁剪重训。这里的扩展值看过 video6 结果，仍需在新对局确认，不能当作最终冻结参数。

复现边界评测：

```sh
PYTHONPATH=build/third_party/YOLOX:python:. .venv/bin/python \
  training/evaluate_yolox_locator.py \
  --yolox-root build/third_party/YOLOX \
  --data-dir data/private/minimap-layout-combined-v1/coco-fullscreen \
  --checkpoint build/training/yolox-nano-minimap-locator-v1-320/best_ckpt.pth \
  --input-size 320 --split val --nms-threshold 0.5 \
  --margin-short-edge 0.025 --device mps \
  --output build/training/yolox-nano-minimap-locator-v1-320/eval-val-boundaries-margin025.json
```

checkpoint SHA-256 为 `aa7f08d67bffc75e20fed21893b393353fc60b680523f4b4840ef4e6076426cf`。TorchScript 转 ncnn 的 16 张验证图原始输出最大误差为 `0.0001513`，检测数量全部一致；由于全屏坐标会放大 320 输入的亚像素插值差异，最终框数值最大差为 `0.164 px`，使用 `0.25 px` 门限时通过。ncnn param/bin SHA-256 分别为 `4649269cae16fef3b64cc366f123ba58259a756b79f7f55d6be20cd3903cae14` 和 `114f810b93555a5c749134f9e2527c519b5357573f15af2dc454fc6408476513`。

v1 learned locator 未接入 APK。定位器 v2 的训练和离线开发评测见下一节；完整连续录像上的 1–2 Hz 检测、时序平滑和失锁静默，以及将 learned locator ncnn 与敌人 ncnn 作为独立模型接入 Android，仍待实现和验证。旧描述符定位器保留为单独对照。

## 定位器 v2：修正边界后的训练与开发诊断（2026-09-27）

video8 的 16 个全屏布局标签重校后，使用修正数据重新训练 YOLOX Nano 320。训练按整场录像分组：112 张 train 含 video8 的 16 张布局帧，16 张 video6 val 用于模型和置信度选择。`metrics.json` 明确标记该流程为开发训练，不能视为独立留出。最佳 checkpoint 为 epoch 10，val 选择的 confidence 为 `0.47`、NMS 为 `0.5`；16 张 video6 val 的选择点为 TP/FP/FN `16/0/0`。加短边 2.5% 安全边距后，video6 val 的 IoU 中位数为 `0.836886`，16/16 帧覆盖全部人工边界，IoU ≥0.75 为 100%。这些是开发验证结果。

另有 120 张 video8 全屏布局帧没有进入 v2 训练集，但它们与训练集中的 16 张布局帧来自同一场 video8。虽然评测 JSON 将其标为 `test`，同一场对局已经进入训练，因此这里只能称为 **same-match development diagnostic**，不能称为独立测试、诚实留出或发布门禁。加短边 2.5% 后，119/120 帧有预测、1 帧缺失，另有 20 个额外预测；IoU 中位数 `0.882222`，IoU ≥0.75 为 99.17%，人工边界覆盖率中位数 100%（最低 97.88%）。这只评估布局框与人工全屏边界，不评估敌人识别或端到端效果。

| 产物 | SHA-256 |
| --- | --- |
| v2 best checkpoint（epoch 10） | `c61769136ee65e1d009860fa0f9bc1c72d48ae646f91a8fddc19e8db18e6d405` |
| v2 `metrics.json` | `3168a4c90ca07dac8e4e4d1e3fae08661170431e08443711795d2479e638f5a0` |
| video6 val 边界评测 JSON | `e59ff38a106371e37fb39f32c0873fd54cd7d0db7bb9f1ec8a72938511231ebe` |
| video8 120 帧 same-match 诊断 JSON | `9b524ac9325d7c03d9ce087fc7e7ca3e61a02d1cf511caaab54cbfb225efc3c6` |

训练报告记录的 train／val annotations SHA-256 分别为 `2d0b3ad32c0ae3e161ba87e74b8778aa2856dc64945d7a97a433367c7fdc6382` 和 `39838180692059d5be5e0433cf130d6e75203b2ce463ea2bc91be51d290173e5`。checkpoint 与两份评测报告都指向同一个 checkpoint SHA。

### 描述符定位器对照

`build/minimap-locator-video8-v2-corrected-ref.json` 是原生描述符定位器在同一组 120 张 video8 布局帧上的回放；它的 scope 明确为 layout acquisition only，未执行 YOLOX。状态为 searching 1 帧、locked 113 帧、held 6 帧，可用率 99.17%；与 reviewed crop 的 IoU 中位数为 89.86%、参考裁剪覆盖率中位数为 98.29%。这组 overlap 只表示与标注时所用 crop 的一致程度，JSON 也明确说明它不是独立描出的 HUD 真值，不能与 learned locator 的全屏边界 IoU 当作同一种指标。

描述符报告 SHA-256 为 `e761b7ad60cee3d4c445c6de2c0ef27a71005a9b2294b5ff048b01ce4f1a430c`；所用 review manifest SHA-256 为 `a11224b0889c5eb258badcf92adc7fd2c3401f76598d7a75b112703ddb5e67da`，locator JSON SHA-256 为 `6dd5e2ce2d554471a95f0c1c845aad5a8d3fedf11234b91141db329c75674f13`，其中的 descriptor SHA-256 为 `38104234d46f12e8e33db5a377bcd0b703ca8429b48065c82c2b77209c993ac7`。该对照不运行敌人检测器，也不证明敌人检测已修好或任何独立发布门禁已通过。

技术选型参考：[Screen Recognition](https://arxiv.org/abs/2101.04893) 证明了屏幕像素上的移动端 UI 元素检测可以在端上运行；[YOLOX Nano](https://github.com/Megvii-BaseDetection/YOLOX) 已有 0.91M 参数的轻量实现；[NanoDet-Plus](https://github.com/RangiLyu/nanodet) 提供 320 输入和 ncnn Android 示例，可作为后备对照；[ncnn](https://github.com/Tencent/ncnn) 同时支持 YOLOX 和 NanoDet。[OpenCV 特征匹配与 Homography](https://docs.opencv.org/4.x/d7/dff/tutorial_feature_homography.html) 需要足够的稳定匹配点，[ECC](https://docs.opencv.org/doc/doxygen/html/dc/d6b/group__video__track.html) 也需要已大致对齐且内容相似的图像；它们适合作为稳定 HUD 外框的低成本校验，不应继续直接匹配持续变化的地图内容。[Domain Randomization](https://arxiv.org/abs/1703.06907) 支持在训练中增加位置、尺度、黑边和压缩扰动，但真实手机/HUD 分组留出仍是验收依据。

## 目的

固定归一化 ROI 只能适配分辨率变化。全面屏比例、左右安全区、黑边和 HUD 位置调整会让实际小地图偏离固定框，因此 Android 小地图链路改为：

```text
MediaProjection 原始横屏帧
→ Screen Normalizer（检测整屏黑边，保留原生分辨率）
→ Layout Profile（短边坐标的粗略锚点和搜索范围）
→ Minimap Anchor Detection（低分辨率亮度描述符相关匹配）
→ ROI Correction（位置、缩放、宽高比和平滑）
→ Minimap Crop
→ 右下填充为 320×320
→ YOLOX Nano / ncnn
→ 两帧确认、去重、冷却和过期丢弃
→ Accessible Event
→ Audio（当前）/ Haptic（后续输出适配）
```

定位器需要连续两帧命中才锁定。搜索阶段不运行小地图检测；短时失配最多保持 6 个采样帧，随后恢复静默。输入分辨率变化会清空旧位置。锁定期间只验证当前锚点，每 30 帧重新搜索一次，减少持续开销。

相关匹配在不同 HUD 上可能更偏爱稳定的地图内部，从而得到一个比训练裁剪更紧的锚点框。直接把该框送给 YOLOX 会裁掉边缘刚出现的敌人。当前 ROI Correction 因此输出“粗略布局框与已确认锚点框的并集”，并限制在活动画面内。锚点仍决定是否锁定和何时重定位，并集只用于下游裁剪；配置项是 `preserve_base_roi=true`。

## 配置与复现

定位描述符由 video1–video6 的人工复核全屏帧生成，不读取模型建议框：

```sh
cmake -S native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel

PYTHONPATH=python .venv/bin/python -m mapassist.calibrate_minimap_anchor \
  data/private/minimap-review-v4-dense/review-manifest.json \
  --output build/minimap-locator-dev.json
```

`profiles/hok_minimap_adaptive.experimental.android.json` 是绑定已退役 dense checkpoint 的历史实验配置。布局回放通过后做的检测联合诊断发现旧 YOLOX 在扩大后的动态裁剪上退化；hard-FP 固定 ROI 候选及其权重、阈值也已退役。两类旧 profile 均不应导入当前 APK；当前 APK 内置的是 HD bootstrap v2 固定 ROI 实验 profile，并在首装时默认启用识别。实现、历史对照和限制见[团队协作与本地运行](../docs/development/团队协作与本地运行.md)。

定位层可独立回放，不执行 YOLOX：

```sh
PYTHONPATH=python .venv/bin/python -m mapassist.minimap_locator_evaluate \
  data/private/minimap-video8-holdout-v1/blind-review-v1/review-manifest.json \
  --locator profiles/hok_minimap_adaptive.experimental.android.json \
  --library build/native/libmapassist.dylib \
  --output build/minimap-locator-video8-apk-profile.json
```

`--locator` 既接受单独的 `mapassist.minimap_locator` JSON，也接受包含 `layout.minimap_locator` 的完整 GameProfile，便于直接验证 APK 内置配置。

video7 表格绑定的 manifest SHA-256 为 `33e3fd6394f7ac871156ac92d056a672ccea7e469f7b18d272e8a20efe1c7603`，原始定位器 JSON 为 `41f8e6a7aa0c2aeb350bddfc7fc562803505e0b198ff3977ddfa7b6bdb4f4397`。完整候选 profile 内嵌同一描述符，SHA-256 为 `38104234d46f12e8e33db5a377bcd0b703ca8429b48065c82c2b77209c993ac7`。状态、分数和 ROI 可由相同输入复算；耗时是开发机上的单次测量，会受机器负载影响。

## video7 开发诊断

video7 为 2712×1220 的高分辨率人机录像。它已经完成过模型评测，因此这里只作为开发回归，不作为新的独立留出证据。111 张有效人工复核帧的结果：

| 指标 | 结果 |
| --- | ---: |
| 搜索 / 锁定 / 短时保持 | 1 / 109 / 1 帧 |
| 可用比例 | 99.10% |
| 相关分数中位数 / 最低值 | 0.510841 / 0.347186 |
| 修正后检测 ROI 中位数 `[x,y,w,h]` | `[0.03748771, 0, 0.15612380, 0.33333334]` |
| 原固定 ROI | `[0.0375, 0, 0.14444444, 0.33333333]` |
| 原人工裁剪覆盖率中位数 / P05 | 100% / 100% |
| 与原人工裁剪 IoU 中位数 / 最低值 | 92.52% / 92.23% |
| 开发机单次 Release C++ P95 | 约 6.07 ms |

video7 的锚点相对旧框向右移动，但 ROI Correction 保留了原人工裁剪并扩展到新锚点。它避免了旧实现把边缘图标裁掉的问题，但会让 320 输入中的地图主体略微缩小。video7 没有单独描出 HUD 外边界，因此覆盖率是相对于标注数据所用裁剪，不代表真实边界精度。

## video8 显示方向修复与定位回放

video8 的视频编码尺寸为 1080×2376，并通过 Display Matrix 旋转为 2376×1080。旧复核 PNG 使用编码方向像素和 EXIF 旋转；浏览器显示正确，但原定位回放直接读取像素时会得到竖屏输入。现在回放按 manifest 的 `source_coded_size`、`display_size` 和 `display_rotation_degrees` 把旧帧恢复为真正的横屏像素；新抽帧同时清除已经应用过的旋转元数据，避免查看器二次旋转。

video8 未参与描述符生成；以下定位器结果由不执行 YOLOX 的回放生成，数值保留为边界重校前的历史记录。随后发现 `minimap-layout-video8` 的 16 个边界框左缘标错，已在 [video8 记录](VIDEO8.md) 中更正，因此以下 video8 覆盖率和 IoU 全部作废，不能作为边界精度或完整覆盖结论。

| 指标 | 结果 |
| --- | ---: |
| 搜索 / 锁定 / 短时保持 | 1 / 113 / 5 帧 |
| 可用比例 | 99.16% |
| 相关分数中位数 / 最低值 | 0.461097 / 0.356342 |
| 修正后检测 ROI 中位数 `[x,y,w,h]` | `[0.03787879, 0, 0.15069388, 0.33333334]` |
| 原人工裁剪覆盖率中位数 / P05（旧真值，已作废） | 99.74% / 99.74% |
| 与原人工裁剪 IoU 中位数 / 最低值（旧真值，已作废） | 95.36% / 93.76% |
| 开发机单次 Release C++ P95 | 约 6.02 ms |

video8 数据在定位回放前已经完成三分片人工审计和像素仲裁：120 个唯一时间点中 102 张为 `corrected`、17 张为 `negative`、1 张结算切换帧为 `excluded`，119 张有效帧含 215 个框。最终 review manifest SHA-256 为 `7927c5692d6cfc4397ef874873a8c7f2e9c365744202ed5fd949f3ecbf13ee38`。

随后直接以完整 Android 候选 GameProfile 重跑定位层。profile SHA-256 为 `8c98248b896483c71c4a2ed4152aaac64ba878c1bb48d6b69942adba0431f3c3`，报告 SHA-256 为 `a4f65bcfcb05f3826b00c287fb10d957aaf1eb43c0d9b6f5f84ddd61c8af41a5`；除机器耗时外，状态、分数、ROI 与旧真值下的覆盖指标逐项一致。此运行只证明状态机返回 ROI；由于 video8 边界真值后来校正，旧覆盖率与 IoU 已作废。实体 `MediaProjection` 像素格式、刘海安全区和自定义 HUD 仍需真机覆盖。

历史 fixed-ROI 检测运行绑定 `profiles/hok_minimap_development.android.json` 的 SHA-256 `999f44ecfa9e58b3704439be45f53dd95e21f0fef64051dd730c9f7a1f752a3e`；该 ROI 后经边界复核证实会截掉右侧目标，成绩仅作为旧裁剪的历史记录。

## 完整 GameProfile 联合诊断

仅验证定位状态不足以判断旧 YOLOX 能否适应新的裁剪。正式冻结评测结束后，使用旧标注的同一批 119 张 video8 帧做了明确标记为 **post-hoc** 的 ncnn 对照；表中数值是右缘截断污染的历史 crop-relative 数值，不能用于模型效果比较或完整地图结论：

| 配置 | TP / FP / FN | Precision | Recall | F1 |
| --- | ---: | ---: | ---: | ---: |
| 固定 ROI Android profile（旧 crop） | 175 / 126 / 40 | 58.14% | 81.40% | 67.83% |
| 自适应 Android 候选 profile（旧 crop） | 169 / 155 / 46 | 52.16% | 78.60% | 62.71% |

该 ncnn 诊断路径与 PyTorch 旧冻结运行实现不同；两者均使用后来确认不完整的 fixed ROI crop，旧绝对数值和配对比较都不能替代完整地图评测。它们仅作运行历史追溯。诊断报告 SHA-256 为 `70a3de3a32105ec81fff1a3d6b1ce905bef7566d577e4aaed9a6ed380b0ae86a`。

此前“定位状态机和裁剪覆盖工作正常”的结论过强：当时只比较了自动 ROI 与人工使用的裁剪，没有核实目标是否被两者共同截断。后续复核界面显示 video8 固定 ROI 会切掉右侧头像；旧 locator 覆盖率以及固定／自适应 post-hoc 敌人检测指标均不能作为模型效果结论。固定归一化裁剪也用于其他录像尺寸，风险不能限定为 video8。当前 Android 固定 ROI 和敌人权重保持不变；布局定位器 v2 已用修正边界重训并完成开发诊断，但尚未按其预测裁剪重训敌人模型，也未通过独立真人对局验收。

2026-09-27 已量化并修正：video8 小地图控件实测 x∈[106,454]、y∈[0,344]（归一化 `[0.044613, 0, 0.146465, 0.3185185]`；测量与验证过程见 [video8 记录](VIDEO8.md)）。`minimap-layout-video8` 的 16 个人工边界框左缘错画到 x=214，已重校为测量值；此前“video8 覆盖率 99.16%、人工裁剪覆盖率 99.74%、IoU 95.36%”均以错误真值为参考，已作废。定位器 v2 随后按修正边界重训；video6 val 是开发验证，另 120 张 video8 布局评测帧虽未用于训练，但同场的 16 张 video8 帧已用于训练，故该 120 帧只作 same-match development diagnostic，见上文。video7 布局边界框与模板匹配结果一致（各边差 ≤9 px），无需修改；但 video7 敌人标注与盲测使用的固定裁剪 x∈[102,494]、y∈[0,407] 相对实际控件左含 41 px 杂景、右切 30 px、下含 29 px，重建 video7 开发数据时应按标定后的 ROI 重新导出。

## 边界

- 黑边归一化只处理贯穿整行或整列的黑边；异形刘海由锚点搜索范围吸收，不尝试伪造被遮挡像素。
- 当前 active-content 坐标只用于小地图锚点。实验配置只开启小地图 YOLOX；将来重新启用 `ping_area` 或主画面规则时，需要为这些区域增加各自的安全区坐标或锚点。
- HUD 外观变化过大、位置超出搜索范围，或相关分数低于阈值时，定位器保持静默。此时应重新采集该 HUD 配置的人工复核帧并生成新 `GameProfile`。
- 上述数据只验证 ROI 获取稳定性和原生耗时，不代表 YOLOX 准确率、事件召回率或真机端到端延迟。
- video7 没有逐帧独立描出的 HUD 外边界；旧 video8 描述符报告中的 `reference_crop_agreement` 也只是与 reviewed crop 的一致性，不是独立边界精度。“锁定”和“可用”表示相关分数及状态机通过阈值，不等同于边界精度。
- video8 未用于原描述符生成或敌人模型选择；定位器 v2 已把同场 16 个修正边界帧用于训练，并对另外 120 个同场帧作布局开发诊断。因此这 120 帧不是独立布局留出，video8 敌人预测失败案例也不能用于调参后再声称独立检测成绩。
