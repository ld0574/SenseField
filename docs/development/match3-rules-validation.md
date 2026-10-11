# 消消乐规则驱动改造工程验收记录

2026-10-11。执行 [规则驱动最大价值 Goal](../plans/消消乐规则驱动最大价值改造.md)，实现及全部规则覆盖见 [规则引擎说明](match3-rules.md)，规则输入为 [开心消消乐元素规则参考](../research/开心消消乐元素规则参考.md)。

规则实现与限定工程验证已完成：38 项 Python、549 项 JVM、80 项 Debug 原生检查通过，同输入成对软件 P95 增幅通过预声明的 10% 上限。完整 Debug 报告继续为 `passed: false`，保留两条已有颜色标签分歧。工程收口不改变独立实局、正式 Release 或实体手机的待验收状态。

## 固定输入与源码

最终回归、producer 和成本报告的引擎源码摘要一致。`source_sha256` 按 `scripts/verify_match3_regression.py::source_digest()` 计算：路径与文件内容共同参与摘要，覆盖 Java 源码及测试、相关构建配置、Match3 Python 脚本、固定 UI／gallery／规则资产和规则参考；不是整个 Git 工作区的摘要。

| 项目 | SHA256 |
| --- | --- |
| 最终引擎源码 | `33fb3ffa835f9a1befe509aa3b3c0494a2f9be989b5177ab42d002285d00c604` |
| 规则参考 | `2f115d9253a0e42eb958cff5ea637a48b6375246f9a9369c87f4c03fecb723b1` |
| 新规则目录 | `289238c63dfb988fe251bd0bc4478416bdaf36d8f44ce024608fb48d4eb37ffb` |
| 保留的原 gallery | `56d8accbab632908e4ea849cccedceb6918d6fa417590c4277b0d59f96933d3c` |
| 开发回放 manifest 文件 | `0569668ecc48d98340c3ca252b7e03f2536920cb6b1a4f135898d79cd0587a63` |
| 成本 manifest 文件 | `3539c9fcb0042c6cb5991ccfb82c46835bae8eca6893d948863d86eacb197d4d` |
| 历史工程 fixture 清单摘要 | `4df4fcdb5061379169cdf9f97b49277adbd4446a295701b98d4ce3f2e3e2044b` |

开发与成本 manifest 用途不同，引用的 10 张原始帧逐张 SHA 一致；每帧摘要保存在私有报告中。43 张历史 fixture 图片均按原 `fixture-sources.json` 校验，图片与来源文件共 44 个文件纳入清单。原 gallery、标签和历史失败记录保持原样。

成本对照的 ranker／value／frontier 是 `759f26a2c2915a6389818c52253d7252f3ee7a57` 的精确类名替换快照，只用于 androidTest。汇总脚本逐文件验证其内容，生产代码不使用基线快照。

## 最终工程结果

下列证据均位于 `validation/private/match3-rules-goal/`，不纳入公开仓库；干净克隆不包含游戏画面、逐格真值和回放 JSON。

| 检查 | 最终结果 | 私有记录路径 |
| --- | --- | --- |
| Python 飞轮／规则／评分／门禁测试 | 38 项通过 | `python-complete.txt` |
| JVM | 549 项，失败／错误／跳过均 0 | `cost-permissions-final/report.json`、`debug-permissions-final/report.json` |
| Debug 构建与原生用例 | 构建通过；80 项通过、跳过 0 | `debug-permissions-final/debug-build.txt`、`debug-instrumentation.txt` |
| 真实引擎 producer | 另 1 项通过，生成固定帧的动作／事件回放 | `debug-permissions-final/debug-accuracy-engine.txt`、`debug-predictions.json` |
| 成对软件成本 | `passed: true`，四项 P95 增幅均 ≤10% | `cost-permissions-final/report.json`、`rules-cost.json` |
| 开发回放与改造前识别输出相等 | 10 盘、743 格、5 轴无差异；旧统计相等 | `engineering-verification.json` |
| strict 开发准确率 | **仍失败**，已有 2 条颜色分歧 | `debug-permissions-final/debug-accuracy.json`、`debug-accuracy-score.txt` |
| 最终 Debug 综合报告 | **`passed: false`**，最后的准确率评分步骤退出 1 | `debug-permissions-final/report.json` |

工程用例独立指定前置盘、合法动作、后继状态和排序预期。覆盖冻结／锁链的权限差异、小鸡任务模式、方向特效、非三连特效交换、多层损伤边界、孵化机会、暂时移除、对象救援去重、钥匙送达、已知拓扑连消、最后一步收益及搜索超限回退。Python 同时覆盖状态／任务复核、训练会话隔离、有限规则绑定和 typed 评分的源码／规则／输入来源约束。

参数缺失时保留未知：大炮只报确认触发与方向；未知蜂蜜／牢笼损伤量不减层；未确认的特效传播、随机补子和动态地板不进入精确后继。已确认 `NO` 的移动权限独立于损伤层数，最终修复及用例避免原 48 格饼干退成 `UNKNOWN`。内置 Coach 同步修正规则表述和动物名称。

规则目录登记参考全部 67 个条目：17 `direct_effect`、14 `partial_effect`、2 `direct_opportunity`、20 `state_only`、14 `pending`。这表示不同程度的工程支持；所有新规则的视觉与实局验收仍为 `pending`，逐项缺证据清单见规则引擎说明。

## 同输入成对成本

运行环境为 Android API 34 的 `ranchu` 模拟器。两种路径在同一 APK 内共用识别、gallery、确认、HUD 和采样产出，比较旧 ranker 与规则 ranker。10 盘，每盘 3 次观察；6 轮预热、30 轮测量，交替顺序。每个耗时样本是一整轮 10 盘的处理线程耗时，含描述符 JSON 序列化；排除异步磁盘 IO，不是单帧端到端延迟。

