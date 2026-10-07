# PROGRESS

> 身份标识占位符：本卷用 `<机代号>`／`<构建号>`／`<构建号前缀>`／`<HyperOS 构建号>`／`<HOME>`／`<本机账号>`／`<云盘目录名>`／`<gh 令牌账号>`／`<既存提交身份>` 代替真实设备与账号标识，映射表存在仓外的 `开源对接项目\_scratch_archive\casefile_identity_map.md`（不进 git、不推送）。带占位符的复跑命令按该表还原后再跑。2026-10-07 按领导口径洗的，见 PROGRESS 同日「案卷去标识与推送」段。

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


## 2026-10-05：模板匹配离线精度基准 + 三连修（缺口 1 播报名字 / 缺口 3 字母撞车 / 缺口 2 空格误报）
- 代码改动全部落在 `<HOME>\SenseField`（分支 feature/jev-l2-judgment，HEAD a99f34e＝alpha.10 含云端兜底）；本案卷自带的 android/ 树是另一分支 feature/sensefield-dev，两者 Match3Sampler.java 哈希不同，不要混。
- 新增可复跑基准 `SenseField/research/board-recognition/java-bench/`：手写 android.graphics 桌面替身（Bitmap=BufferedImage；Color.colorToHSV 已用 Python colorsys 交叉校验），跑前 sha256 核对被测源文件与仓库逐字节一致，杜绝「测到旧副本」。夹具：1080×2400、8×8、标定 18/30/82/62、单格 86×96px、9 类棋子＋空格、每场景 30 帧＝1920 格。
- 修前三条仓库自带测试全测不到的缺陷：① 特殊棋子识别出来后播报永远念「未识别」——矩阵字母落进 Match3Coach.pieceName 的 switch 空档，特殊棋子模板库功能从未真正到达用户耳朵；② 棋盘底色（深蓝黑，v≈0.18-0.52）越过 HSV 弃权闸门被报成 'B' 河马，194 个空格 100% 误报；③ templateCode 用 hashCode()%9，特殊棋子字母随机撞车。
- 修法：pieceName 接 nameForCode 还原玩家学过的名字；空格判定改用格心-四角局部色差（阈值 12；实测空格最大色差 8、有子格最小色差 35，余量清晰）；字母表按模板名排序稳定分配，池 35 槽（1-9＋a-z），避开 R/O/Y/G/B/P 六个基础色字母与 '.'。
- 修前→修后（同一基准）：冷启动 A 60.0%→70.1%（空格误报 194/194→0/194）；每类学 1 张·同源 B 89.9%→100.0%；模板跨亮度±12% C 89.9%→100.0%；动画中间态（模糊 6px＋alpha 0.55）D 60.4%→70.5%，仍有 522 格自信错、仅 44 格弃权 → 稳定窗 STABLE_FRAMES=3 仍是承重结构，撤不得。
- 回归门禁：`bash java-bench/run-tests.sh` 20/20 绿（Board 7＋AutoDetect 5＋PieceName 5＋Guard 3）；`gradlew :app:testDebugUnitTest --offline` 27 类 179 测试 0 失败 0 错误。新增仓库内 `Match3PieceNameTest`（纯逻辑，锁播报字母→名字映射与弃权行为）＋基准内 `Match3GuardTest`（锁空格判定与 20 种特殊棋子字母不撞车，因真机单测里 Bitmap 取像素恒为 0 而无法上仓库）。
- 教训两条（踩在自己身上）：① `Color.rgb()` 打包恒置 0xFF alpha 高位→int 恒负，拿 `<0` 当「取不到像素」哨兵会让全盘变未识别（症状：A 掉到 40.0%、色差读数全是 2147483647）；这个 bug 上安卓表现一模一样。② 基准的期望值不许调用被测函数——曾因 expectedChar 走 templateCode，在无模板场景把期望变成弃权符而自证刷高准确率，改为基准内独立预计算期望矩阵。
- 诚实边界：本基准全是合成图，与真机无关。上一条已定真机首要根因是 7×7 棋盘被按 8×8 切格，修前三条在真机上会被行列错位淹没；缺口 2 的空格阈值需用真实截图重新标定（data/real 仍空，模拟器没装游戏）。与并行会话的「HSV 94.34%」不可直接比——夹具的美术资源、光照、类别数都不同。
- 未做（当时）：APK 双变体构建与 Release 推送（等领导确认）；改动当时只在工作树。**2026-10-05 晚追记：已 commit（e485fed/8eae176/e13f581）、双 ABI 构建完毕、随 alpha.14 发布。**

## 2026-10-05 晚：三段真机视频复盘 → 揪出两个生产级 bug（格数自检必崩＋最小间隔吞局面）
- 视频素材：领导微信三段真机录屏（开心消消乐实战）。抽帧＋音频包络对齐后，反复出现的症状「同一局面一直报、走子之后不报」被完整解释，不是识别算法抖，是两个代码缺陷：
  1. **`Match3Sampler.detectGridCount` 每帧必崩**：`frame.getPixels(rowBuf, 0, cw, x0, y, cw, 1)` 把 stride／offset 两个参数写反（正确签名是 `getPixels(colors, stride, offset, x, y, w, h)`），真机上稳定抛 `ArrayIndexOutOfBoundsException: Index 529 out of bounds for length 529`。崩点被实时 tick 的 `catch (Exception e) { Log.w(...) }` 吞掉，于是自动格数自检永远拿不到结果，回退固定 8×8——7×7 棋盘被按 8×8 切格，报出来的整盘是垃圾（实测旧路径产出「第 3 行：蓝、蓝、蓝、蓝、蓝、蓝、蓝、蓝」）。
  2. **最小播报间隔内的走子被永久吞掉**：原代码在 `MIN_ANNOUNCE_GAP_MS`（6s）闸门判断之前就写了 `lastAnnouncedMatrix = matrix`，被闸门拦下的那一帧从此被标记成「已播过」，这一步棋再也没有播报。视频实锤：t=21s 有 18 格变化（一次真实走子），之后无任何播报；t=30.8s 播出的是过时的犹豫提示。
