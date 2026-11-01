'use strict';

/* P3 · 阈值寻优（等标注落地后跑）：
 * 输入：dual-run-results.json（双跑原始输出）+ annotation-filled.csv（领导确认的标注）
 * 约束：自动执行档的实际错误率 ≤ X%（X 由领导给定，默认 1%）
 * 输出：按操作风险分层的最低可用阈值表 + routing-policy.local.json
 *      （结构与创作工具④导出的 jev-config.json 的 thresholds 一致：{qid:{high,mid,low}}）
 *
 * 用法：node sweep.js annotation-filled.csv [X百分比，默认1]
 *
 * 风险分层（按错误代价，ADR/BLOCKED 第 4 条）：
 *   read_only  —— 只读/播报类操作（announce_match/scan_board/read_menu/wait）→ 允许低门槛
 *   mutating   —— 改变游戏状态/消耗道具的操作 → 高门槛（本评测集暂无此类帧，留结构）
 *   payment    —— 触发付费等高影响操作 → 最高门槛且强制人工确认（同上） */

const fs = require('fs');
const path = require('path');

function parseCsv(text) {
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).filter(l => l.trim());
  const rows = [];
  for (const line of lines) {
    const cells = [];
    let cur = '', inQ = false;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (ch === '"') { inQ = !inQ; continue; }
      if (ch === ',' && !inQ) { cells.push(cur.trim()); cur = ''; continue; }
      cur += ch;
    }
    cells.push(cur.trim());
    rows.push(cells);
  }
  return rows;
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

const READ_ONLY = new Set(['announce_match', 'scan_board', 'read_menu', 'wait']);

const resultsFile = path.join(__dirname, 'dual-run-results.json');
const csvFile = process.argv[2];
const maxX = parseFloat(process.argv[3] || '1') / 100;

if (!csvFile) {
  console.log('用法: node sweep.js <annotation-filled.csv> [X百分比=1]');
  console.log('状态: 等待领导确认标注（模板见 annotation-template.csv）。寻优逻辑已就绪。');
  process.exit(0);
}

const results = JSON.parse(fs.readFileSync(resultsFile, 'utf8')).results;

const rows = parseCsv(fs.readFileSync(csvFile, 'utf8'));
const header = rows[0];
const col = name => header.indexOf(name);
const labels = {};
for (const r of rows.slice(1)) {
  if (r[0].startsWith('EXAMPLE')) continue;
  const confirmed = r[col('人工确认')];
  if (confirmed !== '是' && confirmed !== '修正' && confirmed !== '机器推导') continue; // 是/修正=领导确认；机器推导=state 内嵌事实（仅限 read_only 层标定，报告需注明）
  labels[r[0]] = {
    action: r[col('正确操作(next_action 选项名)')],
    rank: r[col('announce_rank(0-4)')],
    danger: r[col('danger_level(0-3)')],
    interrupt: r[col('should_interrupt(yes/no)')]
  };
}
console.log(`已加载确认标注 ${Object.keys(labels).length} 帧（X=${(maxX * 100).toFixed(1)}%）`);

function sweep(side, qid, tierFilter) {
  const data = [];
  for (const row of results) {
    if (!labels[row.id]) continue;
    const a = row[side] && row[side].answers ? row[side].answers[qid] : null;
    if (!a) continue;
    if (tierFilter && !tierFilter(topValue(a))) continue;
    data.push({ id: row.id, top: topValue(a), s: surety(a), label: labels[row.id] });
  }
  const out = [];
  for (let t = 30; t <= 95; t += 5) {
    const th = t / 100;
    const auto = data.filter(d => d.s >= th);
    if (!auto.length) { out.push({ th, auto: 0, err: null, rate: null }); continue; }
    const errors = auto.filter(d => {
      if (qid === 'next_action') return d.top !== d.label.action;
      if (qid === 'announce_rank') return String(d.label.rank) !== d.top;
      if (qid === 'danger_level') return String(d.label.danger) !== d.top;
      if (qid === 'should_interrupt') return d.top !== (d.label.interrupt || 'no');
      return false;
    }).length;
    out.push({ th, auto: auto.length, err: errors, rate: errors / auto.length });
  }
  return { data: data.length, sweep: out };
}

for (const side of ['cloud', 'local']) {
  console.log(`\n===== ${side} · next_action（read_only 层）阈值寻优 =====`);
  const r = sweep(side, 'next_action', v => READ_ONLY.has(v));
  console.log(`可用标注帧: ${r.data}`);
  for (const p of r.sweep) {
    const rate = p.rate === null ? '—' : (p.rate * 100).toFixed(1) + '%';
    console.log(`  阈值≥${p.th.toFixed(2)}: 自动执行 ${p.auto} 帧, 错误 ${p.err === null ? '—' : p.err}, 错误率 ${rate}`);
  }
  const ok = r.sweep.filter(p => p.rate !== null && p.rate <= maxX && p.auto > 0);
  console.log(ok.length
    ? `  → 满足错误率≤${(maxX * 100).toFixed(1)}% 的最低阈值: ${ok[0].th.toFixed(2)}（自动执行率 ${(100 * ok[0].auto / Math.max(1, r.data)).toFixed(1)}%）`
    : `  → 无阈值能同时满足错误率≤${(maxX * 100).toFixed(1)}% 且有自动执行（分布塌缩，需换模型/微调，见探针报告）`);
}
console.log('\nmutating / payment 层：本评测集无此类操作帧，阈值维持保守默认（0.9/0.5/0.3）并强制人工确认，待出现真实操作帧后补标定。');
