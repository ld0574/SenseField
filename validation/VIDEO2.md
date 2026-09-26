# `video2.mp4` 小地图开发记录（2026-09-25）

> 这是加入 video3–5 前的历史基线。当前地图结构门控和五场回放结果见 [五场录像数据记录](VIDEO3_5.md)。

`video/video2.mp4` 是第二场横屏《王者荣耀》录像：720×324、H.264、约 29.998 帧／秒、总长约 1,086.03 秒。SHA-256 为 `bf37667fe2438b47c31da23e50a04a66598b95d65fcc79942052b4c2ae3d9ab5`。录像、抽帧、预测和实验配置均留在 `.gitignore` 已排除的本地目录。

## 为什么不用单张头像模板

小地图敌人是不同英雄的圆形头像，头像内容会变，但红方阵营环和大致尺寸稳定。地图内还有红色防御塔、路径和基地标记；只找红色会产生大量误报。因此共享 C++ 引擎新增 `minimap_red_ring`，处理顺序为：

1. 在配置的小地图 ROI 内统计青色固定地图标记。数量不足时认为比分面板、菜单或其他页面覆盖了小地图，保持静默。
2. 提取红色占优的像素并按当前小地图尺寸进行轻量膨胀和连通域分析。
3. 用连通域的宽、高、长宽比和红色像素数量排除窄长防御塔图标与小型固定标记；相邻敌人头像允许合并为一个观察框。
4. 输出相对于小地图的方位。事件层按归一化中心位置跟踪最多 8 个头像；同一空间目标只播报一次，短暂检测中断不会立刻重置，所有小地图提示另有 5 秒最短间隔。

小地图单项配置为 `data/private/video2/profile-minimap-red-ring.json`，安卓可导入版本为 `data/private/video2/android-minimap-red-ring.json`。组合配置 `profile-combined-development.json`／`android-combined-development.json` 另行启用 video1 的“撤退”模板，主画面检测仍关闭。所有配置均为 `verified: false`。

## 完整回放

从仓库根目录运行：

```sh
cmake -S native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native -j4
PYTHONPATH=python python3 -m mapassist.replay video/video1.mp4 --profile data/private/video2/profile-minimap-red-ring.json --output data/private/video2/video1-minimap-ring-cooled.jsonl
PYTHONPATH=python python3 -m mapassist.replay video/video2.mp4 --profile data/private/video2/profile-minimap-red-ring.json --output data/private/video2/video2-minimap-ring-cooled.jsonl
```

| 录像 | 12 FPS 帧数 | 逐帧观察 | 限频后提示 | 桌面识别 P50 / P95 |
| --- | ---: | ---: | ---: | ---: |
| video1 | 11,134 | 12,596 | 113 | 0.25 / 0.45 ms |
| video2 | 13,032 | 20,156 | 166 | 0.23 / 0.32 ms |

最初按“事件种类＋方向”去重时，video1/video2 分别产生 317/465 条提示。空间跟踪后降到 173/274；再加入小地图五秒最短间隔后降到 113/166。当前约每分钟 7.3/9.2 条，仍须由 Hero 在真机练习场景中判断是否太频繁。

组合配置回放保留上述 113/166 条小地图提示，并分别识别出 3 条“撤退”提示。事件层允许优先级更高的危险信号抢占一秒全局间隔；修正后 video1 的三条危险提示时间为 177.917、237.750、282.083 秒，没有被相邻小地图音效推迟。

## 人工抽查

使用新增的 `--detections-output` 在 video2 的 180、300、420、540、660、780、900、1,020 秒抽帧。共看到 17 个检测框，均落在肉眼可见的红方英雄头像或重叠头像组上；780 秒抽帧中没有可见红方头像，检测器输出 0 个框。另检查了 120 秒比分面板画面，地图存在性门控输出 0 个框。这 9 个时刻是分散抽查，并非随机抽样或逐帧完整标注，不能据此计算准确率和召回率。

可复查单帧，例如：

```sh
PYTHONPATH=python python3 -m mapassist.extract_frame video/video2.mp4 --at-ms 300000 --output build/video2-300s.png --profile data/private/video2/profile-minimap-red-ring.json --detections-output build/video2-300s-detected.png
```

已知限制：重叠头像会合并，当前事件级真值尚未标注；红色阵营主题、地图样式、录屏缩放或 UI 版本变化都可能需要重新标定。video3–5 后来也参与了失败案例复核，当前五场都属于开发数据；新的完整录像才可冻结为留出对局。
