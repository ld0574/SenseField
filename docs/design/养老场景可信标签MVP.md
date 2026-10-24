# 养老场景「可信标签」MVP 技术方案（方案一）

2026-10-25 ｜ 对应领导方向书《SenseField 养老护理场景第一性原理拆解》落地优先级 0-6 月重点：
**方案一「可信内容沙箱」＋动态风险标签系统的 MVP**。本轮只落判定引擎与播报纪律的纯逻辑层
（旁挂，不接播放路径），接入读屏服务与 TTS 属下一轮。

## 一、第一性原理定位

养老场景下老人面对屏幕的核心矛盾不是「看不清、点不准」，而是认知安全感缺失（分不清帮助与伤害）。
据此，广告治理从「Blocker」重定义为 **Trusted Interpreter**：不删除、不屏蔽，而是把不可信信息
翻译成老人可理解的风险提示；读取识别从「Reader」走向 **Action Validator**（方案二，未启动）。
两者是「可信感知」的一体两面：标签引擎消除认知威胁，读屏锚点建立认知锚。

## 二、工程红线（继承旁挂先例）

与 HapticSemantics（2af4964）、AudioContextLevel（ed3061f）同一纪律：

1. **零 Android import，纯 JVM**——四个新类可在任何 JVM 进程里复用（手机端之外，护理站大屏、
   审核后台可直接引用同一判定语义）。
2. **不接播放路径**——`CuePlayer`/`CueDispatcher`/`AssistantTtsPipeline` 零改动；生产代码仅新增文件。
3. **fail-silent 优雅降级**——null 输入、空文本、超长页面都判 NEUTRAL 并照常标注可信来源，
   引擎不抛异常出去；系统失效时老人回到没有系统的状态，不会更糟。
4. **语料纪律**——测试语料全部为本文件维护的脱敏合成话术与日常文本，不收真实案件原文、
   不收隐私截图内容。

## 三、五档语义

| 档位 | 语义 | 语音 | 留痕 |
| --- | --- | --- | --- |
| RED 高风险 | 命中养老诈骗组合话术（红规则目录） | 立即播解释，同指纹 10 分钟冷却后至多重播一次 | 指纹＋证据窗口 |
| YELLOW 第三方推广 | 营销/诱导内容 | 低频提示，同指纹 30 分钟一次 | 指纹＋证据窗口 |
| REVIEW 待人工审核 | 涉资金/健康但无诈骗特征（单看「请转账」不定罪） | 永不播报，不惊扰 | 进审核队列，白名单＋人工兜底 |
| GREEN 官方认证 | 命中官方服务内容签名，或来源包名在白名单 | 不播报 | 内容证据优先于来源信任 |
| NEUTRAL 无证据 | 既不贴绿也不贴红——没有证据不等于安全 | 不播报 | 来源可信位 |

## 四、模块与判定顺序

新增四个纯 JVM 类（`com.openkhub.sensefield` 包）：

- `ElderRiskLabel`——五档判定结果＋播报紧迫度＋播报去重指纹；
- `ElderRiskRules`——规则目录：8 组红规则（公检法冒充／医保社保恐吓／神药／高息返利／
  解冻刷单／验证码屏幕共享／冒充家人／中奖先交费）、3 组黄规则、3 组 REVIEW 兜底、
  15 条官方服务签名、AOSP 系统包名白名单（政务/医院包名由部署期配置补充，不臆造）；
- `ElderRiskLabelEngine`——判定入口 `evaluate(packageName, screenTexts)`；
- `ElderRiskAnnouncementPolicy`——可注入时钟的播报节流。

判定顺序：红＞黄＞待审＞绿签名＞可信包名＞中性。匹配前把全部节点文本归一
（去所有空白＋小写），挡「公 检 法」「医 保\n资 格」这类插字符绕过；规则全组命中才生效
（单组＝任一关键词命中），防资金词误伤正当文本。证据窗口取命中词前后各 18 字符（有界），
参与判定的文本总量 20,000 字符封顶（主线程成本上界）。

**可信内容与诈骗并存时内容证据优先**：可信包名＋屏幕出现诈骗话术＝RED（防页面劫持/仿冒）。

## 五、KPI 门禁（落地书 0-6 月指标）

`ElderRiskKpiGateTest` 把指标固化为门禁：**高风险召回 ≥95%**、**高危误报 <2%**。
当前语料：30 条合成高危（含插字符/换行变体）全中＝100% 召回；50 条无害文本
（政务、医疗、家庭、银行扣款、社区通知）高危误报 0%。涉资金/保健品近失样本落 REVIEW，
证明白名单＋人工兜底承接层有效。每条红命中必须带 ≥10 字老人可懂解释与 ≤80 字有界证据。
语料不是留出集——扩语料、跑真实脱敏回测是接入轮的事，本数字只证明引擎逻辑。

