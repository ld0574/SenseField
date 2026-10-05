# Jev API 规格（管理者逐字核实版）

核实日期：2026-10-02。来源全部为官方文档与实网探测，见文末「来源」。执行者以本文件为准，**不许凭记忆改写契约**。

## 1. Jev 是什么（两句话）

TypeSafe AI 的旗舰「System One」决策模型：**只吃文本 state，不做屏幕/图像/音频识别**；针对一段 state 并行评估一组强类型问题，直接返回带校准概率的类型化判定，**不生成自然语言**。它是三层架构里的 L2 判断层：L1 感知层（无障碍树/OCR 把屏幕变成文本 state）→ L2 Jev → L3 Harness（App 侧授权与执行）。

**铁律：Jev 判断，Harness 授权与执行。** Jev 说「该按确认」不能绕过 App 的白名单和结果校验。**低置信绝不静默**——对视障用户静默等于程序死了，必须出声播报「没看清」。

## 2. 接入渠道（两条已验证 + 一条仅记录）

| 渠道 | Endpoint | model 字段 | Key 获取 | 计费 |
|---|---|---|---|---|
| **腾讯 EdgeOne Makers（默认推荐，国内）** | `https://ai-gateway.edgeone.link/v1/systemone` | `@makers/jev`（省略即默认，传其他值返回 400） | EdgeOne 控制台 → Makers → Models → API Key → Create API Key | 与同账号其他内置模型共享每月免费 Token 额度，以控制台显示为准 |
| **TypeSafe 官方** | `https://api.typesafe.ai/v1/systemone` | `jev-latest`（响应返回具体版本如 `jev-1.13.0`） | console.typesafe.ai（early access） | 输入 $0.042/MTok，输出免费 |
| OpenRouter（仅记录，不做成预设） | 未验证其 SystemOne 协议接法 | `typesafe/jev-1.13`（$0.042/M 输入、输出免费、32K 上下文、2026-09-18 发布） | OpenRouter | 同左 |

EdgeOne 网关限制：**只支持 `/v1/systemone` 原生协议**（无 chat 协议）；**不支持流式**（System One 本来就是一次性结构化评估）。

## 3. 实网探测结果（2026-10-02，管理者亲手跑）

```
POST https://ai-gateway.edgeone.link/v1/systemone（无 key）→
HTTP/1.1 401 Authorization Required
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: OPTIONS,POST,HEAD
Access-Control-Allow-Headers: Content-Type,Authorization,...
{"error":{"message":"Missing API key. Pass it via Authorization: Bearer {MAKERS_AIG_KEY}","type":"auth_missing","code":"auth_missing"}}

OPTIONS 同地址（Origin: null, Access-Control-Request-Method: POST）→ HTTP 204
```

**结论：`Access-Control-Allow-Origin: *`，浏览器里 file:// 双击打开的单文件 HTML 可以直接发真请求。** 试跑台方案成立。

## 4. 请求契约

`POST <endpoint>`，Headers：`Authorization: Bearer <API_KEY>`、`Content-Type: application/json`。三个必填字段：

```json
{
  "state": "字符串，或对象/数组（如序列化后的无障碍树、棋盘矩阵）",
  "model": "@makers/jev",
  "questions": {
    "<问题id，自定义>": {
      "type": "noul | choice | score",
      "instructions": "判断逻辑写在这里。问题 id 不会发给模型！",
      "criteria": "见下，noul 可选，choice/score 必填"
    }
  }
}
```

关键规则：
- **问题 id（map 的键）不会发送给模型、不参与推理**，判断逻辑必须完整写在 `instructions` 里。
- `instructions` 可以是对象，把问题和引用数据分字段存放，用反引号引用 state 里的字段名，如 `` 评估 `potential_matches` ``。
- `criteria`：
  - **noul**：可选 `{ "true": "…1 的含义", "false": "…0 的含义" }`
  - **choice**：必填 `{ "选项名": "rubric 描述", "选项名": null }`（无需描述的选项用 null），最多 255 个选项
  - **score**：必填**有序数组** `["等级0描述","等级1描述",…]`，至少 2 级最多 10 级

