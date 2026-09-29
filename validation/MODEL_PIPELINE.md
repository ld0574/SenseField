# 模型接入记录

## HD bootstrap v2 PyTorch 开发候选（2026-09-29）

当前 HD bootstrap v2 使用人工复核后的 video1+8+3+4+5+10 作为 train（664 图／1211 框），video2-HD+11 作为开发 val（230 图／400 框），test 为空。video10/11 的队列、ROI、尺寸和来源 provenance 均已审计通过；video10 已进入 train，video11 是 dev-val。video9/12 未读取、未预标注、未查看预测，继续封存。

从 video4/5 扩充候选初始化，在 Apple MPS、YOLOX-Nano、输入 320、batch 16、seed `20260930`、`lr_scale=0.25` 下最多训练 12 轮，best epoch 8，约 337 秒。checkpoint SHA-256 为 `a11b560c6507f51f3e239b358445fb2acc95694edf80bc86c5cc207cc2a12f72`。开发 val 以 confidence `0.49`、NMS `0.5`、IoU `0.5` 评估，TP/FP/FN 为 `366/35/34`，precision / recall / F1 为 `91.2718% / 91.5000% / 91.3858%`。

这些指标是开发诊断：video2-HD 和 video11 已参与选模或阈值选择，video10 已参与训练，不能称独立留出成绩。逐场复测见[video10/11 记录](VIDEO10_11.md)：video10 `213/3/17`（P/R/F1 `98.6111% / 92.6087% / 95.5157%`），video11 `190/12/13`（`94.0594% / 93.5961% / 93.8272%`）。新候选尚未完成 ONNX/TorchScript/ncnn 严格 parity，保持 `verified=false`，没有替换 Android 公共默认模型；实体 Android 仍未验收。

训练与评测 JSON 保存在被 Git 忽略的 `build/training/yolox-nano-hd-bootstrap-video10-video11-v2-320/`，不会把录像、私有标注或 checkpoint 提交到仓库。

## HD bootstrap video4+video5 TorchScript / ncnn 开发候选（2026-09-29，历史）

当前候选以 video1+8+3+4+5 的人工复核帧训练，COCO train 为 534 图／981 框；video2-HD val 为 100 图／197 框，test 为空。video2-HD 已用于历轮选模和本轮 confidence 选择，以下是 same-match 开发诊断，不是独立成绩。video9/12 没有读取或运行模型。本机候选仍为 `verified=false`，public defaults 继续关闭；严格转换和 Android 等价检测门禁失败，开发集检测质量通过不能覆盖这些失败。

训练使用 video3-HD checkpoint 初始化，在 MPS 上以 seed `20260929`、input `320`、batch `16`、`lr_scale=0.25` 最多训练 12 轮；第 10 轮早停，best epoch 6，墙钟 196.834 秒，启动器硬上限 1,200 秒。checkpoint SHA-256 为 `6112fa5ca4829eed583eb2f07c84b679b468294bc78c6410e11c7f071987f1fa`。固定 `c=0.51`、NMS `0.5`、IoU `0.5` 的 PyTorch 评估为 TP/FP/FN `174/13/23`、P/R/F1 `93.0481% / 88.3249% / 90.6250%`。报告见 `build/models/yolox-nano-hd-bootstrap-video4-video5-v1-320/torchscript-development-evaluation-video2-100-c051-iou050.json`。

