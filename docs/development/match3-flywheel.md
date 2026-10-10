# 消消乐识别飞轮执行与验收

对应 `docs/plans/消消乐识别飞轮改造.md`。所有真实画面、逐格预测、家族库、人工标签和评测结果均保存在被忽略的 `validation/private/`。工程测试、开发语料实验、独立留出准确率和实体手机验收分别记录。

## 本轮完成状态（2026-10-11）

按用户“暂无，先完成实现和工程验证”的范围，本轮完成阶段 1 的采集→跨会话归集→持久家族→三轴复核／撤销／拆簇→编译→运行时加载→严格评分闭环，以及阶段 2 的真实排序输出和 top-1／top-3 评分工具。实现沿用现有 Face／Body 和 MAD 判定，没有训练或交付新的神经网络嵌入。旧方案中的“无上限”是指不再受固定目录 327KB 墙限制；实际预算仍为 64 家族、每家族 24 个训练代表、1MiB。

| 工程证据 | 当前结果 | 本地记录（`validation/private/match3-flywheel-goal/` 下） |
|---|---|---|
| Python 管线／安全／评分门禁 | 27 项通过 | `python-final.txt` |
| JVM 回归 | 507 项通过，失败／跳过均 0 | `cost-block-bounds/report.json` |
| Debug 原生工程回归 | 75 项通过，跳过 0 | `debug-final/debug-instrumentation.txt` |
| 最终距离剪枝的真实决策一致性 | 10 盘输出完全一致：格子、HUD、排序、对象、摘要 | `final-decision-parity/report.json` |
| 真实描述子 CLI 回采／复核页 | 8 家族；重复回采摘要不变；未审阅编译被拒绝 | `real-harvest-smoke/report.json` |
| 开发集严格准确率 | **未通过**：743 格，coverage 0.934051，correct 686、miss 38、misclassify 2、false_swap 0、swap_miss 4 | `final-decision-parity/accuracy.json`、`accuracy-strict.txt` |
| 同引擎软件 P95（预声明 ≤+10%） | 冷路径墙钟 −3.63%、CPU −3.47%；缓存墙钟 −11.76%、CPU −11.62%，均通过 | `cost-block-bounds/report.json`、`flywheel-cost.json` |

75 项原生回归在最后一次距离剪枝前执行；最终剪枝包通过独立完整距离 JVM 对照、上述 10 盘完整输出一致性及 15 帧额外真实回放。Debug 综合脚本的 `debug-final/report.json` 仍为 `passed:false`，原因是严格准确率中的两条原始标签分歧，不能将原生工程回归通过改写成完整门禁通过。

这两条分歧是 `ice-task-2.png:r2c6` 和 `sparse-task-2.png:r1c1`：原标签为狐狸 R，当前引擎为棕熊 O，来自历史混簇 c30。原标签未修改；只保存了 `reviewed:false`、`applied:false` 的复核提案。新增冰花／蜜罐形状核对后，10 盘中原来的 6 个障碍误认消失。在另外 15 帧、1080 格的历史开发回放中，112/113 个历史冰花判定、36/36 个蜜罐判定保留；少掉的 1 个冰花在“25 连胜”动画遮挡下弃权。43 个旧 `ice:0` 更新为真正未知的 `ice:-1`，普通动物身份与交换权限未改变。这是旧引擎输出对照，不能当人工真值或独立准确率。

实验资产 `match3-gallery-v1.json` 未改动，SHA-256 为 `56d8accbab632908e4ea849cccedceb6918d6fa417590c4277b0d59f96933d3c`。其中 1 个未经显式审阅的旧任务图标会与固定目录已验证的 SNOW 目标冲突，当前保留在资产中待复核，不参与 HUD 判定，producer／运行诊断记录 `gallery_goals_pending_review:1`；不能继续声称这条历史 ICEFLOWER HUD 实验已通过当前验收。正式编译的 `reviewed:true` 目标图标可正常扩充任务栏。

本轮没有独立人工三轴／HUD／走法语料，未产生 top-1／top-3 验收分数。签名 Release 和实体手机验收也未执行。此前失败的工程与成本记录均保留；最终成本优化是有实现变化后的复测，不删除失败或重复运行挑低值。

## 数据闭环

`Match3HoldoutAccuracyInstrumentedTest` 使用真实 `Match3Sampler`、`Match3HudReader` 和 `Match3MoveRanker`，导出逐格 envelope／inset patch、目标卡图、候选大物体、HUD 判定、每盘排序、逐帧状态和 gallery 摘要。引擎输出只作待审阅材料，不作为真值。

