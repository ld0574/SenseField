# 提示词包：System One（`/v1/systemone` + choice/score/noul）协议兼容复刻

> 用途：把本项目的 Jev 调用从「绑定单一云端供应商」改造成「可替换后端」。
> 用法：在 Qoder / Claude Code / Codex 中打开项目根目录，**依次粘贴 P0 → P1 → P2 → P3**，每段跑完确认再进下一段。
> 前置：已读 `specs/ADR-0001-Jev渠道选型与可替换后端.md`。

---

## 全局铁律（每段提示词都自动继承，不要在我的提示词里重复声明）

1. **禁止改动** `specs/` 下已有两个文件（sha256 `6063675d…83800` / `e2c9f88b…7aec`），改动即失败。
2. endpoint 与 model 字符串**只允许**出现在各 Client 实现内部。业务代码、Harness、UI 中不得散落任何 endpoint / model 字面量。
3. 所有中文注释与文案保持简体中文；代码标识符用英文。
4. 不确定就先写探针脚本实测，**不要凭记忆或文档假定契约**。
5. 每一步结束都要给出**可复制的真实命令输出**，不接受「应该可以」。

---

## P0 · 契约先行：抽出 `JudgmentClient`（先做这个，其余都依赖它）

```
你现在是这个项目的架构重构执行者。目标：把散落在各处的 Jev 调用收敛成一个可替换后端接口，
为后续接入协议兼容的本地/自托管实现铺路。

【背景】
本项目 L2 判断层调用 TypeSafe Jev（System One 模型），目前直接在业务代码里 fetch 端点。
现需改造成可替换后端。Jev 的协议形状已成为事实标准，开源社区有协议兼容的复刻实现
（Laya / OpenJev 等），所以只要守住接口形状，后端可换。

【已知的权威契约，严格照此实现，不要自行发挥】
请求：POST {endpoint}/v1/systemone
  Headers: Authorization: Bearer <KEY> / Content-Type: application/json
  Body: { "model": string, "state": string|object|array, "questions": object }
  questions 每个条目：
    { "type": "choice"|"score"|"noul", "instructions": string, "criteria": 依类型 }
    - choice: criteria 为 object，{ 选项名: 选项含义 }
    - score:  criteria 为 array，按 0,1,2... 顺序给出每一档的描述
    - noul:   criteria 可选，用于写清「是 / 否」各指什么
  同一请求内所有 questions 并行、彼此隔离地评估同一个 state。

响应（真实样本，字段照抄）：
{
  "model": "jev-1.13.0",
  "answers": {
    "department":  {"type":"choice","choice":"technical","confidence":0.78,
                    "probabilities":{"technical":0.85,"billing":0.15,"sales":0}},
    "frustration": {"type":"score","score":1,"confidence":1,
                    "legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},
                    "probabilities":{"0":0,"1":1,"2":0}},
    "is_urgent":   {"type":"noul","noul":1}
  },
  "usage": {"input_tokens":392, "output_tokens":65}
}

【你要交付什么】
1. `JudgmentClient` 接口（Kotlin 优先，若项目主语言不同则跟随主语言）：
   suspend fun judge(state: Any, questions: Map<String, Question>): JudgmentResponse
   Question 用密封类/代数类型表示 Choice / Score / Noul 三种，让类型系统保证 criteria 形态正确。
2. 三个实现：
   - EdgeOneJevClient    → https://ai-gateway.edgeone.link/v1/systemone , model "@makers/jev"
   - OpenRouterJevClient → https://openrouter.ai/api/v1/systemone     , model "typesafe/jev-1.13"
   - LocalSystemOneClient→ baseUrl 由构造参数注入（先留空实现，P1 再填）
3. 统一错误模型，必须区分以下情况并给出不同文案：
   - 网络失败（HTTP 0 / fetch failed）→ 断网分支
   - 401 + auth_missing（未带 key）
   - 401 + auth_failed（key 错误）
   - 其他非 2xx → 原文上屏，不做美化改写
4. 一个工厂/注入点，让后端选择只在一处发生。

【验收标准】
- grep 全仓，`http` 相关字面量只出现在三个 Client 实现文件内，其余 0 命中。
- 写一份契约测试，用固定 state + 三个问题（choice/score/noul 各一），
  对三个实现分别断言：响应能解析成 JudgmentResponse，且 choice 落在声明的选项集内、
  score 落在 0..levelCount-1、noul 落在 0..1。网络不可达时测试标记为 skip 而非 fail。
- 给我 grep 的真实输出和测试结果原文。
```

