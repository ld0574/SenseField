# 魔力鸟被判底盘：0.4.6 真机读数与离线探针（2026-10-12）

本文记录 2026-10-12 一轮：玩家口述的魔力鸟规则、这一盘真机识别缺口读数、离线探针怎样把"为什么没认出来"定到具体代码行，以及顺带查出的三处坑。第 1～8 节那轮零源码改动，只读与离线复算；第 9 节是同日追加，按裁语落了三个判定线读数并出了新测试包；工作树里断流修复的三处改动属上一轮，见[断流修复出包记录](MATCH3_STALL_FIX_RELEASE_BUILD_2026-10-12.md)。

取证时刻：2026-10-12 14:58（本机实测）。判定线沿用现网常量：接受距离 0.12、领先优势 0.025（`Match3VisualCatalog.recognize`）。

## 1 玩家口述的新元素规则

玩家描述：头上顶着五颜六色花冠、形象像鸟的图案＝魔力鸟。

- 魔力鸟与相邻一个棋子交换：只要能把同色连成三个，这一波算翻倍积分。
- 两只魔力鸟直接相邻滑动：拿更多奖励分（不清色，直接放大奖励）。

代码里这两条收法早就写好了，不缺规则：`Match3Action.Type.BIRD_COLOR`（对应 combo_bird_color，清同色全部动物）与 `BIRD_PAIR`（对应 combo_bird_bird，清全盘动物），结算在 `Match3RuleEngine` 第 131～144 行。缺的是**认出来**：这一盘真机一次都没把鸟标成 `Kind.BIRD`。

## 2 真机读数：这一盘的识别缺口

诊断包（会话内部时刻，单位毫秒，已用千分位分隔避免被误认成账号）：

| 读数 | 值 |
| --- | --- |
| 会话时长 | 207,757 |
| 处理帧数 | 249 |
| 存档截图数 | 69 |
| 盘面行列 | 9 行 8 列，盘区像素 87,812 到 993,1830 |
| 未知格 | 72 |
| 元素复核族数 | 19 |
| 整盘有效标志（末次快照） | false |

- `Match3ElementObserved` 共 22 条：SURFACE 20 条、UNKNOWN 1 条、ANIMAL 1 条、**BIRD 0 条**。
- `Match3Ranking` 共 51 条，首选走法的 rule_id **51/51 全是 `ordinary_uncovered_animal`**（普通动物三连），没有一条走鸟的规则。
- 被略过的动作理由 `special_identity_or_effect_unknown` 出现 5 次。
- 首选坐标（该字段自报 zero_based，即从 0 数起）：`6,3->6,4` 13 次、`2,2->2,3` 13 次。后者换算成"从 1 数起"是第 3 行第 3 列换第 4 列，正是玩家截图里黄框标的那一格。

鸟在截图里的位置（从 1 数起：第 1 行第 6 列、第 2 行第 8 列）与 `Match3ElementObserved` 的 u7、u8 两族完全对上，坐标口径无歧义。

## 3 断流修复第一次在真机上留下证据

上一轮加的采集断流修复（`capture_health` 与 `capture_starvations` 两个字段）本轮首次真机回读：

- 快照 1,142,890,319（帧 237）：HEALTHY，starvations 0。
- 审计事件 1,142,894,347：`Match3CaptureHealth state=RECOVERING delay_ms=500 starvations=1`。
- 审计事件 1,142,895,162：`state=HEALTHY stalled_ms=816 starvations=1`。
- 快照 1,142,895,899（帧 242）：HEALTHY，starvations 1；末次快照 1,142,899,482（帧 246）仍是 HEALTHY。
- 会话收尾：249 帧、`interrupted:false`、`end_observed:true`。

读数含义：断流真的发生过 1 次，被探针抓到、进入恢复中、随后自己回到健康，帧继续跑到收尾。这正是要的效果——以前这类卡顿是静默的，没人知道。

## 4 离线探针怎么跑的

本机 adb 物理层不通，所以一切结论只能靠 JVM 侧证据。探针是仓外未跟踪目录 `_scratch_diag/birdprobe/`（不进 git），用仓库真源＋桌面替身的 `android.graphics.Bitmap/BitmapFactory` 与 `android.content.Context`，JDK 17，org.json 取自本地 gradle 缓存。复跑：

```
cd _scratch_diag/birdprobe
javac -encoding UTF-8 -cp "<gradle 缓存里的 json-20240303.jar>" \
  -sourcepath "<本目录 stub;仓库 android/app/src/main/java;本目录 src>" -d out $(find stub src -name '*.java')
java -Djava.awt.headless=true -cp "out;<同一个 jar>" com.openkhub.sensefield.FrameProbe files <截图jpg> 1,6 2,8
```

两个探针：`BirdProbe` 吃诊断包里的 64 像素元素缩略图；`FrameProbe` 吃诊断包里的全屏截图（432×960），按 0.4 比例把盘区像素换算到截图坐标，再走真源的 `obstacle()`、`animal()`、`recognize()` 与色相兜底 `classifyCell()`。日志落 `_scratch_diag/birdprobe/logs/`。

目录装载读数：`available=true id=hanging-cards-v3-idle-animals cells=92`，其中绑定成 BIRD 的模板 3 张。

## 5 探针读数

全屏截图两帧（帧号 80、82 恰是 u7-2、u8-2 采样时刻，同一毫秒）：

