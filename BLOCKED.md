# BLOCKED

## 通关价值排序轮 · 待裁（2026-10-09，主树 `1c09949`）

1. 挂牌采点坐标已按语料实测改掉（2026-10-09 追加轮裁「改常量」这条路）：`CARD_Y_PER_MILLE=94`、双格位 `PAIR_X_PER_MILLE={434,570}`、三格位 `TRIPLE_X_PER_MILLE={366,501,636}`，采点顺序先双格、不成再三格。残余待裁：三格位没有语料样本（20 帧全是双格），是按「以中线为中心、格距 12.9%~14.1% 取均值」推出来的，真三格关首次装机时仍要靠诊断串复核；双格位是实测的，落在 59x50 像素的框内，采点半宽只有 6 像素。
2. 目标「剩余数量」与「已收集满」没读，也没有建模。仓库没有本地 OCR（`docs/plans/合规改造方案.md` 第 168 行口径），计划书第 1 步要的「剩余数量及完成标记」只做到了「收集哪几种」。要裁：完成态走什么方案（数字读数、还是用棋盘上该种动物的存在性做弱推断——后者会撞计划书「不能把棋盘上某种动物数量当作任务要求」那条）。
3. 冰块、银币、鸡蛋、特效类目标一律读不出，按整体弃权处理。计划书第 2 步「补齐棋子与障碍的独立状态」没做，本轮的读数只认基础六种动物。要裁：这类目标要不要进本轮范围，或继续按局部排序兜着。
4. 高亮与播报还没消费目标读数（计划书第 4 步）。本轮只改诊断串 `evidence`，语音与浮层文案沿用原句；「优先消冰」「收集小鸡」这类短句没加。要裁：下一轮做，还是本轮补。
5. 收益没验证（计划书第 5 步）。现有语料带图帧只有 2 份 23 帧、且只有第 3 关一种目标组合，没有目标数量递减的正样本，也没有人工标注的「这步该不该优先」。要裁：出人工标注判对错的现网语料，还是继续只交「读数加接线加红绿」这一层。
6. 性能验收交不出机器读数。计划书要求识别处理 P95 恶化不超过 10%、温升不超过 1 摄氏度，两者都要真机同机对照；本机无 adb。本轮只能证明「没新增模型、没新增截图或推理频率、采点只在棋盘变化分支发生」。要裁：性能项是否挂到装机轮，与 cand2 那只包的读数一起做。
7. 那 7 条 Windows 既存红按任务书不许顺手修，本轮一行未动：`AsrModelStoreTest` 6 条（下载续传与缓存前缀）加 `AssistantTtsCacheTest.sweepNeverFollowsAnOwnedLookingSymlinkOutsideCache`（符号链接扫描）。要裁：单开一轮修，还是在门禁口径里长期把这 7 条列为豁免名单——现在的口径是「红的必须恰好是这 7 个名字」。
8. 一次性脚本目录 `_scratch_diag/` 在主树不在 gitignore 覆盖内（`git check-ignore` 退出码 1）。要裁：往 `.gitignore` 加一行（属白名单外，本轮没动），还是继续靠「不 commit」兜。
9. 相邻旧事登记，不属本活：`SenseField-cand-ag` 那 21 个未提交文件是否 rebase 到 `1c09949`、cand2 那只包的装机读数、DHCP 地址漂移导致局域网下载链接每轮要重给——这三条沿用上一棵树的待裁第 22 至 27 条，未在本轮处理。

## 标定操作卡 · 挂牌采点（2026-10-09 20:23:39 写，21:51:09 按语料实测改版重写）

读数出现在三处，同一串格式：实况审计行 `BoardRecognized` 的末尾（`Match3LiveService.java:553`）、工具页「取样并播报」输出里（`Match3ToolsActivity.java:371`）、走法证据串里（`Match3MoveRanker.java:61` 与 `:65`）。三种形态：

- 单套布局某格不是动物：`goal_strip=abstain reason=slot2=unknown slots=2`
- 双格与三格两套都没读通：`goal_strip=abstain reason=no_layout_matched pair=slot1=empty triple=slot1=gap slots=2`
- 可信：`goal_strip=trusted kinds=YB slots=2`（kinds 就是要收集的种类：R 红狐狸、O 棕熊、Y 小鸡、G 青蛙、B 河马、P 紫猫；`slots` 是采用的那一套的格数）

采点先试双格位，双格整体读通就采用；读不通再试三格位；两套都不成才弃权，弃权原因里两套各自的失败格都带上。`pair=`／`triple=` 后面的 `slotN=<词>` 就是那一格采到的颜色分类结果，槽位从 1 数起。对照改法：

- `empty`：这点落在底色或条框外面，往该格中心挪。
- `unknown`：字符是 `.`，落在挂牌上但颜色匹不到任何一类，多半压在描边或数字上。
- `gap` 或 `non_swap` 或 `ice`：采进棋盘了，把 `CARD_Y_PER_MILLE` 往小调。
- `learned_label`：字符是 `1` 到 `9` 或 `a` 到 `z`，那一格是自定义皮肤，不在基础六色里；换一关同种动物的挂牌复现一次再定。
- `code_X`：X 是上面没列到的字符，原样贴回来。
- 两套都 `empty`：多半是 y 整体偏了或挂牌被动画挡住，先动 `CARD_Y_PER_MILLE`。

常量在 `Match3GoalStrip.java:21`（纵 94‰）、`:22`（双格 434‰／570‰）、`:23`（三格 366‰／501‰／636‰），千分比是为了避开浮点；一次只动一个量，动完复跑

`cd android && ./gradlew :app:testDebugUnitTest --offline --rerun-tasks`

口径不变：`tests ≥ 432`、skipped 恒 0、failures ≤ 7 且名字仍是那 7 条 Windows 既存红（改版实测读数：files=64、tests=432、failures=7、skipped=0、errors=0）。落点本身有回归钉：`Match3GoalLayoutTest.sampledPointsStayInsideTheMeasuredCardBoxes` 与 `theTripleIsCentredOnTheSameAxisWithTheMeanCardSpacing` 会把常量改飞的情况判红（实测过：把纵坐标改回旧的 50‰ 即红）。标定成功的机器判据是审计行从 `abstain` 变 `trusted`；认出的动物与挂牌肉眼所是否一致只能人工看，JVM 侧证不了，别写成「已真机验证」。

兜底不变：采用的那套里只要有一格不是基础动物就不采用，两套都不成即整体弃权，排序逐位等价旧行为，不会把底色补成目标，也不会把两套布局拼在一起。
