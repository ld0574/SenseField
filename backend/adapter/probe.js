'use strict';

/* P1 契约探针（六问）：对 Laya 本地后端逐项实测，只给实测值，不许推测。
 * 实测输出粘贴进 specs/local-backend-probe.md。
 * 运行前提：node_modules 已装、模型已缓存（先跑 load-laya.js）。 */

const { Laya } = require('@receptron/laya');

function choiceConfidence(p) {
  const v = Object.values(p); const n = v.length; if (!n) return 0;
  return n === 1 ? 1 : Math.max(0, (Math.max(...v) - 1 / n) / (1 - 1 / n));
}
function scoreConfidence(p) {
  const keys = Object.keys(p).sort((a, b) => parseInt(a, 10) - parseInt(b, 10));
  const v = keys.map(k => p[k]); const n = v.length; if (!n) return 0;
  let m = 0; v.forEach((x, i) => { if (x > v[m]) m = i; });
  const spread = v.reduce((acc, x, i) => acc + x * Math.abs(i - m), 0);
  const mad = v.reduce((acc, _x, i) => acc + Math.abs(i - (n - 1) / 2), 0) / n;
  return mad === 0 ? 1 : Math.max(0, 1 - spread / mad);
}

(async () => {
  const laya = await Laya.load();
  const report = [];

  /* 问题 1+3+4：真实入参 / 返回体形状 / probabilities 归一 */
  const enState = { subject: 'Refund not received', body: 'I cancelled two weeks ago and still have not got my money back.' };
  const enQuestions = {
    department: { type: 'choice', instructions: 'Which team should handle this?', criteria: { billing: 'Payment or subscription issues', support: 'Bug or integration problems', sales: 'Pricing questions' } },
    urgency: { type: 'score', instructions: 'How urgent is this?', criteria: ['not urgent', 'somewhat urgent', 'urgent', 'critical'] },
    is_churn_risk: { type: 'noul', instructions: 'Is the customer likely to cancel or dispute?' }
  };
  const r1 = await laya.systemOne(enState, enQuestions);
  report.push('【问1/3/4】choice(criteria=object) + score(array) + noul 三问的原始返回：');
  report.push(JSON.stringify(r1.answers, null, 2));
  report.push('usage: ' + JSON.stringify(r1.usage));
  for (const [qid, a] of Object.entries(r1.answers)) {
    if (a.probabilities) {
      const sum = Object.values(a.probabilities).reduce((x, y) => x + y, 0);
      report.push(`归一检查 ${qid}: 概率和=${sum.toFixed(6)}`);
    }
  }

  /* 问题 2：noul 还是 boolean？ */
  report.push('【问2】类型名实测：');
  try {
    const r2 = await laya.systemOne('t', { q: { type: 'boolean', instructions: 'Is this true?' } });
    report.push('  type="boolean" 被接受，返回: ' + JSON.stringify(r2.answers));
  } catch (e) {
    report.push('  type="boolean" 被拒绝: ' + String(e && e.message ? e.message : e).slice(0, 200));
  }

  /* 追加：choice 的 criteria 收 object 还是 array？ */
  try {
    const r3 = await laya.systemOne('t', { q: { type: 'choice', instructions: 'Pick one', criteria: ['alpha', 'beta'] } });
    report.push('【追加】choice criteria=array 被接受，返回: ' + JSON.stringify(r3.answers));
  } catch (e) {
    report.push('【追加】choice criteria=array 被拒绝: ' + String(e && e.message ? e.message : e).slice(0, 200));
  }

  /* 问题 5：中文 vs 英文（同语义对照） */
  const zhState = { subject: '退款没到账', body: '我两周前取消了订阅，到现在还没拿到退款，你们再不处理我就要投诉了。' };
  const zhQuestions = {
    department: { type: 'choice', instructions: 'Which team should handle this? (Chinese input)', criteria: { billing: 'Payment or subscription issues', support: 'Bug or integration problems', sales: 'Pricing questions' } },
    is_churn_risk: { type: 'noul', instructions: 'Is the customer likely to cancel or dispute? (Chinese input)' }
  };
  const r5 = await laya.systemOne(zhState, zhQuestions);
  report.push('【问5】中文 state 对照：');
  report.push('  英文 → department=' + r1.answers.department.choice + ' (conf=' + choiceConfidence(r1.answers.department.probabilities).toFixed(3) + ')'
    + ' | churn=' + r1.answers.is_churn_risk.noul.toFixed(3) + ' (surety=' + Math.abs(2 * r1.answers.is_churn_risk.noul - 1).toFixed(3) + ')');
  report.push('  中文 → department=' + r5.answers.department.choice + ' (conf=' + choiceConfidence(r5.answers.department.probabilities).toFixed(3) + ')'
    + ' | churn=' + r5.answers.is_churn_risk.noul.toFixed(3) + ' (surety=' + Math.abs(2 * r5.answers.is_churn_risk.noul - 1).toFixed(3) + ')');

  /* 问题 6：延迟 p50/p95 ×100 + 常驻内存 */
  const lat = [];
  const q6 = { q: { type: 'noul', instructions: 'Does this message express urgency?' } };
  for (let i = 0; i < 100; i++) {
    const t0 = Date.now();
    await laya.systemOne(i % 2 === 0 ? enState : zhState, q6);
    lat.push(Date.now() - t0);
  }
  lat.sort((a, b) => a - b);
  const p = q => lat[Math.min(lat.length - 1, Math.floor(lat.length * q))];
  report.push(`【问6】单问延迟 ×100：p50=${p(0.5)}ms  p95=${p(0.95)}ms  min=${lat[0]}ms  max=${lat[lat.length - 1]}ms`);
  report.push('常驻内存 RSS=' + (process.memoryUsage().rss / 1024 / 1024).toFixed(0) + 'MB');

  console.log(report.join('\n'));
  await laya.close();
  process.exit(0);
})().catch(e => { console.error('探针失败:', e && e.message ? e.message : e); process.exit(1); });
