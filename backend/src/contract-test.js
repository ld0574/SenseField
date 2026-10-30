'use strict';

/* P0 契约测试：固定 state + 三个问题（choice/score/noul 各一），对三个实现分别断言：
 *   1) 响应能解析成 JudgmentResponse
 *   2) choice 落在声明的选项集内
 *   3) score 落在 0..levelCount-1
 *   4) noul 落在 0..1
 * 网络不可达 / 未提供 key 或地址 → SKIP（不算失败）；断言不过 → FAIL（退出码 1）。
 *
 * 环境变量：
 *   EDGEONE_API_KEY      —— 有则真打 EdgeOne，否则 SKIP
 *   OPENROUTER_API_KEY   —— 有则真打 OpenRouter，否则 SKIP
 *   LOCAL_BACKEND_URL    —— 有则真打本地服务，否则 SKIP
 */

const { createJudgmentClient } = require('./factory');
const { UnifiedJevError, validateAnswers } = require('./protocol');

/* —— 固定 state + 三个问题（契约测试专用，与业务 state 无关）—— */
const STATE = {
  game: 'match3',
  scene: 'playing',
  moves_left: 5,
  board_hint: 'two potential matches near column 3, no special items active'
};

const QUESTIONS = {
  next_action: {
    type: 'choice',
    instructions: 'What should the assistant do next for the visually impaired player?',
    criteria: {
      announce_match: 'Report the position of a possible match',
      scan_board: 'Read the board row by row',
      wait: 'Nothing changed, keep waiting'
    }
  },
  danger_level: {
    type: 'score',
    instructions: 'How dangerous is the current situation?',
    criteria: ['Safe', 'Caution: timer approaching', 'Danger: about to fail']
  },
  should_interrupt: {
    type: 'noul',
    instructions: 'Must the assistant speak immediately, interrupting current speech?',
    criteria: { 'true': 'Speak now', 'false': 'Can wait until current announcement finishes' }
  }
};

function line(kind, name, msg) { console.log('[' + kind + '] ' + name + ' · ' + msg); }

async function runOne(name, clientFactory) {
  let client;
  try {
    client = clientFactory();
  } catch (e) {
    line('SKIP', name, e.message);
    return { pass: 0, skip: 1, fail: 0 };
  }
  if (client === null) {
    line('SKIP', name, '未提供凭据/地址（环境变量缺失）');
    return { pass: 0, skip: 1, fail: 0 };
  }
  try {
    const r = await client.judge(STATE, QUESTIONS);
    const problems = validateAnswers(QUESTIONS, r.answers);
    if (problems.length) {
      line('FAIL', name, '契约断言不过：' + problems.join('；'));
      return { pass: 0, skip: 0, fail: 1 };
    }
    line('PASS', name, 'HTTP ' + r.status + ' · ' + r.elapsedMs + 'ms · model=' + r.model +
      ' · usage=' + JSON.stringify(r.usage));
    Object.keys(QUESTIONS).forEach(function (qid) {
      const a = r.answers[qid];
      let v;
      if (a.type === 'choice') v = 'choice=' + a.choice + ' conf=' + a.confidence;
      else if (a.type === 'score') v = 'score=' + a.score + ' conf=' + a.confidence;
      else v = 'noul=' + a.noul + ' (|2p-1|=' + Math.abs(2 * a.noul - 1).toFixed(3) + ')';
      console.log('       ' + qid + ' [' + a.type + '] ' + v);
    });
    return { pass: 1, skip: 0, fail: 0 };
  } catch (err) {
    if (err instanceof UnifiedJevError && err.kind === 'network') {
      line('SKIP', name, '网络不可达（' + err.message + '）——按 P0 规则 skip 而非 fail');
      return { pass: 0, skip: 1, fail: 0 };
    }
    line('FAIL', name, err.message + '\n       原文: ' + (err.bodyText || '').slice(0, 400));
    return { pass: 0, skip: 0, fail: 1 };
  }
}

(async function main() {
  console.log('P0 契约测试 · 固定 state（match3 摘要）+ choice/score/noul 三问 · 三后端逐个跑');
  let pass = 0, skip = 0, fail = 0;

  let r = await runOne('edgeone', function () {
    if (!process.env.EDGEONE_API_KEY) return null;
    return createJudgmentClient('edgeone', { apiKey: process.env.EDGEONE_API_KEY });
  }); pass += r.pass; skip += r.skip; fail += r.fail;

  r = await runOne('openrouter', function () {
    if (!process.env.OPENROUTER_API_KEY) return null;
    return createJudgmentClient('openrouter', { apiKey: process.env.OPENROUTER_API_KEY });
  }); pass += r.pass; skip += r.skip; fail += r.fail;

  r = await runOne('local', function () {
    if (!process.env.LOCAL_BACKEND_URL) return null;
    return createJudgmentClient('local', { baseUrl: process.env.LOCAL_BACKEND_URL });
  }); pass += r.pass; skip += r.skip; fail += r.fail;

  console.log('SUMMARY: pass=' + pass + ' skip=' + skip + ' fail=' + fail);
  process.exit(fail > 0 ? 1 : 0);
})().catch(function (e) { console.error(e); process.exit(1); });
