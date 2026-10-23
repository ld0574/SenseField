# 小地图绿色外圈识别玩家自身：快速抽样

更新时间：2026-09-30

## 结论

绿色头像外圈是有用的自身候选线索，但这次抽样不足以证明它能单独可靠定位玩家。10 个获准视频各抽 2 帧，共 20 帧；人工目视在 17 帧看到一个明显或部分可见的绿色头像外圈（85% 的抽样帧可见率）。其余 3 帧中，两帧明确处于死亡倒计时，一帧被横贯地图的紫色信号遮住。这个比例是稀疏人工抽样中的可见率，不是检测器 recall。没有逐帧候选真值，因而不能报告 precision；本轮没有测得模型的 precision/recall。

17 个可见样本里各有一个占主导的绿色外圈，未见两个完整绿色外圈互相竞争。数个圈只剩局部弧段，或与另一张头像、红色敌方标记、信号效果叠在一起；“一个候选”仍不等于有充分证据确认它就是自身。单纯按绿色像素阈值会把地图纹理和特效提成高分候选，不能据此直接触发提示。

建议训练独立的 `minimap_player` 类，并以绿色外圈作候选预筛。检测缺失时应记录为未观测／死亡／遮挡，短时沿用上一位置并依 TTL 恢复；不能把“没找到绿色圈”直接当作玩家位置丢失或死亡事件。

## 抽样方法

- 只读原始允许来源：video1hd、video2hd、video3hd、video4hd、video5hd、video7、video8、video10、video11、video13。没有读取或运行 video9、video12。
- 每段只看两个固定时间点，没有遍历连续录像。抽帧使用 `python/mapassist/extract_frame.py` 的 `extract()`；video1hd–video5hd、video8 按记录的 90° display rotation 转成显示方向，其余为 0°。
- 使用每段现有的 minimap widget ROI。video1hd–video11 使用 intake 记录中的 `[left,top,right,bottom)` 边界；video13 使用其专用 `[x,y,width,height]` ROI `[80,70,310,280]`。没有修改 ROI。
- video13 仅作目视诊断，不据此选择颜色阈值或改 ROI。

| 来源 | 抽样时间 | 目视结果 |
| --- | --- | --- |
| video1hd | 120s、600s | 两帧均有；120s 外圈与右下角红色头像重叠，只露出部分绿弧；600s 较清晰。 |
| video2hd | 180s、800s | 两帧均有；800s 两张友方头像靠得很近，外圈只剩局部绿弧。 |
| video3hd | 300s、1200s | 300s 有一个，靠地图边缘且叠有信号效果；1200s 大型紫色斜线覆盖地图，无法确认外圈。 |
| video4hd | 180s、800s | 两帧均有；800s 与另一头像及地图标记拥挤。 |
| video5hd | 180s、750s | 两帧均有，至少一帧靠近小地图边界。 |
| video7 | 180s、780s | 两帧各有一个主导候选。 |
| video8 | 180s、720s | 两帧各有一个清楚的绿圈。 |
| video10 | 180s、780s | 180s 可见；780s 顶部显示死亡回放、倒计时 27 秒，无绿圈。810s 的复核帧已复活，地图边缘重新可见绿圈。 |
| video11 | 120s、800s | 120s 可见；800s 顶部死亡倒计时 6 秒，无绿圈。 |
| video13 | 180s、1200s | 两帧各有一个外圈；1200s 圈被白色矩形和邻近头像遮挡。 |

## 阈值原型与失败来源

在 widget crop 上试了一个仅用于排序候选的 OpenCV HSV 环形分数：`H=45..80`、`S≥80`、`V≥70`，搜索半径 `11..20 px` 的圆环，计算圆环上的绿色像素占比。可见外圈的绿色像素多数落在 OpenCV hue 约 `58..72`，半径约 `13..20 px`。这些值可用作候选预筛的起点，尚未通过逐帧评测。

单一环分数不适合做最终判定：

- 有些地图边缘、绿色地形或地图装饰产生的候选分数高于头像外圈；例如 video1hd 600s 的候选排序中，背景候选高于目视真环。
- 头像重叠、信号线和攻击特效会截断绿色弧段，使真环分数降低。
- 死亡期间确实没有自身头像绿圈；video10 780s 和 video11 800s 都是明确例子。
- 地图图标的蓝／青外圈、红色敌人外圈、绿色特效与地图边缘都应纳入 hard negatives。扩展到相邻 UI 区域会引入按钮和队友头像，应继续按各段已记录的 widget ROI 裁剪。

因此不建议现在给生产端设一个固定的绿色占比门槛。可先用 HSV 生成圆环候选，再训练 `minimap_player` 外观分类器；样本要包括完整圈、被遮挡圈、死亡帧，以及草丛／地图绿纹／信号与技能特效等负例。使用逐帧人工框单独计算 precision、coverage 和 death/occlusion 分组结果，再决定提示门槛。

## 可复现原型

新增脚本 [probe_minimap_player_green_ring.py](../../training/probe_minimap_player_green_ring.py) 固定了上述 allowlist、时间点、显示旋转和每段 ROI。它只输出 widget crop 联系表和 HSV annulus 候选分数，不运行模型，也不输出“玩家已识别”结论。脚本拒绝任何非 allowlist 的视频 ID，因此不会打开 video9 或 video12。

```bash
.venv/bin/python training/probe_minimap_player_green_ring.py \
  --out-dir build/minimap-player-green-ring-probe
```

默认 allowlist 的 10 来源／20 帧完整脚本运行已成功，生成了 crop sheet 和候选 JSON；video13 的专用 xywh ROI 也单独 smoke 验证过。另用相同抽帧函数与记录 ROI 对 20 个报告样本做了人工目视。输出写到临时分析目录，未写源视频。当前代码层没有 `minimap_player` 类；可视化检测输出类型为 `main_enemy`、`minimap_enemy`、`danger_ping`，HD profile 中 `minimap_red_ring` 仍关闭。
