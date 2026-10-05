'use strict';

/* 薄适配服务（P1 第三步）：把 Laya（本地判别式模型）包装成与 Jev 兼容的
 * POST /v1/systemone 端点，入参与出参严格对齐 specs/jev-api-spec.md 的权威契约。
 *
 * 已知差异与处理（如实标注，不假装）：
 * - Laya 不返回 confidence / legend → 由概率分布按官方公式补算：
 *     choice: (p_max − 1/n) / (1 − 1/n)
 *     score : max(0, 1 − Σ p_i·|i−m| / MAD_unif)，MAD_unif = (1/n)·Σ|i−(n−1)/2|
 *   响应中附 engineNote 字段说明补算口径。
 * - Laya 的 usage 只有 input_tokens → output_tokens 记 0（判别式无文本生成）。
 * - state 超过 512 token 会被截断（Laya 限制），适配层原样透传并保留该限制说明。
 *
 * 启动：node server.js（环境变量：PORT=8080, LAYA_SUBFOLDER=english|multilingual）
 */

const http = require('http');

const PORT = parseInt(process.env.PORT || '8080', 10);
/* 注意：receptron/laya-onnx 仓库是扁平布局（无 english/ 子目录），
 * 子目录默认必须为空串；LAYA_SUBFOLDER 仅在切换到带子目录的变体仓库时才设置。 */
const SUBFOLDER = process.env.LAYA_SUBFOLDER || '';

let laya = null;
let engineId = 'laya-unloaded';

function choiceConfidence(probabilities) {
  const values = Object.values(probabilities);
  const n = values.length;
  if (n === 0) return 0;
  const pMax = Math.max(...values);
  if (n === 1) return 1;
  return Math.max(0, (pMax - 1 / n) / (1 - 1 / n));
}

function scoreConfidence(probabilities) {
  const keys = Object.keys(probabilities).sort((a, b) => parseInt(a, 10) - parseInt(b, 10));
  const values = keys.map(k => probabilities[k]);
  const n = values.length;
  if (n === 0) return 0;
  let m = 0;
  values.forEach((p, i) => { if (p > values[m]) m = i; });
  const spread = values.reduce((acc, p, i) => acc + p * Math.abs(i - m), 0);
  const madUnif = values.reduce((acc, _p, i) => acc + Math.abs(i - (n - 1) / 2), 0) / n;
  if (madUnif === 0) return 1;
  return Math.max(0, 1 - spread / madUnif);
}

function normalizeAnswer(qid, question, raw) {
  if (!raw) throw new Error('答案缺失: ' + qid);
  if (question.type === 'choice') {
    const probabilities = raw.probabilities && typeof raw.probabilities === 'object'
      ? raw.probabilities
      : (raw.distribution || null);
    if (!probabilities) throw new Error('choice 答案缺 probabilities: ' + qid);
    const choice = raw.choice !== undefined ? raw.choice
      : Object.keys(probabilities).reduce((a, b) => (probabilities[a] >= probabilities[b] ? a : b));
    return {
      type: 'choice',
      choice: String(choice),
      probabilities: probabilities,
      /* Laya 0.1.2 实测已原生返回 confidence；缺失时才按官方公式补算 */
      confidence: typeof raw.confidence === 'number' ? raw.confidence : choiceConfidence(probabilities)
    };
  }
  if (question.type === 'score') {
    const probabilities = raw.probabilities && typeof raw.probabilities === 'object'
      ? raw.probabilities
      : (raw.distribution || null);
    if (!probabilities) throw new Error('score 答案缺 probabilities: ' + qid);
    const levels = Array.isArray(question.criteria) ? question.criteria : [];
    const legend = typeof raw.legend === 'object' && raw.legend ? raw.legend : {};
    levels.forEach((desc, i) => { if (legend[String(i)] === undefined) legend[String(i)] = desc; });
    return {
      type: 'score',
      score: typeof raw.score === 'number' ? raw.score : parseInt(Object.keys(probabilities).reduce((a, b) => (probabilities[a] >= probabilities[b] ? a : b)), 10),
      legend: legend,
      probabilities: probabilities,
      confidence: typeof raw.confidence === 'number' ? raw.confidence : scoreConfidence(probabilities)
    };
  }
  /* noul */
  const p = typeof raw.noul === 'number' ? raw.noul
    : (typeof raw.p === 'number' ? raw.p : null);
  if (p === null) throw new Error('noul 答案缺 noul 字段: ' + qid);
  return { type: 'noul', noul: p };
}