| 路径／指标 | 基线 P95（ms／整批） | 最终 P95（ms／整批） | 增幅 | ≤10% |
| --- | ---: | ---: | ---: | --- |
| cold wall | 587.462459 | 623.666626 | +6.162805% | 通过 |
| cold CPU | 576.275090 | 611.450090 | +6.103856% | 通过 |
| cached wall | 485.017000 | 501.805500 | +3.461425% | 通过 |
| cached CPU | 475.431043 | 492.130786 | +3.512548% | 通过 |

门禁测的是总处理成本。ranking 阶段 CPU P95 从 cold 31.43→62.60ms、cached 22.69→42.31ms，增幅明显高于总成本；不能将总成本通过解释为所有阶段都低于 10%。实体手机上的延迟、热和功耗仍需要测量。

搜索预算在测量前固定为最多两步、96 个后继节点、节点前后检查 8ms；单个结算可能越过时间边界。超限时所有候选恢复一步结果。当前开发帧只有普通交换候选，成本结果不代表所有新元素／特效盘的最坏耗时。

## 开发回放保持一致与已知失败

以识别飞轮原 `debug-final/debug-predictions.json` 为基线，最终 743 格的 `kind`、`color`、`swap_permission`、`ice`、`object_id` 逐项相等；每帧 SHA 和 gallery SHA 相等。旧识别统计严格相等，新 scorer 仅增加 `unreviewed_labels: 0` 审计字段。

最终覆盖 694／743＝93.405114%，弃权 49；已知标签正确 686、漏认 38、认错 2、false_swap 0、swap_miss 4。这些是固定开发集的不同评分口径，不能把覆盖率写成准确率。未知真值和非普通身份不强行算正确。

| 原有分歧 | 原标签 | 引擎输出 |
| --- | --- | --- |
| `ice-task-2.png:r2c6` | R | O |
| `sparse-task-2.png:r1c1` | R | O |

`sparse-task-2.png:r0c1` 的暂定颜色与标签也不同，但移动权限为 `UNKNOWN`，原 scorer 将其计为漏认，已包括在 38 个 miss 内。汇总脚本使用相同的 `_resolved` 口径，不将弃权额外计为错认，也不改变生产评分。一个旧 ICEFLOWER HUD 模板仍待复核，`gallery_goals_pending_review=1`。

真实 producer 生成 251 个候选，类型全部为 `SWAP`。新增 `ACTIVATE`／`LINE_SWAP`／`BIRD_COLOR`／`BIRD_PAIR`／`CHARGED_PAIR`、拓扑和任务模式由手工工程盘验证。尚未有这些新视觉状态的独立真实对局，因此没有新增视觉准确率、实局 typed top-1／top-3 或玩家收益结论。

## 保留的失败与修复记录

所有路径仍在上述私有证据目录，不覆盖首次失败。

| 记录 | 问题与处理 |
| --- | --- |
| `cost-initial/` | 首次 cold wall +13.56%、CPU +13.02%，超过原 10% 上限。后续优化后通过；固定输入、测量次数、搜索预算及阈值保持不变 |
| `cost-optimized/`、`cost-final/` | 中间源码的成本结果保留；最终结论只取 `cost-permissions-final/` |
| `jvm-current/` | 编译时重名错误，修复后重新验证 |
| `jvm-final/` | 宝箱 fixture 暴露结算中止：宝箱锁与定时石门混淆。分开状态后，确认的钥匙送达可以结算 |
| `debug-final/` | 原生用例的旧 scope 字符串契约失败；实际动作坐标及收益断言通过。同步新规则契约后重跑 |
| `debug-validated/` | 原生 80 项通过，strict 准确率仍因两条旧标签分歧失败；这是权限修复前的中间源码 |
| `parity-initial.json` | 饼干 48 格从已确认不可移动退成未知。修复权限与损伤参数耦合后，最终逐格相等 |
| `debug-permissions-final/` | 最终 549 JVM／80 原生通过；strict 开发准确率失败原样保留，未改标签、gallery 或门限 |

## 复现与待验收