实时链路增加 `Match3FlywheelHarvest`：在现有稳定帧上采集普通／障碍外观、2×2 大物体候选和目标图标，连续三个新鲜观测才导出。每会话格子外观最多 24 家族、每家族两份代表；大物体最多 24 家族、各两份代表；目标图标最多 24 份。每帧最多 8 次序列化／提交尝试，与共享诊断队列容量一致。描述子经 `Match3FlywheelSample` 写入本地诊断，沿用图片开关、代次取消、共享 IO／内存预算和诊断日志总量限制。队列忙或当帧额度用尽时撤销样例预留，下一个新鲜稳定观测重试，不要求出现更细致的新外观或等待十秒；图片关闭或代次改变时取消已排队的描述子。不开新录屏源，不直接进入识别目录。达到预算后停止新增，不能声称已经采全。

回采仍使用 `scripts/pull_android_diagnostics.py`。实时小样例适合训练图库；独立准确率必须保留完整棋盘帧，不能拿没有棋盘上下文的诊断小样例冒充留出盘。

## 持久家族与三轴审阅

输入清单必须声明稳定的 `session_id`，例如：

```json
{"session_id":"session-a","frames":[{"file":"board-a.png"}]}
```

用已有真实 producer 的结果增量更新家族库：

```sh
.venv/bin/python scripts/match3_flywheel.py harvest \
  --registry validation/private/flywheel/registry.json \
  --predictions validation/private/session-a/predictions.json \
  --manifest validation/private/session-a/manifest.json \
  --out validation/private/flywheel/registry.json
```

诊断事件输入改用 `--events /明确提供的会话/diagnostics/current/events.jsonl`，其 manifest 仍必须声明 `session_id`。事件里只保存局部描述子，虚拟帧名不代表存在可供准确率测试的整帧图。

持久家族 ID 不按成员数重新编号。离线去重采用刚体对齐、可丢弃背景和象限检查；这是外观归集，不授予身份或机制。歧义保留供人工拆分，不合成真值。

```sh
.venv/bin/python scripts/match3_flywheel.py review \
  --registry validation/private/flywheel/registry.json \
  --out validation/private/flywheel/review.html
```

人工逐家族选择身份、机制、交换权限并勾选已审阅。导出文件包含 `labels`、`archetypes`、`swappable`、`reviewed` 和 `registry_sha256`。身份不能代替机制证据；未知机制不得给普通交换权限。改动任一下拉项会撤销该家族的审阅勾选。应用显式 `reviewed:false` 会清除库中已有审阅和规则，不能留下旧权限；局部更新里未出现的家族仍保留原记录。旧的只有身份标签的文件不能自动成为已确认的规则。

```sh
.venv/bin/python scripts/match3_flywheel.py apply-review \
  --registry validation/private/flywheel/registry.json \
  --labels validation/private/flywheel/reviewed.json \
  --out validation/private/flywheel/registry.json
```

审阅绑定生成页面时的家族库摘要；库改变后须重生成页面，已有家族的标签仍保留。混簇可用 `split --family <稳定ID> --members <逗号分隔的source键>` 拆分；两侧都撤销旧审阅，重新核对。不得用提高相似阈值掩盖混簇。

## 分割与 gallery 编译

按整段录制会话分割，不能将同段录像的相邻帧交错分成训练和独立测试。划分文件示例：

```json
{"groups":{"session-a":"train","session-b":"dev","session-c":"test"}}
```

所有来源必须明确分区。编译只取 `train`，未审阅、机制未知、特殊收益未确认的样例不被授予规则。

```sh
python3 scripts/match3_build_gallery.py \
  --registry validation/private/flywheel/registry.json \
  --splits validation/private/flywheel/splits.json \
  --out validation/private/flywheel/candidate-gallery.json
```

支持当前有限身份枚举的新外观变体。全新身份或表外机制仍需扩展引擎并验收，不能仅凭标注获得执行规则。统一支持普通动物中心／整格、银币、雪块、冰花、蜜罐、鸡窝外观、任务图标、2×2 饼干身份和明确审阅的裸冰单层背景。饼干仍是 `identity_only_2x2`，不制造损伤层数或移除收益。裸冰选项仅用于不含前景动物的单层冰背景，不把覆盖动物当普通动物样例，也不据此标注多层冰。

图库使用 RGB 十六进制描述子和位掩码；保留 `family_id`、来源、机制和审阅状态。默认最多 64 家族、每家族 24 个训练代表、1MiB；超限报错，不能静默裁掉测试数据。旧实验图库保持原样，替换生产资产前先核对来源与构建摘要。

运行时兼容旧像素数组，事务式加载新增图库。格式、规则或预算不合法时整份回退到固定目录，记录 `gallery_status`；成功时记录 `gallery_id` 和资产文件 SHA-256。

## 准确率门禁

阶段 0 仍可仅记录基线。严格模式要求标签／预测逐格完全对应：缺标签、缺预测、空标签、显式未审阅标签都失败。历史无 `reviewed` 字段的人工标签仍可做开发评分；Full 要求逐条显式审阅。未知规则真值被赋予普通交换权限计入 `false_swap`；显式标注的冰层、HUD 身份／数量独立评分。

