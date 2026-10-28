'use strict';

/* 共享协议层：请求构造、真发送、响应校验、统一错误模型。
 * 铁律 2：本文件不含任何 endpoint / model 字面量，endpoint 与 model 只允许出现在
 * src/clients/ 下各 Client 文件内部。 */

const DEFAULT_TIMEOUT_MS = 30000;

/**
 * 统一错误模型。必须区分的情况（P0 验收要求）：
 *   network       —— 网络失败（HTTP 0 / fetch failed / 超时）→ 断网分支
 *   auth_missing  —— 401 + code:auth_missing（未带 key）
 *   auth_failed   —— 401 + code:auth_failed（key 错误）
 *   auth          —— 其他 401（渠道自有文案，如 OpenRouter 的 cookie 文案）
 *   validation    —— 400 / 422 请求体不合规（EdgeOne 文档用 422，OpenRouter 实测用 400）
 *   rate/overload —— 429 / 529，生产端应做指数退避重试
 *   http          —— 其他非 2xx
 * bodyText 永远保留网关原文，不许美化改写。
 */
class UnifiedJevError extends Error {
  constructor(kind, status, bodyText, detail) {
    super('[' + kind + '] HTTP ' + status + (detail ? ' ' + detail : ''));
    this.name = 'UnifiedJevError';
    this.kind = kind;
    this.status = status;
    this.bodyText = bodyText || '';
  }
}

function parseErrorKind(status, bodyText) {
  let code = null;
  try {
    const j = JSON.parse(bodyText);
    code = (j && j.error && j.error.code) || null;
  } catch (e) { /* 非 JSON 原文也算 */ }
  if (status === 401) {
    if (code === 'auth_missing') return 'auth_missing';
    if (code === 'auth_failed') return 'auth_failed';
    return 'auth';
  }
  if (status === 400 || status === 422) return 'validation';
  if (status === 429) return 'rate';
  if (status === 529) return 'overload';
  return 'http';
}

/** SystemOne 请求体形状：{ model, state, questions } —— state 可为 string|object|array */
function buildRequestBody(model, state, questions) {
  return { model: model, state: state, questions: questions };
}

/**
 * 真发送。cfg: { endpoint, apiKey, model, fetchImpl?, timeoutMs? }
 * 返回 { model, answers, usage, status, elapsedMs, raw, bodyText }；失败抛 UnifiedJevError。
 * 前端不重试：429/529 的指数退避重试在 Harness（Kotlin）端做。
 */
async function postSystemOne(cfg, state, questions) {
  const fetchImpl = cfg.fetchImpl || globalThis.fetch;
  if (typeof fetchImpl !== 'function') throw new UnifiedJevError('network', 0, '此环境没有可用的 fetch 实现');
  if (!cfg.endpoint) throw new UnifiedJevError('network', 0, '缺少 endpoint（应由 Client 注入）');
  if (!cfg.model) throw new UnifiedJevError('network', 0, '缺少 model（应由 Client 注入）');
  if (!cfg.apiKey) throw new UnifiedJevError('auth_missing', 0, '缺少 apiKey：构造 Client 时必须传入');
  const body = buildRequestBody(cfg.model, state, questions);
  const t0 = Date.now();
  let timer = null;
  try {
    const init = {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + cfg.apiKey },
      body: JSON.stringify(body)
    };
    if (typeof AbortController !== 'undefined') {
      const ac = new AbortController();
      init.signal = ac.signal;
      timer = setTimeout(function () { ac.abort(); }, cfg.timeoutMs || DEFAULT_TIMEOUT_MS);
    }
    const res = await fetchImpl(cfg.endpoint, init);
    const bodyText = await res.text();
    if (!res.ok) throw new UnifiedJevError(parseErrorKind(res.status, bodyText), res.status, bodyText);
    let parsed;
    try { parsed = JSON.parse(bodyText); } catch (e) {
      throw new UnifiedJevError('http', res.status, bodyText, '响应不是合法 JSON');
    }
    return {
      model: parsed.model, answers: parsed.answers, usage: parsed.usage,
      status: res.status, elapsedMs: Date.now() - t0, raw: parsed, bodyText: bodyText
    };
  } catch (err) {
    if (err instanceof UnifiedJevError) throw err;
    if (err && err.name === 'AbortError') throw new UnifiedJevError('network', 0, '请求超时被中止（timeout ' + (cfg.timeoutMs || DEFAULT_TIMEOUT_MS) + 'ms）');
    throw new UnifiedJevError('network', 0, (err && err.message) ? err.message : String(err));
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/**
 * 契约校验：answers 能否被判定层安全消费。
 * 返回问题字符串数组（空数组 = 通过）。规则：
 *   choice —— value 落在声明选项集内；probabilities 归一（容差 0.02）；confidence ∈ [0,1]
 *   score  —— score 落在 0..级数-1（可落在两档之间）；confidence ∈ [0,1]
 *   noul   —— noul ∈ [0,1]（noul 无 confidence 字段，把握度用 |2p-1| 自行计算）
 */
function validateAnswers(questions, answers) {
  const problems = [];
  if (!answers || typeof answers !== 'object') return ['answers 缺失或不是对象'];
  Object.keys(questions).forEach(function (qid) {
    const q = questions[qid], a = answers[qid];
    if (!a) { problems.push(qid + ': 缺答案'); return; }
    if (a.type !== q.type) { problems.push(qid + ': type=' + a.type + ' 期望 ' + q.type); return; }
    if (a.type === 'choice') {
      if (!(q.criteria && Object.prototype.hasOwnProperty.call(q.criteria, a.choice))) problems.push(qid + ': choice「' + a.choice + '」不在声明选项集内');
      const sum = Object.values(a.probabilities || {}).reduce(function (x, y) { return x + y; }, 0);
      if (Math.abs(sum - 1) > 0.02) problems.push(qid + ': probabilities 和=' + sum + ' 不归一');
      if (typeof a.confidence !== 'number' || a.confidence < 0 || a.confidence > 1) problems.push(qid + ': confidence=' + a.confidence + ' 越界');
    } else if (a.type === 'score') {
      const max = (q.criteria || []).length - 1;
      if (typeof a.score !== 'number' || a.score < 0 || a.score > max) problems.push(qid + ': score=' + a.score + ' 越界 0..' + max);
      if (typeof a.confidence !== 'number' || a.confidence < 0 || a.confidence > 1) problems.push(qid + ': confidence=' + a.confidence + ' 越界');
    } else if (a.type === 'noul') {
      if (typeof a.noul !== 'number' || a.noul < 0 || a.noul > 1) problems.push(qid + ': noul=' + a.noul + ' 越界');
    }
  });
  return problems;
}

module.exports = { UnifiedJevError, buildRequestBody, postSystemOne, validateAnswers, DEFAULT_TIMEOUT_MS };
