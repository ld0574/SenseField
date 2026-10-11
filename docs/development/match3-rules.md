# 消消乐规则引擎工程说明

依据 [开心消消乐元素规则参考](../research/开心消消乐元素规则参考.md)，执行 [规则驱动最大价值 Goal](../plans/消消乐规则驱动最大价值改造.md)。2026-10-11。

## 工程范围与证据边界

机器目录登记了参考的全部 67 个条目（章节元素、组合与道具），共 67 个有限规则 ID；基础动物共享规则，冲突变体及旧来源单列。文档核实、参数完整度、代码支持、视觉确认和实局验证是不同字段。新规则的视觉与实局验收全部保持 `pending`。

支持统计：`direct_effect` 17；`partial_effect` 14；`direct_opportunity` 2；`state_only` 20；`pending` 14。`direct_effect` 指观察到前置状态后能计算有依据的效果／下界，并不表示整个元素的所有参数已齐全。

规则目录 SHA256：`289238c63dfb988fe251bd0bc4478416bdaf36d8f44ce024608fb48d4eb37ffb`。参考 SHA256：`2f115d9253a0e42eb958cff5ea637a48b6375246f9a9369c87f4c03fecb723b1`。保留的原 gallery SHA256：`56d8accbab632908e4ea849cccedceb6918d6fa417590c4277b0d59f96933d3c`。

## 状态、动作和结算

`Match3Position` 分开前景、静止冰底层、覆盖、对象归属、横纵特效、能量、炮口方向、开闭和暂时缺席；移动、配对、激活、普通邻接受击和特效受击各有权限。冻结禁止移动与配对，锁链禁止移动但允许配对。已确认不能移动的元素不因损伤层数未知而退成移动权限未知；各字段的证据独立。蜂蜜覆盖与单级蜜罐、小鸡普通消除与孵化、雪花与旧冰花分别建模。缺少事实保留未知。

候选包含 `SWAP`、单格 `ACTIVATE`、`LINE_SWAP`、`BIRD_COLOR`、`BIRD_PAIR` 和已观察到双方满能量的 `CHARGED_PAIR`（染色宝宝＋星星瓶）。未核实组合、爆炸的近似范围、身份待核实的精力瓶和玩家道具没有已确认动作。建议系统不执行游戏点击或消耗道具。

纯结算输出 CLEAR／HIT／LAYER／TRANSFORM／GENERATE／HATCH／RESCUE／DELIVER／CHARGE／TRIGGER／TEMPORARY／UNLOCK，按轮次和对象去重。一个多格救援对象只有全部已确认覆盖清完才发一个解救事件。鸡蛋只报孵化机会，不编造随机小鸡数量；无敌毛球只暂时缺席；饼干仅有身份／邻接机会。蜂蜜覆盖与牢笼即使有视觉层数也不借用冰的损伤量；旧单击变体不自动升级成新的多层机制。

精确四连、直线五连、L／T 五格生成不同特效；保留位置缺证据时只给消除下界和生成数，后继停止。未描述的六连／交叉形状不套用其它游戏规则。直线按已观察轴命中，交换同时出现轴外三消、命中其它特效时记录未知结算／触发原因。大炮记录确认触发与方向，射程、阻挡、目标集合及相互传播缺证据，不生成射线命中的确定收益。

拓扑由明确的有向重力／传送连接、收集口类型和证据给出，拒绝环、合流歧义、空洞连接。已知无补子状态可推演当前棋子的落位与至多 12 轮连消；随机补子用 UNKNOWN，保守停止后续连消和两步搜索。未建模的毒液扩散、定时石门、移动地板和临时毛球回归在落位前就阻止精确后继。空间距离仅是准备偏好，送达必须进入已确认收集口。当前没有新实局拓扑语料，拓扑接入与推演由人工指定工程盘验证。

## 任务模式、排序与搜索

目标包含 CLEAR／HATCH／RESCUE／DELIVER／CHARGE／TRIGGER／UNLOCK／UNKNOWN。HUD 小鸡图标默认 UNKNOWN；图标身份不能自动确定任务模式。旧动物／单级障碍模式保留已审阅来源。新增任务模式可以通过独立的 `goal_binding` 审阅入口接入，有冲突的近似模板保持 UNKNOWN。

