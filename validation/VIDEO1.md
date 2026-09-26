# `video1.mp4` 开发标定记录（2026-09-25）

`video/video1.mp4` 是一场约 927.87 秒、720×324、约 30 帧／秒的横屏《王者荣耀》录屏。录像及从中裁出的帧、模板、预测和标注仅存于本机；`*.mp4`、`build/`、`data/private/` 均在 `.gitignore` 中。

## 画面观察与目前决策

| 事件 | 观察 | 决策 |
| --- | --- | --- |
| 主画面边缘敌人 | 红色血条启发式在部分抽帧中命中敌人，也命中技能区、顶部 UI 等，且漏掉部分肉眼可见的敌人。 | `main_red_bar: false`；需要逐帧边框和负样本，再训练、转换、接入模型。 |
| 小地图敌人 | 图标是带阵营色环的不同英雄头像，还与防御塔及其他红色地图元素相邻。 | `minimap_template: false`；一张固定模板不具备跨英雄可靠性。 |
| 队友危险信号 | 顶部蓝色横幅可见“撤退”“敌人消失”等文案。“撤退”图标和文字在本场较稳定。 | 仅用本场裁出的 `retreat.png` 开发“撤退”识别；其他信号尚未覆盖。 |

## 可复现的同场回放

私有目录中，`profile-retreat-only.json` 仅开启 `danger_ping_template`，模板 `retreat.png` 来自本场约 177.833 秒的原始帧，模板匹配阈值 0.94，`verified: false`。在仓库根目录运行：

```sh
cmake -S native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native -j4
PYTHONPATH=python python3 -m mapassist.replay video/video1.mp4 --profile data/private/video1/profile-retreat-only.json --output data/private/video1/predictions-retreat.jsonl
PYTHONPATH=python python3 -m mapassist.evaluate data/private/video1/predictions-retreat.jsonl data/private/video1/labels-retreat-development.json --output data/private/video1/report-retreat-development.json
```

整场按 12 帧／秒回放，处理 11,134 帧，产生 222 次逐帧观察、3 条事件提示。提示时间为 177.917、237.750、282.083 秒。人工复查了这三处出现前后与持续期间的抽帧，对应三段“撤退”横幅，未见其他类型画面触发此模板。三段人工记录的首次可见帧分别约在 177.833、237.667、282.000 秒；按**录像时间轴**统计，这三条提示约晚 83–84 毫秒。`report-retreat-development.json` 的 3/3、0 额外提示、P95 84 毫秒只描述这场开发录像；起点按 12 帧／秒抽样，精度有限。桌面单帧识别 P95 0.56 毫秒也不包含解码、安卓采集、排队、发声。

该模板与阈值都由 `video1.mp4` 制作和调整，标注亦只核对“撤退”横幅。因此不能把开发集上的数值称为泛化准确率，不能推断所有队友危险信号的召回率，也不能据此把私有配置设为 `verified: true`。后续 video2–5 也已用于小地图检测的失败案例复核，见 [五场录像数据记录](VIDEO3_5.md)，所以最终评测仍需要一场新的冻结留出对局。真机还需验证录屏分辨率、授权、实际发声和游戏性能。视频中的“敌人消失”横幅可作为下一类信号单独标注、训练和测试。
