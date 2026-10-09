# 主画面边缘复核公开记录

更新日期：2026-09-30

## 复核终态与数据边界

本批次共有 160 张终态图片：47 张为 `corrected`，113 张为 `negative`，合计 114 个框。完成审计保存在 Git 忽略的私有目录：

`data/private/main-edge-review-v1/review-batch-v1/review-completion-audit.json`

机器红候选与人工诊断的开发对照为：Precision **31.51%**、Recall **65.79%**、方向正确率 **73.97%**。由于输入是候选分层后的诊断批次，这些数值只能作为开发诊断，不能作为独立留出成绩或发布质量结论。

抽查可见标签时发现，候选中混有顶部计分、FPS、其他 UI 红色碎片和真正血条。小兵、野怪也可能有相似血条；镜头漂移还会使画面中的目标不等于玩家附近的威胁。因此，本批次全部降级为 `main_red_candidate` 或 `hard negative`，不能称为 `enemy hero` 真值，不能直接训练敌方英雄分类器，也不能据此宣称主画面英雄检测已经完成。它仍可用于候选生成器诊断和困难负样本挖掘。

## 当前发布边界

主画面边缘分支默认关闭，不进入 `0.3.0-alpha.1`。本记录中的候选、框和指标只保留用于诊断与后续标注设计。

## 下一版标签 schema

下一版必须在上下文充分的前提下重新标注：

- 每个框必须属于以下一个类别：`enemy_hero_bar`、`non_hero_red_bar`、`ui_or_effect`、`uncertain`。
- 每帧必须记录 `player_screen_anchor`、`camera_relation`、`hud_valid`。

## 提示门

主画面提示只有在以下条件同时成立时才可继续跟踪和发声：

- 上下文分类明确为 `enemy_hero_bar`，`non_hero_red_bar`、`ui_or_effect` 和 `uncertain` 均静默；
- `player_screen_anchor` 新鲜度不超过 250 ms；
- 小地图玩家位置新鲜度不超过 500 ms（仅作辅助）；
- 目标满足 `enemy near` 关系；
- 玩家处于 `alive` 状态，HUD 有效，镜头处于 `follow` 关系，且目标仍在配置的外围视野带。

任一条件缺失，都必须清除 track 并保持静默。

## 仍需完成的验收

重新标注和接入后，仍需独立对局、PyTorch／ONNX／ncnn parity，以及真机上的双分支验收。未完成这些门禁前，不得启用主画面提示。

## 封存约束

禁止查看或解封 `video9`、`video12`。