| 格（从 1 数起） | obstacle() | animal() | 最佳模板 | 距离（帧80／帧82） | 首个异类 | 领先优势 | 色相兜底 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 第1行第6列 | null | null | 鸟模板 bound_50b7 | 0.2091／0.2092 | 底盘 0.2773 | 0.0682／0.0696 | 'G'，中心色 4aea3e |
| 第2行第8列 | null | null | 鸟模板 bound_50b7 | 0.2353／0.2359 | 底盘 0.2846 | 0.0493／0.0496 | 'G'，中心色 1db63a |
| 第2行第2列（对照，真机认成 ANIMAL R） | null | 给出身份 | 底盘 0.2079 | 动物模板 0.2152 | — | 0.0073 | 'R' |

整盘 72 格扫下来：`obstacle()` 只有 15 格非空，全是底盘（对底盘模板距离 0.0205～0.0990，真命中），其余 57 格返回 null。

64 像素缩略图那条路（四张鸟图，按真源同款内缩 10%）：`recognize()` 全部返回 null，但前三名全是鸟模板，距离 0.2235～0.2429，第四名才是底盘 0.2898～0.2909，领先优势 0.0469～0.0674。同批若改成不内缩，领先优势塌到 0.0056～0.0109——鸟外围那圈光环对模板匹配影响很大。

## 6 原因：定死了什么，还没定死什么

定死了（两帧独立复现）：

1. 鸟格的 SURFACE **来自 `Match3Sampler.java` 第 169～172 行的色相兜底**。鸟的中心是鲜绿，`classifyCell` 返回字母 'G'；'G' 既不是空格、不是空位也不是未知点，按该分支一律落成 `Kind.SURFACE`。
2. **不是**第 153～154 行的雪块降级（鸟不是 SNOW），**也不是**目录里底盘模板命中（`obstacle()` 实测返回 null）。
3. **"鸟被判成更像底盘"这个猜测不成立**：两条独立离线路径里，鸟模板都排第一，领先优势 0.047～0.070，是 0.025 判定线的两到三倍。

还没定死（离线取不到，不许蒙）：

- 真机分辨率下鸟模板的绝对距离到底比 0.12 差多少。离线两条路都是缩小图，高频细节被毁：同一帧里普通动物模板距离 0.19～0.27、而底盘这种平面模板能到 0.02～0.09，说明绝对值被系统性抬高，**只有排序可信、绝对值不可信**。
- 22 张元素缩略图里没有任何"真机认对了"的正样本（能进元素包的，都是设备自己没认出的格），所以这批语料本身没法给 0.12 定标。
- 结论边界：这盘是"补一张飞法不同的鸟姿态"还是"别的"，需要一次真机分辨率的 16×16 原样读数才能定；本机拿不到，不能写成已定。

## 7 顺带查出的三处坑

1. **同一个诊断包里有两套行列口径**。`DiagnosticRecorder.java` 第 312 行写的是 row+1／col+1（从 1 数起），`Match3FlywheelHarvest.java` 第 34、62 行写的是原始索引（从 0 数起），`Match3Ranking` 的 coordinates 又自报 zero_based。包内交叉对格必须先认字段出处，否则差一格。
2. **元素缩略图混进非棋盘内容**。人眼判读 22 张图，其中 4 张不是棋盘格（一个"发送"按钮、系统识屏浮层两处、音量图标一处）。缩略图裁的是格区像素，浮层压在盘区上就会一起进来。这是人眼判读，不是仪器读数。
3. **飞轮的自我强化路径存在但这盘没走到**。`Match3FlywheelHarvest.java` 第 48 行把 UNKNOWN／SURFACE／COOKIE 三类当合格原料，所以"鸟被判底盘→当底盘样本喂回飞轮"在代码上是通的路。但本包 43 条采样的 kind 分布是 ANIMAL 7、COOKIE 19、SNOW 1、其余 16 条为对象级样本，**SURFACE 0 条**——这条路径这盘一次都没被触发，不能写成已发生。

## 8 待裁与下一步

- 要不要预防性地给飞轮收样加一道闸（身份未确认就不收底盘类样本，即原提案 B）。现在只有代码路径证据，本包 0 命中；当轮裁为"等真机遇再开"，本轮不动这条路径，见第 9 节边界段。
- 给进入复核的格多记三个读数：最佳模板名、最佳距离、首个异类距离。领导裁"先加"，本轮已落进诊断过门禁，一次真机回读就能把"0.12 还是 0.025 在拦鸟"定死；实现与读数见第 9 节。
- 魔力鸟的播报词与推荐文案还没做（现在 51/51 推普通三连，用户听不到"翻倍"这条信息）。
- 上一轮遗留待裁四条不变：FAILED 是否停服务、生产者停帧还是 handler 卡、语音打包脚本的 Windows 分隔符假阳性、手机侧测试位连通性。

## 9 判定线三读数落地：门禁、反向验证与测试包（同日追加）

领导当轮裁语是"先加 3 个诊断字段，试试。然后跑个本地测试验证，最后等真机遇再开飞轮闸"，本节记这一条走完了没有。

**加了什么。** 进入复核的格在 `Match3ElementObserved` 事件里多带三个字段，读的就是判定线当时看见的东西：

- `admission_template`：那一格最像的模板名；一条都没定出最像项时不写这个键。
- `admission_best_distance`：最像项的绝对距离——绝对距离闸门（0.12）量的就是它。
- `admission_second_kind_distance`：最近的异类距离；领先量＝它减最佳距离，领先量闸门（0.025）量的是这个差。