| 门禁 | video2-HD 100 张结果 | 状态 |
| --- | --- | --- |
| PyTorch→ONNX raw（门限 `0.0005`） | 最大误差 `0.002296686`，40/100 帧超限；最终 detection arrays 在 100/100 帧通过 verifier 的 `np.allclose(rtol=1e-4, atol=1e-4)` 检查 | 失败 |
| TorchScript→ncnn raw（门限 `0.0005`） | 最大误差 `0.000997305`，97/100 张通过；3 张超限 | 失败 |
| Android 等价 resize/pad 输入 | 最大像素差 `1`（门限 `1.0`） | 通过 |
| Android 等价检测数量 | 98/100 张一致，总数 `187/189`；video2-hd_000445090 为 `3/4`，video2-hd_000483410 为 `2/3` | 失败 |
| Android 等价 detection values（门限 `0.01`） | 最大误差 `0.724518`；配对 IoU min/median `0.95910/0.99876`，置信度最大差 `0.008447` | 失败 |
| ncnn runtime detections 对 COCO val（`c=0.51`、IoU `0.5`） | TP/FP/FN `175/14/22`，P/R/F1 `92.5926% / 88.8325% / 90.6736%`；P≥90%、R≥80% | 质量门槛通过；不改变 parity 结论 |

ONNX 最大 raw 差在 `video2-hd_000770810.png` 的 stride 8 cell `(x=31,y=36)`、row 1471 `center_y_offset`：PyTorch `1.02278662`，ONNX `1.02048993`，绝对误差 `0.002296686`，解码差约 `0.01837` 输入像素；该低置信度候选没有达到 `0.51`。ncnn 最大 raw 差在 `video2-hd_000876190.png` 的 stride 16 cell `(x=9,y=17)`、row 1949 `center_x_offset`，绝对误差 `0.000997305`，解码差约 `0.01596` 输入像素；置信度约 `1.01e-8`。它们均仍计入严格 raw 门禁失败；低置信度说明没有触发对应检测候选，不会豁免数值门禁。

Android 等价输入的像素差符合 `≤1` 门限，但该预处理会让部分检测坐标偏差明显超过 `0.01`。最终配对总共 182 个候选；逐框 IoU 和 score 只作为描述，不会取代 raw 或检测值门禁。runtime detections 在 `c=0.51`、NMS `0.5` 下对 COCO 人工真值重评为 `175/14/22`；对应脚本 `training/evaluate_yolox_ncnn_report.py` 会先核对完整 val 文件列表和 100 张图的哈希，再按最大一对一 IoU≥0.5 匹配，生成逐帧 JSON。该质量分数仍是同场开发集诊断。

本机 Android assets 与 profiles 已绑定同一 ncnn bin；当前 metadata 中记录 ONNX、TorchScript、ncnn、两份 profile、数据、转换和 parity 报告哈希。ncnn param/bin SHA-256 为 `4649269cae16fef3b64cc366f123ba58259a756b79f7f55d6be20cd3903cae14` / `bb4ac4583dd668388180ddaa3265827f8c6d71aa1980a5cfba8e1f784bd7992d`。Android profile SHA-256 为 `2536d6db4f8d107762acc24e7f40967e4dc44dd5c8d59268bf506497300be3d3`，`verified:false`。没有实体 Android 设备验收记录。

数据与报告哈希：COCO audit `cee324488fac7fc50ae9d4d1cb6b334b044a7b85710a24024d551e5e43af4a33`；train／val annotation `7060428da188cb371df93842ad29ce6b74acca76c69c18b893e7d36712f1f537` / `9220e751343ca8c2050cab79aa543f1689ca563a4abbee4dad3a469201f425e6`；ONNX／TorchScript `b5868060b2794c4eea7885b26bbc55e9d5b22462936e7952dd8fb19097dd1926` / `996ea09e8136c265d4c7d44538f4241d74be719d5c55291e80809c7caf274257`；ONNX／ncnn parity JSON `ce2bc9b7b407f9b0495d11530097acb9801ad2ec2d7b1783e350b6029b821162` / `8e02b500ed21811d7ad0815edd9811f6efb755b7c490d38b9d37d07b15f97a30`；ncnn COCO metrics JSON `eb72b17fd65abc1a2bdce0c78fbc5f8e5e360abac1e653a4f98981162a2329d2`，评估脚本 `9268c703719bb1ef4e65be725ca75a9c37db9e44950fc546d9bd0403b3d46c33`。