排序先比较可确定完成的任务和按剩余数封顶的贡献，再比较已知两步结果、相关受击、已确认通道进度、准备距离、毒液源清除及潜在特效。毒液不会无条件抢过当前任务；已观察到非满星星瓶时，其充能机会能服务 CHARGE 任务，未知能量数不计进度。任务未读时比较真实物理消除下界，特效不会因没有普通三连元数据而被低估。最后一步排除下一步准备／新生成特效收益。

搜索预先固定：最多两动作、96 个后继节点、8ms 在每个节点前后检查。单次结算可能超过时间边界，不能宣称严格的线程 8ms 上限。超限时所有候选恢复完整的一步结果，避免扫描顺序偏好；随机补子、生成位置、传播范围和状态转移未知时不扩展。

## 三轴复核与数据管线

外观身份、机制原型、交换权限继续分开复核。`rule_binding` 另需 `reviewed: true`、有限 rule_id／版本、evidence 和 state；未知字段不从名字补齐。语义相同状态共享 bound ID，不因审阅人或证据文字不同互相竞争。模板不得携带本帧特定 object_id／topology。编译器只从 train 会话采样；冻结等变体不进入普通动物 envelope。gallery 元数据绑定规则 ID／SHA，原 gallery 与历史标签不改写。

状态绑定例：

```json
{
  "reviewed": true,
  "evidence": "人工确认包裹冰变体与两层状态",
  "rule_id": "ordinary_uncovered_animal",
  "rule_version": 1,
  "state": {
    "kind": "ANIMAL",
    "color": "R",
    "layers": 0,
    "floor_ice": 0,
    "cover": "FROZEN_ICE",
    "cover_layers": 2,
    "effect": "NONE",
    "gate": "OPEN"
  }
}
```

任务模式例（只用于有单独任务证据的 goal 家族）：

```json
{
  "reviewed": true,
  "event_mode": "HATCH",
  "evidence": "已人工确认该关任务是孵化收集小鸡"
}
```

蜂蜜覆盖目标可独立选 `honey_cover`；动物身上的蜂蜜仍须绑定被覆盖前景，不用目标图标授予格子动作。仅命名 bird／cannon／bottle 等类型，不提供状态复核，仍不能编入具有新能力的 gallery。

## 回放与收益观察

实时、截图、producer 和收益观察共用候选、事件引擎及规则版本。高亮支持单格点击；直线语音包含方向。`Match3RuleDiagnostics` 同源输出动作坐标／类型、前置状态、事件数组、unknown_reasons、搜索节点／深度／停止原因。棋盘另记录 omitted_action_reasons。没有实际观察证据的激活不标成玩家已采纳；点击后的不变帧不能证明触发。

新 producer 格式为 `match3-actions-replay-v1`。工程驱动器锁定并传入源码 SHA，校验规则、gallery、manifest 和每帧 SHA。排序评测支持相邻交换与同格点击，可选人工 legal_actions 和 rule_truth（certain_units、rule_id、rule_version）。缺少完整合法性／规则真值时总指标为 null，部分结果单列。新格式的独立评分要求相同源码、规则、gallery、manifest、帧 SHA，逐盘人工复核合法动作与规则下界；旧相邻交换格式兼容。

当前无独立人工任务／走法真值，因此不产生独立 top-1／top-3、玩家收益或新视觉准确率声明。工程用例的手工预期与实局人工标注分开。

内置 Coach 知识同步修正包裹冰、静止冰底层、小鸡任务、近似爆炸范围及待核实组合的表述；棕色动物、紫猫头鹰、雪块名称与参考一致。知识提示与规则目录都保留证据边界。

## 复现命令

```sh
.venv/bin/python scripts/match3_rules.py --json validation/private/match3-rules-goal/catalog-audit.json
.venv/bin/python -m unittest discover -s tests -p 'test_match3*.py'
.venv/bin/python scripts/verify_match3_regression.py --mode jvm --output validation/private/match3-rules-goal/jvm-replay
.venv/bin/python scripts/verify_match3_regression.py --mode cost --cost-kind rules \
  --holdout validation/private/match3-flywheel-goal/cost-holdout \
  --fixtures validation/private/match3-flywheel-goal/engineering-fixtures \
  --output validation/private/match3-rules-goal/cost-replay
.venv/bin/python scripts/verify_match3_regression.py --mode debug \
  --holdout validation/private/match3-flywheel-goal/development-holdout \
  --fixtures validation/private/match3-flywheel-goal/engineering-fixtures \
  --output validation/private/match3-rules-goal/debug-replay
```