function validateRequest(body) {
  if (!body || typeof body !== 'object') return '请求体必须是 JSON 对象';
  if (typeof body.state !== 'string' && typeof body.state !== 'object') return 'state 必须是字符串或对象';
  if (!body.questions || typeof body.questions !== 'object' || Array.isArray(body.questions)) return 'questions 必须是对象（map）';
  for (const qid of Object.keys(body.questions)) {
    const q = body.questions[qid];
    if (!q || typeof q !== 'object') return '问题 ' + qid + ' 格式错误';
    if (!['choice', 'score', 'noul'].includes(q.type)) return '问题 ' + qid + ' type 必须是 choice|score|noul，实际: ' + q.type;
    if (typeof q.instructions !== 'string' || !q.instructions) return '问题 ' + qid + ' 缺 instructions';
    if ((q.type === 'choice' || q.type === 'score') && !q.criteria) return '问题 ' + qid + ' 的 ' + q.type + ' 必填 criteria';
    if (q.type === 'score' && (!Array.isArray(q.criteria) || q.criteria.length < 2)) return '问题 ' + qid + ' score criteria 须为至少 2 级的数组';
  }
  return null;
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, engine: engineId, subfolder: SUBFOLDER, loaded: laya !== null }));
    return;
  }
  if (req.method !== 'POST' || req.url !== '/v1/systemone') {
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: { message: '仅支持 POST /v1/systemone 与 GET /health', code: 'not_found' } }));
    return;
  }
  let raw = '';
  req.on('data', c => { raw += c; if (raw.length > 5 * 1024 * 1024) req.destroy(); });
  req.on('end', async () => {
    const started = Date.now();
    let body;
    try { body = JSON.parse(raw); } catch (e) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: '请求体不是合法 JSON: ' + e.message, code: 'validation' } }));
      return;
    }
    const problem = validateRequest(body);
    if (problem) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: problem, code: 'validation' } }));
      return;
    }
    if (!laya) {
      res.writeHead(529, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: '模型尚未加载完成', code: 'overloaded' } }));
      return;
    }
    try {
      const result = await laya.systemOne(body.state, body.questions);
      const answers = {};
      for (const qid of Object.keys(body.questions)) {
        answers[qid] = normalizeAnswer(qid, body.questions[qid], result.answers ? result.answers[qid] : null);
      }
      const out = {
        model: engineId,
        answers: answers,
        usage: {
          input_tokens: (result.usage && result.usage.input_tokens) || 0,
          output_tokens: 0
        },
        engineNote: '本地 Laya 推理；confidence 优先用 Laya 原生校准值（实测 0.1.2 已返回），缺失时按官方公式补算（choice=(pmax-1/n)/(1-1/n)，score=MAD 公式）；契约外字段（rl_agent）已剥离；usage.output_tokens 恒为 0（判别式无文本生成）',
        elapsedMs: Date.now() - started
      };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(out));
    } catch (err) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: '本地推理失败: ' + (err && err.message ? err.message : String(err)), code: 'local_inference_error' } }));
    }
  });
});

(async () => {
  const { Laya } = require('@receptron/laya');
  console.log('[adapter] 正在加载 Laya 模型（subfolder=' + (SUBFOLDER || '（扁平布局，无子目录）') + '）…');
  laya = await Laya.load(SUBFOLDER ? { subfolder: SUBFOLDER } : undefined);
  engineId = 'laya-' + SUBFOLDER + '-local';
  console.log('[adapter] 模型加载完成：' + engineId);
  server.listen(PORT, () => {
    console.log('[adapter] POST http://127.0.0.1:' + PORT + '/v1/systemone 就绪（GET /health 探活）');
  });
})().catch(err => {
  console.error('[adapter] 启动失败：', err && err.message ? err.message : err);
  process.exit(1);
});