```sh
python3 scripts/evaluate_match3_accuracy.py \
  --predictions validation/private/holdout/predictions.json \
  --labels validation/private/holdout/labels.json \
  --strict --min-coverage <冻结前确定的门槛> \
  --json validation/private/holdout/accuracy.json
```

`export-labels --splits ... --partition test` 可将已审阅家族展开为格子和目标身份标签。目标栏数字／完成状态须另有人工真值，不能复制引擎读取值。冰层真值是整数层数或未知，不把未知层数压成零。

门禁目录包含 `manifest.json`、`labels.json` 和完整棋盘帧；manifest 的 `gates.min_coverage` 必须在运行前声明。

```sh
# 不需要 Release 签名，只跑 Debug 的真实识别与评分。
python3 scripts/verify_match3_regression.py --mode accuracy \
  --holdout /明确提供的留出目录 --serial emulator-5554

# Debug 工程回归加准确率。
python3 scripts/verify_match3_regression.py --mode debug \
  --holdout /明确提供的留出目录 --fixtures /明确提供的历史回归图目录

# 已配置原签名：Debug + 最终 minified Release，分别运行同一留出集。
python3 scripts/verify_match3_regression.py --mode full \
  --holdout /明确提供的独立留出目录 --fixtures /明确提供的历史回归图目录
```

Full 要求 manifest 声明 `independent:true`、`frozen:true` 和来源会话；图库需有家族库／分割摘要且训练来源不与留出来源相交，不能有旧目标图标待复核。格子和 HUD 真值逐条要求 `reviewed:true`；必须显式提供 `goal_samples`（没有目标时提供空数组），每个目标包括身份、剩余数量和完成状态。声明不替代对来源和标签的人工核验。门禁固定源码（含 gallery 和相关 Python 工具）、输入语料及标签摘要，核对被测试 APK 实际加载的 gallery 字节。缺几何只能在明确标注 `no_board:true` 的负例上接受。

## 留出排序评测

producer 的 `rankings` 来自真实 `Match3MoveRanker.rankedMoves`，不在 Python 重写排序。人工标注格式：

```json
{
  "independent":false,
  "manifest_sha256":"冻结清单摘要",
  "frame_sha256":{"board-a.png":"已审阅帧的 SHA-256"},
  "boards":[{"id":"board-a.png","reviewed":true,"relevant_swaps":[[0,0,0,1]]}]
}
```

坐标为左上角零起点，交换端点顺序不影响判定。没有任务相关走法的盘需显式 `no_relevant_move:true`，单独报告，不用它提高任务相关率。存在相关走法而引擎没有候选的盘仍留在分母。

```sh
python3 scripts/evaluate_match3_ranking.py \
  --predictions validation/private/holdout/predictions.json \
  --labels validation/private/holdout/ranking-labels.json \
  --json validation/private/holdout/ranking-report.json
```

producer 固定清单文件和每帧实际字节摘要；评分器核对标注与回放的清单摘要，独立模式另外要求冻结／独立声明和逐帧摘要一致。越界交换、重复走法及互相矛盾的空盘声明均拒绝。

top-1 表示首选属于人工认可集合；top-3 表示前三个中至少一个属于该集合。可预声明 `--min-top1`／`--min-top3`，独立验收加 `--require-independent`。指标只描述一步任务相关性，不描述保证通关或随机级联收益。

## 同引擎软件成本

成本输入只需要完整棋盘帧和 manifest，不需要人工身份标签。运行前在 `gates.max_cost_increase_percent` 声明门槛（本计划为 10）：

```sh
python3 scripts/verify_match3_regression.py --mode cost \
  --holdout /明确提供的成本回放目录 --output validation/private/flywheel-cost
```

相同 Debug、相同新识别实现、相同棋盘，交错比较固定目录与 gallery＋实时采集。未缓存／缓存两组各 30 次配对，连续三次观测以实际触发采集，包含描述子 JSON 序列化。每个样本是整批棋盘扫描：本次为 10 盘×3 次观测，报告的毫秒和 P95 是批扫描值，不能解释为单帧延迟。分别固定墙钟／CPU 原始样本、分阶段 CPU 和 P95、帧／gallery／源码／APK SHA。先写报告再断言；首次失败不会被后测覆盖，不重跑挑低值。不包含异步磁盘 IO、录屏、浮层、TTS 或手机温升。模板剪枝仅使用通道和及 4×4 分块通道和的曼哈顿距离下界，超过原决定预算才提前拒绝，不改变阈值；独立完整距离回归核对分类与歧义结果。

## 仍需真实证据

- 与本批开发／图库选择分离的完整留出录制和人工三轴真值。
- HUD 数量、完成状态及排序走法的人工真值。
- 实体设备端到端成本（含诊断异步写入）；模拟器处理线程 P95 不能替代这部分。
- 同机条件匹配的 3×15 分钟温升、外部实际发声和玩家收益。
- 配置原签名后的最终 Release 门禁和相同 APK 字节交付。

未完成这些验收时，记录为待测；不修改历史 `verified`／`release_ready`，不自动上传、合并或发布。
