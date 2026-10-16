# 安卓提示链路复盘

两款游戏共用 `CueDispatcher` 的生命周期门控。消消乐使用[逐提示复盘](match3-diagnostics-audit.md)，王者使用现有 `mapassist.diagnostic_report` 汇总会话、播放、负载和截图覆盖。原始 ZIP、画面及可配对的 cue ID 留在忽略的私有目录。

## 王者离线报告

```sh
PYTHONPATH=python .venv/bin/python -m mapassist.diagnostic_report \
  '/本地路径/诊断.zip' \
  --output-dir validation/private/honor-diagnostics
```

JSON 和 HTML 保留 cue ID 供本地配对；HTML 会内嵌诊断画面。对外分享前另行脱敏，只发布必要计数和摘要。工具兼容旧 `audit.message` 的 `CueDispatch`／`CuePlayback`，也读取新增的同名结构化事件。

结构化请求记录身份、事件键、类别、优先级、请求／接收渠道、创建和到期时间。王者每次分发另外记录 `CuePolicy`：分发时的类别准入、有效渠道、保存的两字及近区震动开关。近区原有 `NearZonePresentation` 继续记录请求生成时采用的措辞和密集状态；与分发时设置快照分别使用，避免将之后的开关状态补填为当时的偏好。旧记录没有设置或 APK 摘要时保持缺失。

报告的 `software_channel_states` 按渠道汇总完成、取消、到期、设置关闭、冷却、渲染失败和缺回调。队列替换、驱逐、抢占及生命周期取消保留原始终态。同步回调可以先于分发事件写入，关联下界采用请求创建时钟；请求前回调、错误事件键、未获准渠道的正常回调和到期后开始另记异常，原始计数仍保留。重复开始／终态也分别报告；截断会话的未完成请求记录 `right_censored`。

`STARTED`／`COMPLETED` 只表示软件回调。`callback_missing`／`started_without_terminal` 表示记录不足，不能据此认定静音。外部录音或听感仍使用独立证据。

## 固定输入成本入口

`HonorPerceptionReplayInstrumentedTest`、`Match3PerceptionCostInstrumentedTest` 和 `Match3FunctionalCostInstrumentedTest` 仅在模拟器上读取明确推送的私有冻结清单；缺清单会跳过，主动选择运行时必须核对执行数和零跳过。清单含原始成员摘要、输入 SHA、来源与非独立开发范围。源码摘要随运行参数保存。

王者入口冻结现有 512 模型、profile、ROI、年龄预算及逐类阈值，保存原始检测、当前事件状态、native 阶段墙钟、worker 与全进程 CPU。抽样 JPEG 重建输入不等同于原始采集像素；稀疏画面不补造成连续轨迹。历史完整观测流另用 `mapassist.diagnostic_replay` 按原时间戳检查跟踪和近区事件。

消消乐入口记录定位后的采样、确认／HUD 和规则排名成本；其固定图工作量每张图会清空 HUD 缓存，不能当作实局的完整功耗。两种入口都排除游戏、采集与实际播放，不用于温升或外部发声结论。

`Match3FunctionalCostInstrumentedTest` 另外读取 `baseline-assets.json` 所绑定的旧固定／反馈／HUD 目录，在同一进程交替测量旧／新目录；三组均需满足墙钟与 worker P95 增幅 ≤10%。它用于区分局部图库增量与整包开销，两者分别记录。默认回归不暗中依赖私有清单，专项运行须主动选择对应类。