---

## P1 · 契约探针 + 本地/自托管后端落地

```
接 P0。现在填充 LocalSystemOneClient 的真实后端。
你的任务是让一个「协议兼容的 System One 服务」在本地跑起来，
并让 P0 的 LocalSystemOneClient 指向它、通过同一套契约测试。

【第一步：先核验，不要假定】
关于开源复刻实现，公开资料里有几处互相矛盾的信息，你必须实测确认，不许猜：
  a) 是非题的类型名到底是 "noul" 还是 "boolean"？
  b) choice 的 criteria 到底收 object（选项名→含义）还是 array（仅选项名）？
  c) 返回体里是否有 confidence 字段？probabilities 是否归一？
  d) 是否有 legend / routing 等 Jev 没有的额外字段？
  e) 中文输入时，是自动路由到多语言分支，还是需要手动指定？置信度掉多少？

另外：名为 "OpenJev" 的仓库在公开资料里出现了至少三个不同的项目，
请先确认你要用的是哪一个、它是否真的暴露 /v1/systemone 兼容端点，再动手。
把核验结果写成 `specs/local-backend-probe.md`（新建文件，不要动已有那两个）。

【第二步：选定并部署】
按优先级尝试，选第一个跑通的：
  1) Laya（pip install laya）—— 判别式、约 420M 参数、有中文多语言权重、单次前向 ~8-16ms。
     注意区分 Router（自动语种路由）与 Agent（锁定单分支）两种调用方式的显存差异。
  2) OpenJev（Docker + vLLM）—— 直接暴露兼容 HTTP API，需 NVIDIA GPU（显存要求先核对清楚）。
  3) Laya ONNX —— 无 Apple Silicon / 无 NVIDIA 时的 CPU 路线。
若本机硬件都不满足，明确告诉我，改为只交付「服务端部署文档 + docker-compose」，不要假装跑通。

【第三步：写一个薄适配服务】
把选定后端包成一个 HTTP 服务，暴露 POST /v1/systemone，
入参与出参**严格对齐 P0 里那份权威契约**（尤其是 answers 下每个条目的 type/choice/score/noul/
probabilities/confidence/legend 字段，缺哪个就补哪个，confidence 若后端不给则由概率分布算出并注明算法）。
这个服务就是本地后端本体；以后换模型只换它内部，对外契约不变。

【验收标准】
- 用 P0 那份契约测试打本地服务，三项类型断言全过。
- 同一份 state+questions 分别打 EdgeOne 和本地服务，两边的响应能被同一个解析器解析。
- 展示本地单次调用的实测延迟（p50 / p95，跑 100 次）。
```

---

## P2 · 双跑评测：本地与云端的差值是多少