Debug strict 开发准确率仍会报告原来的两条标签分歧（ice-task-2:r2c6、sparse-task-2:r1c1，标签 R／引擎 O），应保留失败。一个旧 ICEFLOWER HUD 图标仍待审阅。不能调标签或降低门限把它们变成通过。正式发布仍需要正式签名凭据和独立冻结输入；本地原证书候选与实体手机的本轮限定验收见文末，独立语料仍缺。

成本基线是 759f26a 的 ranker／value／frontier 类改名快照，固定同 APK 的识别、gallery、确认、HUD 和采样产出。10 盘、每盘 3 次观察、6 轮预热、30 轮测量、交替顺序，cold／cached 同测，wall 和 CPU P95 增幅上限均为 10%。测量单位是每轮处理这 10 盘的整批耗时，不能将数值当成单帧端到端延迟。首次失败 cost-initial 完整保留（cold wall +13.56%、CPU +13.02%）；不得把后续通过写成从未失败。范围是模拟器处理线程，含采样产出 JSON，排除异步磁盘 IO 与实体手机热／外部实声。总成本门禁不代表 ranking 单独阶段增幅也小于 10%。

## 最终工程验证

本轮规则实现与限定工程验证已完成，详细证据、失败历史和待验收项见 [规则工程验收记录](match3-rules-validation.md)。最终引擎源码摘要为 `33fb3ffa835f9a1befe509aa3b3c0494a2f9be989b5177ab42d002285d00c604`；此摘要由回归脚本定义，包含规则、gallery 与参考，范围不等于整个 Git 工作区。

Python 38 项、JVM 549 项、Debug 原生 80 项全部通过，JVM／原生均无跳过；真实 producer 另外通过 1 项。最终同输入成对成本通过：cold wall／CPU P95 分别增加 6.16%／6.10%，cached 分别增加 3.46%／3.51%，均在预声明的 10% 以内。

开发回放 10 盘、743 格的 kind、color、swap_permission、ice、object_id 与改造前逐格完全一致；识别统计保持原值，false_swap 仍为 0。完整 Debug 报告仍是 `passed: false`，原因是原来的两条颜色标签分歧；一个旧 ICEFLOWER HUD 模板继续待复核。本轮没有修改旧 gallery、标签或准确率门限。

真实开发回放的 251 个动作候选全部为 `SWAP`。新增特效动作、拓扑和事件模式的手工工程盘证明逻辑与接入，不证明新状态在真实对局已经识别，也不提供玩家收益或独立 top-1／top-3。独立语料、真实转移、原签名 Release 和实体手机验收仍为 `pending`。

## 全量规则覆盖与缺证据清单