## 5. 响应契约

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "<与请求相同的键>": { "...": "按类型见下" }
  },
  "usage": { "input_tokens": 392, "output_tokens": 65 }
}
```

三种答案类型（`answers` 里按问题 id 取）：
- **noul**：`{ "type": "noul", "noul": 0.95 }` —— noul 是「答案为 yes」的概率，0–1。**noul 不返回 confidence**，需要时用 `|2p − 1|`。
- **choice**：`{ "type": "choice", "choice": "technical", "probabilities": {"technical":0.85,"billing":0.15,"sales":0}, "confidence": 0.78 }` —— probabilities 所有选项之和为 1。
- **score**：`{ "type": "score", "score": 1, "legend": {"0":"Calm","1":"Frustrated","2":"Very angry"}, "probabilities": {"0":0,"1":1,"2":0}, "confidence": 1 }` —— score 是概率加权数值，**可以落在两级之间**（如 1.05）。

## 6. confidence 公式与三档策略（官方 Confidence 文档）

- **choice**（n 个选项，p_max 最高概率）：`confidence = (p_max − 1/n) / (1 − 1/n)`。只看最高概率：(0.6,0.3,0.1) 与 (0.6,0.2,0.2) 都是 0.4。
- **score**（n 级，m 为最可能级）：`confidence = max(0, 1 − Σ p_i·|i−m| / MAD_unif)`，`MAD_unif = (1/n)·Σ|i−(n−1)/2|`。相邻等级概率对置信度的削弱小于远处等级。
- 替代度量（可自算）：p_max 本身；p_max/p_second（头两名差距）。

**官方三档策略**：高置信→自动执行；中置信→谨慎推进（请求确认/标记待审）；低置信→不执行，转人工/请求澄清/回退。**「A confidence threshold is not one number」**——同一系统内不同操作的阈值应随出错后果分别设定；从保守阈值起步，用自己的数据测试后调整。

## 7. 错误处理

| 状态码 | 含义 | 处理 |
|---|---|---|
| 401 | key 缺失/无效 | 原样展示给用户 |
| 422 | 请求体校验失败 | 响应体指明字段，原样展示 |
| 429 | 限流 | 指数退避重试 |
| 529 | 服务过载 | 指数退避重试 |

错误体格式：`{"error":{"message":"…","type":"…","code":"…"}}`。

## 8. 语言（中文）注意

官方主训练语言是英文，多来源一致转述：CJK「handled but not equally well」。博客/文档页未给出更细的官方中文基准。**对本产品的影响：criteria 用英文更稳；state 保留真实中文（无障碍树原文）；必须用真实中文 state 建评测集实测，靠 confidence 兜底，阈值从实测分布反推，别拍脑袋定 0.8。**

## 9. 性能

官方博客：响应 70–500ms；一次请求内多个问题**并行独立**评估，加问题几乎不增加延迟。输入 $0.042/MTok、输出免费 → 一帧一组原子问题（5 问左右）成本可忽略。

## 10. 无障碍游戏默认五问（工具内置模板）

| id | type | criteria 默认值（英文，工具内可编辑） |
|---|---|---|
| `next_action` | choice | `{"announce_match":"Report the position of a possible match","scan_board":"Read the board row by row","read_menu":"Read menu or dialog items","wait":"Nothing changed, keep waiting"}` |
| `danger_level` | score | `["Safe","Caution: timer or obstacle approaching","Danger: about to fail","Emergency: act now"]` |
| `should_interrupt` | noul | `{"true":"Must speak now even if it interrupts current speech","false":"Can wait until current announcement finishes"}` |
| `announce_rank` | score | `["Do not announce","Optional","Normal info","Important","Must announce immediately"]` |
| `scene_changed` | noul | `{"true":"Scene differs clearly from previous frame","false":"Same scene continues"}` |

## 来源（核实日期 2026-10-02）

- 官方博客（模型定位、定价、70–500ms）：https://typesafe.ai/blog/introducing-system-one-models-and-jev
- API reference（契约全文）：https://docs.typesafe.ai/api
- Introduction（并行机制、原子问题建议）：https://docs.typesafe.ai/introduction
- Confidence（公式、三档策略、代码示例）：https://docs.typesafe.ai/confidence
- EdgeOne Makers 使用 Jev（endpoint、key 路径、curl 示例、限制）：https://pages.edgeone.ai/document/using-the-jev-model
- 腾讯云文档：https://cloud.tencent.com/document/product/1552/138731
- OpenRouter 模型页：https://openrouter.ai/typesafe/jev-1.13
- 实网探测（401/CORS/OPTIONS 204）：管理者 2026-10-02 curl 实测，原文见 §3
- CJK 多来源转述：https://blog.csdn.net/2401_82779864/article/details/166239882 、https://zhuermu.com/en/blog/jev-system-one-hands-on
