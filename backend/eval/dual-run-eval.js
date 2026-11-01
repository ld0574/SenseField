'use strict';

/* P2 双跑评测执行器（无标注部分）：
 * 50 帧 × 本地(Laya 适配服务) / 云端(OpenRouter) 各跑一次五问，
 * 产出：
 *   dual-run-results.json  —— 每帧每问的原始答案/置信/延迟（P3 直方图数据源）
 *   控制台汇总             —— top-1 一致率（分题型）、P50/P95 延迟、低置信率、中英文分行
 *
 * top-1 准确率（对照人工标注）不在此跑：标注由领导确认后用 sweep.js 计算。 */

const fs = require('fs');
const path = require('path');
const { createJudgmentClient } = require('../src/factory');

const LOCAL_URL = process.env.LOCAL_BACKEND_URL || 'http://127.0.0.1:8080';
const LANG = (process.env.JEV_LANG || 'zh') === 'en' ? 'en' : 'zh'; /* 2026-10-03 起 default zh（领导改判） */
const frames = JSON.parse(fs.readFileSync(path.join(__dirname, 'frames.json'), 'utf8')).frames;

function questions() {
  if (LANG === 'en') {
    return {
      next_action: { type: 'choice', instructions: 'Given the serialized game state, what should the assistant do next for the visually impaired player?', criteria: { announce_match: 'Report the position of a possible match (or best move)', scan_board: 'Read the board or screen row by row', read_menu: 'Read menu or dialog items so the player can choose', wait: 'Nothing changed or nothing actionable, keep waiting' } },
      danger_level: { type: 'score', instructions: 'How dangerous is the current situation for the player (timer pressure, about to lose, piece under attack)?', criteria: ['Safe', 'Caution: timer or obstacle approaching', 'Danger: about to fail', 'Emergency: act now'] },
      should_interrupt: { type: 'noul', instructions: 'Must the assistant speak immediately, even if that interrupts the current announcement?', criteria: { true: 'Must speak now even if it interrupts current speech', false: 'Can wait until current announcement finishes' } },
      announce_rank: { type: 'score', instructions: 'How important is it to announce the latest change right now?', criteria: ['Do not announce', 'Optional', 'Normal info', 'Important', 'Must announce immediately'] },
      scene_changed: { type: 'noul', instructions: 'Does the current state represent a clearly different scene or screen from the previous frame?', criteria: { true: 'Scene differs clearly from previous frame', false: 'Same scene continues' } }
    };
  }
  return {
    next_action: { type: 'choice', instructions: '根据序列化后的游戏状态，接下来应该为视障玩家做什么？', criteria: { announce_match: '播报可消除组合（或最佳走法）的位置', scan_board: '逐行读取棋盘或屏幕内容', read_menu: '朗读菜单或对话框选项供玩家选择', wait: '局面无变化或无可执行操作，继续等待' } },
    danger_level: { type: 'score', instructions: '当前局面对玩家的危险程度如何（计时压力、即将失败、棋子被攻击）？', criteria: ['安全', '注意：计时或障碍逼近', '危险：即将失败', '紧急：立即行动'] },
    should_interrupt: { type: 'noul', instructions: '是否必须立即播报，即使会打断当前正在进行的播报？', criteria: { true: '必须立即说，可以打断当前播报', false: '可以等当前播报结束后再说' } },
    announce_rank: { type: 'score', instructions: '最新的变化现在有多重要、必须马上播报吗？', criteria: ['不必播报', '可说可不说', '普通信息', '重要信息', '必须立即播报'] },
    scene_changed: { type: 'noul', instructions: '当前 state 相比上一帧是否明显切换了场景或界面？', criteria: { true: '与上一帧明显不同（新场景或新界面）', false: '同一场景延续' } }
  };
}

function topValue(a) {
  if (!a) return 'ERR';
  if (a.type === 'choice') return a.choice;
  if (a.type === 'noul') return a.noul >= 0.5 ? 'yes' : 'no';
  return String(Math.round(a.score));
}

function surety(a) {
  if (!a) return 0;
  if (a.type === 'noul') return Math.abs(2 * a.noul - 1);
  return typeof a.confidence === 'number' ? a.confidence : 0;
}

const qs = questions();
const local = createJudgmentClient('local', { baseUrl: LOCAL_URL });
const cloud = createJudgmentClient('openrouter', { apiKey: process.env.OPENROUTER_API_KEY });

