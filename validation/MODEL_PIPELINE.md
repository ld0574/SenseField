# 主画面模型接入记录

当前只有实验性红色血条启发式。没有真实《王者荣耀》边框标注、训练权重或 ncnn 依赖；因此没有可验证的主画面模型 APK。以下步骤在取得多场对局后执行，任何模型都需先经过留出对局评测和真机延迟测试。

1. 按 `annotations/DETECTION.example.json` 标注敌方标记／血条的归一化框，包含没有敌人的帧与易混淆红色 UI。训练、验证和测试按**整场对局**分开。运行 `python3 -m mapassist.detection_dataset ...` 后，输出为 `train2017/`、`val2017/`、`test2017/` 与相应 COCO JSON。导出器检查相同录像路径不会跨组；不同路径的同一场副本仍需人工排除。该布局对应 [YOLOX 官方 `COCODataset` 的路径拼接](https://github.com/Megvii-BaseDetection/YOLOX/blob/main/yolox/data/datasets/coco.py)。
2. 在有合适 GPU 的机器上安装官方 [YOLOX](https://github.com/Megvii-BaseDetection/YOLOX)，以其 [Nano 实验配置](https://github.com/Megvii-BaseDetection/YOLOX/blob/main/exps/default/yolox_nano.py)为起点；设置 `num_classes=1`、`data_dir` 为本地导出目录、`train_ann=instances_train2017.json`、`val_ann=instances_val2017.json`。固定训练代码版本、随机种子、输入尺寸和权重哈希。先在验证集选阈值，测试集只用于最终留出评测。当前尚无数据和训练环境，训练命令及超参数需要据真实数据确定。
3. 使用官方 [ONNX 导出脚本](https://github.com/Megvii-BaseDetection/YOLOX/blob/main/tools/export_onnx.py)导出同一权重，再按 [YOLOX 的 ncnn 转换说明](https://github.com/Megvii-BaseDetection/YOLOX/blob/main/demo/ncnn/cpp/README.md)转换。官方说明指出 Focus／Slice 需要专门处理，不能把 `onnx2ncnn` 的输出直接当作可用模型。ncnn 的 [CMake 接入说明](https://github.com/Tencent/ncnn/wiki/use-ncnn-with-own-project)使用 `find_package(ncnn REQUIRED)`；版本、ABI 和模型文件哈希应随 APK 固定。
4. 选一组私有帧，逐框比较 PyTorch、ONNX 和 ncnn 输出的类别、置信度、位置与预处理；然后接入当前 `Observation`／事件层，在 Android 13/14 上测完整采集到实际声音的 P95。只有真实留出对局达到预定准确率、召回率、方位正确率和真机延迟目标，才能把模型识别器在发布配置中打开。失败时保持主画面识别关闭，并记录误报和漏报画面。

官方 YOLOX 示例默认面向一般目标检测；其预训练权重不能证明能识别《王者荣耀》敌方 UI。当前工程未包含或声称已验证模型推理。