两条换算口径：距离统一裁到小数点后 4 位；"没看见"（无穷大）一律写成 -1，因为 JSON 放不进非有限数，字段静默消失就是静默事故，所以宁可留一个显式的 -1。第 6 节那句"到底是 0.12 还是 0.025 在拦鸟"下次真机回读一眼可判：最佳距离大于 0.12 是绝对距离拦的；最佳距离不大于 0.12、但两数之差小于 0.025 是领先量拦的。

**门禁。** 三次跑都带 `--rerun-tasks`，日志里的"25 actionable tasks: 25 executed"逐条核对过，没有 UP-TO-DATE 冒充执行；时刻取自各凭证落盘后的实测 mtime。

| 时点 | tests | failures | errors | skipped | 测试类 |
| --- | --- | --- | --- | --- | --- |
| 15:08 改代码前 | 575 | 7 | 0 | 0 | 76 |
| 15:18 加字段后 | 581 | 7 | 0 | 0 | 77 |
| 15:19 突变还原后复跑 | 581 | 7 | 0 | 0 | 77 |

新增 6 条全绿；7 条红的还是基线那 7 个名字（6 条 `AsrModelStoreTest` 断点续传 ＋ 1 条 `AssistantTtsCacheTest` 软链接清扫），逐名一致，没新增、没 skip、没放宽断言、没删测试。判卷标准（`docs/plans/消消乐目标价值排序.md` 与既有全部 Match3 测试类）一字未改。

**反向验证（证明报警器会响）。** 两次定向突变都只改本轮新写的代码，改前把源文件 `cp` 进 `_scratch_diag/mutbak/`：

1. 15:18 让判定线读数不落进容器（`recognize` 里那句记录 best／error／second 的赋值被短路）→ 新测试类 6 条里 4 条红。
2. 15:19 把 -1 兜底摘掉（非有限数原样交给 JSON）→ 3 条红，含"每格必须带得上两个距离"那条。

还原后用 sha256 复认：`Match3VisualCatalog.java` 改前与还原后同为 `f310d987` 开头那一串，全文 `MUTATION` 标记命中 0 次；15:19 那次复跑就是表里第三行。

**测试包。** 走上一轮同一条路：`assembleRelease --offline -x verifyBundledSpeechAssets` 于 15:28 绿（BUILD SUCCESSFUL in 40s，51 个任务里 12 个执行、39 个 UP-TO-DATE——判断改动有没有真编进去不靠任务计数，靠包内字符串）。未签名产物 17,887,521 bytes，用本机 Android debug 密钥签名后 17,979,324 bytes，实测 mtime 15:28:41，sha256 `79da8710` 开头那一串，签名证书指纹 `624a0d84` 开头（与上一轮 stallfix 包同证书，覆盖安装不撞签名）。包内 `classes.dex` 能搜到三个新字段名，证明字段随包出厂而不只在源码里。

局域网测试位仍在 8777 上跑：15:28:58 从本机回环地址取回 17,979,324 bytes、sha256 与原件逐位一致。手机侧链接是 `http://192.168.0.107:8777/SenseField-0.4.6-admissionreadout-arm64-release-cand.apk`（IP 当轮实测）。手机能否连通 8777 仍未证，那是上一轮遗留第四条。

**这一轮没验到的（边界）。**

- Debug 变体出不了包：本树 `configureCMakeDebug` 要现下 sherpa-onnx 的 C API 头文件，本机 raw.githubusercontent 那条路被重置，回 `SSL connect error`；上一轮靠本机代理过网，这轮代理没开。所以本包是 Release 未签名再打 debug 证书，与上一轮同类，不是官方签名链。
- `androidTest` 侧编译无法离线验证：`androidx.test.ext:junit:1.2.1` 不在本机缓存，`--offline` 直接解析失败。为不动既有 Match3 仪器测试，我给 `elementSample` 保留了原来三参数的入口（走 null 读数，字段写成 -1），仪器测试的调用形状一个字没改——但这只经代码路径推理，没被机器证过。
- 0.4.6 正式 Release 仍要原签名与完整语音资产；本包只作局域网测试位用途，没碰 0.4.5 的公开附件，也没改公网更新清单。
- 飞轮那道闸按裁语本轮不开：本包 SURFACE 采样 0 条，等真机真走到那条路径再裁。

## 复跑口径披露

- 本轮所有识别结论来自 JVM 离线复算＋真源代码路径，**没有任何一条是"已真机验证"**；真机侧证据只有诊断包内的事件读数。
- 探针里的绝对距离与真机不同尺度，只能用于排序判断；这一条在第 6 节已写成边界，引用本文数字时不许把它当设备阈值依据。
- 探针目录与被解包语料都在 `_scratch_diag/`（未跟踪、不进 git）；本文不含机型、账号、聊天目录或本机绝对路径。

## 10 彩虹鸟：入口一直都有，缺的是现网样本；优先级用测试钉死（同日追加）

**先更正我上一轮的错话。** 我说过「根本没有识别入口」，不成立。`match3-vine-v1.json` 里一直带三条 `magic-bird` 模板，绑定到 `BIRD`／无覆盖／门开的状态；`Match3Action.addPair` 也一直在把「鸟＋普通动物」枚举成 `BIRD_COLOR`。真正卡住的是距离：那三条模板在现网鸟格上的实测距离是 0.1796／0.1784／0.1629／0.1624（四个被复核过的格子），全部落在剪枝下界 0.145 之外，于是候选连一次测距都没拿到就被跳过；同族其它模板也全被跳过时，次优距离保持正无穷，边界判定形同空转。这是数据缺样，不是代码缺路径。

