/* 加载 Laya 模型（触发权重下载到缓存）并做一次最小推理验证 */
const { Laya, defaultCacheDir } = require('@receptron/laya');

(async () => {
  console.log('[load] cache dir:', defaultCacheDir());
  const t0 = Date.now();
  const laya = await Laya.load();
  console.log('[load] 模型加载完成，耗时', ((Date.now() - t0) / 1000).toFixed(1), 's');
  const r = await laya.systemOne(
    { subject: 'Refund not received', body: 'I cancelled two weeks ago and still have not got my money back.' },
    {
      department: { type: 'choice', instructions: 'Which team should handle this?', criteria: { billing: 'Payment or subscription issues', support: 'Bug or integration problems', sales: 'Pricing questions' } },
      urgency: { type: 'score', instructions: 'How urgent is this?', criteria: ['not urgent', 'somewhat urgent', 'urgent', 'critical'] },
      is_churn_risk: { type: 'noul', instructions: 'Is the customer likely to cancel or dispute?' }
    }
  );
  console.log('[probe-min] 原始答案 JSON:');
  console.log(JSON.stringify(r.answers, null, 2));
  console.log('[probe-min] usage:', JSON.stringify(r.usage));
  await laya.close();
  console.log('[load] LOAD-OK');
  process.exit(0);
})().catch(e => { console.error('[load] 失败:', e && e.message ? e.message : e); process.exit(1); });