| rule_id | 参考条目／旧来源 | 文档证据 | 参数 | 工程支持 | 仍需证据 |
|---|---|---|---|---|---|
| `ordinary_uncovered_animal` | 小黄鸡（黄色）、红狐狸（红色）、蓝河马（蓝色）、绿青蛙（绿色）、紫猫头鹰（紫色）、棕色小动物（棕色） | documented | complete | direct_effect | generated_special_retained_position |
| `line_horizontal` | 直线特效（横向 / 纵向） | documented | complete | direct_effect | generated_special_retained_position、simultaneous_swap_match_settlement_order、secondary_special_hit_trigger |
| `line_vertical` | 直线特效（横向 / 纵向） | documented | complete | direct_effect | generated_special_retained_position、simultaneous_swap_match_settlement_order、secondary_special_hit_trigger |
| `blast` | 爆炸特效 | documented | partial | partial_effect | exact_hit_mask_each_round、activation_guards、retained_position |
| `magic_bird` | 魔力鸟（俗称"彩虹"） | documented | complete | direct_effect | generated_special_retained_position |
| `combo_bird_color` | 组合:魔力鸟 + 普通动物 | confirmed | complete | direct_effect | — |
| `combo_bird_bird` | 组合:魔力鸟 + 魔力鸟 | confirmed | complete | direct_effect | — |
| `combo_bird_line` | 组合:魔力鸟 + 直线特效 | partial | partial | pending | exact_conversion、hit_mask |
| `combo_bird_blast` | 组合:魔力鸟 + 爆炸特效 | unverified | missing | pending | exact_conversion、hit_mask |
| `combo_line_line` | 组合:直线 + 直线 | unverified | missing | pending | kxxxl_specific_trigger_and_mask |
| `combo_line_blast` | 组合:直线 + 爆炸 | unverified | missing | pending | kxxxl_specific_trigger_and_mask |
| `combo_blast_blast` | 组合:爆炸 + 爆炸 | unverified | missing | pending | kxxxl_specific_trigger_and_mask |
| `combo_dye_dye` | 组合:染色宝宝 + 染色宝宝 | confirmed | partial | pending | exact_hit_mask |
| `combo_dye_star` | 组合:染色宝宝 + 星星瓶（能量集满） | confirmed | complete | direct_effect | — |
| `combo_star_star` | 组合:星星瓶 + 星星瓶 | confirmed | partial | pending | exact_hit_mask |
| `combo_energy_energy` | 组合:精力瓶 + 精力瓶 | confirmed | partial | pending | independent_visual_identity |
| `combo_dye_egg_bird` | 组合:染色蛋 + 魔力鸟/彩虹特效 | partial | partial | pending | exact_conversion_and_mask |
| `snow` | 雪块 | documented | complete | direct_effect | — |
| `frozen_ice` | 冰块 | documented | complete | direct_effect | — |
| `locked_animal` | 锁链 / 藤蔓 | documented | complete | direct_effect | — |
| `poison` | 毒液 | documented | partial | partial_effect | spread_count、spread_direction、suppression_condition、energy_per_clear |
| `honey_cover` | 蜂蜜 | documented | partial | partial_effect | layer_count_or_unknown、move_and_match_permissions |
| `chest` | 宝箱 | documented | partial | partial_effect | key_requirement、contents |
| `stone_gate` | 翻转石门 | partial | partial | state_only | observed_open_closed、exact_period |
| `transport` | 传送带 / 传送门 / 滑道 | documented | partial | partial_effect | observed_edges、motion_timing、collector_edges |
| `coin` | 银币 | documented | complete | direct_effect | — |
| `leaf` | 小叶堆 / 树叶 | unverified | missing | state_only | layer_count_and_damage |
| `furball` | 毛球（褐色毛球） | documented | complete | direct_effect | — |
| `invincible_furball` | 无敌毛球 | documented | partial | partial_effect | absence_duration、return_position |
| `snowflake` | 雪花 | documented | partial | state_only | trigger、exact_hit_mask |
| `cloud` | 云层 | unverified | missing | state_only | clear_rule |
| `flower_cluster` | 花团 | unverified | missing | state_only | activation_and_goal_event |
| `cage` | 牢笼 | documented | partial | partial_effect | hit_count、match_permission |
| `egg_shell` | 鸡蛋壳 | unverified | missing | state_only | identity_vs_egg、clear_rule |
| `ice_barrier` | 冰块栏杆 | unverified | missing | state_only | damage_rule、topology |
| `chick_hatch_goal` | 小鸡（小黄鸡） | documented | partial | direct_opportunity | activation_count、spawn_count、collection_event |
| `egg` | 鸡窝 | unverified | partial | direct_opportunity | egg_vs_nest、activation_count、spawn_count |
| `penguin` | 企鹅 | documented | partial | partial_effect | footprint、rescue_terminal_state |
| `snow_bear` | 北极熊 / 雪怪 / 雪熊 | documented | partial | partial_effect | footprint、object_state、terminal_rescue_event |
| `sprite` | 精灵 / 精灵萌豆 | unverified | missing | state_only | collection_event |
| `one_eye` | 独眼仔 | documented | partial | state_only | activation、generated_effect_count_and_positions |
| `dolphin` | 海豚 | unverified | missing | state_only | cover、rescue_event |
| `gold_pod` | 金豆荚 | documented | complete | direct_effect | — |
| `key` | 钥匙 | documented | complete | direct_effect | — |
| `gem` | 宝石 | unverified | missing | state_only | collection_event |
| `star` | 星星 | unverified | missing | state_only | collection_event |
| `jellyfish` | 水母宝宝 | documented | partial | partial_effect | exact_hit_mask |
| `cannon` | 魔法炮 / 大炮 | documented | partial | partial_effect | observed_direction、activation_guards、exact_range、blocking_rules、target_mask、cannon_propagation_links |
| `shell` | 贝壳 | documented | partial | state_only | hit_count、release_positions |
| `puffer` | 气鼓鱼 | documented | partial | state_only | damage_state、terminal_hit_mask |
| `magic_stone` | 魔法石 | documented | partial | partial_effect | exact_hit_mask、linked_object_relations |
| `dye_egg` | 染色蛋 | documented | complete | direct_effect | — |
| `dye_baby` | 染色宝宝 | documented | partial | state_only | energy_threshold、energy_sources、standalone_effect |
| `star_bottle` | 星星瓶 | documented | partial | state_only | energy_threshold、energy_per_clear |
| `energy_bottle` | 精力瓶 | partial | partial | state_only | visual_difference_from_star_bottle |
| `octopus` | 章鱼 | documented | partial | state_only | exact_spawn_rule |
| `cuttlefish` | 墨鱼宝宝 | documented | partial | partial_effect | exact_hit_mask、stone_cannon_relocation、coin_fall |
| `moving_floor` | 移动地板 | unverified | missing | state_only | observed_state_and_transition |
| `tool_hammer` | 道具:小木槌 | unverified | missing | pending | official_effect、explicit_player_use_authorization |
| `tool_shuffle` | 道具:刷新 | unverified | missing | pending | official_effect、explicit_player_use_authorization |
| `tool_extra_steps` | 道具:加步数（+5 步等） | unverified | missing | pending | official_effect、explicit_player_use_authorization |
| `tool_start_special` | 道具:开局特效 | unverified | missing | pending | official_effect、explicit_player_use_authorization |
| `tool_undo` | 道具:后退一步 | unverified | missing | pending | official_effect、explicit_player_use_authorization |
| `stationary_single_layer_ice` | docs/development/match3-element-evidence.md | legacy_reviewed | partial | direct_effect | keep_original_source_and_variant |
| `single_clear_honey` | docs/plans/消消乐识别飞轮改造.md | legacy_reviewed | partial | direct_effect | keep_original_source_and_variant |
| `multistage_iceflower` | docs/plans/消消乐识别飞轮改造.md | legacy_reviewed | partial | partial_effect | keep_original_source_and_variant |
| `identity_only_2x2` | docs/development/match3-element-evidence.md | legacy_reviewed | partial | state_only | keep_original_source_and_variant |