**改了什么（一处，纯数据）。** 给同一份补充目录追加一条来自真机诊断包的魔力鸟样本：19:40:33 落盘，64798 bytes，sha256 以 `60197b7f` 开头，`cells` 从 29 条变 30 条，离 65536 的硬上限还剩 738 bytes。追加前先把整份文件重压成紧凑 JSON（省出 2558 bytes），并用深度 JSON 等值证明重排没有改动任何一个解析出来的值。样本取四张 64 像素元素小图的逐通道中位数，理由是一个坑位要盖住两种姿态：中位数模板最差 0.0483，单张真实小图最差 0.0890。

追加后同一四格的实测距离变成 0.042／0.0483／0.0407／0.0403（准入线 0.12），最近的非鸟对照 0.2802，与边界余量之间约 5.8 倍分离。全语料前后对照：20 张元素小图里恰好 4 张身份改变，其余 16 张逐字节不变。

**链路是否真走到棋盘。** `Match3Sampler.samplePosition` 拿到 `obstacle()` 的返回值后，只要身份已知就原样写进 `cells[r][c]` 并跳过后续着色；那条降级只对雪块生效。`recognizeLargeObjects` 只重写身份未知与未知表面的格子。所以识别出的鸟原样进棋盘，并作为可交换格进入 `Match3Action.enumerate`。这条以代码路径加 JVM 单测为证，真机没证。

**优先级那一头：先量，没改生产代码。** 手写棋盘跑离线探针（`_scratch_diag/run_bird_rank_probe.sh`，19:53 编译执行）：

- 同一只鸟有两个邻色可选时，过关收益大的那个排第一：青蛙五只、进度 833 毫；河马那条进度 0。
- 鸟一次换完就收满本关任务时它排第一（完成目标数 1、`allTargetsFinish` 为真、直接收益 6 只），远处普通三连（收益 3 只、完成目标数 0）退到第二位。
- 反面闸门：鸟碰不到任务色时，它不许插到能过关的三连前面——三连第一、鸟第二。这条防的是「为了优先用鸟而谎报收益」。
- 最后一块饼干（剩 5 步）：贴饼干的那一步排第一（相关接触 1、目标距离 1），棋盘另一头的三连目标距离 5、排第二。饼干剩余数读不到时仍按已观察到的接触面排第一，但不谎称过关。

**新测试与门禁。** `Match3BirdPriorityTest` 七条（手写棋盘＋手写期望，不由 `Match3MoveRanker` 自己的评分函数生成）；加上上一轮的 `Match3MagicBirdRecognitionTest` 七条。19:57:16 全量门禁 `--offline --rerun-tasks`：595 tests completed，7 failed，skipped 0，errors 0；红的仍是那七个名字（六条 `AsrModelStoreTest`、一条 `AssistantTtsCacheTest`）。计数链：581（开工基线）到 588（加识别七条）到 595（加优先级七条）。

**反向验证。** 19:56:09 把 `Match3Action.java` 备份进 `_scratch_diag/mutbak/` 后做定向突变：让「鸟＋普通动物」那条分支不再产出动作类型，等价于候选不再枚举。新测试类七条里四条红，两条饼干用例保持绿——说明这四条真在盯鸟这条路径，而饼干用例不依赖它。还原后 sha256 与改前逐位相同（以 `401d71cb` 开头），全文 `MUTATION` 标记命中 0 次，随后复跑全量门禁即为上节那行读数。

**没做的三件事，都在等裁。**

1. 魔力鸟加其它特效（直线、爆炸）至今不枚举：`Match3Action.omittedReasons` 明写这个组合未经核实，且既有冻结测试断言它必须缺席。玩家口述「鸟跟另一种交换也会被消除、而且积分奖励大」与此冲突。要接就得先拿到该组合的真实结算证据，我不会单方面改判卷文件。
2. 播报里鸟那一步只说目标名（例如「收集青蛙」），不说「魔力鸟」。改文案要动 `Match3Hint` 的句子长度，而上面的硬要求是坐标播报不许再被拖长。
3. 补充目录的 65536 硬上限只剩 738 bytes。再加第二条鸟姿态（能把最差距离从 0.0483 压到 0.0144）就必须抬上限。

**边界（读这些数字时不许越过）。** 本节全部读数来自 JVM 离线复算与代码路径，没有一条是「已真机验证」。诊断包里的元素小图是真机单元的 64×64 最近邻重采样，离线绝对距离比设备偏大（雪块一例：设备 0.116 对离线 0.1367，约加 0.02 悲观），只能用于排序比较，不能当设备阈值。包内整屏图只有 432×960 缩略，无法按原几何复演。追加样本的来源帧是开发者复核过的帧，因此这条改动是工程回归，不是独立留出正确率。


## 11 彩虹鸟第二条姿态样本：先把补充目录上限抬上线（同日追加）

**裁决来源。** 上一轮把「封面补充目录 65536 字节上限」列为待裁第 3 条；本轮领导原话「先抬目录上线，再把 2 样本加进去。专注于修复了识别，然后给我一个最新下载测试的链接」即为裁决：先抬上限，再加第二条姿态样本，范围锁在识别本身。

