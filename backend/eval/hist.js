'use strict';

/* P3 · 置信分布直方图（无需标注，现在即可跑）：
 * 从 dual-run-results.json 统计每端每问的把握度(surety)分布，0.1 一档给原始计数。
 * choice/score 用 confidence；noul 用 |2p−1|。 */

const fs = require('fs');
const path = require('path');

const results = JSON.parse(fs.readFileSync(path.join(__dirname, process.argv[2] || 'dual-run-results.json'), 'utf8')).results;

function surety(a) {
  if (!a) return null;
  if (a.type === 'noul') return Math.abs(2 * a.noul - 1);
  return typeof a.confidence === 'number' ? a.confidence : 0;
}

const buckets = Array.from({ length: 10 }, (_, i) => [i / 10, (i + 1) / 10]);

for (const side of ['local', 'cloud']) {
  console.log(`\n===== ${side} 把握度分布 =====`);
  const qids = Object.keys(results[0][side].answers || {});
  for (const qid of qids) {
    const counts = Array(10).fill(0);
    let n = 0;
    for (const row of results) {
      const a = row[side] && row[side].answers ? row[side].answers[qid] : null;
      const s = surety(a);
      if (s === null) continue;
      n++;
      const idx = Math.min(9, Math.max(0, Math.floor(s * 10)));
      counts[idx]++;
    }
    const bar = counts.map((c, i) => {
      const lo = (i / 10).toFixed(1);
      return `${lo}: ${String(c).padStart(2)}`;
    }).join(' | ');
    const high = counts[8] + counts[9], mid = counts[5] + counts[6] + counts[7];
    console.log(`${qid} (n=${n}): ${bar}  → ≥0.5: ${mid + high}/${n}，≥0.8: ${high}/${n}`);
  }
}
