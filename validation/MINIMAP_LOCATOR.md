# 小地图自动定位器验证记录

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

0.3.0 开发 APK 已内置 `profiles/hok_minimap_adaptive.experimental.android.json`。它仍是 `verified:false` 的实验配置，只有测试者明确开启实验识别器后才运行。用于 video8 一次性检测盲测的固定 ROI 文件 `profiles/hok_minimap_development.android.json` 保持原哈希并作为历史证据保留。

定位层可独立回放，不执行 YOLOX：

```sh
PYTHONPATH=python .venv/bin/python -m mapassist.minimap_locator_evaluate \
  data/private/minimap-video8-holdout-v1/blind-review-v1/review-manifest.json \
  --locator android/app/src/main/assets/profile.json \
  --library build/native/libmapassist.dylib \
  --output build/minimap-locator-video8-apk-profile.json
```

`--locator` 既接受单独的 `mapassist.minimap_locator` JSON，也接受包含 `layout.minimap_locator` 的完整 GameProfile，便于直接验证 APK 内置配置。

video7 表格绑定的 manifest SHA-256 为 `33e3fd6394f7ac871156ac92d056a672ccea7e469f7b18d272e8a20efe1c7603`，原始定位器 JSON 为 `41f8e6a7aa0c2aeb350bddfc7fc562803505e0b198ff3977ddfa7b6bdb4f4397`。完整 APK profile 内嵌同一描述符，SHA-256 为 `38104234d46f12e8e33db5a377bcd0b703ca8429b48065c82c2b77209c993ac7`。状态、分数和 ROI 可由相同输入复算；耗时是开发机上的单次测量，会受机器负载影响。

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

video8 未参与描述符生成，定位回放不执行 YOLOX，也不改动已冻结的敌人检测成绩。119 张有效人工复核帧的结果：

| 指标 | 结果 |
| --- | ---: |
| 搜索 / 锁定 / 短时保持 | 1 / 113 / 5 帧 |
| 可用比例 | 99.16% |
| 相关分数中位数 / 最低值 | 0.461097 / 0.356342 |
| 修正后检测 ROI 中位数 `[x,y,w,h]` | `[0.03787879, 0, 0.15069388, 0.33333334]` |
| 原人工裁剪覆盖率中位数 / P05 | 99.74% / 99.74% |
| 与原人工裁剪 IoU 中位数 / 最低值 | 95.36% / 93.76% |
| 开发机单次 Release C++ P95 | 约 6.02 ms |

video8 数据在定位回放前已经完成三分片人工审计和像素仲裁：120 个唯一时间点中 102 张为 `corrected`、17 张为 `negative`、1 张结算切换帧为 `excluded`，119 张有效帧含 215 个框。最终 review manifest SHA-256 为 `7927c5692d6cfc4397ef874873a8c7f2e9c365744202ed5fd949f3ecbf13ee38`。

随后直接以 APK 内置完整 GameProfile 重跑定位层。profile SHA-256 为 `8c98248b896483c71c4a2ed4152aaac64ba878c1bb48d6b69942adba0431f3c3`，报告 SHA-256 为 `a4f65bcfcb05f3826b00c287fb10d957aaf1eb43c0d9b6f5f84ddd61c8af41a5`；除机器耗时外，状态、分数、ROI 和覆盖指标与单独 locator JSON 报告逐项一致。这些结果证明 APK profile 能在该全面屏录像上稳定取得不会明显裁掉人工复核区域的检测框；实体 `MediaProjection` 像素格式、刘海安全区和自定义 HUD 仍需真机覆盖。

这次 video8 回放只运行布局层，没有再次执行 YOLOX。固定 ROI 检测的一次性成绩仍绑定 `profiles/hok_minimap_development.android.json` 的 SHA-256 `999f44ecfa9e58b3704439be45f53dd95e21f0fef64051dd730c9f7a1f752a3e`。

## 边界

- 黑边归一化只处理贯穿整行或整列的黑边；异形刘海由锚点搜索范围吸收，不尝试伪造被遮挡像素。
- 当前 active-content 坐标只用于小地图锚点。实验配置只开启小地图 YOLOX；将来重新启用 `ping_area` 或主画面规则时，需要为这些区域增加各自的安全区坐标或锚点。
- HUD 外观变化过大、位置超出搜索范围，或相关分数低于阈值时，定位器保持静默。此时应重新采集该 HUD 配置的人工复核帧并生成新 `GameProfile`。
- 上述数据只验证 ROI 获取稳定性和原生耗时，不代表 YOLOX 准确率、事件召回率或真机端到端延迟。
- video7/video8 没有逐帧独立描出的 HUD 外边界；这里报告的是与人工检测裁剪的覆盖和重叠。“锁定”和“可用”表示相关分数及状态机通过阈值，不等同于边界精度。
- video8 未用于描述符生成、定位阈值或敌人模型选择。它在敌人检测冻结盲测结束后用于验证显示方向和 ROI Correction；不得再用其敌人预测失败案例调模型并继续声称为独立检测成绩。