**代码改动（一处具名常量）。** `android/app/src/main/java/com/openkhub/sensefield/Match3VisualCatalog.java` 新增 `COVER_CATALOG_MAXIMUM_BYTES = 98304`，两处强制点都改成引用它：资产流读取那处（约 319 行，累计字节超限即抛）与 `withReviewedSupplement` 的长度检查那处（约 402 行，抛在解析之前）。另一条上限——补充目录格数不得超过 32——本轮没碰，现值 31 格仍在线内。该文件改前与两次突变还原后 sha256 同为 `b0c9f97a` 开头那一串。

**资产改动。** `android/app/src/main/assets/match3-vine-v1.json` 由 64798 字节变 67079 字节，sha256 由 `60197b7f` 开头变 `b897ca98` 开头，格数 30 变 31，距新上限余 31225 字节。样本做法与前一条同口径：取 u1-1 与 u1-2 两张 64 像素元素裁剪，按设备采样口径（四边各内缩十分之一、16×16 最近邻）逐通道取中位数，得 256 点 768 字节，base64 后 1024 字符；`kind` 与 `rule_binding_ref` 都绑 `bound_50b7e909ad0d9ade699a`，`rule` 为 `reviewed_state`，`family_id` 为 `magic-bird`，`reviewed` 为真，掩码 256 位全 1；`metadata` 新增 `magic_bird_pose_addendum`，声明 `independent` 为假、`scope` 为 `engineering_regression_on_reviewed_frames_not_independent_accuracy`。生成脚本在仓外 `_scratch_diag/_tmp_add_bird_pose_exemplar.py`，落盘前断言：无 CR 字节、不超 98304、格数恰 31、去掉末格后与改动前逐格相等、`metadata` 去掉新增键后与原值相等。

**逐格读数（补样前后差分）。** 下表左边一列是离线复算，右边一列走同一份真源代码里的准入函数，两边数值逐位相同；只能用于排序判断，不许当设备阈值依据。

| 裁剪格 | 补样前最近鸟模板距离 | 补样后距离 | 补样后判定 |
|---|---|---|---|
| u1-1 | 0.0420 | 0.0072 | BIRD |
| u1-2 | 0.0483 | 0.0072 | BIRD |
| u14-1 | 0.0407 | 0.0407 | BIRD |
| u14-2 | 0.0403 | 0.0403 | BIRD |

其余 16 张邻居格逐位不变：判 SURFACE 的五张（u6-1 0.0384、u12-1 0.0743、u13-1 0.0676、u15-1 0.0524、u16-1 0.0787）、有最近模板但越过判定线而弃权的三张（u3-1 0.1367、u4-1 0.1224、u9-1 0.1230）、连候选都没有的八张（u2-1、u5-1、u7-1、u8-1、u10-1、u11-1、u17-1、u18-1），补样前后一列对一列完全相同。目录总格数 93 变 94。新样本到最近非鸟控制格 u6-1 的距离是 0.2926，与判定线 0.12 之间没有灰区。

**这条样本是加固不是准入。** 四格鸟裁剪在第一轮那一条样本下就已经全部准入，本轮补的是同一只鸟的另一种姿态；它的可证效果是把四格里的最坏距离从 0.0483 压到 0.0407，同时让 u1 姿态自身从 0.042 量级进到 0.0072 量级。上一节第 3 条预估的「压到 0.0144」是当时按单姿态候选算出的中间值，本轮按落盘口径实测是 0.0072，引用时以本文实测数为准。测试没有假装「没有第二条样本就认不出」——那条反向断言写的是把两条现网样本一起摘掉后四格全部重新弃权，与上一轮一致。

**测试。** `Match3MagicBirdRecognitionTest` 十条全绿（原七条）：改名三条以适配两条样本，新增三条——`theSecondPoseTightensTheWorstBirdDistanceWithoutMovingTheLine`（差分：新样本只会缩短距离且他类领先量仍守住 0.025 余量）、`theSecondPoseRecruitsNoExtraNeighbour`（16 张邻居格一张都不许被读成鸟）、`raisedCeilingStillRejectsASupplementOverTheMaximum`（抬帽后的闸门仍在）。`Match3BirdPriorityTest` 七条全绿未改。门禁 `:app:testDebugUnitTest --offline --rerun-tasks` 于 20:22 读数 598 条、失败 7、错误 0、跳过 0，7 条红仍是既有的 `AsrModelStoreTest` 六条与 `AssistantTtsCacheTest` 那条 Windows 符号链接与文件锁名字，一字未变；计数链 581 → 588 → 595 → 598。

**反向验证（证明报警器会响）。** 两次定向突变都只动本轮新写的东西，动前先 `cp` 进 `_scratch_diag/mutbak/`：

1. 把常量从 98304 改回 65536 → 10 条里 6 条红，含 `everyArchivedBirdCropBecomesABirdCell`：67079 字节整份补充目录被拒收，两条现网样本一起消失，四格重新弃权。
2. 把资产退回补样前的 64798 字节三十格 → 3 条红，正是依赖第二条样本存在性的三条（两条样本一起摘才弃权的那条、姿态差分那条、来源声明那条）。

还原后以 sha256 复认：源文件回到 `b0c9f97a` 开头、资产回到 `b897ca98` 开头，全文 `MUTATION` 标记命中 0 次。另外 `raisedCeilingStillRejectsASupplementOverTheMaximum` 是一把内置差分闸：同一份文档用元数据填充补到恰好 98304 字节判 `loaded`（这本身就过不去旧的 65536 线，证帽真抬了），再加一字节判 `rejected:` 开头（证抬帽没把闸门拆掉）。