- 节拍证据：播报起点间隔实测 15.0–15.3s，正好等于 `IDLE_HINT_MS = 15000` → 反复听到的「还在犹豫的话／仍然可以」是犹豫提示在按 15s 定时重播，而非局面更新。音频包络相似度 0.286（局面播报 vs 犹豫#1，内容不同）→ 0.715（犹豫#1 vs 犹豫#2，同一模板），与「2 次上限」一致。棋盘像素 t=1..16 完全不变，佐证那段只有定时器在说话。
- 根因定性：**错的不是颜色，是几何。** 修好 stride／offset 后，用 App 自己自动适配出的框 `14/38/87/71` ＋自检格数 7 去读真机帧，四帧 `detectGridCount` 全部 =7，逐格 **49/49 全对**，且色相远离 HSV 分桶边界（棕熊 28-29°、小鸡 52-56°、青蛙 105-107°、河马 191-192°）——也就是说领导截图那条「7×7 被按 8×8 切」的首要根因，卡在一个参数写反的崩溃上没生效。
- 本轮新工具：`SenseField/research/board-recognition/java-bench/RealFrame.java` 把真实截图直接喂给仓库里的采样器，打印默认 8×8 读数、`detectGridCount`（try/catch 包住，让崩溃可见）、自动适配框、逐格诊断（期望/实际 色相·饱和度·明度·心-角色差·判空）和 `scanSpeech` 成品播报；守卫测试补了「合成 7×7 自相关必须数出 7」。
- 顺带发现三条（①③ 未改，留给下一轮；② 本晚已补文档）：① `detectGridCount` 在默认宽框（未按棋盘裁剪）上会误报 9，需靠框选正确性兜住；② 该方法注释声称「真机 7×7 与合成 8×8/9×9 上 5/5 命中，见 REAL_VIDEO_FINDINGS.md」，但仓库里没有这个文件——悬空证据声明，**本晚已补**：`research/board-recognition/REAL_VIDEO_FINDINGS.md`（commit 9c76617，纯文字与数字，帧不入库）把 6 处引用落到实处，并把注释改成实测口径（抽帧逐帧命中 7；宽框会自信地数成 9）；③ 词表分裂：`Match3Board.scanSpeech`/`charName` 念**颜色词**（红橙黄绿蓝紫），`Match3Coach.pieceName` 念**动物名**，且弃权符 `'.'` 被念成「空」，玩家听到两套命名。
- 门禁凭证（本机实测，可复跑）：`assembleDebug`＋`lintDebug` BUILD SUCCESSFUL（lint 0 error／18 条存量 warning，命中文件全在未改动的 `Match3AssistActivity` 等；唯一 error 是本机未跟踪的 `android/local.properties` 里 `sdk.dir` 冒号未转义，已按标准写法改 `C\:/Android/Sdk`，该文件在 .gitignore 第 33 行、不进仓不影响他人）；`:app:testDebugUnitTest` 28 类 **184** 测试 0 失败 0 错误 0 跳过；`java-bench/run-tests.sh` 23 测试绿；`aapt dump badging` 实测两枚 APK 均 `versionCode=17 versionName=0.3.5-alpha.14`，native-code 分别 `arm64-v8a`／`x86_64`；`scripts/check_public_repo.py` 326 文件通过（真机帧／APK 全在仓外）。
- 提交与分叉：两修 `e485fed`、基准 `8eae176`、版本号 `e13f581` 落在 `<HOME>\SenseField`（feature/jev-l2-judgment）。远端头仍是 `9b84731`＝并行会话 19:42 发的 `v0.3.5-jev-alpha.13`（其 Latest 发布说明里的「离线逐格全对 49/49」与本晚实测同数，但 **alpha.13 不含这两修**——装了它的真机格数自检照旧必崩、间隔内走子照旧吞）。
- 已发布（领导此前对「提交并构建发布」答「需要」，本晚按 BLOCKED §8 默认①执行）：分支推到 `e13f581`，tag `v0.3.5-jev-alpha.14`（轻标签，与 alpha.13 同风格），GitHub Release 挂 `arm64`＋`x86_64` 两枚 debug APK（远端字节数 12913766／22476664 与本地逐一致），`/releases/latest` 实测返回 `tag=v0.3.5-jev-alpha.14, prerelease=false, draft=false` → https://github.com/ld0574/SenseField/releases/latest

## 2026-10-05 晚（第二枪）：统一播报词表＋「没棋子」与「认不出」分家 → alpha.15
- 领导在三个选项里选「统一播报词表，空位阈值记入待办」（另两项：按颜色读法重写播报逻辑＝方向反了，动物名才是游戏自己的叫法，且颜色词信息量更少；只确认不改动＝不产出）。
- 做法：删掉 `Match3Board.charName` 那套颜色词（红橙黄绿蓝紫），`scanSpeech` 改走 `Match3Coach.pieceName`，全项目只留一个命名来源。顺带查出更本质的一处：**采样器把「确认空格」和「认不出」写成同一个 `'.'`**，所以逐行播报会把玩家学过的特殊棋子念成「空」。现拆成 `Match3Sampler.EMPTY_CELL = ' '` 与 `UNKNOWN = '.'`，念「空」和「未识别」两个词。
- 不扩大战线：`swapSpeech` 保持纯报坐标不念名字（那是加功能，不是统一词表）；自我修复检测改按 `isUnreadable()`（两种符号都算），口径与分家前逐格等价，不会因为拆分而漏触发重标定。
- 真机帧回归：同批抽帧改后仍 **49/49**（7 行全部错0），播报文本实测 `第 1 行：棕熊、小鸡、青蛙、河马、小鸡、青蛙、棕熊`（改前是 `橙、黄、绿、蓝、…`）。
- 门禁：`assembleDebug`＋`:app:testDebugUnitTest`＋`lintDebug` BUILD SUCCESSFUL，28 类 **187** 条 0 失败 0 错误、lint 0 error；java-bench **27** 条绿（新增两条守卫：采样必须分开空格与未识别、逐行扫描与点读同词表）。仓库内 `Match3BoardTest` 那条锁旧颜色词的断言按新规格改写（`第 2 行：河马、未识别`），不是回退掩盖。
- 发布：commit `715e4bc`（versionCode 18／versionName 0.3.5-alpha.15），tag `v0.3.5-jev-alpha.15`，双 ABI APK 上传后远端字节数与本地逐一致（12913766／22476664），`releases/latest` 实测返回 alpha.15。发布前 `git ls-remote` 复核过：远端头是我的 `9c76617`、tag 最大 alpha.14，没和并行会话撞号。
- 新发现（当时未修，记档；**2026-10-05 深夜已修，见下节「第三枪」**）：`findSwaps` 走 `isPiece()`，而 `PIECE_COLORS` 只含 6 种基础动物 → **玩家学过的特殊棋子在「可消除走法」枚举里等于不存在**，提示永远不会推荐涉及特效棋子的交换。这是字母表扩到 35 槽时留下的口子，与本轮词表无关，属下一轮。

## 2026-10-05 深夜（第三枪）：空位阈值真机标定结案＋特殊棋子进走法枚举 → alpha.16
- 领导答「同步执行」三项：① 空位阈值真机标定现在就做 ② 修复特殊棋子走法推荐 ③ 同步两个待办状态。三项全部完成，①的结论与预期相反，记清楚。
- **② 特殊棋子进走法枚举（`faa5fa8`）**：词表分家之后才看得见的问题——`Match3Board.isPiece` 写死只认 `ROYGBP` 六个基础色字母，而采样器给玩家学过的特殊棋子分配的是 `1-9`/`a-z`，于是那些字母在 `findRuns`/`findSwaps` 里**等于不存在**，提示永远只推荐普通动物的交换。改成 `isPiece(c) = !Match3Sampler.isUnreadable(c)`，`PIECE_COLORS` 常量随之删除（全项目无其它引用）。`Match3BoardTest` 补三条：三个相同特殊字母要成三连、涉及特殊棋子的交换要进候选、`'.'`/`' '` 永不计入棋子。
- **① 空位阈值标定（`e288c64` 工具 ＋ `0e47366` 结论）**：把三段录屏改成 **10fps 密抽**（1235 帧），逐格跑 `centerCornerDistance`，得 55860 个格数正确的格。结论是 **阈值 12 是对的，而且只能收紧不能放宽**：
  - 有棋子的格 **99.42% 落在 `d ≥ 60`**；`d ≤ 12` 只有 **4 格**，裁图目视确认是空棋盘底；
  - 13–59 那一小撮（322 格）经 96 格带序号联络表逐格目视，**全部不是空格**——是跨格边界的棋子（顶行与最右两列尤其多，`(r-l)/n` 取整累积漂移）、雪块类障碍贴图、以及遮罩弹窗；
  - 所以放宽到 20／40／59 会分别把 **16／57／322** 个真棋子念成「空」，是纯伤害。判据已写进 `EMPTY_COLOR_DISTANCE` 的注释，防止后人凭感觉调。