(async () => {
  const results = [];
  for (const f of frames) {
    const row = { id: f.id, lang: f.lang };
    for (const [side, client] of [['local', local], ['cloud', cloud]]) {
      try {
        const r = await client.judge(f.state, qs);
        row[side] = { elapsedMs: r.elapsedMs, answers: Object.fromEntries(Object.entries(r.answers).map(([qid, a]) => [qid, { type: a.type, choice: a.choice, score: a.score, noul: a.noul, probabilities: a.probabilities || null, confidence: a.confidence === null ? null : a.confidence }])) };
      } catch (e) {
        row[side] = { error: (e.kind || 'ERR') + ' ' + (e.message || ''), bodyText: (e.bodyText || '').slice(0, 200) };
      }
    }
    results.push(row);
    const lok = row.local && row.local.answers ? 'ok' : 'ERR';
    const cok = row.cloud && row.cloud.answers ? 'ok' : 'ERR';
    console.log(`[${results.length}/${frames.length}] ${f.id} local=${lok}(${row.local && row.local.elapsedMs || '-'}ms) cloud=${cok}(${row.cloud && row.cloud.elapsedMs || '-'}ms)`);
  }

  fs.writeFileSync(path.join(__dirname, LANG === 'en' ? 'dual-run-results.json' : 'dual-run-results-zh.json'), JSON.stringify({ generated_at: new Date().toISOString(), lang: LANG, questions: qs, results }, null, 2));

  /* ---- 汇总 ---- */
  function pct(n, d) { return d ? (100 * n / d).toFixed(1) + '%' : 'n/a'; }
  function p50v(arr) { const s = arr.slice().sort((a, b) => a - b); return s.length ? s[Math.floor(s.length * 0.5)] : null; }
  function p95v(arr) { const s = arr.slice().sort((a, b) => a - b); return s.length ? s[Math.min(s.length - 1, Math.floor(s.length * 0.95))] : null; }

  const lines = [];
  for (const qid of Object.keys(qs)) {
    const type = qs[qid].type;
    let agree = 0, tot = 0;
    const lowLocal = [], lowCloud = [];
    for (const row of results) {
      const a = row.local && row.local.answers ? row.local.answers[qid] : null;
      const b = row.cloud && row.cloud.answers ? row.cloud.answers[qid] : null;
      if (!a || !b) continue;
      tot++;
      if (topValue(a) === topValue(b)) agree++;
      (row.lang === 'en' ? lowLocal.en = 1 : 0);
      lowLocal.push({ surety: surety(a), lang: row.lang });
      lowCloud.push({ surety: surety(b), lang: row.lang });
    }
    const ll = lowLocal.filter(x => x.surety < 0.5).length;
    const lc = lowCloud.filter(x => x.surety < 0.5).length;
    lines.push(`${qid}[${type}]: 一致率 ${agree}/${tot}=${pct(agree, tot)} | 低置信(<0.5) 本地 ${ll}/${tot}=${pct(ll, tot)} 云端 ${lc}/${tot}=${pct(lc, tot)}`);
  }
  const okRows = results.filter(r => r.local && r.local.answers && r.cloud && r.cloud.answers);
  const lLat = okRows.map(r => r.local.elapsedMs), cLat = okRows.map(r => r.cloud.elapsedMs);
  lines.push(`延迟: 本地 p50=${p50v(lLat)}ms p95=${p95v(lLat)}ms（n=${lLat.length}） | 云端 p50=${p50v(cLat)}ms p95=${p95v(cLat)}ms`);
  const allAgree = [];
  for (const qid of Object.keys(qs)) {
    for (const row of okRows) {
      const a = row.local.answers[qid], b = row.cloud.answers[qid];
      allAgree.push(topValue(a) === topValue(b) ? 1 : 0);
    }
  }
  const agreeN = allAgree.reduce((x, y) => x + y, 0);
  lines.push(`总 top-1 一致率: ${agreeN}/${allAgree.length}=${pct(agreeN, allAgree.length)}`);
  for (const lang of ['zh', 'en', 'mixed']) {
    const rowsL = okRows.filter(r => r.lang === lang);
    if (!rowsL.length) continue;
    let ag = 0, tt = 0;
    for (const row of rowsL) for (const qid of Object.keys(qs)) { tt++; if (topValue(row.local.answers[qid]) === topValue(row.cloud.answers[qid])) ag++; }
    lines.push(`  ${lang} 帧(n=${rowsL.length}): 一致率 ${ag}/${tt}=${pct(ag, tt)}`);
  }
  const errRows = results.filter(r => (r.local && r.local.error) || (r.cloud && r.cloud.error));
  if (errRows.length) lines.push(`错误帧: ${errRows.map(r => r.id).join(', ')}`);
  console.log('\n===== P2 双跑汇总 =====\n' + lines.join('\n'));
})().catch(e => { console.error('双跑失败:', e); process.exit(1); });
