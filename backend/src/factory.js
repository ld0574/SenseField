'use strict';

/* 后端选择的唯一注入点（P0 验收第 4 条）。
 * 业务代码与 Harness 只允许通过本工厂拿 Client，不得散落 endpoint / model 字面量（铁律 2）。 */

const { EdgeOneJevClient } = require('./clients/edgeone-jev-client');
const { OpenRouterJevClient } = require('./clients/openrouter-jev-client');
const { LocalSystemOneClient } = require('./clients/local-system-one-client');

/**
 * createJudgmentClient('edgeone' | 'openrouter' | 'local', opts)
 *   edgeone    —— { apiKey }                       key 来自 EdgeOne 控制台
 *   openrouter —— { apiKey }                       key 来自 OpenRouter
 *   local      —— { baseUrl, apiKey?, model? }     P1 部署薄适配服务后注入
 */
function createJudgmentClient(kind, opts) {
  switch (kind) {
    case 'edgeone': return new EdgeOneJevClient(opts);
    case 'openrouter': return new OpenRouterJevClient(opts);
    case 'local': return new LocalSystemOneClient(opts);
    default: throw new Error('未知后端类型: ' + kind + '（可选 edgeone | openrouter | local）');
  }
}

module.exports = { createJudgmentClient };