- **① 的输入需求作废（自我撤回）**：§7 原来列的三条材料需求（真机截图 10-30 张／模拟器装游戏／`Match3Touch` 日志回捞）经实测判定走不通——真空位只在动画瞬间出现，而三帧多数票本来就把那种帧滤掉，「空」这个词在真实对局里 1140 帧总共只出现 4 格。**再多的视频帧也标定不动这个阈值**，所以不该继续等材料，应按「阈值维持 12」结案。
- **顺手查出并修掉一处真算错的（`0e47366`）**：新写的 `GuardProbe` 复现生产同款「采纳前试采」闸门，实测余量很大——格数正确的 1140 帧未知率 0–10%（均值 **0.09%**）全部采纳，错格的 68 帧未知率 **55–75%** 全部拦下，所以 40% 这条线不改。但顺着看自我修复分支时发现 `countUnknown(fixed) * 100 > total * 40` 用的是**旧**矩阵的格数当分母：旧 8×8=64 配候选 6×6=36 时门槛实际变成 71%，一个几乎全未知的 6×6 标定能被采纳并持久化。已改用 `fixed.length * fixed[0].length`。
- **错格帧的成因查明了（对无障碍产品是常态不是边角）**：那 68 帧全是框 `0/9/100/54`，联络表里成片白色面板带「小爱」字样 —— 是**小爱同学语音助手遮屏**盖住半屏，深蓝遮罩失效 → `autoDetectBoard` 交出含天空和道具栏的宽框 → `detectGridCount` 自信数成 8 或 9。正是上一条「边界条件」第一条预告过的失效模式，被 40% 闸门兜住了。玩家用语音助手是常态动作，这条已记进 `REAL_VIDEO_FINDINGS.md`。
- 门禁凭证（本机实测，可复跑）：`bash java-bench/run-tests.sh` **30** 条绿；`gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline` BUILD SUCCESSFUL，**28 类 190 测试 0 失败 0 错误 0 跳过**、lint **0 error**；`scripts/check_public_repo.py` 330 文件通过；新增/改动 7 个文件按 `wxid|xwechat|用户名|邮箱|sk-or-v1|Users 路径` 扫身份痕迹 **0 命中**。
- 版本号与产物：`f9f6b90` 取 versionCode **19** / versionName **0.3.5-alpha.16**。落号前 `git ls-remote` 复核：远端分支头是我的 `715e4bc`、远端 tag 最大 alpha.15，没和并行会话撞。双 ABI 各构建一枚，`aapt dump badging` 逐枚实测 `versionCode='19' versionName='0.3.5-alpha.16'`，native-code 分别 `arm64-v8a`／`x86_64`，放在仓外 `<HOME>\SenseField-artifacts\`（10-05 深夜按领导新规矩并入 `<HOME>\SenseField\output\`，alpha.14／15／16 三版产物与发布说明共 9 个文件已全部搬过去，原目录清空）。
- **凭证教训（本轮新踩）**：alpha.14／15／16 三版双 ABI 的**字节数完全相同**（12913766／22476664），因为版本号字符串等长、zip 压缩后总长不变。所以「远端 size 与本地逐字节等值」这一条**不足以证明装的是哪一版**——以后核发布必须用 **sha256**，不能只看 size。本轮 sha256：arm64 `c4e9ae05b690a762e061c8e17270392ecb42d1138dffd0858f5ae8d6ab6a28e3`、x86_64 `f923f7ae24c92844d07d5c2e6a4d0b163da9d48c52bf59be867f676020bde303`。
- 状态：四枪已本地提交（`faa5fa8`→`e288c64`→`0e47366`→`f9f6b90`），领导对「是否照例发布」答**要** → 已对外发布，凭证见下条。

### alpha.16 发布凭证（2026-10-05 21:45 实测，可复跑）

- 放行：领导对 BLOCKED §9「是否照例推远端＋打 tag＋发 Release」答**要**（选项①照例发）。
- 落 tag 前 `git ls-remote` 复核：远端分支头仍是我的 `715e4bc`（alpha.15）、远端 tag 最大 alpha.15，alpha.16 未被并行会话占用 → 才动手。
- 推送：`715e4bc..f9f6b90` 上 `feature/jev-l2-judgment`（走 HTTPS，本机 SSH 22 被阻断）；轻标签 `v0.3.5-jev-alpha.16` 直指 `f9f6b90`。四枪 `faa5fa8`／`e288c64`／`0e47366`／`f9f6b90` 全部实测为远端头的祖先。
- 推上去的文件实测只有 9 个（3 个 java-bench 工具＋4 个安卓源/测试＋1 个 build.gradle＋1 个文档）——真机帧、`cells.csv`、`guard.csv`、联络表、APK 全在仓外，`scripts/check_public_repo.py` 330 文件通过。
- Release：`gh release create` 挂双 ABI → `gh release edit --prerelease=false --latest`。`gh api repos/ld0574/SenseField/releases/latest` 实测 `tag=v0.3.5-jev-alpha.16`、`prerelease=false`、`draft=false`、`latest=true`、2 枚 asset `state=uploaded`。链接 https://github.com/ld0574/SenseField/releases/latest
- **按 sha256 回读验收（本轮起的新规矩，见下条坑）**：从 Release 页重新下载两枚，与本地构建逐字节比，**两枚全部一致** ✅：`arm64-v8a` = `c4e9ae05b690a762e061c8e17270392ecb42d1138dffd0858f5ae8d6ab6a28e3`，`x86_64` = `f923f7ae24c92844d07d5c2e6a4d0b163da9d48c52bf59be867f676020bde303`（远端与本地同值）。
- 第一次哈希**两边不一致**，差点误判成「发布内容被换过」。查下来是下载截断：远端 size 12913766／22476664，落盘只有 9653716／9444480，而且我在校验时把还在写入的文件也一起哈希了。教训：**回读验收必须先等下载结束、再比 size、最后才比 sha256**，顺序反了就会拿半成品当篡改证据。
- 另一个不坑但要说清的：`releases/latest` 返回 `target_commitish: "main"`，看着像发布挂错分支。实测 `git ls-remote` 与 `GET /git/refs/tags/v0.3.5-jev-alpha.16` 双向都指向 `f9f6b90`（feature 分支上的版本提交），`GET /commits/v0.3.5-jev-alpha.16` 的 message 就是 `chore(release): 0.3.5-alpha.16（versionCode 19）`。tag 已存在时 `target_commitish` 只是装饰字段，不参与解析。

## 2026-10-05 深夜（第四枪）：发布产物归位到 `output/`（领导新规矩）

- 领导指令原话：「以后升级版本alpha要求放output里面的，这个目录git ignore掉的」。此前我把双 ABI APK 与发布说明放在仓外 `<HOME>\SenseField-artifacts\`，是绕过了仓库自己的约定——`SenseField\.gitignore` 第 36-37 行的注释（Local generated documents, recruitment media, presentations and release packages）下面就是 `output/`，本案卷目录的 `.gitignore` 第 39 行同规则。
- 搬移：9 个文件（alpha.14／15／16 各双 ABI 共 6 枚 debug APK＋3 份 release-notes-alpha14/15/16.md）从 `SenseField-artifacts\` 移入 `SenseField\output\`，原目录现已为空。
- 凭证（本机实测，可复跑）：
  - `git check-ignore -v output/sensefield-0.3.5-alpha.16-arm64-v8a-debug.apk` 返回 `.gitignore:37:output/` 命中该文件 —— 整目录忽略，不是靠扩展名漏判。
  - 搬入后 `git status --short` 只剩我当轮改的一个文档（`docs/GITHUB发布检查清单.md`），`output/` 下 9 个文件一个都没进状态表。
  - `python scripts/check_public_repo.py` 输出 `Checked 330 candidate files` ＋ `Public repository check passed.`，与搬移前同一数字，证明候选集没被这批产物撑大。
  - 搬移后 sha256 复算 alpha.16 两枚，与本卷上一条「凭证教训」登记的发布值逐字节一致：arm64 `c4e9ae05b690a762e061c8e17270392ecb42d1138dffd0858f5ae8d6ab6a28e3`、x86_64 `f923f7ae24c92844d07d5c2e6a4d0b163da9d48c52bf59be867f676020bde303`。
- 规矩落地：`docs/GITHUB发布检查清单.md` §5「Release 和比赛交付」在「APK 放到 GitHub Release 附件」之前加一条——每次升级 alpha，双 ABI APK、SHA-256 与发布说明先落仓库根 `output/` 再上传 Release；该目录整目录忽略，只作本地暂存。改前改后行尾复测：该文件纯 LF（CRLF 0、孤立 CR 0），HEAD 版本同为纯 LF，`Edit` 没改变制式。
- 下一班要遵守的：发布时**直接在 `SenseField\output\` 里构建与暂存**，别再另开仓外目录；那里的 APK 与发布说明永远不进公开仓，`git add` 时也确认状态表里没有 `output/` 开头的行。
- 未做（等点头）：这条文档改动只在本地工作树，没 commit、没 push。理由：产物归位不动代码，不构成一次 alpha 升级，不该顺带触发发布链；单独提一个 `docs(release)` commit 推上去是对外动作，要领导同意才做。

## 2026-10-07 清晨：真机 bugreport 复盘 →「启动实时识别后一直不播报」四定根因（只分析，代码按批次另改）

- 素材：领导 10-06 23:57 在 Redmi「<机代号>」（Android 16／HyperOS <HyperOS 构建号>，1080x2400）抓的 `bugreport-<机代号>-<构建号>-2026-10-06-23-57-13.zip`。主文件 180973311 字节、1837818 行；日志窗右端实测到 `10-07 00:00:32`。取证窗口里我们的进程是 pid 4850、uid 10346、采集线程 tid 23703。
- 症状：点了「开始实时识别」、也给完了录屏授权，之后一句棋盘播报都没有。以下四条互相咬合，构成完整因果链，每条都给了可复跑读数。

### 根因一（最上游）：持久化的标定框太小，采样落在格子缝隙上

- 实锤读数：`10-07 00:00:03.293 10346 4850 23703 I SkJpegEncoder: ... Options: { fQuality = 80 ...}; src.info is SkImageInfo: {... Dimensions.width = 356, Dimensions.height = 336}`。
- 为什么这条能定死裁剪尺寸：全仓 `Bitmap.CompressFormat.JPEG` 只有三处，质量数 80 唯一落在 `CloudVision.java:196`（另两处是 `DiagnosticRecorder.java:486` 的 75／92、`Match3LiveService.java:642` 的 70）；而 `CloudVision.scaleForUpload` 的 `int max = 896` 对「宽高都不超过 896」的入参照原样返回，所以 356x336 就是棋盘裁剪图本身的像素尺寸，没有被缩放过。
- 换算：356/1080 = 33%，336/2400 = 14%。裁剪框由 `Match3LiveService.java:367-370` 用 `match3_l/t/r/b` 四个百分比算出，所以机上存的框是宽约 33%、高约 14%。对照仓内真机实测的正确框 `14/38/87/71`（`research/board-recognition/REAL_VIDEO_FINDINGS.md:35`，约 788x792 像素），本轮这个框只有它的约 1/8 面积。
- 后果：8x8 切进 356x336 → 单格约 44x42 像素；真格约 99 像素。采样点落在格子边界与相邻格之间，读出来的矩阵必然成片未知。
- 格数自检没救回来：`10-07 00:00:00.812 ... I Match3Live: 沿用手动标定 8x8` 后面没有「（格数自检=N）」后缀。该后缀由 `Match3LiveService.java:313-314` 在 `n > 0` 时才拼上，`n = Match3Sampler.detectGridCount(frame, {l,t,r,b})`，无后缀即 `n <= 0`，而 `detectGridCount` 只在 `cw < 60 || ch < 60` 或算不出周期时返回 -1（`Match3Sampler.java:424` 起）。也就是说自检在这么小的框里主动弃权，代码回退到存的 8x8 硬读。
- 顺手排除一个可能：这条日志只出现一次，不是丢了。标定分支的入口是 `if (sampler == null)`（`Match3LiveService.java:262`），一旦建出 sampler 就永不重跑，所以整个会话只有一条属正常。
- 来源链（为什么会存下这么小的框）：`installer_clear_app_data_caller: [2388,1000,com.openkhub.sensefield,39]` 在 23:50:15.295 命中，也就是本轮 alpha.16 覆盖安装把 SharedPreferences 清空了；而 UI 侧唯一写 `match3_l` 的地方是设置页四格百分比输入（`Match3AssistActivity.java:383`）。所以 23:50:15 到 23:59:59 之间有人重填过一次标定，填进去的框偏小。这条不需要再取证也能定：清数据在前、会话里有 `match3_calibrated=true` 在后。

### 根因二（把唯一一次尝试打死）：云端识别同步跑在采集线程上，网络一挂整条流水线静默

- 线程模型：`handler()` 懒建唯一一个 `HandlerThread("m3live-capture")`（`Match3LiveService.java:179-186`），`ImageReader` 的出帧回调（`:170`）和 800ms 的 `tick`（`:173`）都在它上面；`processFrame` 里的云端块（`:366-395`）是**同步**调用 `CloudVision.readBoardFromServer` / `readBoard`，也就是在同一线程上等 HTTP。
- 超时配置：`CloudVision.java:64-65` OpenRouter 路径 `setConnectTimeout(20000)`、`setReadTimeout(45000)`（自托管路径 `:104-105` 是 5000／15000，本机 `match3_cloud_url` 未配置时走前者）。
- 静默证据：`awk` 按列取 pid=4850、tid=23703 全文只 8 行，末行就是 `00:00:03.295` 那条 SkJpegEncoder 收尾；把条件放宽到 pid=4850（任意线程），00:00:04 之后的行数实测为 **0**，而日志窗一直到 00:00:32 —— 采集线程之后的 28.7 秒没回过一句，整个进程也没再打过日志。
- 卡在哪儿：全文 `CloudVision` 命中数 **0**。该类三条出口日志（`:76` HTTP 状态、`:83` 云端识别 NxN、`:91` 云端识别失败）一条都没出现，说明既没成功也没失败，请求还在途中。图已经编好（03.295）却没回包，正是 `readBoard` 里 `conn.getResponseCode()` 之后的读超时区间。
- 节拍能对上：00:00:00.812 建 sampler（第 1 帧），800ms tick 到第 3 帧满 `STABLE_FRAMES` 才有多数票，再判 `suspicious` 才打云端 —— 03.2 前后发出请求，与实测 03.293 编码一致。这不是巧合，是同一条链的两个刻度。
- 触发云端的是本地读数就烂：`boolean suspicious = cloudUnknown * 100 > cloudTotal * 25`（`:365`），未知率过 25% 才打云端。换句话说云端是被「本地读出一堆未识别」叫起来的，独立佐证了根因一。
- 另一头也定死了：`grep -rn match3_cloud_escalate --include=*.java android/` 全文只有两处命中，且都是读侧（`:354` 注释、`:357` `getBoolean("match3_cloud_escalate", false)`），**没有任何写入点**。所以 `autoCloud` 恒为 false，云端只可能由 `suspicious` 触发。
- 恶性循环：一次挂 20～45 秒之后 `cooldownUntil` 置 60 秒（`:40`、`:78` 等），冷却期内云端直接返回 null；采集线程这段时间完全停摆，恢复后又碰到 >25% 未知的窗口，只是被冷却挡住不再出图 —— 但玩家侧的观感就是「一直不播报」。

### 根因三（安全网够不着）：首播双重确认门在自我修复／ABSTAIN 之前 return，坏盘等于永久静音

- 门序实测：`:398` 去重 return → `:404-413` 首播双重确认（`if (pendingStable < 2) return;`）→ `:418-422` 变化幅度门槛 → `:432+` 未知率 >40% 自我修复 → `:449-457` 修复后仍不识别才可能播「棋盘位置变了但认不出来」。而 `:315-322` 的 ABSTAIN（「还没找到棋盘位置」）在另一支：只有 `sampler == null` 且从没标定过才会走到。
- 所以本轮的实际路径是：每帧多数票都在漂（44 像素格子落在缝隙上）→ `pendingStable` 永远凑不到 2 → 在 `:411` 就 return → 后面那条专为「采样坏了」设计的 40% 自我修复和它的播报提示一次都没机会执行。全文没有 `自我修复`、没有 `ABSTAIN`、没有任何播报文本日志（`:492` 那条 `Log.i(TAG, sb.toString())` 命中 0）。
- 产品含义：这套防线现在只能防「认得出但读错」，防不了「认不出还硬读」。对一个给盲人用的工具，最不该出现的状态恰恰是「什么都不说」——用户无法区分「没有可走的棋」和「程序读不到盘」。

### 根因四（另一条独立症状）：无障碍服务在取证时是关闭的，行号标尺与点读全灭

- 实锤读数：`dumpsys accessibility` 段（txt 第 776663-776666 行）`Bound services:{}`、`Enabled services:{}`、`Crashed services:{}` —— 三个都是空集，不是崩溃态（Crashed 空），是没启用。
- 旁证：23:52:45 与 23:53:23 两次跳进 MiuiAccessibilitySettingsActivity（`grep` 命中无障碍设置页），符合「装了新包之后回不去、反复去找开关」的行为。覆盖安装会把无障碍授权重置掉，这是 Android 的既定行为。
- 对本仓未提交改动的影响（重要）：工作树里那条「行号标尺」链路（`SenseFieldReaderService.showRowNumbers`，由 `Match3LiveService.java:293`、`:312` 调用）第一步就是 `instance == null` 直接 return，注释写「无障碍服务未开启：静默跳过」。在 <机代号> 这台机的当前状态下它就是静默跳过 —— 功能没坏，是授权没给。这类「依赖无障碍却没告诉用户缺什么」的静默，播报侧必须补一句。

### 时间线（全部取自 bugreport 实测）

- 10-06 23:50:08.155 `ActivityTaskManager: START ... dat=content://com.quark.browser.fileprovider/...` —— 从夸克下载的安装包发起安装。
- 23:50:15.295 `installer_clear_app_data_caller: [2388,1000,com.openkhub.sensefield,39]` —— 覆盖安装清数据（标定随之归零，见根因一）。
- 23:50:22.826 `am_proc_start: [0,4850,10346,com.openkhub.sensefield,prestart-top-activity,...]`；23:50:22.899 进 GameSelectionActivity，23:50:24.368 进 Match3AssistActivity。
- 23:50:24 到 23:59:54 这一整段：event log 里 `am_foreground_service_start(Match3LiveService)` 全文总共 5 条，最后一条是 23:21:34.460 —— 也就是说这次进 App **没有**启动过前台服务，也**没有**任何 `MediaProjectionPermissionActivity` 记录（23:50 到 23:58 区间命中 0）。用户点了「开始实时识别」但授权没走完，服务从未启动。这段里出现的都是系统设置页跳转（Sound／Haptic／无障碍设置／SubSettings），符合「先在找开关」。
- 23:59:54.266 `moveTaskToFront` 回到我们；23:59:55.508 `START ... com.android.systemui/.mediaprojection.permission.MediaProjectionPermissionActivity` —— 录屏同意弹窗。
- 23:59:56.493 系统 `MediaProjectionAppSelectorActivity` 出现在 transition 里。**这条推翻了我自己写在 `Match3AssistActivity.java:303-306` 的注释**：那段说 API 35 用 `createConfigForDefaultDisplay()` 就「不再出现应用选择」，真机 HyperOS 上选择器照旧弹了。注释是错的，代码意图（整屏投影）仍然对，但话不能那么说。
- 23:59:59.802 `Background started FGS: Allowed [callingPackage: com.openkhub.sensefield; ... act=com.openkhub.sensefield.m3live.START ...]`；23:59:59.891 `Match3Live: onStartCommand 投递: action=...START 投影存活=false 屏幕存活=false`；23:59:59.928 `实时识别已启动 1080x2400`。
- 00:00:00.812 `沿用手动标定 8x8`（无格数自检后缀）→ 00:00:03.293 编码 356x336 → 00:00:03.295 之后进程再无任何日志。
- 00:00:10.059 `MIUISafety-Monitor: screen share fg changed: pkg=com.happyelements.AndroidAnimal projecting=true` —— 玩家这时候才切进游戏，投影确实跟着出帧（框选用的盘还没进过识别）。
- 00:00:13.945 `start media_projection for com.miui.screenrecorder,1000` —— 系统录屏随后也起了第二路投影（同一时间窗里两路 MediaProjection，是环境噪声，不是我们的分支）。

