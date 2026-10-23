# backend · 可替换后端（ADR-0001 D2 落地）

把 L2 判断层的 Jev 调用收敛成单一接口 `JudgmentClient`，其下挂三个可互换实现。
**零依赖**（只用 Node 内置 fetch），Node ≥ 18。

## 结构

```
JudgmentClient（接口约定：judge(state, questions) -> JudgmentResponse）
├── EdgeOneJevClient      # 现网渠道，默认（ADR-0001 D1：验证期用但不绑定）
├── OpenRouterJevClient   # 备选（/api/v1/systemone 路由已实测存在）
└── LocalSystemOneClient  # Laya / OpenJev 自托管兜底（baseUrl 注入，P1 部署后可真调）
```

- `src/protocol.js` — 共享契约层：请求构造 / 真发送 / 响应校验 / 统一错误模型。**不含任何 endpoint / model 字面量。**
- `src/clients/*` — 三个实现。**endpoint 与 model 字符串只允许出现在这三个文件里**（铁律 2；本仓 grep 验收按 BLOCKED.md 第 6 条默认：限定 `backend/src` 源码目录）。
- `src/factory.js` — 后端选择的唯一注入点。
- `src/contract-test.js` — P0 契约测试：固定 state＋choice/score/noul 三问，三后端逐个断言。

## 统一错误模型（业务侧按 kind 分流）

| kind | 含义 | Harness 建议动作 |
|---|---|---|
| `network` | 断网 / 超时 / 无 fetch | 切本地兜底或出声提示「离线」 |
| `auth_missing` | 401＋未带 key | 提示配置 key |
| `auth_failed` | 401＋key 错误 | 提示重配 key |
| `auth` | 其他 401（渠道自有文案） | 原文上屏 |
| `validation` | 400 / 422 请求体不合规 | 原文上屏，查 questions 构造 |
| `rate` / `overload` | 429 / 529 | 指数退避重试（Kotlin 端做） |
| `http` | 其他非 2xx | 原文上屏 |

所有错误的 `bodyText` 一律保留网关原文，不许美化改写。

## 用法

```js
const { createJudgmentClient } = require('./src/factory');

const client = createJudgmentClient('openrouter', { apiKey: process.env.OPENROUTER_API_KEY });
const r = await client.judge(state, questions);   // state: string|object|array
// r.answers.next_action = { type:'choice', choice, probabilities, confidence }
```

## 契约测试

```bash
# 三后端按环境变量决定真跑或 SKIP（网络不可达 = SKIP 而非 FAIL）
EDGEONE_API_KEY=…    node src/contract-test.js   # 有 key 才真打
OPENROUTER_API_KEY=… node src/contract-test.js
LOCAL_BACKEND_URL=http://127.0.0.1:8080 node src/contract-test.js
```

## 语言决策（P0 的「Kotlin 优先」按其兜底规则处理）

P0 原文：「Kotlin 优先，若项目主语言不同则跟随主语言」。本仓当前主语言是 JS（创作工具），
且契约测试必须可运行——故本层用 Node/JS 落地。Kotlin 侧的 sealed class 三态骨架已在
`无障碍判定创作工具.html` ④导出，P4（Android 离线兜底）接入工程时以 `JevAnswer`/`JevResponse`
同名对齐，契约测试逻辑移植为 JVM 测试。
