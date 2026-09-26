# 模型接入记录

## 小地图 YOLOX Android 接入（2026-09-26）

冻结的 YOLOX Nano 320 小地图模型已经接入 Android 实时截屏链路，仍属于默认关闭的实验能力：

- Android 构建固定使用 ncnn `20260526`。CMake 从官方 Release 下载 Android 包并校验 SHA-256 `85b18b875488585c2d21360430e0e54abb6c04aa88094b471c20208ab55ff796`，只构建 `arm64-v8a`。
- APK 内含 `minimap-yolox-nano-320.param`、`minimap-yolox-nano-320.bin` 和模型元数据。`.param`／`.bin` 的 SHA-256 分别为 `4649269cae16fef3b64cc366f123ba58259a756b79f7f55d6be20cd3903cae14` 和 `b3dbc844cc148aaa1a794e1bf03cb02a847b9c185215045c4b19b9ba4982236a`。
- JNI 从完整 RGBA 截屏按 `GameProfile` 裁出小地图，转换为 BGR，保持比例缩放到 320 并在右侧／下方填充 114。ncnn 输出为单类 `1×2100×6`；按 strides `8/16/32` 解码，使用 `objectness × class`、开发阈值 `0.29` 和 IoU `0.5` NMS，再把框映射回整屏归一化坐标。
- 模型框转换为现有 `MA_MINIMAP_ENEMY` observation，继续使用两到三帧确认、空间跟踪、五秒小地图冷却、全局限流、过期丢弃和左右／上下声音方向。
- pnnx 不支持 YOLOX Focus 的 `Slice(step=2)`。`training/convert_yolox_ncnn.py` 严格验证并将 `Input + Split + 4 Crop + Concat` 替换为运行时注册的 `YoloV5Focus`，结构不符即停止转换。
- 30 张真实 `video6` 验证裁剪上的 TorchScript／ncnn 原始输出最大绝对误差为 `0.0004493`，低于 `0.0005` 门限。Android 的 ncnn resize 与训练侧 OpenCV resize 最多出现 1 个像素值差异；固定阈值后的逐图检测数量全部一致，最大框／置信度差异为 `0.003071`，低于 `0.01` 门限。
- Gradle 在每次构建前按 metadata 校验 param／bin 的 SHA-256；CMake 对下载和已有缓存中的 ncnn 压缩包均校验固定哈希，避免混用模型或依赖版本。
- `training/replay_yolox_ncnn.py` 可把完整录像按 12 FPS 送入同一 ncnn param/bin、裁剪、解码和 C++ 事件层，并把录像、APK 内 profile、模型、native library 与预测 JSONL 的 SHA-256 写入 provenance。它使用可复现的 CFR 媒体时间和零排队延迟事件时钟，不能代替真机时延。

真实素材冒烟使用 video7 的 10 秒开发片段和 APK 内同哈希 `profile.json`：处理 123 帧，得到 253 个 YOLOX 检测和 2 条事件，回放约 24.9 FPS；预测时间戳严格按 83／84 ms 递增。该结果证明整段录像工具能实际执行冻结 ncnn 模型，不是独立准确率成绩。

模型配置为 `verified: false`。应用首次打开时不会运行它；开发测试者必须勾选“允许未通过真人录像评测的实验识别器”，下一次截屏会话才加载模型。`video7` 人机盲测只有 65.57% precision／63.49% recall，因此不能作为发布默认能力。

当前证据能证明模型转换、APK 打包和代码链路成立。实体 Android 13/14 上的模型加载、持续推理耗时、实际发声、游戏帧率和发热仍需真机记录；完整验收还需要未参与开发的真实匹配留出对局。

## 主画面边缘敌人

主画面边缘敌人目前仍只有默认关闭的红色血条启发式，没有真实标注或训练权重。后续步骤：

1. 按 `annotations/DETECTION.example.json` 标注敌方标记／血条的归一化框，同时保留无敌人帧和易混淆红色 UI。训练、验证和测试按整场对局分开。
2. 使用 `mapassist.detection_dataset` 导出 COCO 数据，并确认同一对局不会跨训练／留出组。
3. 训练单独的轻量检测器，固定训练代码、随机种子、输入尺寸、权重和阈值，再执行 PyTorch／ONNX／ncnn 一致性检查。
4. 只有真实匹配留出和实体手机端到端门禁均通过，才在现场配置中启用。

小地图模型不能证明主画面检测已经完成，两类画面的目标尺度、背景和提示语义不同，需分别标注和验收。