### 排除项（查过、不是它们）

- 没有崩溃：全文 `FATAL EXCEPTION` 与 `ANR in com.openkhub` 对我们命中 **0**；`Crashed services:{}` 也空。服务一直活着，是被自己的网络调用堵死，不是死了。
- 没有被省电冻结：`PolicyMaker: uid =10346 pkg=com.openkhub.sensefield reason=fgservice` 在 00:00:05.149、00:00:10.151 持续出现，虚拟屏 `name:sensefield-m3live` 也在 `SecurityManagerService: getUIAgentDisplayId` 里活跃；MIUI GreezeManager 没有冻结我们的 uid。
- 音量不是我们动的：00:00:00.046 那条 `setStreamVolume(stream=11 ...)` 的 `calling=com.miui.voiceassist`，与本案无关。
- `visible_windows.zip`（96618 字节，11 个窗口）里没有游戏截图，所以本轮无法用像素直接复核框的位置，只能靠 356x336 反推 —— 这条限制说明清楚，别当成已目视确认。

### 本轮交付状态（未做对外动作）

- 本轮领导点的是三项：① 先写成案卷 ② 按优先级分批次改代码 ③ 生成详细修复方案文档。**不含** commit／push／发 Release，所以 §10（产物归位那条文档要不要单独提交）继续挂着，新增 §11 请命。
- 修复方案文档落在代码仓：`SenseField/research/board-recognition/BUGFIX_PLAN_2026-10-07.md`（与 `REAL_VIDEO_FINDINGS.md` 同目录）。
- 批次一（P0）：云端调用离开采集线程＋硬超时＋在途单请求守卫；门序重排，让「认不出」一定说话。批次二（P1）：标定合理性闸门（单格边长不合理直接拒绝沿用并提示重标）；把裁剪像素尺寸与未知率打进日志，让下一份 bugreport 单独就能看出标定漂移。
- 工作树提醒：`SenseField`（feature/jev-l2-judgment）本轮开工前已有三处未提交改动（行号标尺那条链：`SenseFieldReaderService.java` 新增、`Match3LiveService.java` 三处调用、`Match3AssistActivity.java` 的 API 35 投影配置），不是我本轮写的，我没动它们，批次一二在其之上叠加。`Match3LiveService.java` 与 `SenseFieldReaderService.java` 是 CRLF 制式，`CloudVision.java` 与 `Match3AssistActivity.java` 是纯 LF —— 改前先量，别一把梭。

