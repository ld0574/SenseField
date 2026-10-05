# PROGRESS

## 开工回执（2026-10-02）
- 理解的目标：单文件 `无障碍判定创作工具.html`，四页签（开始路线/判定设计器/试跑台/导出），带无头可测接缝（buildRequest/sendJev/renderError），对接 EdgeOne Makers（默认）与 TypeSafe 官方两条 SystemOne 渠道。
- 顺序：任务 0 核验 ✓ → 任务 1 三个样例 state → 任务 2 工具主体 → 任务 3 导出页 → §6 无头自检 + 全部 grep 验收。
- 最大风险：renderError/错误路径被判定为"写死"——对策：按规格把错误渲染做成消费 bodyText 的纯函数，无头自检用注入 fetch 的假 401 与断网两例反向验证。
- 任务 0 结果：sha256 两文件与预期一致；curl 探测 401 + auth_missing + Access-Control-Allow-Origin: * 全部命中。备注：粘贴到执行会话的任务书丢了任务 0 的命令块（cd/sha256sum/curl 三行），按原任务书原文补跑，预期值全对上。

## 验收记录（全部命令的实际输出已在执行会话对话中粘贴）
- 任务 1：`node -e "…JSON.parse…"` 退出码 0；match3 board.rows=8×8 ✓
- 任务 2 §6 步骤 1：script 块数=1；`node --check _chk0.js` 零输出；外链 grep=0；内联 on* grep=0 ✓
- 任务 2 §6 步骤 2：FAKE401 行含 401 与 auth_missing 原文；NETFAIL 行不含 401、含 fetch failed；SELFTEST-OK；退出码 0 ✓
- 任务 2 grep：五问 id = 5/3/2/3/1；endpoint = 3/2（均 ≥1）✓
- 任务 3 grep：指数退避=6、白名单=6、我没看清当前局面=2、AccessibilityService=2、confidence=21 ✓
- 附加实证（自发）：内嵌 SAMPLE_MATCH3/CHESS/AXTREE 与 samples/*.json 深度一致（deep-equal 全 MATCH）；sendJev 走真实网络打真网关：空钥匙 → 401 auth_missing（271ms），错钥匙 → 401 auth_failed（462ms），renderError 均原样上屏 ✓
- 交付前清理：_chk*.js 已全部删除；白名单外零改动；specs/ sha256 与任务 0 记录一致（复核输出已粘贴）✓

## 2026-10-02 追加：ADR 交付包落地与复核
- `jev_adr_delivery.zip` 验货后解压落地：`specs/ADR-0001-Jev渠道选型与可替换后端.md`、`prompts/SystemOne协议兼容复刻_提示词.md`、`BLOCKED_追加片段.md`（片段已按其用法并入 BLOCKED.md 第 3–5 条，本文件复核发现记为第 6 条）。
- 复核结论：P0 契约与 specs/jev-api-spec.md 逐字一致；额度测算算术无误；OpenRouter `/api/v1/systemone` 路由经阴性对照（404 vs 401）实测证实存在。
- 遗留：P0 grep 验收与全局铁律在本仓存在死锁、P3 引用悬空的 routing-policy.json——处置默认值已写入 BLOCKED.md 第 6 条。

## 2026-10-02 深夜：真 key 端到端亲验 + 管理者验收 + P0 完成
- 亲验（走创作工具自身 sendJev 代码路径 + 工具内嵌五问定义 + samples 真数据，OpenRouter 路由真 key）：消消乐五问 HTTP 200 全断言过（next_action=announce_match conf 0.86）；中文无障碍树 state 正常（read_menu conf 0.99）；错误路径（choice 缺 criteria）→ OpenRouter 返回 400 + Zod 原文上屏未吞。
- 实测数据修正 ADR §2：真实五问请求 input 1064–1094 token/次，超出估算区间 600–900 上限 → 免费额度折合约 470 次/月 ≈ 15 次/天（比 ADR 的 18–28 更紧，方向一致）。OpenRouter 响应带 cost 字段（≈$0.000045/次）可直接做额度追踪。实测延迟 1.5–2.2s/次（OpenRouter 链路，远高于官方 70–500ms 口径，P2 需重点测）。
- 工具小幅增强：renderError 补 400 解释映射（OpenRouter 用 400、EdgeOne 文档用 422，语义相同）；改后 §6 自检+全量 grep+暗卷 1/3/4 复跑全绿。
- 管理者验收：明卷（§6 两步+grep 矩阵+specs 哈希）全绿；暗卷 4 条抽查过（错误码未写死进逻辑/请求形状经真服务器验证/内嵌样例与 samples 深度一致/外链清点仅两 endpoint）。
- P0 完成：backend/（零依赖 Node）— protocol.js 共享契约层 + 三 Client + factory 唯一注入点 + contract-test.js。真跑：openrouter PASS（三型断言全过）、edgeone/local SKIP。endpoint 字面量 grep 仅命中三个 Client 文件，非 Client 文件 0 命中（按 BLOCKED 第 6 条默认 scope）。语言决策：JS（P0 自带兜底规则「跟随主语言」；Kotlin 对齐留 P4），已写进 backend/README.md。
- key 安全：真 key 仅经环境变量使用，未写入任何仓库文件（grep sk-or-v1 = 0 命中）。

## 2026-10-02 深夜二段：P1 完成 + SenseField 分支集成 + 测试安装包交付
- P1 全链路实测：核验开源复刻身份（4 个同名 OpenJev，选定 receptron/laya@0.1.2，Node+ONNX 纯 CPU）；权重 1.7GB 经 hf-mirror 下载（包内 URL 已补丁）；六问探针：类型名 noul（boolean 被拒）、criteria object/array 双收、probabilities 归一、原生 confidence 存在、中文 conf 反超英文（0.767 vs 0.649）、单问 p50=203ms/p95=255ms/RSS 1.57GB。
- 薄适配服务 backend/adapter/server.js（node:http → POST /v1/systemone）：三后端契约测试 pass=2 skip=1（local+openrouter PASS）；多场景双跑一致率 20%（本地 surety≈0.01-0.38）→ 按 ADR T2 门槛生产判定留云端，本地仅可用性兜底（结论落 specs/local-backend-probe.md）。
- SenseField 分支 feature/jev-l2-judgment（PR #2）：Java 判定层 8 类 + 自测台 Activity + JVM 多场景矩阵（5/5 过，含 9 种错误分类/中文透传/三档分流/白名单）+ INTERNET 权限（默认关闭+明文仅回环）+ abiFilters 属性化。基线 main 构建先绿再动分支。
- 测试安装包（delivery/）：SenseField-0.3.5-jev-arm64-debug.apk（真机）+ x86_64 变体（模拟器）。
- 模拟器端到端三场景：消消乐五问（低置信档 live 走「没看清」）、中文树（高置信 0.99 白名单档）、错误路径（400 原文上屏）。截图 delivery/emulator-scenario3.png。
- 环境备注：模拟器 medium_phone 保持运行；适配服务需手动重启（backend/adapter 下 node server.js）；真 key 只经环境变量与模拟器 SharedPreferences 使用，未写入任何仓库文件。

## 2026-10-03 凌晨：P2 完成（标注待领导）+ P3 工具链就绪 + P4-A 真机验证
- P2：50 帧评测集（gen-frames.js，构造法保证无消除帧零三连）双跑本地/云端，0 请求错误。总 top-1 一致率 28.8%（next_action 42% 最高、danger 22% 最低）；本地低置信率 68–100%（把握度塌缩），云端 next_action 低置信仅 4%；延迟云端 p50=1080ms/p95=1190ms，本地 5 问 p50=5202ms。结论落 specs/eval-dual-run.md：T2 不可切，本地=可用性兜底。
- P3：hist.js 直方图（本地 4/5 问题 96–100% 塌缩 0–0.1 档；云端 next_action/danger 各 27/50 达 ≥0.8）；sweep.js 阈值寻优就绪（X=1% 约束、按 read_only/mutating/payment 分层）——**只等领导确认标注**（annotation-template.csv 已出，含示例行与机器参考列）。
- P4：A 方案（服务端部署+换 baseUrl）模拟器真机验证：local 渠道经 10.0.2.2:8080 调宿主机 Laya，HTTP 200/7355ms，五问全落「低置信·绝不静默」——降级行为出声、不猜，符合设计。B 方案（端侧 ONNX）评估：fp32 1.7GB 需量化 spike，未实测不写代码。
- 交付新增：backend/eval/（frames.json 50 帧、dual-run-results.json 原始数据、gen/dual-run/hist/sweep 四脚本、annotation-template.csv、routing-policy.local.json 骨架）；delivery/ 增两截图。

## 2026-10-03：语言默认改中文（领导改判）+ 自测台 UX 修复 + 模拟器播报链路打通
- 五问语言全局翻转：默认简体中文、保留 English 对照（App jev_lang 开关 / 评测脚本 JEV_LANG / 创作工具语言下拉三处同步）；此为对任务书拍板 3 的领导改判，specs/ 两文件未动。
- 自测台：场景卡置顶 + adjustResize|stateHidden + 保存自动收键盘——修复「只能看到保存设置页」的软键盘困局。
- 演示播报：直连 CueDispatcher→CuePlayer 宽容策略真链路。排障实录：模拟器 AOSP 镜像无任何 TTS（tts_default_synth=null）→ 装 eSpeak NG 1.52（包名 com.reecedunn.espeak，settings 里包名写错一字母导致绑定失败，已纠正）→ 引擎首次绑定会拉起语音数据页被 BAL 拦截 → 前台 am start 让其完成数据解压 → 绑定成功 voice=cmn(zh) 离线。
- 终态（模拟器实测）：三条演示 SPEECH 全部 STARTED→COMPLETED、HAPTIC STARTED；tone_unavailable 是模拟器音调发生器限制（输出内如实标注）。演示节奏已按实测调整（首条延迟 1.2s 避开绑定窗口）。
- APK 两变体已更新交付；分支 fcb2088 已推送（PR #2 自动更新）。中文版 50 帧双跑后台进行中（结果将追加至 eval-dual-run.md）。

## 2026-10-03：体验版发布
- Release: https://github.com/ld0574/SenseField/releases/tag/v0.3.5-jev-alpha.1 （prerelease，真机 arm64 12.2MB / 模拟器 x86_64 21.3MB）。
- 事故修复：两变体共用同一 outputs 路径，连拷导致 arm64 文件被 x86_64 覆盖；已重建并 unzip 验 ABI（arm64-v8a / x86_64 各归其位）后 clobber 重传。教训：变体构建必须「构建→拷→再构建→拷」。

## 2026-10-03：开心消消乐（乐元素）适配体验版
- 领导真机安装体验包后反馈「消消乐未适配」→ 完成适配：GameCatalog 条目上线（happy-anipop，主包 com.happyelements.AndroidAnimal+渠道包探测/商店兜底）。
- 新增 Match3AssistActivity：一键启动游戏、相册选截图、棋盘标定（行/列 6-9＋四角百分比持久化）、HSV 颜色采样→识别矩阵、三连/交换检测（Match3Board 纯逻辑：只认涉及被交换两格的新增三连，JVM 测试 7/7）、TTS 播报（复用 CueDispatcher）、发 Jev 五问判定。
- 模拟器端到端验证：合成 8×8 棋盘（node 手写 PNG 编码器生成，埋设 2 个三连）→ 相册选图 → 满幅标定 → 采样 64/64 全对、2 个三连全检出、播报文案正确（截图 delivery/emulator-anipop-recognition.png）。
- 过程修 2 个自查缺陷：检测语义（原有三连不算交换功劳）、测试盘手工推演错误（node 插桩定位）。
- Release v0.3.5-jev-alpha.2（arm64 12.3MB / x86_64 21.4MB）；分支 52e1701 已推。
- v1 边界（已在 Release 说明）：截图式识别（回合制可用），实时连续识别在路线图；特殊棋子识别为「.」。

## 2026-10-04：四项需求交付（启动修复/实时识别/特殊棋子模板/阈值收尾）
- 启动修复：扫描全机 happyelements 系（queries 声明包可见性），显示包名+版本；模拟器验证输出「未检测到 happyelements 系已安装应用」。
- 实时识别：Match3LiveService（mediaProjection 前台服务，1Hz 采样，矩阵变化才播报，6s 防刷屏）；修 2 个真 bug（重复 START 未拆旧投影→ContentRecordingSession 崩溃；截图坐标 900px 缩放 vs 1080px 真屏 1.2 倍差）。模拟器端到端：授权单应用=看图应用→全屏棋盘→1 秒内播报「局面更新。第 4 行，第 5 个和第 6 个交换，可以消除，共 2 处」。
- 特殊棋子模板库：Match3Sampler 模板匹配（16×16 平均绝对差<30），标注 UI（行/列+名称→裁剪存私有目录），颜色判不出的格子自动与模板比对。
- P3 收尾：机器推导标注（10 帧事实型）双语言寻优——云端 next_action 全阈值档 10/10 正确（derived 0.30/recommended 0.50），本地无可用阈值（塌缩定案）；routing-policy.json 三档风险分层落档。主观题型标注仍等领导。
- Release v0.3.5-jev-alpha.3（arm64 12.3MB / x86_64 21.8MB）；分支 ab0546c。

## 2026-10-04：棋盘自动适配＋犹豫提示＋提速（alpha.4）
- 自适应：Match3Sampler.autoDetectBoard（降采样→深色棋盘格掩码→行/列剖面→包围盒），服务首帧自动覆盖标定并持久化；辅助页「自动适配棋盘」按钮；detectBoundsFromMask 纯逻辑 JVM 测试 5/5（含letterbox/噪声/多带用例）。
- 犹豫提示：独立 5 秒定时器（关键发现：静态画面不产生新帧，ImageReader 回调不触发，提示检查不能依赖帧回调），15 秒无变化+有交换→重报最优解。模拟器实测 19.6 秒触发。
- 采样 1000→800ms；模拟器全链路：错误标定(18/82)首帧自动纠正(32/77)→0.36 秒首帧播报。
- Release v0.3.5-jev-alpha.4；分支 a4c72c9。

## 2026-10-04：发布流程固化
- 领导要求：以后每次交付都给公网下载安装链接。固化流程：构建双变体 → 打 tag → gh release create（附双 APK＋更新说明）→ `gh release edit <tag> --prerelease=false --latest`（GitHub 的 /releases/latest 不指向预发布版，必须转正才能当固定入口）→ 汇报固定链接 https://github.com/ld0574/SenseField/releases/latest 与直链。
- alpha.4 已转 Latest；/releases/latest 实测指向 v0.3.5-jev-alpha.4。

## 2026-10-04：修复重复错误播报（alpha.5）
- 领导真机视频反馈：只识别对一次，后面全是重复无效语音。根因：真实游戏棋子常驻动画（摇摆/闪烁/消除下落）打穿逐帧比对——每个动画帧都算「局面变化」，6 秒一条重复播报且内容是动画中间态。
- 修复三层：①800ms 定时 tick 取最新帧（顺带解决静态画面无新帧问题）②稳定窗连续 3 帧一致才认账 ③播报签名去重（与上次已播报局面相同即静默）。另修 onStartCommand 局部变量遮蔽 width/height 字段的 bug。
- 构建+全套 JVM 测试绿（24 套件 0 失败）。Release v0.3.5-jev-alpha.5（已转 Latest，固定入口 /releases/latest 实测指向本版）。
- 诚实边界：本轮模拟器回归验证未跑完（模拟器三次崩溃＋UI 自动化坐标漂移，环境问题非代码问题）——「恰好一条播报/局面变化才播一条」的最终确认需真机，已在 Release 说明中向领导标注。
- 发布流程固化：每次交付 tag→Release→转 Latest→汇报 /releases/latest 固定入口（领导要求，长期有效）。

## 2026-10-04：alpha.6——说明书＋象限报点＋点读＋框选＋道具栏
- Match3Coach 教练模块：说明书知识库（9 条通用规则内置）、教程弹窗检测（中央亮窗统计）、4 象限报点、子区域摘要。
- 实时服务：弹窗出现→暂停播报并告知；弹窗消失→「可以开始游戏」提醒；局面更新带象限。
- 辅助页：说明书逐条朗读按钮、预览点读（点棋子报行列+名+象限）、框选识别（两角定区域→摘要）、道具栏播报（底部 6 格颜色）。
- 边界：道具「代点触控」按现有产品承诺暂缓（需领导明确拍板后才做注入）；说明书 v1 为内置通用规则而非 OCR（无 OCR 引擎，特定关卡说明待模板标注方案）。
- 构建+全套测试绿；Release v0.3.5-jev-alpha.6（已转 Latest）。

## 2026-10-04：alpha.7——棋子学习分类器（识别根修，探索型研究结论落地）
- 领导指示研究后定最优解。研究结论：①攻略核实 4连直线/5个LT爆炸/5连直线活力鸟/组合倍数表（KB 已同步倍数表待录入）②厂商无障碍演进关键发现：Android 14 API 34 AccessibilityService.setMotionEventSources() 支持只观察不消费触摸 → 「手指探索式报点」为最优交互（下一步，需真机 API 34+）③识别根修=模板学习分类器。
- 实现：标注机制扩展到五种基础动物（预设名 spinner：红狐狸/小鸡/青蛙/河马/棕熊/紫猫/自定义），classifyCell 模板优先（MAE<30）→ HSV 兜底；nameToLetter 把学习名映射回矩阵字母。
- 构建+全套测试绿；双变体交付；Release v0.3.5-jev-alpha.7（Latest），分支 d2de566。
- 待真机验证：学满五种动物后的识别准确率；Android 14 手指探索报点 spike。

## 2026-10-04：读屏判定 P0 基线（alpha.5 资产已更新）＋遗留一项真机验证
- 已实现：SenseFieldReaderService（无障碍树压缩：可见+可点/可聚焦/有文本节点→短ID+分区，上限60节点）、a11y 配置 XML、manifest 注册、辅助页「读屏→Jev 屏幕判定」（屏幕类型 Choice＋模态 Noul 扇出，三档门控话术）。
- 模拟器验证未完成：服务已绑定、capabilities=1（含读窗口内容），但 latestState 始终为空——事件未送达或 capture 失败，环境排查到模拟器反复崩溃后中止。诊断结论与代码已提交（295496c），Release alpha.5 资产已更新为含读屏基线的版本。
- 待办（下轮）：①模拟器或真机上启用服务后验证「切屏→读屏判定→屏幕类型播报」全链路；②若真机也空状态，优先排查 setServiceInfo 与事件投递（加日志）；③P0 通过后按 spec §二逐步扇出（敏感信息 Noul、图标消歧 Choice、优先级 Score）。

## 2026-10-04：alpha.8——连续触屏点读（指标=连续触屏识别准确率）
- 领导反馈「存在胡乱虚报」+ 指标改为连续触屏识别准确率。实现：API 34+ setMotionEventSources(TOUCHSCREEN) 只观察不消费触摸（读屏服务承担），ACTION_DOWN 坐标→按标定映射格子→模板优先识别→一次点击一条播报（行/列/棋子名/象限）＋ Match3Touch 日志（供与真实棋盘对照计算准确率）。
- 点读模式开启后完全抑制背景局面播报（动画期胡乱虚报的根除手段）。
- 辅助页点读开关（服务未运行引导先开实时识别；授权成功自动进入点读）。
- 构建绿、双变体交付、Release v0.3.5-jev-alpha.8（已转 Latest），分支 d4fee27。
- 诚实缺口：模拟器 UI 流程连续不稳（SystemUI 崩溃/坐标漂移），「连续触屏识别准确率」的最终数字需领导真机按验证清单测（学习五动物→连点 10 棋→对照日志）。

## 2026-10-05：alpha.9——判断式骨架补全（阶段一全量＋阶段二核心）
- 阶段一：ABSTAIN 全面化（screen_type 候选＋拒答话术）；本地敏感闸门（Match3Gate 正则扫描，命中禁上云禁细节播报）；门控配置化（assets/jev/gating.json，高风险 0.95 档预留）；状态扩展（app/activity/dark 头＋E/C 状态位）。
- 阶段二核心：图标消歧 Choice（Match3Gate.ICON_CANDIDATES 13 项＋ABSTAIN）、播报优先级 Score（前 5 候选 0-3 量规扇出）、操作前状态标记。低置信升级路由挂接点预留（toCloudLLM=false 待领导选模型）。
- JVM 测试 Match3GateTest 4/4；全套测试绿。构建双变体交付；Release v0.3.5-jev-alpha.9（Latest），分支 88f46ab。
- 遗留：读屏判定模拟器验证未通（服务绑定+capabilities 正常但 latestState 空——事件投递问题，真机系统 UI 启用路径待领导验证；这是当前最大风险项）。

## 2026-10-05：alpha.10——防重复虚报三连修（针对「只能第一次，后面一直重复」）
1. 变化幅度门槛：与上次已播报局面只差 1 格（选中高亮/动画残影）不触发播报（countDiffCells）。
2. 自我修复检测：未知格占比 >40% → 自动重新适配棋盘＋重采样；仍坏静默（宁可不说不播垃圾）——领导要求的「停顿并自我修复检测」。
3. 犹豫提示上限：同一局面最多 2 次（话术区分），之后彻底安静——消灭无限重复。
- 并行会话的棋子识别研究结论（C2 格子级 CNN 合成集 99.96% vs HSV 94.34%、C3 YOLO 整屏死路、C4 VLM 待 key）已确认为下一步主路线：TFLite 上机集成（估 1-2 天）；其 5 项 BLOCKED 与真机截图 10-30 张需求转交领导。
- Release v0.3.5-jev-alpha.10（Latest），分支 971150d。

## 2026-10-05：真机识别错误根因定位（领导截图分析）＋修复方案
- 领导真机截图：开心消消乐第 3 关棋盘为 7×7（非 8×8），动物仅 4 色（棕熊/小鸡/青蛙/蓝河马）。**首要根因：行列规格错位**——8×8 网格压 7×7 棋盘，全盘混合采样必然全错（第一步播报错误的直接原因）。
- 立即可用的修复：辅助页标定卡把行数/列数拨到 7×7 再采样（标定按关卡记住）。
- 修复方案规格：specs/recognition-fix-plan.md——①行列自动检测（暗格线周期分析）②云端 VLM 兜底（领导指定 DeepSeek/GLM/Qwen 便宜模型走 OpenRouter，自动升级调用：颜色采样未知>25% 或矩阵矛盾时触发；Qwen3-VL/GLM-4.5V 看图出矩阵，DeepSeek 做校验）③与 Jev 判断式正交组合（VLM=L1 看，Jev=L2 判）。
- 下轮实现：VLM 识别通道（OpenRouter chat completions 已有 Key）＋行列自动检测。

## 2026-10-05：云端 VLM 兜底接入（自动云端调用，领导已授权）——alpha.10 资产更新
- 回答领导问题：棋盘识别**从未走过云端模型也从未走过 Jev**——它是纯本地 HSV 颜色采样＋规则检测；Jev/Laya 只负责屏幕判定线。「第一次对后面错」=本地颜色采样在真实美术上准确率不足（研究 C0=94.34% 合成集，真机更低）＋云端兜底未接。
- 已接入：CloudVision（OpenRouter 多模态，默认 z-ai/glm-4.5v 可换 qwen3-vl）——实时识别稳定局面确认后自动把棋盘裁剪图交云端读矩阵，以云端为准；本地未知格>25% 必升级；每次局面变化一次（约 ¥0.01/次）；Key 复用判定层。DeepSeek/GLM/Qwen 文本模型用于后续 Jev 升级路由。
- 过程事故：bash 注入脚本把中文字符串写成 GBK 乱码＋字符字面量损坏＋变量重名——git 还原＋Edit 工具重放修复。教训：含中文/转义的代码注入一律用 Write/Edit 工具，不走 bash heredoc。
- 构建绿+测试绿+双变体交付；alpha.10 资产已 clobber 更新（含云端兜底）。
