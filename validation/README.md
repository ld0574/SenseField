# 验证记录索引

先看[当前验证状态](STATUS.md)，再按问题进入对应主题。92 份原有记录完整保留，新增批次继续按主题收录；每个目录提供按日期排列的索引，历史包的通过结果和失败记录各自保留。

## 按主题查找

| 主题 | 记录数 | 查什么 |
| --- | ---: | --- |
| [开心消消乐](match3/README.md) | 10 | 棋盘定位、无提醒、任务价值、交换推荐、语音、高亮与设置 |
| [王者荣耀](honor/README.md) | 4 | 操控期间漏报、敌人再出现、提醒频率与范围 |
| [声音与提示呈现](audio/README.md) | 12 | 离线语音、TTS、音色、混音、声像、震感与听感 |
| [发热与性能](performance/README.md) | 13 | 负载、像素复制、推理缓存、真机发热与对照试验 |
| [AI 助手与部署](assistant/README.md) | 13 | ASR、视觉模型、回复时效、取消、默认开关与部署 |
| [Android 与界面](android/README.md) | 10 | 授权、模拟器、真机冒烟、说明学习与大字布局 |
| [升级与发布验收](releases/README.md) | 6 | 自动更新、CDN、Gitee 分发与 GitHub 发布核对 |
| [模型与数据证据](models/README.md) | 17 | 开发集、标注、定位、导出与 parity；含两个固定入口 |
| [验证方法与试用工具](protocols/README.md) | 7 | 真机记录模板、实声门禁、诊断工具与玩家试用 |
| [历史状态快照](archive/README.md) | 1 | 原状态页的完整批次记录 |

## 当前入口

- [0.4.5 目标读取与任务价值排序](match3/MATCH3_GOAL_VALUE_0_4_5_2026-10-09.md)
- [0.4.5 整段无提醒的排查与修订](match3/MATCH3_NO_HINTS_0_4_5_2026-10-09.md)
- [0.4.5 按游戏区分设置与反馈](match3/MATCH3_DIAGNOSTIC_CONTEXT_0_4_5_2026-10-09.md)
- [0.4.4 GitHub 发布记录](releases/GITHUB_RELEASE_0_4_4_2026-10-08.md)
- [符合度复核与迭代证据](../docs/plans/符合度改造方案.md)
- [版本说明与交付索引](../docs/releases/README.md)

## 常用工具

| 需要做什么 | 入口 |
| --- | --- |
| 记录一局真实体验 | [真机与玩家记录模板](protocols/SESSION_TEMPLATE.md) · [玩家试用说明](protocols/PLAYER_TRIAL_KIT.md) · [离线记录表](player-trial.html) |
| 生成／比较诊断报告 | [诊断报告](protocols/DIAGNOSTIC_REPORT.md) · [两局对照](protocols/DIAGNOSTIC_COMPARE.md) |
| 测量实际发声延迟 | [延迟 CSV 空模板](LATENCY.example.csv) · [最终证据格式示例](FINAL_EVIDENCE.example.json) |
| 自动收回手机日志 | [ADB 诊断收集](../docs/development/android-adb-diagnostics.md) |
| 先自动验证消消乐改动 | [消消乐回归入口](../docs/development/match3-regression.md) |

模型资产使用的两个固定证据入口为[模型接入记录](MODEL_PIPELINE.md)和[video10/11 审核记录](VIDEO10_11.md)，模型目录也提供导航。原始诊断、截图与机器输出保存在本地 `validation/private/`；这里索引公开汇总。

## 维护方式

1. 新批次记录放进对应主题目录，沿用主题、版本、日期组成的文件名，并更新该目录的索引。
2. `STATUS.md` 只写当前结果、对应证据和待验证项；逐批过程写在专题记录中，历史状态快照进入 `archive/`。
3. 同版本修订明确 APK／源码摘要，历史记录保留原日期、版本、失败与验收边界；不会用新一轮通过结果覆盖旧一轮失败。
4. 发布使用说明在 `docs/releases/`，部署步骤在 `deploy/assistant/`；本目录负责验证结果与复现证据。