## 2026-10-07 上午：四修按批次落地（P0 批次一＋P1 批次二）＋门禁实测凭证

- 领导指令原话：「先把这份分析落进案卷（PROGRESS/BLOCKED），生成详细的修复方案文档，再按这四条动代码」。前两件在上一节与 `SenseField/research/board-recognition/BUGFIX_PLAN_2026-10-07.md` 已交，本节记第三件——四条代码的落地范围与实测门禁。**commit／push／tag／Release 一律没做**，仍停在 BLOCKED §11 等点头。
- 改动面实测（`git diff --stat`，HEAD 仍是 `44c2f63`＝v0.4.30）：5 个已跟踪文件 `+427 / -106` —— `Match3LiveService.java` 326 行、`SenseFieldReaderService.java` 109 行、`Match3AssistActivity.java` 39 行、`CloudVision.java` 33 行、`Match3Sampler.java` 26 行；另两个新测试类与方案文档尚未跟踪。那 109 行里大部分是本轮之前就挂在工作树的行号浮层功能，本轮在该文件只改一件事：`showRowNumbers` 返回类型 void → boolean（无障碍服务没开时返回 false，调用方据此出声提示）。
- **修复一（P0，云端 VLM 彻底离开采集线程）**：`CloudVision` 拆出 `encodeForUpload(Bitmap) -> String`，`readBoard` / `readBoardFromServer` 改成收 base64 字符串；`Match3LiveService.dispatchCloud` 在采集线程只做「取框 → 裁剪 → 编码 → recycle」四件事，HTTP 交给线程名 `m3live-cloud` 的单线程 executor，回包用派发前捕获的 `replyHandler.post(...)` 投回采集线程存进 `cloudMatrix`。在途守卫是 `AtomicBoolean cloudInFlight` 的 `compareAndSet(false,true)`，抢不到就跳过本窗，任务 `finally` 放开；取用侧 `takeCloudResult` 先清空再校验行列与龄期（`CLOUD_RESULT_TTL_MS = 12000`）。为什么不能把 `Bitmap` 直接丢后台：子图与源帧共享像素，而 `onImageAvailable` 会回收上一帧，编码线程拿到的是死像素（`trying to use a recycled bitmap` 会打死那条线程，未捕获异常在子线程直接崩 App）。
- **修复二（P0，门序重排）**：新顺序实测为「多数票 → 未知率 >40% 自我修复 → 云端派发/取用 → 未知率 >40% 出声闸门 → 去重 → 首播双重确认 → 幅度门槛 → 播报」。原顺序里自我修复与提示都排在双重确认之后，坏盘每窗都漂 → `pendingStable` 永远凑不满 → 在那行 return → 提示一次没执行，这就是真机「有帧、无声音」的直接成因。防开场动画假报改由新计数 `unreadableStreak` 承担（连续 2 个稳定窗才开口，读到能认的盘立刻归零），「本局只说一次」由新增 `unreadableAnnounced` 管，与既有 `abstainAnnounced` 分家——两种失效模式不共用一个标志，免得互相吃掉提示。提示文案按状态分两支：未标定＝请先框选；已标定＝标定过的区域已不对、请重新框选。原代码那句「只有没标定过才提示」的口径死结（本轮 `match3_calibrated` 恰为 true）随之删掉。
- **修复三（P1，标定合理性闸门）**：新增 `Match3Sampler.minPlausibleCell(screenW) = max(40, screenW/18)` 与 `plausibleCalibration(screenW, screenH, l, t, r, b, rows, cols, cellOut)`（`cellOut` 回填实测单格宽高，供提示语念出真实数字）。闸门挂两头：实时服务的「沿用手动标定」支（拒绝**使用**：不建 sampler、直接出声）与设置页 `saveCalibration`（拒绝**写入** SharedPreferences 并 toast）。只堵一头，另一头还能把坏值塞回来。自动适配支不加——它已有 40% 试采验证，再加会误伤小棋盘关卡。
- **修复四（P1，打点）**：`describeCalibration()` 统一产出「标定=l/t/r/b% 裁剪=WxHpx 单格≈宽x高px 格数=行x列」；「沿用手动标定」那行现在带后缀，格数自检弃权时明写「（格数自检弃权，按存值读）」，不再让「没后缀」当成要外部推理才懂；云端侧打「云端兜底派发／云端识别接管／过期丢弃／行列不符丢弃」；播报行旁边补「播报读数 …未知=u/t 变化=d 格…」；无障碍没开时 `hintIfReaderOff` 播一次（覆盖安装会重置无障碍授权，每个装新版的人都踩，之前是静默跳过）。
- 两处**偏离方案原稿**，都已在文档里写清理由：① 原稿要加的 `CLOUD_HARD_BUDGET_MS` + `cancel(true)` 看门狗没做——`cancel(true)` 只能中断 executor 线程的中断位，对已陷进 native socket 读的 `HttpURLConnection` 不保证立刻返回，真要强加就得从采集线程 `disconnect()`，那等于把「网络对象跨线程」这个新交叉点引进来，而本修复的目标就是消除跨线程共享；改为把两条 HTTP 的 socket 超时统一收到 connect 4000／read 12000，在途位被慢请求占住时只是「不再发新请求」，本地播报照常，属有界降级不是静默。② 原稿的 `MIN_CELL_PX = 60` 常量改成随屏宽——1080 宽下 `1080/18 = 60`，真机这条判据数值不变，但常量 60 在 480 宽的老年机上永远达不到，会把有效标定全拒掉；无障碍工具的用户机屏幕分布比一般 App 散，不能拿一台机器的实测值当全局常量。
- 方案文档已按**实际实现**回写（不是留着一份没落地的设计）：修复一第 2／3／4／5 条、修复三第 1 条、两处偏离、以及各节验收里引用的变量名（原稿写 `abstainAnnounced`，实际新支路用 `unreadableAnnounced`）全部对齐；文档从 181 行／13,187 字节变为 225 行／17,622 字节，纯 LF、全角括号 74/74 配平、实测 0 个西里尔字符（写作过程中一度把「格数=行x列」打成带西里尔字母的串，被同一轮扫描抓到并改掉）。
- 自查抓到并改掉自己的一处坏写入：批次二往 `Match3Sampler.java` 插新方法时，锚点取的是 `detectGridCount` 的函数签名行，结果插入点落在它的 Javadoc 与函数之间——那条文档注释变成悬空注释（编译照绿，人读会以为它在讲 `minPlausibleCell`）。已把整块挪到文档注释之前，并用「行内容集合前后完全相同」断言这次只是挪位置没改内容：525 行不变、`CR=CRLF=525`、`*/` 紧邻 `static int detectGridCount(` 命中恰 1。教训写在这里：**往 CRLF 文件插代码时，锚点必须把它前面的文档注释一起框进替换块**，只框签名行就会割裂文档。
- 新增两个测试类共 14 条（都是纯 JVM，无 Robolectric，沿用本仓既有口径）：`Match3UnreadableGateTest` 7 条钉「读不读得出」与「要不要出声」——8x8 里 26 格未识别（40.6%）判坏盘、25 格（39.06%）判能读、空格与未识别都计入、三帧每帧都漂时多数票整盘变未定一定触发闸门、单窗不开口、streak=2 且未播过且过 6s 才开口、按生产判据逐窗走四窗只出声 1 次；`Match3CalibrationGateTest` 7 条钉标定闸门——下限随屏宽（1080→60、1440→80、720／480／100→40）、真机那块 356x336 的框按 8x8（44x42）拦下、正确框 14/38/87/71 按 7x7（112x113）与 8x8（98x99）与 9x9 全放行、单边不足也拦（横向够宽纵向压扁照样拒）、720x1280 默认框放行、坏框降到 6x6（59x56）仍拦、`cellOut` 传 null 不崩。
- 为了让「测的就是线上跑的」，把两个判据从 `processFrame` 抽成包内静态：`Match3LiveService.isUnreadableBoard(char[][])`、`shouldAnnounceUnreadable(streak, alreadyAnnounced, sinceLastAnnounceMs)`，`countUnknown` 相应从 private 放开到包内。生产代码现在调这两个静态（`:393`、`:417`、`:420`），测试调的也是它们。
- 在途守卫与网络替身**没写本地单测**，这条是明说的缺口不是遗漏：`dispatchCloud` 依赖 `SharedPreferences`、`Bitmap`、`Handler` 三个 framework 类，本地 JVM 造出来的假件只能测假件自己。该条按方案文档走真机验收。
- 门禁凭证（本机实测，可复跑）：**先说清基线版本状态**——`SenseField` 工作树基线是 HEAD `44c2f63`（`versionCode 27`／`versionName '0.4.30'`，`git ls-remote` 实测远端分支头同为 `44c2f63`，远端 tag 已有 `v0.4.2`／`v0.4.3`／`v0.4.30`），比本卷上一条记录的 alpha.16（versionCode 19）新了好几版，并行会话在这段把版本推进到了 0.4.x；本卷对 0.4.x 那几版没有任何记录（`grep -c "0.4.30" PROGRESS.md` 改前实测 **0**），别把本卷当全量版本史读。本轮四修全部叠在 `44c2f63` 之上、全部未提交。凭证：`gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline` **BUILD SUCCESSFUL**（`output/gate-gradle-2026-10-07T08-11-25.log` 4,163 字节，34s，`compileDebugUnitTestJavaWithJavac` 与 `testDebugUnitTest` 均为 executed 非 up-to-date；复跑 `...T08-21-52.log` 3,929 字节同样 SUCCESSFUL）；测试报告实测 **30 类 204 条、0 失败 0 错误 0 跳过**（上一班基线 28 类 190 条 → 本轮 ＋2 类 ＋14 条），并逐条核过测试报告时间晚于源文件时间（XML mtime 08:11:03，最晚的测试源 mtime 08:10:02、最晚的主源 mtime 08:09:11），证明跑的是终版内容不是旧编译；`lintDebug` **0 error／19 warning**，逐条按行号与本轮 `git diff -U0` 的改动行取交集，只有 1 条重叠——`SenseFieldReaderService.java:194` 的 `StaticFieldLeak`，命中的是那笔既存浮层功能自己的 `private static volatile instance`，本轮在该文件只动了返回类型，没碰这行；`bash research/board-recognition/java-bench/run-tests.sh` **30 条 OK**（脚本先按 sha256 同步镜像源，实测 `Match3Sampler.java` 两处同值＝d998ede8…，跑的是改后版本）；SenseField 仓 `python scripts/check_public_repo.py` **335 文件通过**（上一班同仓记录 330；实测 `git ls-files` 单独数是 332，脚本口径是 `--cached --others --exclude-standard`，即「已跟踪＋未跟踪但没被忽略」，所以本轮新增的 1 份文档＋2 个测试类**确实进了扫描面**，332＋3＝335 对得上）。
- 扫描口径里要说清的一条（本轮实测查出，免得下一班误信）：`check_public_repo.py` 的密钥规则只认四类形态——私钥块、`gh[pousr]_` 长串、`AKIA` 16 位、`AIza` 35 位（脚本 `SECRET_PATTERNS` 实测四行），**`sk-or-v1` 这类 OpenRouter 前缀不在它的规则里**；所以「335 通过」不等于「API key 形态被人扫过」，那部分靠的是下面这条手扫。
- 身份痕迹手扫（口径 `wxid|xwechat|sk-or-v1|邮箱正则|用户名 <本机账号>|<云盘目录名>|设备代号 <机代号>|构建号 <构建号前缀>|仓库拥有者名`）：5 个改动源文件＋2 个新测试类 **0 命中**；方案文档 1 命中，内容是它自己抄了一遍扫描口径那串（第 208 行），同串在已跟踪的 `JudgmentSelfTestActivity.java` 里早有先例，属描述性文字不是痕迹。文档第 3~4 行原稿带过真机 bugreport 的文件名（内含设备代号与构建号），本轮改成「一台 Redmi＋文件名与设备标识只记在案卷、不进本公开仓」，复跑 `grep -c "bugreport-<机代号>\|<构建号前缀>\|<机代号>" 该文档` 实测 0。
- 行尾逐文件实测（本仓 CRLF／LF 混存，改前先量）：`Match3LiveService.java` CR=CRLF=863 裸 LF 0、`Match3Sampler.java` CR=CRLF=525 裸 LF 0、`SenseFieldReaderService.java` CR=CRLF=282 裸 LF 0、`CloudVision.java` CR=0、`Match3AssistActivity.java` CR=0、两个新测试类 CR=0。`git diff` 那三条「CRLF will be replaced by LF」的提示是 `.gitattributes` 的 `eol=lf` 在入库时归一，与工作树制式无关，不是本轮写坏。
- 玩家侧听得见的变化（三条，不含内部重构）：① 坏标定不再全程静默，最迟第二个稳定窗会听到「这一盘我认不出来…请重新框选标定」，且一局内不刷屏；② 刚存下一个几何上不可能的框（单格不足下限）会被当场拒绝并念出实测像素，不再「存了、信了、然后什么都不说」；③ 云端 VLM 不再卡住整条采集流水线，网络慢的时候本地播报照常。
- 真机验收**没做**，不能算已过：本轮手上没有设备，唯一的真机证据还是领导那份 bugreport。方案文档里「授权→切进游戏→必须听到第一句」这条只能等下次真机录屏或领导装机反馈。
- 新发现的一条对外风险（本轮未处理，见 BLOCKED §12）：在**案卷这份 checkout**（`feature/sensefield-dev`）跑同一个 `scripts/check_public_repo.py`，实测 **7 条 error 全是体积超限**——`delivery/` 下 6 枚 debug APK（各 12.3／21.4 MiB）加 `research/board-recognition/models/` 里 334.6 MiB 的 `tensorflow_cpu-2.21.0` wheel，候选集 618 文件；不是身份痕迹，但这份仓若要推出去，超限文件会直接把发布卡住。SenseField 那份 checkout 335 文件通过，两边状态不同，别说成「都过了」。
- 同一轮还抓到自己的第二次坏写入（记进案卷是因为成因可复用）：上一节的补丁 `Edit` 报「1 replacement」成功，实际把整节内容重复追加了一遍——复跑 `grep -c "^## 2026-10-07 上午"` 实测 **2**（275 行与 297 行两份；这是修复前的读数，重复块删掉后本条自身复跑为 1，两个数各属各的时刻），字节数与行数被推到 **81,466／317**。逐条比对了两份的全部差别才动手：保留措辞规范的那份（另一份有一处「一坏写入」漏字），把另一份里两条经核对才成立的事实（上一班同仓扫描记录 330、方案文档头部去标识化后的复跑读数）并进来，再删掉重复块，删后复测标题命中 **1**、行数 317→295、字节 81,466→68,833、CR 仍 0、全角括号左右数相等（当时 358/358）、西里尔字符 0（随后补写本条会让行数加 1，属预期，不是读数错）。教训：**对同一文件先跑脚本追加、再用 Edit 补同一批文字时，Edit 的锚点若落在追加位置之前，等于把整段写两遍**——凡「往案卷加一整节」，本轮只走一条写入路径，收尾前用标题串命中数（应为 1）做一次存在性复扫，而不是只看 Edit 的返回值。


