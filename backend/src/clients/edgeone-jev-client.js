'use strict';

/* EdgeOne Makers 渠道（ADR-0001 D1/D2：现网默认渠道）。
 * 铁律 2：endpoint 与 model 字符串只允许出现在本文件内部。 */

const { postSystemOne } = require('../protocol');

const ENDPOINT = 'https://ai-gateway.edgeone.link/v1/systemone';
const MODEL = '@makers/jev';

/**
 * 用法：new EdgeOneJevClient({ apiKey, fetchImpl?, timeoutMs? })
 * key 获取：EdgeOne 控制台 → Makers → Models → API Key → Create API Key
 */
class EdgeOneJevClient {
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

module.exports = { EdgeOneJevClient, ENDPOINT, MODEL };