工程命令及输入路径见 [复现命令](match3-rules.md#复现命令)。已有私有输入时，可用 `.venv/bin/python validation/private/match3-rules-goal/verify_engineering.py` 重新核对最终摘要、输出相等、基线快照与历史图片，生成工程汇总；该汇总的 `passed: true` 仅指其声明的工程范围，`strict_development_accuracy_passed` 和 `independent_acceptance` 均为 false。

文档收口时，公开仓库扫描检查 1627 个已跟踪／未忽略候选文件通过，`git diff --check` 通过，5 份更新文档的 61 个相对链接路径存在；最终源码、规则、参考与原 gallery 摘要再次相符。检查记录为 `public-repo-final.txt` 和 `final-document-check.json`。

| 待验收项 | 当前状态／需要的证据 |
| --- | --- |
| 独立语料与新视觉规则 | pending；与开发录像／gallery 训练分离，逐格人工确认身份、覆盖、权限、对象、方向、能量和 HUD 任务模式 |
| 真实动作与规则收益 | pending；逐盘 reviewed 合法动作、规则下界真值，及实际操作前后转移／HUD 计数，用冻结源码、规则、gallery 和帧 SHA 评分 |
| 玩家收益 | pending；真实任务推进、建议采纳及玩家体验，合成盘与未变化的点击帧不能替代 |
| 原签名最终 Release | 本地沿用手机原证书的 minified 候选已构建、安装并通过限定检查，见下方真机补充；完整回放 producer 与正式发布验收仍未通过 |
| 实体手机端到端成本／热／外部实声 | pending；同机同输入 P95、3×15 分钟温升及实际外部语音测量 |
| 公开交付 | pending；负责人按发布流程审核正式包、SHA 与 `latest.json` 后上传，不以源码推送代替发包 |

本轮工程收口未修改历史 `verified`／`release_ready`，没有合并 main、发布正式包或消费玩家道具。

## 手机验收补充（2026-10-11）

以下为工程完成后的另一次验收，保留上文 `33fb…` 历史报告。生产改动仅补充 Release 测试跨 APK 调用所需的精确 R8 保留项，包括规则枚举、状态字段、观测／采样入口和成对基线使用的棋盘接口；规则 Java、规则目录和 gallery 未因本次验收改变。未使用整个引擎的通配 keep。

| 固定项 | 值 |
| --- | --- |
| 本轮源码 SHA256 | `62c9b254965645185a67831c2ddb13aa771b2ccd56c26ebe792942ba75474a73` |
| 本地 minified Release APK SHA256 | `e9324c708f4e4b101e745de303ae1de298028ba79dd9796a21e7ac0cfa22359f` |
| 包信息 | 0.4.5／22，arm64，24,493,284 bytes，非 debuggable，不含测试 fixture |
| 证书 SHA256 | `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`，与手机已有安装一致 |
| 规则／gallery／参考 | 与上文冻结摘要一致 |

这是本地原证书候选，未更新公开包或 `latest.json`。版本号沿用已有版本，不能据此把公开同版本包视为这个候选。全部原始证据在忽略目录 `validation/private/match3-rules-phone-20261011T012306Z/`。

| 检查 | 结果与边界 |
| --- | --- |
| Release 构建／完整性／lint | 构建及现有包完整性检查通过；lint 0 errors／33 warnings |
| 模拟器 minified Release 套件 | 55 项通过、跳过 0；运行前核验主 APK 与测试 APK 两个 SHA |
| 手机规则目录与 GoalVision | 11 项通过、跳过 0，双包 SHA 核验通过 |
| 手机录屏授权取消后的恢复 | 实际第 1 项通过；整套 5 项随后超时，整套报告保留为失败 |
| 完整 Release 逐格回放 producer | **失败**；新增截图探查时 `diagnosticGoalPatches()` 被 R8 移除，跨 APK 调用报 `NoSuchMethodError`。55 项通过不等于完整 Release 回放已通过 |
| 独立准确率／排序／发布 | pending；没有修改标签、阈值、规则状态或发布状态换取通过 |

对应记录为 `r8-benchmark-abi/emulator-release-report.json`、`phone-core-verified-report.json`、`phone-ui-report.json`；失败的逐格回放位于 `vine-live-20261011T020807Z/release-vine-replay/`。首次 ABI 失败、一次测试助手更新取消造成的无效核心结果和后续有效双包验证均保留，不能混用不同测试 APK 的名义通过结果。

### 手机软件成本仍待验收

使用上文相同 10 张冻结开发帧，每盘 3 次观察，6 轮预热、30 轮测量；交替旧／新 ranker，门槛仍为四项 P95 增幅 ≤10%。这是整批处理线程成本，排除录屏、高亮、TTS、异步磁盘 IO 和受控温升，不能解释为端到端手机延迟。

| 手机测量 | cold wall／CPU 增幅 | cached wall／CPU 增幅 | 判定 |
| --- | --- | --- | --- |
| 首次恢复的报告 | +4.37%／+4.42% | −18.61%／+9.17% | 样本与摘要已复核，指标符合上限；ADB 断开，缺最终 runner 回执，**不算完整通过** |
| 为补回执的第二次报告 | +111.17%／+37.86% | +4.85%／+5.65% | cold 超限，且仍缺完整 runner 回执，**失败保留** |

两次记录为 `phone-cost-recovered.json`、`phone-cost-durable.json`。期间手机前台被其他操作切换，不能挑选较低的首次结果宣布通过，也不能据此断言超限完全由环境造成。

后续私有测试助手在测量块外启动前台 Activity 并保持亮屏，原 benchmark 与门槛块逐字不变，生产 APK SHA 不变；该环境修正与旧失败预先记录。首次前台复核启动时 ADB 离线，没有产生新测量。重新连接后双包核验通过，runner 只留下开始记录，240 秒超时；手机前台再次离开本 APP，最终没有本轮 harness 对应的新报告。拉回文件的 harness SHA 仍为旧值，明确排除，不能把旧设备文件当作新结果。超时与旧文件排除分别保存在 `phone-foreground-cost/phone-cost-foreground-reconnected-report.json` 和 `phone-cost-foreground-reconnected-verification.json`；随后只停止本 APP 的残留 instrumentation、清理本次测试输入，没有操作其他应用或清应用数据。**手机软件成本验收未完成**。

### 本局藤蔓证据与新增缺口

用户开始游戏后，监听既有辅助会话并保留日志，未为采集重启对局。诊断覆盖第 76～78 关，共 357,019ms、439 帧、34 张周期性截图，正常结束，事件丢弃 0；另有 19 次图片请求丢弃，不能声称完整录像。生成 25 条提示，并记录了棋盘运动、任务变化和连消后的撤销；最终弹窗状态下 `hint` 为空、高亮为 false。用户停止后确认本 APP 的录屏已结束，自动导出核验 ZIP SHA，原诊断未删除。原始截图、设备标识和日志均保持私有。

在第 78 关的原始截图中，左侧第二列第 2～8 行有 7 个藤蔓覆盖动物，任务栏显示藤蔓 7。私有模拟器探查使用同一个生产 Release APK，分别固定第 77／78 关两张原图 SHA，自动检出的 9×9 几何与实局日志一致。探查只读取现有 sampler／HUD 的原始输出，未改 gallery 或生产代码，也未替代实时确认链或独立人工留出。

| 藤蔓检查 | 实际结果 |
| --- | --- |
| 第 78 关 7 格覆盖身份 | **0／7 识别为 LOCK**：4 格为 SURFACE，3 格为覆盖未知的动物 |
| 这 7 格的交换权限 | 全部 UNKNOWN，授予 YES 的格数为 0；这是限定截图的弃权结果，不能外推整局 false_swap 率 |
| 配对权限 | 4 格 NO、3 格 UNKNOWN；参考规则允许锁住动物配对，目前视觉链未接通这项能力 |
| 第 78 关藤蔓 HUD | UNKNOWN、数量 7，未进入藤蔓任务收益排序 |
| 第 77 关同类图标 | 被识别为 COOKIE，完成数 0，模式 UNKNOWN；有图标身份冲突，不能写作饼干已识别正确 |
| 截图中实际高亮 | 底部第 9 行第 6／7 列，与提示 revision 19 的坐标一致，两端未落在这 7 格藤蔓上 |
| 真实收益归因 | 19 条 outcome 的 `action_evidence` 全为 `not_observed`；计数变化和通关画面不能证明玩家采纳了某条建议或规则收益预测正确 |
| 新元素采样 | 会话末尾 24 类预算已满、饱和计数 1992；进入第 78 关后无新增 `Match3ElementObserved`。需复核跨关预算与重复家族行为，不能把自动发现机制已实现写作本关新样例已收齐 |

限定观察通过私有测试 1 项，双包 SHA 核验通过；生产 APK 仍为 `e932…`。证据是 `vine-observation-verification.json`、`release-vine-probe/observations.json` 与当前会话 ZIP。它们属于开发与诊断证据，没有写入 gallery，没有将规则的 `visual_status`／`live_validation` 改为 verified。

后续需要接入经复核的藤蔓覆盖视觉绑定，解决藤蔓／饼干 HUD 冲突，并为“解开藤蔓”建立独立的目标身份和计数事件。当前 `locked_animal` 能结算锁住后的权限与邻接解锁，但目标 Kind 尚无 LOCK，解锁事件尚不能推进这一 HUD 目标；仅增加模板或把所有绿色覆盖当藤蔓仍不足以完成任务排序。

本局低频温度记录有缺测，不具备匹配对照或 3×15 分钟条件；语音 dispatch／TTS 回调也没有外部实声证据。手机成本、受控温升、实声、独立规则收益和正式发布继续待验收。

### 追加三局日志（第 79～81 关）

用户继续对局并停止后，另一个辅助会话正常结束。三局关卡号由诊断图片读出，分别有第 79／80 关连胜弹窗和第 81 关三星结算图；引擎 HUD 的 `level` 仍为 −1，不能将读图结果写作自动关卡识别。此会话与上文第 76～78 关分开统计，主 APK、规则目录和 gallery 的 SHA 未变。

| 记录检查 | 本次结果 |
| --- | --- |
| 辅助会话时长／帧 | 269,323ms（约 4 分 29 秒），330 帧 |
| 周期性截图／事件丢弃 | 26 张、事件丢弃 0；另有 13 次图片请求丢弃，仍是采样诊断而非完整录像 |
| 提示与撤销 | 21 条提示、21 条撤销；提示坐标与对应 top-1 一致，结尾 popup、高亮 false |
| 排序与动作 | 44 条 ranking、128 个已记录 top-3 候选，全部 SWAP；38 条 ranking 记有 `special_identity_or_effect_unknown`，真实特效动作的视觉接入继续待验证 |
| 收益观察 | 19 条，实际动作证据全部 `not_observed`；三局完成画面不能证明玩家采用建议或排序正确 |
| 导出与停止 | ZIP 字节数、SHA 和会话 ID 校验通过；只分析新会话，两个采集任务自行结束，手机本 APP 的辅助服务已退出 |

第 80 关原始截图的 HUD 为剩余 20 步、藤蔓 4、雪块样图标 7、小鸡图标 74。同一生产 Release APK 的私有模拟器观察器复核该原图，自动几何为 9×9、边界与实局一致。4 格藤蔓仍为 **0／4 LOCK、交换权限全部 UNKNOWN、0 格 YES**；其中 1 格 SURFACE、3 格覆盖未知的黄色动物，配对权限分别为 NO／UNKNOWN。当前建议的第 5 行第 6／7 列与实际高亮一致，端点没有落在这 4 格藤蔓上。这只是限定原图的原始观测，不能外推整局 false_swap；观察器通过 1 项，运行前核验双包 SHA，完成后恢复模拟器原测试助手，未操作手机对局。

藤蔓 HUD 在此图及本关实时日志中被识别为 **COOKIE**，数量曾记录 9、5、4，完成勾选后记为 0；模式 UNKNOWN，不能将这些读数解释为饼干任务验证通过。小鸡身份为 CHICK、任务模式 UNKNOWN，本关候选没有声称确定完成小鸡收集量；鸡蛋相关提示只报机会。初始采样图显示小鸡 132，约 1 秒后的实时读数曾为 32，另有三位数未读到的记录；新增三位数漏首位的疑点，需固定同一输入复核，不能以不同时间的截图直接计算准确率。

这次发现 37 次待复核元素观测，34 张小图保存、3 次 `budget_busy`。进入第 80 关前已有 22 类；本关新增至 24 类，确实保存了同一藤蔓家族的两张小图。会话结尾饱和计数 237，另有重复魔力鸟等外观家族；因此本次有藤蔓样本，也仍需复核跨关预算与家族去重。两张小图尚未进入 gallery，不能自动授予覆盖或移动规则。

本次原始 ZIP、日志、原图、元素小图、观察器输出和逐项统计在私有目录 `validation/private/match3-rules-phone-20261011T012306Z/vine-live-round2-20261011T022709Z/`；可读摘要为 `analysis/README.md`，机器统计为 `analysis/round-summary.json`。本轮仅补诊断证据与后续缺口，没有修改生产规则、模板、阈值或历史视觉／实局验收状态。低频温度与 TTS 回调仍不构成成本、受控温升或外部实声验收。


## 藤蔓补齐工程验收（2026-10-11）

用户要求实际补齐后，本轮已完成藤蔓覆盖识别、独立 HUD 任务、UNLOCK 计数／排序、三位数计数和跨关采样配额保护。工程源码 SHA `619f884ca5f970143835e1656bcf6689ea2d3da5fc769773ffe6592be7435275`；手机主 APK SHA `bdaaf2ed5f4df5a67790e3284a1dbac99f9bb323ebc27c52d9008fdae265c3ec`，原 Android debug 证书与旧安装一致。这是可在原安装上更新的本地混淆候选，不能将它写成持有正式发布凭据或已更新公网同版本包。

| 本轮检查 | 结果与范围 |
| --- | --- |
| Python / JVM | 39／553 项通过，JVM 失败、错误、跳过均 0 |
| Debug | 藤蔓专项＋旧 HUD 7 项通过；现有工程套件另 80 项通过，无跳过 |
| 混淆 Release | lint Error 0（Warning 33），APK 完整性检查通过；现有 55 项＋藤蔓 1 项＋HUD 6 项＋完整 producer 1 项，共 63 项通过，无跳过 |
| 开发原图藤蔓 | 第 78 关 7／7，第 80 关初始帧 9／9，底层 R／Y、LOCK 单层、交换 NO、配对 YES 均正确，没有额外 LOCK |
| 训练帧健全性 | 另两帧 4／4、5／5，只作开发训练样例健全性检查；不能计入独立留出准确率 |
| 负例／HUD | 无藤蔓盘、普通黄色／发光动物无 LOCK；LOCK 不再混成 COOKIE；9→0 完成勾、132／110／74 同一固定输入计数正确；倾斜动画牌继续弃权 |
| 原开发回放 | 10 盘、743 格身份、颜色、交换、冰层、对象、覆盖与配对等状态与原结果逐格一致，false_swap 0；strict 准确率仍因原两条颜色标签分歧失败 |
| 手机候选 | 主包／辅助包安装 SHA 核验一致，限定静止帧／规则 12 项通过，无跳过；数据与用户设置未清除，未执行游戏动作 |
| 采样预算 | 总量 24 类、每类两张不变，六类预留给动物身份已确认但覆盖未知的外观；早期普通／不透明外观不能占满这些位置。饱和计数继续记录，不能保证任意长会话采齐所有新类 |

原 fixed UI、原 gallery、原规则目录逐字节保留。新补充 `match3-vine-v1.json` 为 51,770 字节，SHA `b4f7dab6797ebb1fa197ce29f5d9ae936dd007d53857085eccba2ce6a045cec1`，含三个开发训练源、16 个覆盖动物描述符和两个 HUD 描述符。整格动物和已复核藤条掩码必须同时匹配，可一起作一个 patch 像素的刚性配准；没有降低 cell／goal 门限。补充独立绑定规则 SHA 和审阅范围，metadata 明示助手开发标注、`independent:false`；倾斜动画帧不作目标图标训练。旧 `gallerySha256` 保持原含义，新的 `coverCatalog*` 字段进入诊断及完整 producer。第 81 关稀疏帧的自动几何仍弃权，该限定 HUD 例使用显式审阅网格。

LOCK 目标只接 UNLOCK。单层邻接受击和锁住动物参与三消各按实际解除去重计数，不把存活的底层动物收集，也不让未知层数报完成。新增独立指定 JVM 反例验证两种解锁途径、不能移动但可以配对、任务完成排序与未知层数弃权。Python 复核流程仅将单独 lock 标签用于目标图标；棋盘覆盖状态必须有底层动物颜色及独立规则绑定。

### 本轮成本及仍待验收项

同一 Release、同一 10 盘、每盘三个观察、六轮预热及 30 轮交替成对测量，模拟器处理线程的预声明 wall／CPU P95 增幅上限仍均为 10%。cold wall／CPU 为 -2.00%／-1.65%，cached 为 +8.91%／+8.50%，限定工程成本通过。其范围是相同当前识别／HUD／gallery／采样下的旧 ranker 与规则 ranker比较，不能拿它宣称覆盖补充相对旧识别器的总增幅，也不是端到端手机延迟。

手机前台成本 runner 未取得稳定前台，且无线 ADB 中断，未生成完成的测量报告；记录和停止本任务 runner 的证据保留，没有停止其他应用。改成无需 Activity／占屏的固定输入处理线程测试后，已取得完整设备端 transcript 和 30 轮报告：cold wall +10.29%、CPU +4.88%，cached wall -17.90%、CPU -15.26%。cold wall 超出原 10% 上限，故 `passed:false`，不重跑挑选较好结果。并行设备负载未受控，不能将此报告当成受控前台验收。受控手机处理成本、端到端 P95、3×15 分钟温升和外部实声继续 pending。

本轮保留全部首次失败：Android JSON 的 keySet API 构建失败；首版模板在发光藤蔓上弃权及普通黄色动物误匹配的诊断；动画 HUD / 稀疏几何导出失败；Debug 240 秒 runner 超时；Release withGallery 入口被裁掉，以及旧 shell 创建的 predictions 文件不可覆盖；手机误选模拟器限定测试的 guard 失败及前台成本未完成。对应修复是原生 API 兼容、独立正面覆盖证据与负例、动画／几何边界明确、保留日志后延长套件 runner 超时、精确 R8 ABI 保留，以及备份并仅清理旧生成输出。没有删真值、反例或降低性能／视觉门限。

原始图、fixture／标签、模板重建输入、日志、双 APK、SHA、transcript、结果与完整源码绑定保存在私有 `validation/private/match3-vine-fix/`。关键回执是 `jvm-release-final/report.json`、`debug-vine-final/receipt.json`、`debug-full-repair1/receipt.json`、`release-full/receipt.json`、`release-paired-cost/receipt.json`、`replay-parity.json`、`phone-final-pair-core/receipt.json`、`phone-background-cost/receipt.json`。手机当前辅助包 SHA `71e754067c5254e4395893feafdbddda243afe8a80f81fcfb208dc013fb480ab`，其中包含私有、不占屏的成本 harness；Release 全套回执绑定辅助包 SHA `fe2460cd73bf237bdfa39a61d10d0ead53018fedbfbf0817872e687406865129`，二者主 APK 相同。

独立人工准确率／实局转移与收益、真实特效身份接入、原颜色分歧／旧 HUD 复核、受控实体手机成本／温升／实声和正式公开发布仍待条件。规则目录的视觉／实局 pending、历史发布状态和公网 latest.json 不升级。

## 修复后新包实局诊断（2026-10-11，第 82～83 关）

用户授权再录后，新包主 APK／辅助包及源码摘要与上述藤蔓工程回执一致，新增覆盖目录状态 `loaded`。本次 160,353ms、193 帧、16 张周期性截图、4 条提示及 4 条撤销；诊断事件丢弃 0，图像请求丢弃计数 11。无线 ADB 一次重连有显式记录，完整 APP 内诊断已导出并核验 ZIP 字节数、SHA 和会话 ID。结束高亮 false、hint 为空，导出后本 APP 辅助服务全部退出，双包未变化。

任务栏修复在本次有实际观测：第 82 关截图为雪块 35、藤蔓 7、蜜罐 19、剩余 22 步；藤蔓识别为 LOCK／UNLOCK，日志记录 7→1→0 完成勾，不再混成 COOKIE。后续截图同时显示雪块 1、藤蔓 1。蜜罐图标仍 UNKNOWN，初始数量 19 可读。第 83 关初始图为冰图标 72、27 步，结果图可见三颗星和 816180 分；关卡号由助手读图确认，引擎 `level` 仍 −1。

棋盘覆盖尚未闭环：第 82 关初始截图右侧七格藤蔓底层为紫／紫／蓝／紫／蓝／紫／红。与同一截图输入绑定的第 5／7／8 行第 9 列三个飞轮样例均为 SURFACE、交换 UNKNOWN；另有蓝色覆盖动物弃权记录。早先 R／Y 开发帧通过不能外推这些新外观。此处只证明明确格子的弃权，未生成整盘准确率或 false_swap 真值。

39 条排序、58 个记录候选全部是普通 SWAP，提示坐标全部匹配对应 top-1；116 个候选端点的已记录前提均为无覆盖动物、交换／配对 YES。两关截图均可见魔力鸟，第 82 关同帧魔力鸟原始样例 UNKNOWN，32 条排序明确特效身份／效果未知；真实特效动作验收仍待完成。35 次元素采样保存 33 张、2 次 `budget_busy`，终态 22 类、饱和 0；未将本次图像自动加入 gallery。

4 条收益观察实际动作证据全部 `not_observed`。藤蔓 1→0 只记 `progress_unattributed`，不能归因于提示；十秒级截图和缺失的动作图像上下文不足以还原每次触摸。温度 38.2→39.5℃且全程充电，只作观察。独立准确率、实际动作前后转移／收益、受控性能／温升／实声和发布状态不升级。

完整证据在私有 `validation/private/match3-new-release-live-20261011T042149Z/`；机器摘要、助手读图、逐图 SHA、提示／排序／收益关联、重连记录和导出后检查各自保存。后续优先固定输入复核紫／蓝藤蔓及蜜罐 HUD，再完成魔力鸟视觉状态和有界动作前后证据采集。

## 第 82～83 关外观补齐与提示上下文（2026-10-11）

已用固定原始输入补充开发审阅样例：紫／蓝／红藤蔓、两关魔力鸟、蜜罐 HUD 和鸟旁普通棕色动物。原 fixed UI、gallery、规则目录逐字节不变，旧补充库的 16 个覆盖样例及两个目标样例的像素／掩码／状态／来源均保留。新补充库为 62,361 字节，27 个 cell、3 个 goal、1 个普通 body；SHA `a2123c5a03c29b0e76eaa690cbedf643a4945061cd5d0b51ac332de49365064f`。通过无损 RGB 打包与共享状态绑定减少重复，大小上限仍为 65,536 字节，额外封顶 cell 32／goal 8／body 4；门限不变。

13 个原始 patch 正例和 14 个普通动物负例通过。四个指定 JPEG 开发盘中，第 82 关训练／测试盘右侧七格藤蔓颜色及 LOCK／不可交换／可配对正确；两盘蜜罐目标为 HONEY 19／CLEAR，藤蔓为 LOCK 7／UNLOCK。两关训练／测试盘各识别一只鸟、各枚举两条 `BIRD_COLOR`，没有额外 LOCK／BIRD，LOCK 不作候选端点。第 82 关邻近普通棕色动物通过既有 face／body 路径，仍未知的邻格权限不放行。截图均使用明确审阅的 9×9 网格，不能写成自动几何验证；训练／测试来自同一会话，`independent:false`。

提示与特定撤销原因接入原有前 3 秒／后 2 秒、单窗最长 5 秒、自动触发间隔 10 秒的有界图像窗口，共用原队列／字节预算。真实 recorder／IO 工程测试确认准入、限流以及前后图片输出，不产生 `user_marker`；窗口明确记录 `action_evidence:not_observed`。每个 HUD 槽分别要求三帧新鲜稳定采样，避免邻槽动画阻止稳定图标采集。该修复提供后续分析材料，不能把旧四条未知动作变成已采纳，或证明新窗口已经捕获真实玩家动作。

候选源码 SHA `ef765bfcfdadf86baa8635a94c83fc3c9ea4bdf7dd74ca84dbb7310fadc0c545`，混淆 Release 主 APK SHA `8b6bd25fdb8e9973c0a30cfd5c82f0bd92ef0d18feadf883552ee08fb0bbb58a`，辅助包 SHA `bf63e542617b64021e6cd2afe027dcddbefb324c5d2aafe88b57c24db4700c68`。沿用本地原 Android debug 证书，不是生产 Release 凭据；构建／APK 完整性通过，lint Error 0、Warning 33。39 Python、557 JVM、Debug 专项 5 项、混淆 Release 新外观／窗口／成本 5 项均通过，无跳过。

完整混淆 Release 67 项通过，包括原套件、真实 producer、新外观及提示窗口；成本另 1 项通过。10 盘、743 格所有导出状态字段和 HUD 与旧候选一致，同输入 SHA／样本集合一致，false_swap 仍 0；strict 评分继续 `passed:false`，仅保留两条已有颜色标签分歧。手机已用 `install -r` 更新同一双包，安装后拉回字节 SHA 与构建回执完全一致；手机静止输入／规则／新外观 15 项全过，无跳过。这些检查不执行玩家游戏动作、不测触摸采纳或手机受控性能。

完整 Debug 的 80 项既有检查在延长 runner 等待后通过，加上前述 5 项专项均无跳过。关键回执为 `debug-full-final/receipt.json`、`debug-new-final/receipt.json`、`release-build-final/receipt.json`、`release-full/receipt.json`、`release-cost-final/receipt.json`、`replay-parity.json`、`phone-final/receipt.json`。测试 fixture 仅通过 `sensefieldCaptureScaleFixtureDir` 显式加入测试 APK；成本测试另需同输入 10 盘清单和冻结旧补充库资产 `live-elements-before-supplement.json`，不加入主 APK或版本控制。

本次新增成对成本比较同一当前引擎／HUD／规则 ranker／逐槽采样下的旧补充库与新补充库，包含原 10 盘及两张新测试截图，共 12 盘、每盘三次观察、六轮预热、30 轮交替顺序。cold wall／CPU P95 为 257.79→272.69ms（+5.78%）／248.92→256.63ms（+3.10%）；cached 为 107.68→111.88ms（+3.90%）／105.12→109.09ms（+3.77%）。四项通过原 10% 上限；报告保留所有样本并先保存后断言。这是整批模拟器处理线程成本，排除库加载、异步提示图片 IO、手机端到端和温升；也不隔离旧采样代码的成本。此前手机 cold wall +10.29% 失败仍保留。

保留本轮首次失败及修复过程：原始／压缩姿态弃权、掩码支持不足、生成脚本错误／超出资产大小、JVM 文件 API 不兼容、旧 APK 误用的来源纠正、整盘一处紫藤蔓弃权、第 82 关邻格权限未知导致鸟动作未准入，以及完整 Debug runner 240 秒超时。对应用正面审阅证据、无损去重、精确 API 和足够的 runner 等待解决，没有调整旧真值、规则／视觉阈值或成本门禁。私有完整重建／输入／双包／回执保存在 `validation/private/match3-live-elements-fix/`。

另发现并保留新计数缺口：同一第 83 关初始低清 JPEG 显示 ICE 72，当前 HUD 读为 22；实时日志的 52 在另一时刻，不能替代同输入验证。本轮没有宣称修复此计数。独立人工准确率、真实动作前后转移／收益、受控手机成本／端到端 P95／3×15 温升／外部实声和正式发布继续 pending，历史发布状态与公网清单不变。

## HUD 72 计数与提示证据复核（2026-10-11）

已复现并修复上述同输入 ICE 72→22。修复前的专项明确报 `expected:72, actual:22`；导出实际 Android 头部像素后，卡片与数字连通域边界正常，“7”的旧模板距离 .28442 超过最大距离 .22，反而“2”距离 .1875 通过。补入第 82 关另一张截图中经开发读图确认的“7”后，第 83 关字形距离 .1125、第二名“2” .1875，间隔 .075，通过原 .035 门限。没有用第 83 关输入建模板，没有调阈值或按历史计数纠正。

新 OCR 侧库 `match3-hud-glyphs-v1.json` 为 779 字节／1 字形，SHA `9484889abc44cbcd0cb3e75b30bc7f41c6a4746817faf705c5bb7daf73d4673e`；字节／条目上限分别 4,096／8，原 fixed UI SHA、开发审阅、来源 SHA、完整边界、160 位笔画、有限宽高比必须全部通过，失败整份回落。诊断记录目录 ID／SHA／状态，源码摘要包含此资产。原 fixed UI、gallery、藤蔓补充、规则目录和规则参考 SHA 均与上一候选一致。

第 83 关两张固定 JPEG 均读 ICE 72／27 步，原图、缓存重读、1220 宽缩放和裁切弃权通过。两张图片的实际 HUD 头部像素完全相同，不能计作两个独立准确率样本。第 82 关稍后原图为藤蔓 1、蜜罐完成，整盘无鸟、仅一格蓝色 LOCK；该格仍不可交换／可配对，变化后的格子不能由旧鸟身份填充。确认门禁测试使用合成时间戳，不证明连续实局稳定。

| 检查 | 本轮实际结果 |
| --- | --- |
| Python／JVM | 39／561 项通过，失败／错误／跳过 0 |
| Debug 完整首次检查 | 90 项执行，89 通过，七秒提示照片切换 1 失败；首次回执与事件／图片保留 |
| Debug 修复复核 | 相关 10 项通过，无跳过；覆盖安全撤销、全部旧照片、七秒正常完成、软件取消 0 |
| 完整混淆 Release | 72 项通过，无跳过；最终主 APK 字节完全相同，测试前提修复后的最终辅助包另 17 项通过 |
| APK／lint | 原 Android debug 证书的本地混淆候选，完整性通过；lint Error 0／Warning 33 |
| 原开发 producer | 10 盘 743 格全部状态字段不变，false_swap 0；HUD 一项有明确改善，详见下文 |
| 手机 | 最终主包／辅助包 `install -r` 后拉回 SHA 一致，21 项固定输入／规则检查通过，无跳过；没有执行游戏动作 |
| 有界字形工程成本 | 同一当前 reader／引擎及同一 gallery／藤蔓补充，只比较字形侧库缺席／存在；12 盘、每盘三次观察、六轮预热、30 轮交替成对，原 wall／CPU P95 10% 上限均通过 |

七秒提示失败是旧测试前提问题：第三张原图自带黄色游戏提示框，覆盖零基 (2,5)／(2,6) 两个端点。原图片和实际投屏都能确认 O／R 身份，但普通 body 证据缺失，cover／交换 UNKNOWN。此前照片的交换在这一帧必须撤销，保留会违反既有门禁。新增真实图片反例验证撤销；持续提示场景改从第三张照片启动，让真实 ranker 选择框外有正面证据的交换，然后仍遍历全部三张照片，要求同一提示和七秒完成。原图、标签、生产权限和过滤器均未修改。相关十项通过；软件证据的提示 revision 1 保持，观察 revision 1→7，取消 0，`acoustic_evidence:false`。不能把修复复核写成首次完整 Debug 90 项全绿。

原开发回放有一项预期 HUD 改善：`task-feedback-1.png:goal0` 的 SNOW 计数由 −1 弃权变为 17。原图可见 17，既有开发测试已有独立指定的 17 真值；本轮没有改标签。其他 HUD 字段、输入 SHA、样本集合和 743 格所有语义字段一致。strict 评分仍因原两条 R／O 颜色标签分歧 `passed:false`，不以 producer 执行成功替代准确率通过。

成对成本报告的 cached wall 111.028→111.003ms（−0.02%），CPU 107.537→107.781ms（+0.23%）。cold wall 537.106→270.801ms（−49.58%），CPU 496.102→262.944ms（−47.00%）；基线尾部有明显异常，不能据此宣称提速。全部原始轮次和阶段数据保留，不重跑选择较好结果。范围为模拟器处理线程，包含同步描述符序列化，排除目录加载、异步图片 IO、手机端到端与温升；此前手机 cold wall +10.29% 失败仍保留。

最终源码 SHA `6b08d8cff4798bece25636e5925a45da81d1d3652d893cacc385f7d7121dde30`；主 APK SHA `782818c78e2c0dd67b75921a0fd7f5900580fcc7caf75bdd1fbdcf645617c958`，辅助包 SHA `92076e5f9dd296f090dd9753a2b76f8af5d8e2af0c2c258bbf29579a304eea8c`。完整 Release 72 项用此前辅助包 `6e0e0efba45d628baf45d5c362d079e3ad73a56a4539544c1b490c0933aabac7`，其主包与最终主包逐字节相同；最终辅助包绑定 17 项补充复核、成本 1 项及手机 21 项。Debug 测试前提修复前后主包也完全相同，这一步只涉及测试前提与新增反例。

原始数据与逐次失败在私有 `validation/private/match3-hud-cv-fix/`。关键回执为 `before-fix/receipt.json`、`baseline-export/receipt.json`、`cause-and-scores.json`、`debug-full/receipt.json`、`idle-probe/receipt.json`、`debug-idle-repaired/receipt.json`、`release-build-final/receipt.json`、`release-full/receipt.json`、`release-final-targeted/receipt.json`、`replay-parity-final.json`、`release-hud-cost/receipt.json`、`phone-final/receipt.json`、`engineering-summary.json`。首次 JVM 文件 API 编译错误、零测试发现命令、旧 HUD 全等断言失败也保留；分别改用兼容 API、正确测试目录及明确记录唯一有真值的改善，没有删除失败证据。

下一局需要实际采集新的有界提示前后窗口，核对连续动画中的藤蔓／鸟权限、真实交换及收益。旧会话四次动作仍 `not_observed`，不会由本轮回放升级为已采纳；独立人工留出集、受控实体手机成本／端到端 P95／3×15 分钟温升／外部实声、正式签名与公开发布继续待条件。本轮没有新实局录像或公网发布。