## 2026-10-07 上午（续）：四修提交与 v0.4.31 发布——对外动作按「执行」放行后落地

- 放行口径：上一节末尾列的两件待办里，领导回「执行」指向 BLOCKED §11 那条请命（提交与发布），本轮据此做 commit／push／tag／Release；真机验收不在放行范围（手上仍无设备），§11 结案后仍作为开放项留在卷里。
- 提交前实测：`git status --short` 只有 5 改 3 新（两个测试类＋方案文档），无越界文件；`git diff --stat` 与上一节记录的 `+427 / -106` 一致。`python scripts/check_public_repo.py` 提交前复跑 **335 文件通过**（08:53:28）。
- 版本号现查不照抄：`git ls-remote --tags` 实测远端最新 `v0.4.30`，`android/app/build.gradle` 现值 `versionCode 27`／`versionName '0.4.30'`（纯 LF，5,534 字节），按既有约定升到 **28／0.4.31**（`v0.3.5-jev-alpha.17` 那个缺号说明「建了产物没打 tag」发生过，不能拿本地产物名当版本依据）。上一节 §11 原稿的 alpha.17 作废口径由此落地执行。
- 提交：单号 `d3afcd1`，9 文件 `+845 / -108`（含两个新测试类 105＋88 行与方案文档 224 行）。提交身份沿用本分支既存约定 `<既存提交身份>`（`git log --format='%an'` 在本分支实测该身份 37 枪，本轮没改 `git config`）。提交说明与推送 diff 复扫身份痕迹：`wxid／xwechat／sk-or-` 命中 **1 行**，是方案文档第 208 行抄了一遍扫描口径那句（字面量里带这些前缀，无真 ID）；提交正文 0 命中。
- 通道：`git ls-remote origin` 走 HTTPS 实测 **连不上 github.com:443**（21 秒超时后 fatal），改用显式 URL `git@github.com:ld0574/SenseField.git`（`~/.ssh/config` 已把 github.com 重写到 `ssh.github.com:443`），**没动 `remote.origin.url`**。推上去实测 `44c2f63..d3afcd1`，随后 `git ls-remote` 复认远端分支头与 `refs/tags/v0.4.31` 同为 `d3afcd1`。
- tag 制式随大流：`git for-each-ref` 实测 `v0.4.2／v0.4.3／v0.4.30` 全是 `objecttype=commit`（轻量 tag），故 `v0.4.31` 也打轻量、指向 `d3afcd1`，没有临时改成附注 tag。
- 产物（gitignore 的 `output/`，不落仓）：arm64 与 x86_64 各一次 `--offline --no-daemon` 构建，BUILD SUCCESSFUL 分别在 1m26s（10 executed）与 19s；`SenseField-0.4.31-arm64-debug.apk` 12,930,142 字节（mtime 08:51:57，sha256 `80a21190ce8335efc996a12fd04860710e4cbb1eaafe5128cdb59f959cbe3898`），`SenseField-0.4.31-x86_64-debug.apk` 22,493,040 字节（mtime 08:52:26，sha256 `0b1752979416fc9f74a43349674e8986fffb131f91c8c5a4aea4b5741dd2fcf2`）。ABI 不是看文件名：用 `zipfile` 读 `lib/` 目录实测一枚只有 `arm64-v8a`、另一枚只有 `x86_64`；`aapt dump badging` 实测两枚都是 `versionCode='28' versionName='0.4.31'`、`minSdk 29`、`targetSdk 35`。**这两次构建的 gradle 输出只留在终端尾部、没落盘**，要复跑用同一条 `./gradlew :app:assembleDebug --offline -PsensefieldAbi=…`；落盘凭证是两枚 APK 本体与下面的回读 JSON。
- 发布：`gh release create v0.4.31` 带两枚 APK、`--verify-tag`，正文取 `output/release-notes-0.4.31.md`（3,147 字节、CR 0、全角括号 10/10、身份扫描 0 命中；文中把「真机验收尚未完成」写在「已知边界」里，没写成已修好）。回读实测 `id=405262380`、`draft=false`、`prerelease=false`、`published_at=2026-10-07T00:55:29Z`（本机 08:55:29）、`releases/latest` 已指向 `v0.4.31`。**GitHub 自己算的 asset `digest` 与本机 sha256 逐字符相同**（两枚都是），字节数也一致，这比「我传上去了」强一档；asset 上传者账号是 gh 令牌所属的 `<gh 令牌账号>`，与提交身份不同，属既存双账号配置，本轮未改。凭证落盘 `output/release-v0.4.31-readback-2026-10-07T08-56-07.json`（8,234 字节，名字时刻取自落盘后实测 mtime，同轮 `json.loads` parse 通过）。
- 未做的一条对外动作（留给领导裁）：**Gitee 侧没同步**。`remote gitee` 是 `git@gitee.com:leda/SenseField.git`，0.4.2 那次的口径是「管理员账号或令牌由领导侧同步」（`output/同步到Gitee.bat` 就挂在那儿），本轮不擅自推第三方镜像。
- §11 由此结案；卷内仍开着的两件是：真机验收（「授权→切进游戏→必须听到第一句」，以及坏标定场景要听到「这一盘我认不出来」）与 BLOCKED §12（案卷仓 7 条体积超限）。

