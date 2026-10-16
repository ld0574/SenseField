# 小地图模型训练与端侧转换

听野的小地图检测链路使用 YOLOX 与 ncnn。此目录提供训练、评估、格式转换和运行一致性检查工具；训练数据、模型权重、检查点和逐轮实验报告留在本地。

## 数据与独立评测

只接受人工复核的 HD 数据，保留完整画面来源、ROI、标注人与审阅状态。同一场录像整体属于同一分组，邻近帧不跨训练和测试分组。

- `safe_roi` 约束完整框，`label_roi` 约束可标注中心，`widget_roi` 提供小地图主体参考。
- 敌人头像和玩家自身头像分别建类；机器建议先经人工复核。
- 参与选模、调参、错误分析或规则修改的数据属于开发数据，不能再列为独立测试。
- **`video9` 与 `video12` 保持封存**：不查看内容、不运行模型或读取预测。独立评测前冻结模型、阈值及评测协议。

标注操作见 [团队协作](../docs/development/团队协作与本地运行.md)。外部数据的来源、许可与隔离要求见 [外部数据引入](../docs/development/外部数据引入与预训练.md)。

## 工具入口

| 工具 | 用途 |
| --- | --- |
| [train_yolox_minimap.py](train_yolox_minimap.py) | 按已审阅分组训练 |
| [evaluate_yolox_minimap.py](evaluate_yolox_minimap.py) | 固定配置下评估检测结果 |
| [convert_yolox_ncnn.py](convert_yolox_ncnn.py) | TorchScript 到 ncnn 转换 |
| [verify_yolox_onnx.py](verify_yolox_onnx.py) | ONNX 与训练框架输出核对 |
| [verify_yolox_ncnn.py](verify_yolox_ncnn.py) | ncnn 与参考运行输出核对 |
| [replay_yolox_ncnn.py](replay_yolox_ncnn.py) | 用冻结模型回放开发录像 |

先查看各入口的 `--help`，根据本机 YOLOX 源码、数据和模型路径设置参数。例如：

```sh
python3 training/train_yolox_minimap.py --help
python3 training/verify_yolox_ncnn.py --help
```

## 开发流程

1. 完成人工复核与来源审计，冻结录像级分组，再导出 COCO 数据。
2. 在训练分组训练、开发验证分组选模，保存配置、随机种子与检查点摘要。
3. 导出 ONNX／TorchScript／ncnn，核对预处理、原始输出、解码结果与最终检测；按既定容差报告各项结果。
4. 将匹配模型、metadata 和 profile 作为一个版本管理，核对 Android 打包及运行时摘要。
5. 用未参与开发的对局评估识别、方向与提示效果，另在真机测量实际发声延迟和运行负载。

模型转换通过仅说明对应数值链路一致；合成与开发回放结果不替代独立准确率、玩家收益或实体设备验证。不能因文档整理而改变既有 `verified`、`release_ready` 或测试分组。

## 多类别与部署契约

模型 metadata、类别顺序、profile 和 Android 解码必须匹配。输入尺寸、均值归一化、置信度与 NMS 配置一并记录；更换模型后重新进行一致性核对，不能混用旧 profile 与新权重。

模型权重不提交到 Git。安卓构建按 metadata 准备本地资源，正式包要求见 [内置资源与构建](../docs/development/offline-speech.md)，仓库检查使用 `python3 scripts/check_public_repo.py`。