## 藤蔓与三位数任务数补齐（2026-10-11）

按参考“锁链 / 藤蔓”实现 `LOCK` 任务和 `UNLOCK` 事件：单层藤蔓邻接受击解除，锁住动物作为非交换端点参与三消时也解除；一次结算去重，不把存活底层动物算成收集。未知覆盖层数不能宣称任务完成。任务排序使用解锁事件，理由为“解除藤蔓”；准备价值来自当前 LOCK 格，理由为“靠近藤蔓”。旧枚举值保持顺序，新成员追加。

视觉补充独立保存在 `match3-vine-v1.json`，原 fixed UI、原 gallery 和规则目录逐字节保留。三个已明确开发审阅的训练帧提供 16 个覆盖动物描述符和两个 HUD 图标；倾斜动画帧只提供棋盘外观，不作 HUD 图标训练。描述符同时匹配整格动物与已审阅藤条形状的独立像素掩码，允许双方一起作一个 patch 像素的刚性配准。绿色本身、普通动物面部或整格平均相似度均不能独自授予 LOCK。数值阈值仍是 cell 0.12／margin 0.025，goal 0.14／margin 0.025。补充资产最大 65,536 字节，规则 SHA 不符或资源／掩码不合格时整份回落；补充有独立 `coverCatalog*` ID、SHA 与状态，不改变原 `gallerySha256` 的含义。

HUD 读取完整下半数字区域，覆盖 132／110 等三位数；完成勾继续在原右半区域独立核对，防止藤蔓绿色图标自己成为完成信号。裁切／未知字形和倾斜动画版式继续弃权。第 81 关稀疏开发帧自动几何不能确认，限定 HUD 反例使用显式审阅的 7×9 网格，不把它写成自动定位通过。

会话采样总上限仍为 24 类、每类两张，其中六类预留给有动物身份但覆盖／交换权限未确认的外观。新家族仍需三个新鲜稳定观察、正面身份／覆盖／规则复核；几何／稳定性重置不复用次数、不清空家族预算、不自动准入。有限配额不能保证任意长会话的所有后来外观都被采集，饱和计数仍保留。

这批样例是助手在用户实施授权下做的开发标注，`independent:false`，同局训练帧与验收帧分离也不等于独立会话留出。最终 Debug、混淆 Release、真实 producer 与手机回执见 [本轮验收记录](match3-rules-validation.md#藤蔓补齐工程验收2026-10-11)；规则目录里的独立视觉／实局状态保持 pending。