**测试包。** `assembleRelease --offline -x verifyBundledSpeechAssets` 于 20:24 绿（BUILD SUCCESSFUL in 46s，51 个任务里 14 个执行、37 个 UP-TO-DATE；判断改动有没有真编进去不靠任务计数，靠包内字节）。未签名产物 17889461 bytes，用本机 Android debug 密钥签名后 17979324 bytes，实测 mtime 20:25:55，sha256 `e86bfc5c` 开头那一串，签名证书指纹 `624a0d84` 开头（与上一轮 stallfix、admissionreadout 两包同证书，覆盖安装不撞签名）。从包内解出 `assets/match3-vine-v1.json` 与源资产逐字节相同（67079 bytes、sha256 `b897ca98` 开头），证明第二条姿态样本随包出厂而不只在源码里。

**局域网测试位。** 8777 端口仍在 0.0.0.0 上监听。20:26 从本机回环地址取回 17979324 bytes、sha256 与原件逐位一致；再从当轮实测的主机局域网地址取回同样 17979324 bytes、HTTP 200。手机侧链接是 `http://192.168.0.107:8777/SenseField-0.4.6-birdpose-arm64-release-cand.apk`（IP 当轮实测，两次取数分别是回环与该地址）。手机能否连通 8777 仍未证，那是上一轮遗留第四条。

**这一轮没验到的（边界）。**

- 本轮所有识别结论来自 JVM 离线复算＋真源代码路径，**没有任何一条是「已真机验证」**；真机侧要等装机后走那四格鸟的现场。
- 离线复算的绝对距离与真机不同尺度（实测约 +0.02 的重采样偏差），只许用于排序判断，不许当设备阈值依据。
- Debug 变体仍出不了包：本树 `configureCMakeDebug` 要现下 sherpa-onnx 的 C API 头文件，本机 raw.githubusercontent 那条路被重置，回 `SSL connect error`。所以本包是 Release 未签名再打本机 debug 证书，与上一轮同类，不是官方签名链。
- `androidTest` 侧编译无法离线验证：`androidx.test.ext:junit:1.2.1` 不在本机缓存，`--offline` 直接解析失败。
- 0.4.6 正式 Release 仍要原签名与完整语音资产；本包只作局域网测试位用途，没碰 0.4.5 的公开附件，也没改公网更新清单。
- 未 commit、未 push、未 tag。

**仍待裁（不自行落地）。**

- 鸟与另一个特殊棋子的组合是否进枚举：现由一条冻结测试强制其缺席，本轮未动。
- 播报理由是否点名「魔力鸟」：现在的理由说的是目标（例如「收集青蛙」），改成点名鸟会与「坐标句不许变长」那条相撞。

## 复跑口径披露（随第 11 节追加）

- 本轮所有识别结论来自 JVM 离线复算＋真源代码路径，**没有任何一条是「已真机验证」**；本轮没有新增真机证据，真机侧只有上一轮诊断包内的事件读数。
- 探针里的绝对距离与真机不同尺度，只能用于排序判断；引用本文数字时不许把它当设备阈值依据。
- 探针目录与被解包语料都在 `_scratch_diag/`（未跟踪、不进 git）；本文不含机型、账号、聊天目录或本机绝对路径。
## 12 两项裁决落地：鸟＋直线进候选、播报点名魔力鸟（同日追加）

玩家本轮把上一节挂起的两项待裁直接拍死：「把两种特效加一起加进枚举」与「播报，你直接点名魔力鸟」。范围我按最小可证的一步收：只放**鸟＋直线**这一对，鸟＋爆炸与身份未定的特效仍然缺席——爆炸射程从没被观测过，一条候选都不给。

**枚举落点。** `Match3Action.addPair` 新增分支，产出 `Type.BIRD_SPECIAL`、规则号 `combo_bird_line`（这条规则号本来就在规则目录里，状态是 partial/pending，之前只是没有代码走它）。`omittedReasons` 侧同步收紧：只有当伙伴**不是**已确认直线时才记 `bird_special_combination_parameters_unverified`，并把原串名从 `bird_special_combination_unverified` 改成带 parameters 的写法，让「弃指原因」说的是参数而不是「这组合到底有没有」。

**结算落点。** `Match3RuleEngine` 里这条组合走的是**下界**：清除面 = 鸟的全屏同色 ∪ 直线自己那一行或那一列，随后立即 `uncertain("bird_special_combination_settlement_unverified")`。这条 uncertain 把 `exact` 打成 false，于是 `Result.successorKnown=false`，`Match3Search` 拒绝从这一步往下展开后续盘面——与既有 `CHARGED_PAIR` 的处理同一个House style。为什么不宣称真实结算：现网里鸟跟直线互换会把同色动物全变成直线再一起开火，这个转换我们一帧都没观测过，写进下界等于把没见过的东西当已知。

**播报落点。** 只有 `Match3MoveValue` 的理由位加长：鸟的三种走法（颜色、配对、直线组合）前缀「魔力鸟」。坐标段仍由 `Match3Coach.swapSpeechWithQuadrant` 原样产出，`Match3Hint` 一行没动——冻结计划文档第 22、109 行禁的是把坐标说长，理由位加长不在禁列，所以判卷文件不需要改。`Match3Coach` 的知识条目补了一句组合口径，没有测试断言那段文字。

