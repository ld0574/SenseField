# adapter · 本地薄适配服务（P1 第三步）

把 **Laya**（开源判别式 System One 模型，Apache 2.0 权重）包装成与 Jev 兼容的
`POST /v1/systemone` 端点，供 `backend/src/clients/local-system-one-client.js`
与 SenseField Android 端的 `LocalJevClient` 作为本地兜底后端。

## 为什么是 Laya

P1 探针核验结论（2026-10-02，详见 `specs/local-backend-probe.md`）：
- 开源复刻生态存在同名混乱：`razorback16/openjev`（vLLM 需 N 卡）、`GPT-AGI/OpenJev`、
  `githubnext/localjev`、`openjev-sglang` 是**至少四个不同项目**，不能按名字猜。
- 本机无 NVIDIA GPU → vLLM 系出局；Laya（`@receptron/laya`，Node 运行器 +
  ONNX Runtime，判别式 ~421M 参数）是唯一纯 CPU 可行的候选，且有 multilingual 检查点。

## 运行

```bash
npm install          # @receptron/laya + onnxruntime-node
node load-laya.js    # 首次运行下载约 1.7GB ONNX 权重到 ~/.cache/receptron-laya
node server.js       # POST http://127.0.0.1:8080/v1/systemone（GET /health 探活）
node probe.js        # P1 六问探针（真实实测输出）
```

环境变量：`PORT`（默认 8080）、`LAYA_SUBFOLDER`（默认 english）。

## 如实标注的差异（不假装与云端 Jev 完全一致）

1. Laya 不返回 `confidence` / `legend` → 适配层按官方公式补算并在响应
   `engineNote` 中注明：choice `(pmax−1/n)/(1−1/n)`；score 为 MAD 公式。
   因此**本地实现的阈值不能照搬云端标定值**（ADR-0001 §5 与 BLOCKED.md 第 4 条）。
2. `usage.output_tokens` 恒为 0（判别式无文本生成）。
3. state 超 512 token 会被 Laya 截断（模型输入上限）。

## 本机已知补丁（交付时须保留说明）

`node_modules/@receptron/laya/dist/download.js` 的权重下载 URL 被本机 sed 从
`https://huggingface.co` 换成 `https://hf-mirror.com`（HF 直连在本机网络不可达）。
重装依赖后该补丁会丢失，需重打；上游若支持镜像环境变量即可移除。
