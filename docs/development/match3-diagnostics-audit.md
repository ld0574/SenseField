# 消消乐已有实局自动复盘

工具为 `python/match3/diagnostics_audit.py`，服务于[自动复盘 Goal](../plans/消消乐实局自动复盘.md)。输入现有导出 ZIP、解压目录或单个事件 JSONL；本地运行，不控制游戏。输出按会话内的目标、动作、提示版本与时间关联，定位任务价值、识别弃权、门禁等待及语音取消的证据。

## 运行

在仓库根目录运行，替换下面的本地输入路径。输出目录须在原导出目录外，避免覆盖原来的`summary.json`。无需手机连接或重新玩关卡。

```sh
PYTHONPATH=python .venv/bin/python -m match3.diagnostics_audit \
  '/本地路径/队友导出目录' '/本地路径/灰毛球导出.zip' \
  --output validation/private/match3-diagnostics-audit
```

重新安装本项目的 editable Python 包后也可使用 `match3-diagnostics-audit` 入口。模块读取当前仓库的规则目录、参考文档及有限规则原因；这些摘要属于**审计工具使用的依据**，不是原录像 APK／源码的摘要。原导出没有 APK 摘要时继续为 null，不将历史记录绑定为当前候选。

输出包括：

| 文件 | 内容与使用范围 |
| --- | --- |
| `summary.json` | 脱敏计数、首次复核段、最长门禁段、输入成员聚合 SHA 和完整报告 SHA |
| `report.json` | 脱敏的全部排名、提示、回调、收益观察、明确图片窗口及事件行号／相对时间 |
| `report.md` | 便于阅读的计数和长门禁说明 |
| `inputs.private.json` | 本地输入路径、原会话 ID 和所读取成员 SHA；只留在私有目录 |

输出报告只保留有限类型／规则原因、游戏数字、坐标、SHA、事件行号和相对时间，不复制原日志自由文本、设备名称、会话 ID、绝对时钟、原图片或文件路径。未知原因不在有限集合内时写作 `unrecognized`。生成的详细报告仍建议保留本地；共享摘要即可定位案例。

## 关联与证据口径

输入必须是单一会话，外层单调时钟与相对时钟一致；排名／提示／收益观察的引擎顺序不能倒退。TTS 与图片窗口来自异步线程，可在文件中较晚写入较早的时间；按准确 cue ID／窗口 ID 关联，回调不能早于请求。首次真实运行发现两份导出各有一次相差 1ms 的异步回调顺序，保留首次拒绝记录后修正了对引擎与异步事件的区分，没有将所有事件重新排序成假想动作。

提示只借用此前 2400ms 内、同动作、同目标键的 top-1，几何失效立即断开。收益观察还需要相同提示版本、动作、基线目标及 12 秒窗口。匹配只证明日志关联；`exact_visual_exchange_only` 继续表示软件看见两格互换。计数减少、多步、重置、缺失数字、超时、未观察到交换分别保留，采纳率和实际收益率始终为 null。

图片只关联明确接受的窗口 ID，不用最近图片填补缺窗；窗口里的成员逐 SHA 固定，缺图为 missing。前／后图片数量仅描述保存结果，不证明玩家操作。相同会话的完整副本重复导入不重复计数；成员有差异的副本拒绝合并。原始 ZIP 不解压、不改写。

长门禁标准预设为至少 10 秒。只有有正常结束、无已记录丢事件、帧／图片计数相符及连续帧支持时，记作“有采样帧与门禁日志支持”；缺失或截断为 incomplete，未观察结束的尾段保留右截断。门禁为 popup 等正常流程时也会列出，应结合具体原因看。任何一种门禁或回调状态都不证明整局无声音。

## 任务价值复核

| 标记 | 解释 |
| --- | --- |
| `goal_identity_unknown`／`goal_count_unknown`／`goal_event_mode_unknown` | 身份、数字和事件模式分别缺证据，不能互相补齐 |
| `top1_zero_certain_task_units` | 当前首选日志预测的确定任务下界为零；不等于无用，接触和准备仍可能有价值 |
| `low_remaining_top1_without_logged_task_contact` | 全部目标数字已知，唯一未完成任务剩 1～3 个，首选日志的确定收益及该任务接触均为零 |
| `special_alternative_*_review` | 记录的备选特效有更高的确定单位或更多任务接触，需核对排序；不自动宣布权重错误或特效应无条件优先 |
| `no_admitted_candidate_logged` | 该次排名没有已准入候选；不代表游戏没有合法交换 |

候选范围仅是日志实际保存的部分，通常为 top-3。没有记录到彩虹鸟不代表棋盘没有鸟。饼干的接触格数与损伤／清除任务量分别保留；灰毛球的邻接收益、未见底层和跳跃落点仍按[规则参考](../research/开心消消乐元素规则参考.md)处理。复核段只合并相邻、同目标数字／步数、同几何阶段且间隔不超过 2400ms 的排名，不把多个更新计成多个独立实局。

## 本轮工程验证

45 项新反例和 45 项既有飞轮／规则／评测检查通过，共 90 项、另 8 个 subtest。覆盖目标冲突、未知模式、几何／时间／会话隔离、旧动作、多步／重置、丢事件／缺帧、缺图、异步回调、敏感文本、冲突副本、原导出保护及重建摘要。

```sh
PYTHONPATH=python .venv/bin/python -m pytest -q \
  tests/test_match3_accuracy_eval.py tests/test_match3_flywheel.py \
  tests/test_match3_rules.py tests/test_match3_regression_gate.py \
  tests/test_match3_diagnostics_audit.py
```

四份冻结历史诊断的结果见[复盘工程记录](../../validation/match3/MATCH3_DIAGNOSTICS_AUDIT_2026-10-13.md)。本轮仅添加离线工具，Android 实现、识别资产、原阈值／预算及已验 0.4.6 APK 保持；工程源码摘要仍为 `dd193c850324d91d94a59ee4c30d20c83b0df12142d926e48053babfad586d4f`，APK SHA 为 `340e3b0f8547944287eea311207d5ebbee5ac28757f8009a982cbbcd84f9e56d`。无需重新运行同候选成本或泡机门禁。