## 六、播报纪律（防「狼来了」）

同一屏会被 TYPE_WINDOW_CONTENT_CHANGED 反复判出同一条红/黄，不节流就是「后面一直重复」的
翻版；不节流的重复红又是狼来了——老人第二次就不听了。纪律：

- 红：新指纹立即播；10 分钟冷却后至多重播一次；
- 黄：30 分钟一次；
- REVIEW/GREEN/NEUTRAL：永不语音播报；
- 任何异常输入 fail-silent。

## 七、反向验证（红→绿两段）

1. RED-06 档位突变 RED→REVIEW：`highRiskRecallMeetsFloor` 红（召回跌破 95%）；
2. 红冷却常量突变 10 分钟→0：`cooldownConstantsMatchDocumentedDiscipline` 与
   `sameRedInsideCooldownHolds` 红。
   （第一次突变时冷却测试没红——测试引用了生产常量，改常量连测试一起变，自洽空转；
   已把节流测试改为文档纪律字面毫秒值并新增常量锁，突变即红。）
3. 恢复后三类 26 条全绿。

## 八、门禁读数（本机 Windows，2026-10-25 实测）

本机仓库路径含中文，AGP 拒绝构建；`-Pandroid.overridePathCheck=true` 只过编译检查，
测试 worker 仍在中文路径下 `ClassNotFoundException`。**用 `subst W:` 把仓库映射为 ASCII
盘符后门禁可跑**（`subst W: "<仓库路径>" && cd W:/android && ./gradlew ...`）——
这是本机跑 JVM 门禁的既知解法，记入团队备忘。

**native 缓存被 gradle 清空**（金丝雀实证）：`configureCMakeDebug` 任务每次重跑前会清空
自己声明的输出目录（`app/.cxx/Debug/<hash>/<abi>/`），而四个固定第三方包（ncnn／speexdsp／
webrtcvad／sherpa-onnx）恰好缓存在里面——网络受限机器上每次构建都要重新下载，下载失败则
永久卡死。本轮给 `CMakeLists.txt` 加了 `MAPASSIST_THIRD_PARTY_CACHE_ROOT` 环境变量：设置后
第三方缓存根改到该目录（持久、不被清空），不设置则行为与原来完全一致（默认仍用
`CMAKE_BINARY_DIR`）。五个包哈希逐一核验后预置于 `C:/Android/cxx-cache/tpc`。

- 基线（移出本轮 7 文件）：tests=630 skipped=0 failures=8——7 条为历史记录过的 Windows
  临时目录 `FileSystemException` 环境红（AsrModelStore 6＋AssistantTtsCache 1，即「恒 7」）；
  另 1 条 `PauseableRecorderLoopTest.invalidationDropsPartialFrame…` 时序断言，为合并带入的
  波动项，终读自愈。
- 终读（含本轮）：**tests=656 skipped=0 failures=7 errors=0**——656 = 630 + 26（本轮新增），
  失败仅剩既存「恒 7」，本轮零新增失败。
- `:app:assembleDebug` 绿，app-debug.apk 22,086,459 字节（含 native 库）；`:app:lintDebug`
  需联网拉取 androidTest lint model 依赖，读数见 PROGRESS 当轮记录。

## 九、接入路线（下一轮，未启动）

1. **读屏挂接**：`SenseFieldReaderService.captureNow` 已产出节点文本行，接入时把
   `label` 列表喂给引擎；事件频率用现有 2 秒节流。
2. **播报挂接**：RED/YELLOW 走 `CueRequest.Category.SYSTEM` 通道（播报文案已由引擎给全）；
   先过离线 TTS 可用性检查。
3. **家属/护工端**：REVIEW 队列与高风险推送进诊断导出（JSONL 留痕已有 DiagnosticArchive 惯例）。
4. **真机回测**：接入后用领导真机录屏的脱敏文本回放跑一轮召回/误报复核；本 KPI 语料随之扩充。

## 十、与方案二/三的接口预留

- 方案二（意图-现实双通道）：引擎输出已是结构化档位＋意图类别雏形，药盒/传感器交叉验证
  在 `evaluate` 之上叠加第二通道，不改动现有语义；
- 方案三（分层动态配置）：基础安全层（红规则，强制）／照护协商层（黄阈值、播报频率）／
  个人自主层（白名单、兴趣）天然对应 `ElderRiskRules` 的三张表，配置中心包住表加载即可。
