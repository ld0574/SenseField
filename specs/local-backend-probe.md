# P1 契约探针报告：本地后端（Laya）实测

探针日期：2026-10-02。原则：只给实测值，不推测；跑不通的直说。

## 0. 开源项目身份核验（P1 第一步）

「OpenJev」同名混乱被证实——公开资料中至少**四个不同项目**：
| 项目 | 形态 | 本机可行性 |
|---|---|---|
| razorback16/openjev | vLLM 决策服务 | ✗ 需 NVIDIA GPU（本机无） |
| GPT-AGI/OpenJev | 决策引擎演示 | 未采用（非 HTTP 服务形态） |
| githubnext/localjev | TS 包装生成式模型（fusionGemma） | 未采用（生成式+更重） |
| **receptron/laya（选定）** | 判别式 ModernBERT 421M，Node 运行器 + ONNX Runtime，代码 MIT/权重 Apache 2.0 | ✓ 纯 CPU，npm `@receptron/laya@0.1.2` |

权重：HF `receptron/laya-onnx`（扁平布局，**无子目录**；README 提到的 multilingual 变体位置未核验到，留待后续）。
本机网络 HF 直连不通，已把包内下载域名补丁为 `hf-mirror.com`（见 adapter/README.md）。

## 1. 六问实测结果（node probe.js 原始输出）

**问 1（入参）/ 问 3（返回体形状）**：choice（criteria=object）+ score（criteria=array）+ noul 三问一次请求全部接受；
返回字段：`type` / `choice` / `score` / `noul` / `probabilities` / `legend`（score）/ **`confidence`（原生返回，README 未写）** / `rl_agent.act_probability`（契约外，适配层已剥离）。

**问 2（noul 还是 boolean）**：`type:"boolean"` 被拒绝（报 `Cannot convert undefined to a BigInt`）——**类型名是 `noul`**。

**追加（choice criteria 形态）**：object 与 array **都接受**（array 实测返回 `{"alpha":0.4741,"beta":0.5259}`）。

**问 4（归一）**：department 和=1.000000，urgency 和=0.999900 → 归一（浮点容差内）。

**问 5（中文对照，同语义）**：
```
英文 → department=billing (conf=0.649) | churn=0.936 (surety=0.871)
中文 → department=billing (conf=0.767) | churn=0.918 (surety=0.837)
```
默认检查点下中文 state 可用且置信度未崩。

**问 6（延迟/内存）**：单问 ×100：**p50=203ms，p95=255ms，min=137ms，max=287ms**；常驻 RSS≈1.57GB。

## 2. 薄适配服务 + 三后端契约测试

`backend/adapter/server.js`：node:http 暴露 `POST /v1/systemone`（+`GET /health`），
响应按权威契约归一（confidence 优先用 Laya 原生值，缺失才按官方公式补算并注明）。

```
[PASS] openrouter · HTTP 200 · 2937ms · model=typesafe/jev-1.13-20260917
[PASS] local · HTTP 200 · 1364ms · model=laya--local
[SKIP] edgeone · 未提供凭据
SUMMARY: pass=2 skip=1 fail=0
```

## 3. 多场景双跑（本地 vs 云端，5 问 × 3 场景）——重要负面结果

| 场景 | top-1 一致 | 本地 surety 区间 | 延迟（本地/云端） |
|---|---|---|---|
| 消消乐 8×8 | 1/5 | 0.01–0.11 | 6366ms / 1183ms |
| 中文无障碍树 | 2/5 | 0.02–0.36 | 7916ms / 1958ms |
| 象棋 9×10 | 0/5 | 0.01–0.38 | 7730ms / 1549ms |

**双跑 top-1 一致率 = 3/15 = 20%**；本地单请求（5 问）延迟 6.4–7.9s。

**判定（按 ADR-0001 触发条件口径）**：
- T2（一致率 ≥90% 且低置信率差 ≤10pp 才可切本地）：**远未达成，生产判定留在云端**。
- 本地默认检查点对本领域输入近似输出均匀分布（surety≈0）→ Harness 按铁律全部落入
  「没看清」分支：**不会执行错误动作（安全），但也提供不了信息（无用）**。
- 结论：本地后端当前的定位是**断网可用性兜底 + 客户端联调**，不是判定质量兜底；
  multilingual 变体、领域微调、或按 P2 重新标定阈值是后续方向。
- 本地 5 问延迟 ~7s 仅适合回合制兜底；单问 203ms 可支撑更高频场景。

## 4. 移交物

- `backend/adapter/`：server.js（薄适配）、probe.js（六问探针）、dual-run.js（双跑）、load-laya.js、README（含 hf-mirror 补丁说明）
- 契约测试：`backend/src/contract-test.js` local 真实 PASS（2026-10-02 实跑记录）
