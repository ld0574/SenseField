'use strict';

/* 多场景双跑：同一批 state（消消乐五问 / 中文无障碍树五问 / 象棋五问）
 * 分别打本地适配服务与 OpenRouter 云端，输出判定一致率与延迟对比。
 * 用途：P1 收尾的多场景排查 + P2 双跑的先行小样。 */

const { createJudgmentClient } = require('../src/factory');
const fs = require('fs');

const LOCAL = process.env.LOCAL_BACKEND_URL || 'http://127.0.0.1:8080';
const ASSETS = '../../sensefield-integration/app/src/main/assets/jev';

function questions() {
  return {
    next_action: { type: 'choice', instructions: 'Given the serialized game state, what should the assistant do next for the visually impaired player?', criteria: { announce_match: 'Report the position of a possible match (or best move)', scan_board: 'Read the board or screen row by row', read_menu: 'Read menu or dialog items so the player can choose', wait: 'Nothing changed or nothing actionable, keep waiting' } },
    danger_level: { type: 'score', instructions: 'How dangerous is the current situation for the player (timer pressure, about to lose, piece under attack)?', criteria: ['Safe', 'Caution: timer or obstacle approaching', 'Danger: about to fail', 'Emergency: act now'] },
    should_interrupt: { type: 'noul', instructions: 'Must the assistant speak immediately, even if that interrupts the current announcement?', criteria: { true: 'Must speak now even if it interrupts current speech', false: 'Can wait until current announcement finishes' } },
    announce_rank: { type: 'score', instructions: 'How important is it to announce the latest change right now?', criteria: ['Do not announce', 'Optional', 'Normal info', 'Important', 'Must announce immediately'] },
    scene_changed: { type: 'noul', instructions: 'Does the current state represent a clearly different scene or screen from the previous frame?', criteria: { true: 'Scene differs clearly from previous frame', false: 'Same scene continues' } }
  };
}

function surety(a) {
  if (a.type === 'noul') return Math.abs(2 * a.noul - 1);
  return typeof a.confidence === 'number' ? a.confidence : 0;
}

function topValue(a) {
  if (a.type === 'choice') return a.choice;
  if (a.type === 'noul') return a.noul >= 0.5 ? 'yes' : 'no';
  return String(Math.round(a.score));
}

(async () => {
  const local = createJudgmentClient('local', { baseUrl: LOCAL });
  const cloud = createJudgmentClient('openrouter', { apiKey: process.env.OPENROUTER_API_KEY });
  const qs = questions();
  const scenarios = ['match3', 'menu-axtree', 'chess'];
  const rows = [];
  let agree = 0, total = 0;

  for (const name of scenarios) {
    const state = JSON.parse(fs.readFileSync(`${__dirname}/${ASSETS}/${name}.json`, 'utf8')).state;
    const rl = await local.judge(state, qs);
    const rc = await cloud.judge(state, qs);
    for (const qid of Object.keys(qs)) {
      const a = rl.answers[qid], b = rc.answers[qid];
      const same = topValue(a) === topValue(b);
      total++; if (same) agree++;
      rows.push(`${name}.${qid}: 本地=${topValue(a)}(surety=${surety(a).toFixed(2)}) 云端=${topValue(b)}(surety=${surety(b).toFixed(2)}) ${same ? '一致' : '不一致'}`);
    }
    rows.push(`${name}: 延迟 本地=${rl.elapsedMs}ms 云端=${rc.elapsedMs}ms`);
  }
  rows.push(`\n双跑一致率（不依赖标注的 top-1 一致率）: ${agree}/${total} = ${(100 * agree / total).toFixed(1)}%`);
  console.log(rows.join('\n'));
})().catch(e => { console.error('双跑失败:', e && e.message ? e.message : e, e.bodyText || ''); process.exit(1); });
