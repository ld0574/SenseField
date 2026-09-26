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

定位器需要连续两帧命中才锁定。搜索阶段不运行小地图检测；短时失配最多保持 6 个采样帧，随后恢复静默。输入分辨率变化会清空旧位置。锁定期间只验证当前 ROI，每 30 帧重新搜索一次，减少持续开销。

## 配置与复现

定位描述符由 video1–video6 的人工复核全屏帧生成，不读取模型建议框：

```sh
cmake -S native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel

PYTHONPATH=python .venv/bin/python -m mapassist.calibrate_minimap_anchor \
  data/private/minimap-review-v4-dense/review-manifest.json \
  --output build/minimap-locator-dev.json
```

可导入 Android 应用的实验配置是 `profiles/hok_minimap_adaptive.experimental.android.json`。当前 APK 内置配置仍保留冻结的固定 ROI，直到 video8 一次性盲测完成，避免改变已经登记的 profile 哈希。

定位层可独立回放，不执行 YOLOX：

```sh
PYTHONPATH=python .venv/bin/python -m mapassist.minimap_locator_evaluate \
  data/private/minimap-video7-holdout-v1/review-manifest.json \
  --locator build/minimap-locator-dev.json \
  --library build/native/libmapassist.dylib \
  --output build/minimap-locator-video7.json
```

本次记录绑定的 video7 manifest SHA-256 为 `33e3fd6394f7ac871156ac92d056a672ccea7e469f7b18d272e8a20efe1c7603`，定位器 JSON 为 `66a7a69e036f0eada232a03fd7a212025ade0acedff55e5081f1170656ff54bb`，其中描述符为 `38104234d46f12e8e33db5a377bcd0b703ca8429b48065c82c2b77209c993ac7`。状态、分数和 ROI 可由相同输入复算；耗时是开发机上的单次测量，会受机器负载影响。

## video7 开发诊断

video7 为 2712×1220 的高分辨率人机录像。它已经完成过模型评测，因此这里只作为开发回归，不作为新的独立留出证据。111 张有效人工复核帧的结果：

| 指标 | 结果 |
| --- | ---: |
| 搜索 / 锁定 / 短时保持 | 1 / 109 / 1 帧 |
| 可用比例 | 99.10% |
| 相关分数中位数 / 最低值 | 0.510841 / 0.347186 |
| 定位器输出 ROI 中位数 `[x,y,w,h]` | `[0.05332943, 0.00026397, 0.13970418, 0.32199983]` |
| 原固定 ROI | `[0.0375, 0, 0.14444444, 0.33333333]` |
| 开发机单次 Release C++ 耗时中位数 / P95 / 最大值 | 0.024 / 5.99 / 6.89 ms |

定位器输出相对旧框向右偏移约 43 个原始像素，说明它没有把 2712×1220 画面机械套用旧归一化框。video7 没有单独标注小地图真实边界，因此该回放只证明输出状态稳定和 ROI 确实发生修正，不能单独证明 43 像素的修正量就是准确边界。

## 边界

- 黑边归一化只处理贯穿整行或整列的黑边；异形刘海由锚点搜索范围吸收，不尝试伪造被遮挡像素。
- 当前 active-content 坐标只用于小地图锚点。实验配置只开启小地图 YOLOX；将来重新启用 `ping_area` 或主画面规则时，需要为这些区域增加各自的安全区坐标或锚点。
- HUD 外观变化过大、位置超出搜索范围，或相关分数低于阈值时，定位器保持静默。此时应重新采集该 HUD 配置的人工复核帧并生成新 `GameProfile`。
- 上述数据只验证 ROI 获取稳定性和原生耗时，不代表 YOLOX 准确率、事件召回率或真机端到端延迟。
- video7 没有独立的小地图边界真值；“锁定”和“可用”表示相关分数及状态机通过阈值，不等同于定位准确率。新设备仍需抽帧人工核对输出 ROI 是否完整覆盖小地图。
- video8 未用于描述符生成或参数选择。冻结评测结束前，不用 video8 结果调整定位阈值。