**判卷文件。** 按上一节声明的唯一一处解冻：`Match3RuleEngineTest.unverifiedSpecialPairsAreAbsentEvenIfTheirColorsCouldMatch` 改名 `...AbsentExceptABirdWithALine`，前半（两只普通特效互换仍缺席）逐字保留，后半从「鸟＋直线也缺席」翻成「恰好一条 BIRD_SPECIAL 进候选」，并在方法上写清裁决日期。其它 Match3 测试类与 `docs/plans/消消乐目标价值排序.md` 一字未改。

**新用例。** `Match3BirdSpecialCombinationTest` 七条，棋盘与期望清除数全部手写：候选恰好一条且规则号为 `combo_bird_line`；候选进了名单就不许同时出现在弃指名单里；爆炸与未知身份伙伴仍缺席并留下弃指原因；横向直线那一行多清掉 (0,2) 一格而纯颜色清扫清不掉；纵向伙伴开自己那一列、碰不到横向才会碰的那格；下界口径带 `successor_known=false` 且鸟与直线各发一次 TRIGGER；播报整句等于「坐标段＋，魔力鸟收集青蛙。」。`Match3BirdPriorityTest` 只改一条断言（`收集青蛙` 变 `魔力鸟收集青蛙`），另两条饼干断言不含鸟、未动。

**门禁。** `:app:testDebugUnitTest --offline --rerun-tasks` 于 21:09 读数 605 条、失败 7、错误 0、跳过 0，80 个结果类；7 条红仍是既有的 `AsrModelStoreTest` 六条加 `AssistantTtsCacheTest` 那条 Windows 符号链接与文件锁名字，一字未变。计数链 581 → 588 → 595 → 598 → 605。

**反向验证。** 四次定向突变，每次都断言锚点恰命中一次、跑完按原字节恢复并复核 sha256：把 `BIRD_SPECIAL` 的赋值改成空类型 → 14 条里 5 条红；删掉那条 uncertain → 1 条红（正是下界那条）；删掉播报前缀 → 2 条红（组合播报与优先级各一条）；把弃指收紧条件里的「不是直线」去掉，让鸟＋直线同时进候选又被声明弃指 → 1 条红。恢复后三个源文件 sha256 与突变前逐位一致（`Match3Action.java` c8e818616b9c215c、`Match3RuleEngine.java` b3d153107e8b7442、`Match3MoveValue.java` 0b1c38666eafcef6，仍为纯 LF，全文件 CR 计数 0）。

**一次事故留痕。** 突变驱动第一版把 gradle 的相对命令交给 subprocess，Windows 下直接 FileNotFoundError，崩在「已写坏、未恢复」的窗口里，`Match3Action.java` 带着 M1 的突变留在工作树上一小段时间。当轮按同一锚点反向替换恢复，并核对 sha256 前缀与字节数（6691）与基线一致才继续。教训记死：**定向突变要在仓库副本上跑，不在工作树上跑**；驱动已改成 try/finally 无条件恢复，但恢复不该依赖崩之前的那一步侥幸。

**测试包。** `assembleRelease --offline -x verifyBundledSpeechAssets` 于 21:09 绿（BUILD SUCCESSFUL in 29s）。未签名产物 17,889,677 bytes，本机 Android debug 密钥签名后 17,983,420 bytes，实测 mtime 2026-10-12 21:10:18，sha256 完整串 `e984d4c181f84c3f63e5034286d88fba422ce6ed91d855acad461a9be419a1df`，签名证书指纹 `624a0d84` 开头——与 stallfix、admissionreadout、birdpose 三包同证书，覆盖安装不撞签名。包名 `com.openkhub.sensefield`，versionName 0.4.6、versionCode 23、native-code 仅 arm64-v8a。包内 `classes.dex`（910,864 bytes）实测命中：`combo_bird_line` 1 次、`bird_special_combination_settlement_unverified` 1 次、`bird_special_combination_parameters_unverified` 1 次、`BIRD_SPECIAL` 1 次、「魔力鸟」10 次；`assets/match3-vine-v1.json` 67,079 bytes、sha256 `b897ca98` 开头，与源资产逐字节相同，证明上一轮的第二条姿态样本随包出厂。

**局域网测试位。** 8777 端口仍在 0.0.0.0 上监听（PID 当轮实测）。21:11 从本机回环地址与主机局域网地址各取一次，两次都是 17,983,420 bytes、HTTP 200、sha256 与暂存原件逐位一致。手机侧链接：`http://192.168.0.107:8777/SenseField-0.4.6-birdcombo-arm64-release-cand.apk`。手机能否连通 8777 仍未证，那是前几轮遗留的同一条。

**仍挂着的事项。** 一、没断言也不打算断言：鸟＋直线一定排在鸟＋同色普通动物之前——真实游戏会把同色全变直线，收益显然更高，但我们的下界给不出这个保证，硬排序就是替游戏编规则。二、鸟＋爆炸要接进候选，需要玩家给一次该组合的真实结算读图。三、0.4.6 正式 Release 仍缺原签名与完整语音资产，测试包走的是本机 debug 证书，二者不可混用。四、跨树合并（把续 14 的三条并进 0.4.34 基线）仍未做。

