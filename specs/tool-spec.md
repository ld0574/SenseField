# 无障碍判定创作工具 · 设计规格

执行者按本文件实现。日期基准 2026-10-02。API 契约一律以 `specs/jev-api-spec.md` 为准，本文件不复述。

## 0. 目标与非目标

目标：一个**单文件** `无障碍判定创作工具.html`，双击即开，零构建、零服务器。四个页签：①开始路线 ②判定设计器 ③试跑台 ④导出。用途：为「听野」无障碍游戏辅助工具设计 Jev 判定（state schema + 原子问题）、打真请求看概率分布、导出配置与 Kotlin 代码。

非目标：不做 Android 端实现；不做账号系统；不做后端。

死规矩（违反即失败）：
- 除试跑台的 API 请求外，**零外部网络依赖**：不许 CDN、不许 `<script src="http…">`、不许外链字体/图片/分析脚本。
- 全部 JS 放**单个** `<script>` 块内，**不用内联 on* 事件属性**（onclick/onchange/oninput 一律不用，用 addEventListener），保证 `node --check` 可全量校验。
- **可测性接缝（死规矩）**：脚本顶层只定义函数与常量，DOM/UI 初始化包在 `if (typeof document!=='undefined')` 里；`buildRequest(cfg)`、`sendJev(cfg, fetchImpl)`、`renderError(status, bodyText)` 三个纯函数必须独立存在（契约见 §4），脚本尾部加 `if (typeof module!=='undefined') module.exports={buildRequest,sendJev,renderError};`——这样验收能在 node 里无头跑，不依赖浏览器。
- 页面语言：简体中文。术语首次出现给半句人话。
- API key 只存 localStorage，输入框旁标注「仅存本机，不会上传到任何服务器（除你所选的判定渠道）」。

## 1. 页签 ① 开始路线

静态内容页，断网可用：
- 一段话讲清三层架构：L1 感知层（无障碍树优先、OCR/模板匹配兜底 → 文本 state）→ L2 判断层（Jev：一帧一组原子问题并行判定）→ L3 执行层（Harness：白名单授权、结果校验、TTS 播报）。
- 渠道表格（两条预设 + OpenRouter 仅记录），复制 `jev-api-spec.md` §2 的信息。
- Day-1 清单：注册 EdgeOne 拿 key → 打开本工具载入模板 → 贴一帧真实无障碍树 → 试跑台打真请求 → 照概率分布反推阈值。
- 三条提醒（红色 callout）：Jev 只吃文本不做识别；「不会幻觉」只对类型成立（不会返回未声明的选项，但会自信选错）；低置信绝不静默。
- 中文注意：criteria 英文更稳、state 保留中文、必须建中文评测集（spec §8）。

## 2. 页签 ② 判定设计器

- 模板选择（下拉）：`消消乐（8×8）` / `象棋（9×10）` / `通用无障碍树` / `空白`。选定后 state 编辑器与问题编辑器预填对应模板。
- **state 编辑器**：`<textarea>`，JSON 格式，预填对应 samples 文件的内容（与 `samples/*.json` 一致）。旁边显示 JSON 校验状态（合法/报错行提示，用浏览器 JSON.parse）。
- **问题编辑器**：五个默认问题（jev-api-spec.md §10），每问一行卡片，可增删改：
  - id 文本框；type 下拉（noul/choice/score）；
  - instructions 多行文本框；
  - criteria 编辑器：noul→true/false 两个文本框；choice→「选项名/描述」可增删行；score→有序等级列表（可增删行，2–10 级）。
  - 每张卡片显示该问返回什么（如「返回：choice + probabilities + confidence」）。
- 页面顶部常驻铁律条：「Jev 判断，Harness 授权与执行；低置信绝不静默」。
- 底部实时「请求预览」：按 API 契约拼好的完整请求体 JSON（state + model + questions），带复制按钮。
- 「送入试跑台」按钮：把 state 与 questions 传给页签③。

## 3. samples 三个样例 state（模拟数据，不是真实抓取）

每个文件是合法 JSON，顶层加 `"_note": "模拟样例，供测试判定用，非真实抓取"` 字段：

1. `samples/match3.json` — 消消乐：`game:"match3"`、`scene:"playing"`、`board:{size:[8,8], rows:[[8 个颜色字母]×8]}`（字母 R/Y/B/G/O/P 代表红黄蓝绿橙紫）、`potential_matches:[{from:[r,c],to:[r,c],run:3,reward:"low"}]` 至少 2 条、`moves_left`、`score`、`time_limit_sec`。
2. `samples/chess.json` — 象棋：`game:"chess"`、`turn:"red"`、`board`（9×10，用棋子汉字「車馬炮將士兵相」等，空位 null，双方用前缀 r/b 区分或分大小写）、`last_move:"炮二平五"`、`in_check:false`、`captured_recently:null`。
3. `samples/menu-axtree.json` — 游戏菜单无障碍树：`package`、`activity`、`nodes:[{id,text,content_desc,clickable,bounds}]` ≥5 个节点（含「开始游戏」「设置」「商店」「第 12 关」等）。

HTML 内嵌的模板数据必须与这三个文件内容一致（同一份数据复制两处）。

## 4. 页签 ③ 试跑台

