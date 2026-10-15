# PROGRESS

## 开工回执（2026-10-15，feature/field-memory，P0 FieldMemory 评测工具）
- 理解的目标：把「长上下文≠长期记忆」变成可测量数字。造合成事件流 + 三件套评分（existence/temporal/binding/refusal），产出长度衰减曲线、最小对归因、拒答率。不碰 Android/网关生产代码。
- 顺序：任务 0 基线核验 ✓ → 任务 1 数据结构+合成流生成器 → 任务 2 三件套评分 → 任务 3 单测 → 任务 4 validation 报告。
- 最大风险：合成事件流若不够「严格包含」会伪造出衰减曲线；对策是用集合包含断言硬卡，且最小对只准差一条线索。
- 任务 0 结果：Python 基线精确复现 `35 failed / 923 passed / 86 errors / 15 skipped / 29 subtests passed`（命令带 `PYTHONPATH=python`，排除 2 个 fcntl 结构性不可收集文件）。与任务书预期一致，通过。
- 环境备注：本机无 C++ 编译器，native 编译失败，故 P0 走合成事件流绕开（任务书已裁定）。

## 完成记录（2026-10-15，P0 全部四任务做完）
- 任务 1 ✓ `python/mapassist/field_memory_eval.py`（830 行，26 个函数）：`field_memory/v1` L0 记录、确定性合成事件流（种子 20261015，168 条）、受控长度四版（2/5/10/20 分钟，时间前缀保证长版严格包含短版）。只做王者小地图通道。
- 任务 2 ✓ 三件套评分按 existence/temporal/binding/refusal **分组统计不混总分**；最小对按线索全流剥离做归因。
- 任务 3 ✓ `tests/test_field_memory_eval.py` 16 个测试全绿，含三条硬断言（长版严格包含短版 / 最小对只差一条线索 / 拒答集必被拒答）。
- 任务 4 ✓ `validation/assistant/FIELD_MEMORY_0_4_8_2026-10-15.md` + `.json`。

### 实测结果（合成流，检索预算 top_k=12，非真机指标）
| 版本 | 记录数 | 绑定 | 存在性 | 时序 | 拒答 |
|---|---:|---:|---:|---:|---:|
| 2 min | 26 | 100% | 100% | 100% | 100% |
| 5 min | 44 | 85% | 50% | 20% | 100% |
| 10 min | 90 | 55% | 33% | 20% | 100% |
| 20 min | 168 | 40% | 17% | 0% | 100% |

- 最小对：direction/tone/pan/intensity 四条线索各自去掉都掉 25%，**各自独立、缺一不可**，没有哪条能替代另一条。
- 拒答率四个版本全 100%。

### 回归
全量 `35 failed / 86 errors` 与任务 0 基线**完全一致**，`passed` 923 → 939（+16 新测试）。未新增失败。

### 过程中修掉的一个真 bug
`judge()`  originally compared the raw cue value against a derived gold band:
`-0.532 == "左"` was always False, so pan/intensity binding questions failed even on
the unmodified stream. Fixed by deriving the band from the observed value before
comparing.  This is why baseline binding is now 100% instead of 50%.

### 暗卷（自留抽查，执行者视角不可见）
投毒注入一条本不该存在的证据 → 拒答判据如预期不再拒答；删掉目标 APPEAR → 存在性判错；
把时序端点拉开超容差 → 时序判错。三条都翻转，证明判据不是恒真。

### 边界守住
只新增 5 个文件、0 修改：`python/mapassist/field_memory_eval.py`、`tests/test_field_memory_eval.py`、
`validation/assistant/FIELD_MEMORY_0_4_8_2026-10-15.{md,json}`、`PROGRESS.md`。
未碰任何 Android 生产代码、未碰 `glm.py`/`vision_prompt.txt`/`validation.py`。
`non_verbal` 只存 cue_id/tone/direction/pan/intensity 五个参数指纹，无 PCM。

### 下一步（未做，留待后续任务书）
P1 端侧记忆本体（FieldMemoryRecord/Store/Query/Block + 单测 + CueDispatcher/CueArbiter 旁路写入）；
P2 网关注入（memory.py + vision_prompt 规则 + validation.py 截断）；
P3 消消乐通道与跨局偏好。真实设备上的长度衰减需用真机事件流重测，本报告数字不可外推。
