'use strict';

/* 本地/自托管后端（ADR-0001 D2/D3：Laya / OpenJev 自托管兜底）。
 * 铁律 2：本文件不写死任何可公网解析的 endpoint；baseUrl 由构造参数注入（P1 部署后填充）。
 * model 字符串同样只允许出现在本文件内部。 */

const { postSystemOne } = require('../protocol');

const DEFAULT_LOCAL_MODEL = 'local-system-one';

/**
 * 用法：new LocalSystemOneClient({ baseUrl, apiKey?, model?, fetchImpl?, timeoutMs? })
 * baseUrl 例：http://127.0.0.1:8080 —— 实际请求打 {baseUrl}/v1/systemone。
 * 本地服务尚不存在时（P1 交付薄适配服务后）才可真实调用。
 */
class LocalSystemOneClient {
  constructor(opts) {
    if (!opts || !opts.baseUrl) {
      throw new Error('LocalSystemOneClient 需要 opts.baseUrl（P1 部署本地服务后由注入点传入）');
    }
    this.opts = opts;
  }
  judge(state, questions) {
    return postSystemOne({
      endpoint: String(this.opts.baseUrl).replace(/\/+$/, '') + '/v1/systemone',
      model: this.opts.model || DEFAULT_LOCAL_MODEL,
      apiKey: this.opts.apiKey || 'local',
      fetchImpl: this.opts.fetchImpl,
      timeoutMs: this.opts.timeoutMs
    }, state, questions);
  }
}

module.exports = { LocalSystemOneClient, DEFAULT_LOCAL_MODEL };