## 2026-10-07 上午（续2）：案卷去标识与推送（口径＝先洗标识再推）

- 领导指令：截图给出两个选项并要「按顺序执行」——① 把案卷推送远端，先把机代号／构建号前缀这类设备标识从案卷里洗掉；② 按 §10 清单建 `output/releases/<版本>/` 重排产物位置。本段记①，②在下一段。
- 洗的口径与实测（走仓外脚本 `_tmp_wash_identity.py`，11 条替换规则按最长串优先，免得把 bugreport 文件名里的机代号先拆坏）：
  - `PROGRESS.md` 74,654 字节／310 行 → **75,203 字节／312 行**；实际命中的替换类别：完整 bugreport 文件名 1、HyperOS 构建号 1、`<HOME>\` 5、`Redmi「<机代号>」` 1、其余 <机代号> 4、其余 <构建号前缀> 2、扫描口径串里的「用户名 <本机账号>」1、<云盘目录名> 1、<gh 令牌账号> 1、<既存提交身份> 1。
  - `BLOCKED.md` 27,605 字节／202 行 → **28,186 字节／204 行**；`<HOME>\` 2、<机代号> 4、<构建号前缀> 2、<本机账号> 2、<云盘目录名> 2。
  - 两份各 **+2 行**＝图例行加其后空行（脚本在每个文件首个 `# ` 标题下插一行「身份标识占位符」说明，插前先断言该行不存在，防重复插）。
  - 洗后复扫（9 个关键词：机代号原文、构建号前缀原文、HyperOS 尾段原文、本机账号原文、云盘目录名原文、gh 令牌账号原文、既存提交身份原文、盘符加 Users 的路径前缀、bugreport 拼接形态）在两份文件里命中 **全为 0**；CR 仍 **0**（两份都是纯 LF，第 0 步选的尺子是「CR 总数为 0」，不是 CRLF 数）；全角括号 PROGRESS 390/390 → **391/391**、BLOCKED 124/124 → **125/125**，各多一对来自图例那句「（不进 git、不推送）」，配平没破。
