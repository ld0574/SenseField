# PROGRESS

## 开工回执（2026-10-04）
- 目标：听野 L1 棋盘识别方案对比 POC——C0 基线移植 vs C1 多尺度模板 vs C2 格子 CNN vs C3 整屏 YOLO（C4 VLM 视 key 而定），用合成 200 帧（+data/real 真机图若有）出数字，回答「有没有更好更可行的方案」。
- 顺序：T0 核验✓ → T1 调研 sources.md → T2 渲染 200 帧+真值+联图 → T3a 五候选代码 → T3b 训练+eval+两条反向验证+results.json → T4 report.md+BLOCKED.md。
- 最大风险：C0 移植失真（Java Bitmap 像素循环 vs numpy 批处理、HSV 阈值 hue 域 0-360 vs cv2 0-180 换算）——对策：阈值逐行对照 Java L138-147/L273，移植函数加注释标 Java 行号，合成集上 C0 若异常高/异常低先查换算。
- C0 移植对应（Java 行号）：autoDetectBoard L261-277（step=宽/160、mask=v<0.45 且 hue 170-300，hue 按 Java 0-360 域；cv2 需 ×2 折到 0-180）；detectBoundsFromMask L219-255（行/列剖面取最长密集带，输出百分比边界）；classifyCell L116-148（模板优先→HSV 桶：S<0.18 或 V<0.15→再走模板，否则 h≥345∨h<14→R，<38→O，<68→Y，<165→G，<262→B，else→P）；matchTemplate L151-169（裁 half*4 正方形→缩 16×16→MAE，best>30 判未识别）；meanAbsDiff L197-210；模板名→字母 nameToLetter L172-181。
- 素材结论：animals/ 8 动物（bear/bird/cat/chicken/fox/frog/horse 等，~1000px，色彩各异）；炸弹 kai_231_bomb_*（横条 774x82，需裁方形做特殊棋）；obstacles/ 72px 三件（chain/crate/ice）。合成类名：bear,bird,cat,chicken,fox,frog,horse,bomb,obstacle,empty（horse 暂弃用，动物 7 类＋特殊＋障碍＋空）。
- 任务0 核验：python 环境 5.0.0/2.13.0+cpu ✓；animals 8 个 .png ✓（≥7）；基线两文件 sha256 与书一致 ✓；SenseField git status 空 ✓。

## 素材色表（alpha 内均值 BGR，2026-10-04 实测）
bear(56,108,173)蓝 / bird(207,158,111)橙红 / cat(212,88,170)粉 / chicken(71,188,232)黄白 / fox(66,91,211)蓝紫 / frog(91,181,103)绿 / horse(202,171,78)金 / bomb 横条 / chain(80,81,81)灰 / crate(59,118,159)青 / ice(178,154,112)浅金

## 交付状态（2026-10-05 完成）
- T1 调研：sources.md 12 来源（验收 12∈[5,15]、全含 http）✓
- T2 合成：200 帧 14160 格 10 类全出现；labels.csv 14161 行（表头+14160）✓；contact_sheet 领导目测用 ✓；auto_detect 200/200 检出、f000 边界与真值逐位一致 ✓
- T3 评测（合成 f100-f199 测试集 6978 格，CPU）：
  - C0 移植 grid_acc=94.34%（空格 0%、同色相混淆——基线真实缺陷，非移植失真）
  - C1 多尺度模板 grid_acc=87.73%（bird 79%/chicken 64% 偏低，模板匹配不如 HSV 桶稳）
  - C2 MobileNetV3-Small grid_acc=99.96%（20 epochs 11 分钟，val_acc 0.9993；p50 726ms 串行，可批处理）
  - C3 YOLOv8n grid_acc=5.66% 死识别器（限时降 10 epochs@320，mAP50=0.147，全判 empty；conf=0.1 三帧 12 框证明管线正常是训练不收敛）
  - C4 VLM 跳过（无 key，记 BLOCKED.md）
- 反向验证：DEADCHECK C0/C1/C2 same=False（过）、C3 same=True（抓出死识别器）；FLIP-DETECT C0/C1/C2 各 4/5（过，要求≥3/4）、C3 0/5（如实记录）
- 复跑一致性：两次独立 eval，grid_acc/special_recall/n_cells/p50 完全一致（DETERMINISM PASS，results_run2.json 存档）
- 推荐：C2 格子级 CNN 主路线 + C0 兜底；唯一工程项是 C2 批处理/ TFLite 集成（估 1-2 天）
- 待领导：真机截图 10-30 张放 data/real/ 重跑 eval；BLOCKED.md 5 项待裁决
- 交付物：report.md（结论）/ sources.md / models/results.json / src/*.py / data/synth/contact_sheet.png