**复跑口径（随第 12 节追加）。** 门禁：`cd android && ./gradlew :app:testDebugUnitTest --offline --rerun-tasks`，读数用 `python _scratch_diag/_tmp_gate_tally.py`（它扫 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 汇总四条计数并列红名）。突变反向验证：`python _scratch_diag/_tmp_mutate_bird_special.py`，脚本自带锚点唯一性断言与 try/finally 恢复，跑完会在末尾打印「all three sources byte-identical to baseline」；没有这行就当它没跑过。包内容取证：`python _scratch_diag/_tmp_verify_birdcombo_apk.py`（列 dex 命中标记与资产逐字节比对）、`python _scratch_diag/_tmp_serve_check.py`（两个地址各取一次并核 sha256）。签名与 badging 需要显式 JDK 与 build-tools 路径：`/c/Java/jdk-17.0.20+8/bin/java.exe -jar /c/Android/Sdk/build-tools/35.0.0/lib/apksigner.jar`，`aapt2.exe dump badging`；这些命令当轮都亲手跑过，`java` 与 `apksigner` 直接敲名字在本机 shell 里是不通的。


## 13 拉取上游 31 个提交：采上游，只把判定线读数并回新基线（2026-10-15）

- 连接：GitHub HTTPS 直连可读，`git ls-remote --heads` 返回 3 条分支。
- 分叉：本地分支 0 领先／31 落后，`1f4d782` → `29cb75b` 纯快进；顺带取到 `v0.4.6`、`v0.4.7` 两个标签与 `main` 的 `4c6d5e6`。全程未 commit／push／tag。
- 保全：13 个已跟踪改动收进一条带唯一标记的 stash `sensefield-jevl2-dirty-20261015T152413`；276 个改动＋未跟踪文件按字节备份到 `_scratch_diag/local_dirty_backup_20261015/`，附逐文件 sha256 清单；备份前自检 `Match3Action.java` 前缀 `c8e818616b9c215c` 与拉取前一致。
- 撞车事实：上游 `04df08e` 已把两项裁决独立落地——枚举里叫 `BIRD_LINE`、规则 `combo_bird_line`、播报写「魔力鸟组合」；我这轮是同一件事的另一套命名（`BIRD_SPECIAL`＋「魔力鸟收集X」）。按「采上游」处理：我那两套鸟测试移出工作区存档，上游 `Match3BirdLineRankingTest`、`Match3CookieBirdRankingTest` 作规格。
- 只并回上游没有的判定线读数，四个源文件：`Match3VisualCatalog`（`ADMISSION_MAXIMUM`／`ADMISSION_MARGIN`／`COVER_CATALOG_MAXIMUM_BYTES` 三个常量＋`Admission` 侧信道＋`recognize`、`classifyObstacle` 的出参重载）、`Match3Sampler.elementAdmission`、`DiagnosticRecorder.elementObservedData` 的三个键、`Match3LiveService` 一处调用点。上游新加的灰毛球补充目录与棋盘反馈各有自己的预算，那两处字面量没动。
- 门禁（`--rerun-tasks` 同口径两次）：纯上游 609 tests／7 failures／0 errors／0 skipped（78 类）；并回读数后 615／7／0／0（79 类）。红的 7 个名字与上游基线逐字相同：`AsrModelStoreTest` 6 条加 `AssistantTtsCacheTest#sweepNeverFollowsAnOwnedLookingSymlinkOutsideCache`。
- 反向验证三条，恢复一律写在 finally 里，跑完两个源文件都与基线字节一致：
  - 红：把 `recognize` 的回填删掉，4 条红；把诊断行那三个读数键删掉，1 条红，正是锁字段的那条用例。
  - 没红：把格子判定改回不接 `cache.admission`，全绿。成因说清：单测面从 `Match3Sampler` 起步的一条都没有（复跑 `grep -rln "new Match3Sampler(" android/app/src/test` 命中 0 个文件；`androidTest` 面命中 24 个文件但本机无设备通道跑不了），所以采样器到诊断行这段接线只有实况路径会走，本轮未补这条覆盖，记为缺口。
- 抬顶这条在新基线上有了实测意义：上游那版补充目录 65,507 字节，距旧上限 65,536 只剩 29 字节。
- 识别回归没跟过来，已存档并记遗留：`Match3MagicBirdRecognitionTest`（10 条）在新基线 6 条红。做过一次可逆的因由实测——把我那版目录（67,079 字节、含两条诊断包来源的姿态）整文件换上去再跑，红变 7 条，其中 4 条的错误从判定改成 `rejected:IllegalArgumentException`，也就是上游重写后的加载校验不收这版目录；而两张诊断包鸟帧的判定线读数在两个版本里都是 `Infinity`，一个候选都没打到。所以这不是样本来源被换掉的问题，是识别路径本身变了。目录已按字节还原：sha256 前缀 `bdb49a634dc01e83`、65,507 字节，`git diff` 对该文件 0 行。
- 本轮结束时的工作区：跟踪态只有 4 个 `M`，就是上面并读数的四个源文件；未跟踪剩 3 份案卷 md 与 `_scratch_diag/`。

**复跑口径（随第 13 节追加）**：门禁 `cd android && ./gradlew.bat :app:testDebugUnitTest --offline --rerun-tasks`；计数 `python _scratch_diag/_tmp_gate_tally.py`；三条突变 `python _scratch_diag/_tmp_mutate_readout_port.py`；因由实测的做法是先把 `app/src/main/assets/match3-vine-v1.json` 复制出保留副本再整文件替换、跑完按副本还原，还原判据是 sha256 前缀与字节数两个读数都对上（还原后实测 `bdb49a634dc01e83`／65507，替换期间实测过 `b897ca9817793a76`／67079）。