- **渠道配置**：endpoint 下拉（EdgeOne 默认 / TypeSafe 官方 / 自定义 URL）+ model 文本框（选渠道时自动填 `@makers/jev` 或 `jev-latest`，可改）+ API key 密码框（存 localStorage）。
- **发送**：`sendJev(cfg, fetchImpl)` 真 POST，超时 30s。UI 调 `sendJev(cfg, window.fetch)`。正常返回 `{status, bodyText, elapsedMs, parsed}`；**网络失败返回 `{status:0, bodyText:'<错误信息>'}`**；不吞错、不在前端重试（退避重试是 Kotlin 端的事）。渲染：HTTP 状态码、耗时 ms、原始响应体（折叠 `<pre>`）、usage token 数；每个问题一张卡片：类型、判定值、confidence（choice/score 画条形）、**完整概率分布条形图**（每选项一条，宽度=概率，标百分比）；noul 显示 p 值与 `|2p−1|`。
- `renderError(status, bodyText)`：纯函数，返回展示字符串＝状态码＋**响应原文**＋一行解释（401 key 无效 / 422 请求体不合规 / 429 限流 / 529 过载 / 0 网络失败）。
- 错误处理：401/422/429/529/网络错误**原样显示**状态码与错误体，不许吞、不许替换成友好文案遮盖原始信息（可在旁边附一行解释）。
- **假钥匙测试**按钮：用 `sk-fake-test` 发一次，预期 UI 显示 401 `auth_missing`——用于自证错误路径没写死。
- **历史**：每次请求/响应存 localStorage（state、answers、耗时、时间戳），列表可查看、可删除、可导出 JSON 文件。
- **分布统计**：对历史记录按问题聚合，显示每个 choice/score 问题的 confidence 最小/中位/最大值；noul 显示 |2p−1| 分布。旁注：「阈值照分布反推，别拍脑袋定 0.8；不同操作阈值应不同（高置信自动执行/中置信确认/低置信播报没看清）」。

## 5. 页签 ④ 导出

- **config JSON**：`{channel:{endpoint,model}, questions:{…}, thresholds:{每问:{high,mid,low}}, generated_at}`。默认阈值：choice/score 高 0.9 / 中 0.5，noul 用 |2p−1| 同档。若试跑台有统计数据，提示「按分布调低/调高」。下载为文件。
- **Kotlin 片段**（`<pre>` 显示 + 复制 + 下载）：
  - 数据类：`JevRequest/JevAnswer` 按 jev-api-spec.md §4–§5 字段逐一对应（sealed class 三态：Noul/Choice/Score）。
  - 调用：OkHttp/HttpURLConnection 伪代码皆可，POST endpoint，Bearer key；429/529 指数退避；401/422 原样上抛。
  - **Harness 循环骨架**：帧回调 → L1 序列化 state → 一帧一组原子问题 → 按阈值三档分流：高置信→查白名单（只有白名单内的 next_action 才许触发 AccessibilityService）→ 执行后结果校验 → TTS 播报；中置信→先播报待确认；**低置信→TTS 播报「我没看清当前局面，请让我重新扫描」——绝不静默、绝不猜**。
- 导出前自检：点「生成」时对 config JSON 做一次 `JSON.parse(JSON.stringify(cfg))` 往返校验，失败则禁止导出并显示原因。

## 6. 无头自检（验收命令，判定标准）

不需要浏览器，在仓库根目录逐条跑。步骤 1 提取＋语法校验；步骤 2 反向验证（假 401＋断网各一次，证明错误路径消费真实响应、没写死）：

```bash
# 步骤 1：提取脚本并做语法校验（零输出＝通过），顺带外链/内联事件检查
node -e "const fs=require('fs');const s=fs.readFileSync('无障碍判定创作工具.html','utf8');const blocks=[...s.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)];if(blocks.length!==1){console.error('script块数='+blocks.length);process.exit(1)}fs.writeFileSync('_chk0.js',blocks[0][1])"
node --check _chk0.js
grep -cE "<(script|link)[^>]+(src|href)=\"http" 无障碍判定创作工具.html   # 期望输出 0
grep -cE "on(click|change|input)=" 无障碍判定创作工具.html               # 期望输出 0

# 步骤 2：无头反向验证（期望输出含 401/auth_missing 的 FAKE401 行、不含 401 的 NETFAIL 行、SELFTEST-OK，退出码 0）
cat > _chk1.js <<'EOF'
const t = require('./_chk0.js');
(async () => {
  const fake = async () => ({ status: 401, text: async () => '{"error":{"message":"Missing API key. Pass it via Authorization: Bearer {MAKERS_AIG_KEY}","type":"auth_missing","code":"auth_missing"}}' });
  const r = await t.sendJev({ endpoint: 'https://x.invalid', apiKey: 'sk-fake', state: 't', questions: {} }, fake);
  const o = t.renderError(r.status, r.bodyText);
  console.log('FAKE401=>', o);
  if (!(/401/.test(o) && /auth_missing/.test(o))) process.exit(1);
  const bad = async () => { throw new TypeError('fetch failed'); };
  const r2 = await t.sendJev({ endpoint: 'https://x.invalid', apiKey: 'k', state: 't', questions: {} }, bad);
  const o2 = t.renderError(r2.status, r2.bodyText);
  console.log('NETFAIL=>', o2);
  if (/401/.test(o2) || !/fetch failed/.test(o2)) process.exit(1);
  const cfg = t.buildRequest({ endpoint: 'e', apiKey: 'k', model: '@makers/jev', state: { a: 1 }, questions: { q1: { type: 'noul', instructions: 'x' } } });
  const j = JSON.stringify(cfg);
  if (!(/"state"/.test(j) && /"questions"/.test(j) && /"model"/.test(j) && /"instructions"/.test(j))) process.exit(1);
  console.log('SELFTEST-OK');
})().catch(e => { console.error(e); process.exit(1); });
EOF
node _chk1.js && rm _chk0.js _chk1.js
```

判定标准：步骤 1 三条命令分别「零输出 / 0 / 0」；步骤 2 打印的三行符合注释里的断言、退出码 0。任何作弊写法（renderError 写死 401 文案、sendJev 不调 fetchImpl）都会在这里或抽查里露馅。