可复现导出与评测命令见[video4+video5 HD bootstrap 导出流程](../training/README.md#video4-hd--video5-hd-扩充训练与-android-开发候选2026-09-29)。模型和逐帧报告在 ignored `build/models/yolox-nano-hd-bootstrap-video4-video5-v1-320/`；训练结果和 COCO 审计在 ignored `build/training/yolox-nano-hd-bootstrap-video4-video5-v1-320/` 与 `data/private/hd-bootstrap-assistance-video4-video5-v1/coco/`。

### 首轮 video1+8+3 bootstrap 候选（2026-09-29，历史）

video4/5 纳入前的 v3 checkpoint、阈值 `0.21` 以及 parity 指标保留在其原始 artifacts 中；它们不再是当前本机 assets。该轮也使用 video2-HD 选模和置信度，因此同样不是独立成绩。其历史报告与 hash 仍可从 `build/models/yolox-nano-hd-bootstrap-video3-v1-320/` 检查。

## hard-FP 固定 ROI Android 开发候选（2026-09-27，已退役）

历史开发 APK 曾使用 checkpoint `f7176b7ea9de65fb0f1fe4262514992fdda2ed8691a7a87851a2d27a910c7cfb`，输入 320、confidence `0.19`、NMS `0.5`。该低清候选、对应 checkpoint 和指标均已退役；APK 默认 profile 的 `detectors.minimap_yolox` 仍为 `false`。

本页 video6 检测指标是 legacy fixed-ROI crop-relative 内部开发对照。video8 的边界复核发现该 ROI 会截掉右侧目标；video6 同一固定 ROI 的指标不能代表完整小地图覆盖或召回。

- ncnn param SHA-256 为 `4649269cae16fef3b64cc366f123ba58259a756b79f7f55d6be20cd3903cae14`，bin SHA-256 为 `34b2cc80e47bd197e52a40ff69e39d60aea363de2071c8a89510c6398bbfbc56`。ONNX 和 TorchScript SHA-256 分别为 `f45fe90e6cbc87e891fe18846f57a89d4eb63eb2d8a5de814e554050d39ecb00`、`d1398a939db88f6e0e7979dbbaf6a8f76c18c3a433974c3c4625e631e3ddc23f`。
- video6 开发验证的框级 TP/FP/FN 为 `167/18/56`，P/R/F1 为 `90.2703% / 74.8879% / 81.8627%`。同帧同方向合并后的 `set` 事件为 `148/10/26`，P/R/F1 为 `93.6709% / 85.0575% / 89.1566%`；要求同方向且 IoU 达标的 `iou_gated` 事件为 `144/14/30`，P/R/F1 为 `91.1392% / 82.7586% / 86.7470%`。两种事件口径均越过开发门槛 P≥90%、R≥80%，但 video6 已用于选模和选阈值，因此不是独立留出成绩。评估 JSON SHA-256 为 `117561be1bb634cb2caf327bbb106bd27820716ccbde74adde5114504506c95e`。
- PyTorch／ONNX 在 152 张 video6 开发图上的 raw 最大误差为 `0.000477791 < 0.0005`，通过。TorchScript／ncnn raw 最大误差为 `0.000515342 > 0.0005`，其中一张图超过严格门槛，所以总体 raw parity 如实记为失败。Android 等价预处理后的最终检测计数在 152/152 张图上一致，总实例为 185/185，最大框或置信度差为 `0.005043 < 0.01`，检测层门禁通过。ONNX 与 ncnn 报告 SHA-256 分别为 `2afee938651f1c8df9fe55f104e39cf4641e349077c873bb0fee8c993614ee33` 和 `0341ac24e364994b554c9997aaf3a744dc41e07872935425d7a4d067ef5dc119`。
- video7 开发负样本片段覆盖 5 个零框人工采样点。新候选回放 122 帧得到 0 detection、0 observation、0 cue；旧 fixed 与 adaptive 链路在同片段分别产生 6 和 31 个检测。片段、预测和 metadata SHA-256 分别为 `7e9ef922c19f52200946eecd281d95cee2a5c5bd7eec941b9ba640acf4941746`、`457840c948751f8cbcf42d278bde3add9b46cf537814e36cf6c37f0d3a690745`、`52e57c6b163ff5da95fc86e253efe27d95932f043b398d82f28ef16d484eea70`。
- video7 开发正样本片段取原录像 74–92 秒：216 帧产生 501 detection／observation 和 4 条冷却后的 cue，桌面处理 P95 为 29.938 ms。7 个既有人工采样点的预测／真值框数逐点为 `1/1、1/1、2/2、3/3、3/3、3/3、3/3`。片段、预测和 metadata SHA-256 分别为 `98f6bb7abcb688afe02e221c39abfe5f9312fef4f92be603d69da74915b80f4e`、`c29bb9f17a67650342b689de6fc3ca2f3ad4c48ba050b489ed86dc8472d4e787`、`43e08e146518ad3f716204a657c2d1a0fa46952084c31c63cfd4e1570c89e029`。这两段只证明开发数据上的正负链路行为，不是独立质量评测，桌面 P95 也不是真机端到端时延。
- 集成后全量 pytest 为 144 passed、1 skipped；clean `assembleDebug lintDebug` 成功并执行模型哈希校验。debug APK 只含 arm64-v8a，minSdk 29、targetSdk 35，SHA-256 为 `7778468c64c07444767b7bddc9f5578e5639db319e9c4e695afcf45519f6125e`。

本机模型文件由 Git 忽略。测试人员需先安装包含上述 param／bin 的本机 APK，再导入 `profiles/hok_minimap_development.android.json` 并打开实验识别器。旧自适应 profile 绑定 dense baseline checkpoint，与当前权重不兼容，不应导入当前 APK。

## dense baseline 历史 Android 接入（2026-09-26）

冻结的 YOLOX Nano 320 小地图模型已经接入 Android 实时截屏链路，仍属于默认关闭的实验能力：

- Android 构建固定使用 ncnn `20260526`。CMake 从官方 Release 下载 Android 包并校验 SHA-256 `85b18b875488585c2d21360430e0e54abb6c04aa88094b471c20208ab55ff796`，只构建 `arm64-v8a`。
- APK 内含 `minimap-yolox-nano-320.param`、`minimap-yolox-nano-320.bin` 和模型元数据。`.param`／`.bin` 的 SHA-256 分别为 `4649269cae16fef3b64cc366f123ba58259a756b79f7f55d6be20cd3903cae14` 和 `b3dbc844cc148aaa1a794e1bf03cb02a847b9c185215045c4b19b9ba4982236a`。
- JNI 从完整 RGBA 截屏按 `GameProfile` 裁出小地图，转换为 BGR，保持比例缩放到 320 并在右侧／下方填充 114。ncnn 输出为单类 `1×2100×6`；按 strides `8/16/32` 解码，使用 `objectness × class`、开发阈值 `0.29` 和 IoU `0.5` NMS，再把框映射回整屏归一化坐标。
- 模型框转换为现有 `MA_MINIMAP_ENEMY` observation，继续使用两到三帧确认、空间跟踪、五秒小地图冷却、全局限流、过期丢弃和左右／上下声音方向。
- pnnx 不支持 YOLOX Focus 的 `Slice(step=2)`。`training/convert_yolox_ncnn.py` 严格验证并将 `Input + Split + 4 Crop + Concat` 替换为运行时注册的 `YoloV5Focus`，结构不符即停止转换。
- 30 张真实 `video6` 验证裁剪上的 TorchScript／ncnn 原始输出最大绝对误差为 `0.0004493`，低于 `0.0005` 门限。Android 的 ncnn resize 与训练侧 OpenCV resize 最多出现 1 个像素值差异；固定阈值后的逐图检测数量全部一致，最大框／置信度差异为 `0.003071`，低于 `0.01` 门限。
- Gradle 在每次构建前按 metadata 校验 param／bin 的 SHA-256；CMake 对下载和已有缓存中的 ncnn 压缩包均校验固定哈希，避免混用模型或依赖版本。
- `training/replay_yolox_ncnn.py` 可把完整录像按 12 FPS 送入与 Android 相同的原生定位器、动态裁剪、ncnn param/bin、解码和 C++ 事件层，并把录像、APK 内 profile、模型、native library 与预测 JSONL 的 SHA-256 写入 provenance。每帧记录 `searching/locked/held/fixed`、ROI 和内容区；定位搜索时跳过 YOLOX。它使用可复现的 CFR 媒体时间和零排队延迟事件时钟，不能代替真机时延。

0.2.0 的历史固定 ROI 冒烟使用 video7 的 10 秒开发片段：处理 123 帧，得到 253 个 YOLOX 检测和 2 条事件，回放约 24.9 FPS。

自适应候选链路使用另一段 video7 的 10 秒开发片段重跑：122 帧中首帧为 `searching` 并保持小地图静默，随后 121 帧为 `locked`；得到 31 个 YOLOX 检测、31 个 observation 和 2 条事件，开发机单次回放约 33.1 FPS、处理 P95 约 28.1 ms。输入片段 SHA-256 为 `7e9ef922c19f52200946eecd281d95cee2a5c5bd7eec941b9ba640acf4941746`，候选 profile 为 `8c98248b896483c71c4a2ed4152aaac64ba878c1bb48d6b69942adba0431f3c3`，预测 JSONL 为 `e603f129aaadd676199a463b7ab12d63d1000b77658bc80a1d89e808ddc483a8`，provenance 为 `3f74e29ee7544a1737f4863ad3014e8f101bf697a099337c23a3d1b3b572ab65`。它当时证明定位器、动态 ROI、ncnn 和事件层已连通；后续核对该时段的 5 个人工采样点均为零框，因此 31 个检测也暴露了旧模型误报。

随后在 video8 修正标签上做的 post-hoc 配对诊断记录了固定 ROI 与自适应候选在同一 ncnn 路径下分别为 58.14% / 81.40% 和 52.16% / 78.60%（precision / recall）。这些仅是受右缘截断污染的旧 crop-relative 历史值；标签和裁剪完整性没有边界触碰审计，不能据此比较模型或判断完整地图效果。动态并集裁剪也会改变旧 YOLOX 输入分布；当前 Android ROI 与权重不变，不能直接扩大 ROI 并沿用旧权重。详细边界见[定位器记录](MINIMAP_LOCATOR.md)。

模型配置为 `verified: false`。当前 APK 默认 profile 还显式设置 `minimap_yolox:false`；开发测试者必须先导入与权重匹配的固定 ROI 开发 profile，再勾选“允许未通过真人录像评测的实验识别器”，下一次截屏会话才加载模型。`video7` 人机历史盲测只有 65.57% precision／63.49% recall，因此不能作为发布默认能力。

当前证据能证明模型转换、APK 打包和代码链路成立。实体 Android 13/14 上的模型加载、持续推理耗时、实际发声、游戏帧率和发热仍需真机记录；完整验收还需要未参与开发的真实匹配留出对局。

## 主画面边缘敌人

主画面边缘敌人目前仍只有默认关闭的红色血条启发式，没有真实标注或训练权重。后续步骤：

1. 按 `annotations/DETECTION.example.json` 标注敌方标记／血条的归一化框，同时保留无敌人帧和易混淆红色 UI。训练、验证和测试按整场对局分开。
2. 使用 `mapassist.detection_dataset` 导出 COCO 数据，并确认同一对局不会跨训练／留出组。
3. 训练单独的轻量检测器，固定训练代码、随机种子、输入尺寸、权重和阈值，再执行 PyTorch／ONNX／ncnn 一致性检查。
4. 只有真实匹配留出和实体手机端到端门禁均通过，才在现场配置中启用。

小地图模型不能证明主画面检测已经完成，两类画面的目标尺度、背景和提示语义不同，需分别标注和验收。
