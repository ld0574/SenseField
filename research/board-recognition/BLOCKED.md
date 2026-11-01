# BLOCKED（待裁决清单）

## 1. C4 VLM 未跑：无 OPENROUTER_API_KEY
书内约定「环境变量有 OPENROUTER_API_KEY 才跑，无 key→跳过写 BLOCKED.md」。本机无此 key，C4 跳过，results.json 记 `C4_vlm_skipped`。若领导配 key，跑法：`python -c "import sys; sys.path.insert(0,'src'); from src import c4_vlm; c4_vlm.available() and print(c4_vlm.run_frame(...))"`（骨架已写好 src/c4_vlm.py）。不阻塞交付。

## 2. 真机截图未提供（data/real 为空）
书内约定「<10 张由领导执行前放 data/real/；若空→跳过真机部分，结论标仅合成」。执行时目录为空，全部结论基于合成帧（素材取自 isghost 复刻工程，与真实游戏同套美术）。**影响：99.96% 是合成数字，上真机前不能当承诺值。** 建议补 10-30 张（含特殊棋子局），放好后重跑 `python src/eval_all.py --c2 --c3` 即可出真机列。

## 3. 合成集特殊棋子覆盖不全
复刻工程素材只有炸弹（爆炸棋原型）+冰障碍，**没有 4 连/5 连/活力鸟的形态素材**（Texture/231/ 只有 kai_231_bomb_* 系列）。C2 的 special_recall=1.0 只代表「炸弹+冰」两类。真机完整覆盖需：从真机截 4 连/5 连/活力鸟的格子图补进素材库重渲染重训（11 分钟）。不阻塞本轮结论（报告已注明）。

## 4. 顺手活（书内点名，不自行处理）
- 顺手修 Match3Sampler 已知问题：C0 移植暴露了基线两个真实缺陷（空格被 HSV 桶判成蓝棋子、同色相棋子只报字母区分不了）——修复方案在 C2 路线里已覆盖（CNN 直接分类 10 类含 empty），**不建议在旧代码上打补丁**，待裁决是否保留 C0 兜底路径的这两处行为。
- 把 POC 结论直接改进 App：未动（书内禁止改 App 代码）。下一步清单在 report.md 末节（TFLite 集成估 1-2 天）。
- 新增 pip 依赖：未新增（torch/torchvision/ultralytics/opencv/pillow/numpy 均已有；torchvision 0.28 的 weights=None 接口与旧文档不同，已在代码里适配，非新依赖）。

## 5. C3 YOLO 训练按书内限时规则降档
50 epochs 在 CPU（imgsz 640 单 epoch >4.5 分钟）全程估 >60 分钟，按书内「单训超 60 分钟→降到 10 epochs 并标注」降到 10 epochs，并进一步降 imgsz 640→320 控时（报告已标注「未训足，结论仅供参考」）。**此降档直接导致 C3 呈死识别器状态（mAP50=0.147）**——若领导认为 C3 结论下死太快，可给 GPU 或更长时间预算重训 50 epochs@640 复核（预计 4-5 小时）。当前判断：即使训足，整屏 YOLO 的标注成本（5-10 倍于逐格）与密集小目标场景不匹配，死路结论大概率不变（见 sources.md 问①证据链）。