```
接 P1。现在要回答一个决策问题：本地实现能不能换掉云端？差多少？

【准备评测集】
用 `samples/` 下三个样例的结构，构造 50-100 帧带人工标注的评测数据：
  每帧 = 一份 state + 该帧「正确操作 / 正确播报」的标注。
标注必须由我确认，你不许替我生成标注答案——先给我标注表的空模板和字段说明。

【跑什么】
同一份评测集，分别打 EdgeOne 与本地服务，记录三项：
  1) top-1 准确率（与标注一致的比例）
  2) P95 延迟
  3) 低置信率（confidence 落在最低档的比例）
分题型统计：choice / score / noul 各单独出一份，不要只给总数。
再补一项：**两端 top-1 一致率**（即不依赖标注，直接看两边答案有多像）。

【产出】
`specs/eval-dual-run.md`，含：
- 分项对比表（真实数字，不要估算）
- 中文输入下的表现单独列一行（这是本项目的主战场）
- 一句话结论：按 ADR-0001 的 T2 条件（一致率≥90% 且低置信率差≤10pp），现在能不能切
- 若不能切，指出差在哪些题型、可能原因、下一步要调什么

【重要】
不要在报告里写「效果良好」「基本可用」这类形容词，只给数字和判定。
```

---

## P3 · 阈值迁移：本地实现的置信度三档要重新标定

```
接 P2。本地实现的置信度分布和云端不一样，现有阈值不能照搬，要重标。

【为什么】
Jev 的 confidence 是用 RLCD 训练校准过的；开源复刻的置信度分布形状不同，
直接套用 0.8 / 0.5 会导致「自动执行档」的实际错误率失控。

【怎么做】
1. 用 P2 评测集的真实输出，画出 confidence 的分布（分档直方图，给原始数据不要只给图）。
2. 按「错误代价」而不是「拍脑袋」反推阈值，分三档：
   - 只读/播报类操作：允许低门槛
   - 改变游戏状态 / 消耗道具的操作：高门槛
   - 触发付费等高影响操作：最高门槛，且强制人工确认
3. 给出一个阈值寻优脚本：以「自动执行档的实际错误率 ≤ X%」为约束，
   扫出满足约束的最低阈值。X 由我给，先按 1% 跑一版给我看。
4. 输出 `routing-policy.local.json`，与现有 `routing-policy.json` 结构一致，便于对比。

【硬约束】
- 低置信档的行为必须是「明确提示未识别」，绝不允许静默失败——这是无障碍产品的底线。
- 阈值必须落在配置文件里，不许硬编码。
- 给出切换前后的「自动执行率 / 错误率」对比，让我自己权衡。
```

---

## P4（可选）· 移动端离线兜底

```
接 P3。目标平台是 Android（Harness 是 Kotlin）。

【先评估再动手】
评估两条路，给我对比再决定做哪条：
  A) 服务端部署本地后端，Android 端只换 baseUrl —— 改动最小，但断网仍不可用
  B) Android 端用 ONNX Runtime Mobile 直接跑量化后的决策模型 —— 真离线，但包体积与性能需实测

【若选 A】
只需把 LocalSystemOneClient 的 baseUrl 改成可配置，并加连通性探测与失败回退。

【若选 B】
必须先回答：量化后模型体积、低端机单次推理延迟、内存占用、是否支持中文分支。
这些数字没测出来就不要写代码。

【无论选哪条】
断网路径必须有明确的语音/触觉反馈：「已切换到本地模式」或「离线不可用，请联网」。
静默失败视为验收不通过。
```

---

## 附：一条可以直接粘去问模型的短版探针提示词

用于快速摸清任意一个自称「Jev 兼容」的开源实现到底兼容到什么程度：

```
我要把 Jev（TypeSafe System One）的调用换成自托管实现。先别写代码，
用一个最小脚本实测并回答下面 6 个问题，每个都要贴真实输出：

1. POST /v1/systemone（或它实际暴露的端点）的真实入参是什么？
   choice 的 criteria 收 object 还是 array？
2. 是非题的类型名是 noul 还是 boolean？
3. 返回体完整长什么样？有没有 confidence、legend、probabilities？
4. probabilities 是否归一到 1？
5. 中文 state 下能否正常工作？与英文相比置信度差多少？（同一语义的中英两版对照跑）
6. 单次前向延迟 p50/p95（跑 100 次）与常驻内存/显存是多少？

不要引用文档里的说法，只给我你在本机实测出来的结果。
跑不通就直说跑不通，不要给我推测值。
```