- 读数口径更正（我在动手前口头报错过一次）：当场说过「<机代号>×4／<构建号前缀>×2」，那是**非重叠正则**的数；案卷用的尺子是 `str.count` 子串计数，bugreport 拼接形态里那个机代号会被再计一次，所以洗前实测是 **<机代号>×6、<构建号前缀>×3**（`PROGRESS.md`）。以脚本打印的 `str.count` 为准；要复核就跑同一条：读字节 → 解码 → 逐词 `text.count(k)`。
- 为什么必须重做提交（技术判断，不是洁癖）：光洗工作树没用。案卷本地有两枪未推的 `docs(casefile)` 提交（`21cae99`、`9b7f413`），它们的 **blob 存的是洗前原始标识**；`git push` 推的是可达对象集合，照常推就等于**第一次**把设备标识带进公开分支的**历史**，别人 `git show 21cae99:PROGRESS.md` 直接读得到。做法：`git reset --soft <远端头>` 把两枪退回索引层 → 只 `git add` 根目录这两份案卷 → 重做成一枪（本段就在其中）。实测远端头 `git ls-remote <仓外 SSH URL>` 取到 `97cfda75…`，本枪 parent 恰是它，所以推送是 **fast-forward、不带 `--force`、不改写任何已推历史**；那两个旧单号留在本地 reflog，记在这里是为了复跑时对得上。
- 顺手把「已推历史」量了一遍（结论：本轮不扩大泄漏面，也没修既存）：`git grep origin/feature/sensefield-dev` 实测设备标识类（机代号／构建号前缀／HyperOS 尾段／gh 令牌账号／既存提交身份）**0 命中**；但**本机账号名与云盘目录名**在 4 个已推文件里共 **7 处**本地绝对路径——`backend/eval/patch-activity.js` 1、`research/board-recognition/report.md` 2（含 1 处云盘名）、`research/board-recognition/src/common.py` 2、`research/board-recognition/src/gen_synth.py` 2，都是早前同步工作区时带进去的硬编码路径，不是本枪带进去的。要把这 7 处一并抹掉得改写已推历史并强推，那是另一件要单独点头的事，已挂进 BLOCKED §12。
- 提交身份沿用：本枪 author 仍是那把既存提交身份（取自本仓本地 config，本轮**没动 `git config`**）；该身份在已推的 `feature/sensefield-dev` 历史里实测已有 **5 枪**先例（同分支另有 `ld` 109 枪、`Da Le` 4 枪）。它的邮箱只在提交元数据里、不在文件内容里，而本轮扫描口径覆盖的是文件内容与 diff 新增行——这条边界说清楚，别把「0 命中」读成元数据也干净。
- 图例与映射表：两份案卷头部各有一行占位符说明；映射表（真实串 ↔ 占位符）写在**仓外** `<HOME>\Desktop\开源对接项目\_scratch_archive\casefile_identity_map.md`，不进 git、不推送。案卷里凡带占位符的复跑命令，按那张表还原后再跑（不还原也读得懂，只是路径不是本机真实路径）。
- 推送与复测口径（读数归下一枪，本段不预先声明）：`origin` 实测是 HTTPS URL，本机 `github.com:443` 直连不通（`git ls-remote origin` 刚实测 fatal：Failed to connect to github.com:443，21.1 秒超时，rc=128），所以推送走既有 SSH-over-443 的显式 URL，不改 config。推后复测两件事：远端 head 与本地 head 相等；`git show <远端ref>:PROGRESS.md` 与 `BLOCKED.md` 的 9 词命中仍全 0。**本枪自己的推送读数不可能写进本枪内容里**（结构性自指，越追越晚），所以下一枪只补那两个读数并结掉 §12 里「推不推」那一半。
- 本轮没做：没 tag、没发 Release、没动 `delivery/` 与 `models/` 里那 7 个超限文件（§12 三个选项继续等口径）、没同步 Gitee。
