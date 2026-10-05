'use strict';

/* OpenRouter 渠道（ADR-0001 D2：备选渠道）。
 * 铁律 2：endpoint 与 model 字符串只允许出现在本文件内部。
 * 注：/api/v1/systemone 路由已实测存在（404 阴性对照，见 BLOCKED.md 第 6 条）。 */

const { postSystemOne } = require('../protocol');

const ENDPOINT = 'https://openrouter.ai/api/v1/systemone';
const MODEL = 'typesafe/jev-1.13';

/**
 * 用法：new OpenRouterJevClient({ apiKey, fetchImpl?, timeoutMs? })
 * 响应实测会多带 cost 字段（美元计价），usage 原样透传。
 */
class OpenRouterJevClient {
  constructor(opts) {
    this.opts = opts || {};
  }
  /** judge(state, questions) -> Promise<{model, answers, usage, status, elapsedMs, raw, bodyText}> */
  judge(state, questions) {
    return postSystemOne({
      endpoint: ENDPOINT,
      model: MODEL,
      apiKey: this.opts.apiKey,
      fetchImpl: this.opts.fetchImpl,
      timeoutMs: this.opts.timeoutMs
    }, state, questions);
  }
}

module.exports = { OpenRouterJevClient, ENDPOINT, MODEL };
