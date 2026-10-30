
'use strict';

/* ================================================================
 * 纯函数与常量区（无 DOM 依赖；node 可直接 require 做无头自检）
 * ================================================================ */

var CHANNELS = {
  edgeone:  { label: '腾讯 EdgeOne Makers（默认，国内免 TypeSafe 账号）', endpoint: 'https://ai-gateway.edgeone.link/v1/systemone', model: '@makers/jev' },
  typesafe: { label: 'TypeSafe 官方 API', endpoint: 'https://api.typesafe.ai/v1/systemone', model: 'jev-latest' },
  custom:   { label: '自定义 URL（自己填）', endpoint: '', model: '@makers/jev' }
};

/* 样例 state：与 samples/*.json 是同一份数据（模拟样例，非真实抓取） */
var SAMPLE_MATCH3 = {
  _note: '模拟样例，供测试判定用，非真实抓取。R红/Y黄/B蓝/G绿/O橙/P紫',
  game: 'match3', scene: 'playing',
  board: { size: [8, 8], rows: [
    ['R','Y','B','G','O','P','R','Y'],
    ['G','R','Y','B','G','O','P','R'],
    ['B','G','R','Y','B','G','O','P'],
    ['Y','B','G','O','Y','B','G','O'],
    ['O','Y','B','G','O','Y','B','G'],
    ['P','O','Y','B','G','O','Y','B'],
    ['R','P','O','Y','B','G','O','Y'],
    ['Y','R','P','O','Y','B','G','O']
  ]},
  potential_matches: [
    { from: [2,3], to: [3,3], run: 3, reward: 'low' },
    { from: [5,1], to: [5,2], run: 4, reward: 'medium' }
  ],
  moves_left: 12, score: 4520, time_limit_sec: 30
};

var SAMPLE_CHESS = {
  _note: '模拟样例，供测试判定用，非真实抓取。棋子前缀 b=黑方 r=红方，null=空位；9 列 × 10 行，行 0 在黑方底线',
  game: 'chess', turn: 'red',
  board: [
    ['b車','b馬','b象','b士','b將','b士','b象','b馬','b車'],
    [null,null,null,null,null,null,null,null,null],
    [null,'b炮',null,null,null,null,null,'b炮',null],
    ['b兵',null,'b兵',null,'b兵',null,'b兵',null,'b兵'],
    [null,null,null,null,null,null,null,null,null],
    [null,null,null,null,null,null,null,null,null],
    ['r兵',null,'r兵',null,'r兵',null,'r兵',null,'r兵'],
    [null,'r炮',null,null,null,null,null,'r炮',null],
    [null,null,null,null,null,null,null,null,null],
    ['r車','r馬','r相','r仕','r帥','r仕','r相','r馬','r車']
  ],
  last_move: '炮二平五', in_check: false, captured_recently: null
};

var SAMPLE_AXTREE = {
  _note: '模拟样例，供测试判定用，非真实抓取。游戏菜单界面的无障碍树节选（Android AccessibilityNodeInfo 序列化）',
  package: 'com.example.match3game',
  activity: 'com.example.match3game.MainActivity',
  nodes: [
    { id: 'lbl_level',   text: '第 12 关',          content_desc: null,               clickable: false, bounds: [420,200,660,260] },
    { id: 'lbl_coins',   text: '金币 1280',         content_desc: null,               clickable: false, bounds: [60,200,300,260] },
    { id: 'btn_start',   text: '开始游戏',          content_desc: '开始一局新的消消乐', clickable: true,  bounds: [360,900,720,1020] },
    { id: 'btn_continue',text: '继续冒险 第 12 关',  content_desc: '继续上次进度',       clickable: true,  bounds: [360,1080,720,1200] },
    { id: 'btn_settings',text: '设置',              content_desc: '打开设置菜单',       clickable: true,  bounds: [360,1260,720,1380] },
    { id: 'btn_shop',    text: '商店',              content_desc: '进入商店',           clickable: true,  bounds: [100,1500,300,1620] }
  ]
};

/* 默认五问：中文为默认（2026-10-03 领导改判，覆盖「criteria 默认英文」原拍板）；English 保留作对照模式 */
function defaultQuestions(lang) {
  return lang === 'en' ? englishQuestions() : chineseQuestions();
}

function chineseQuestions() {
  return [
    { id: 'next_action', type: 'choice',
      instructions: '根据序列化后的游戏状态，接下来应该为视障玩家做什么？',
      criteria: {
        'announce_match': '播报可消除组合（或最佳走法）的位置',
        'scan_board': '逐行读取棋盘或屏幕内容',
        'read_menu': '朗读菜单或对话框选项供玩家选择',
        'wait': '局面无变化或无可执行操作，继续等待'
      } },
    { id: 'danger_level', type: 'score',
      instructions: '当前局面对玩家的危险程度如何（计时压力、即将失败、棋子被攻击）？',
      criteria: ['安全', '注意：计时或障碍逼近', '危险：即将失败', '紧急：立即行动'] },
    { id: 'should_interrupt', type: 'noul',
      instructions: '是否必须立即播报，即使会打断当前正在进行的播报？',
      criteria: { 'true': '必须立即说，可以打断当前播报', 'false': '可以等当前播报结束后再说' } },
    { id: 'announce_rank', type: 'score',
      instructions: '最新的变化现在有多重要、必须马上播报吗？',
      criteria: ['不必播报', '可说可不说', '普通信息', '重要信息', '必须立即播报'] },
    { id: 'scene_changed', type: 'noul',
      instructions: '当前 state 相比上一帧是否明显切换了场景或界面？',
      criteria: { 'true': '与上一帧明显不同（新场景或新界面）', 'false': '同一场景延续' } }
  ];
}

function englishQuestions() {
  return [
    { id: 'next_action', type: 'choice',
      instructions: 'Given the serialized game state, what should the assistant do next for the visually impaired player?',
      criteria: {
        'announce_match': 'Report the position of a possible match (or best move)',
        'scan_board': 'Read the board or screen row by row',
        'read_menu': 'Read menu or dialog items so the player can choose',
        'wait': 'Nothing changed or nothing actionable, keep waiting'
      } },
    { id: 'danger_level', type: 'score',
      instructions: 'How dangerous is the current situation for the player (timer pressure, about to lose, piece under attack)?',
      criteria: ['Safe', 'Caution: timer or obstacle approaching', 'Danger: about to fail', 'Emergency: act now'] },
    { id: 'should_interrupt', type: 'noul',
      instructions: 'Must the assistant speak immediately, even if that interrupts the current announcement?',
      criteria: { 'true': 'Must speak now even if it interrupts current speech', 'false': 'Can wait until current announcement finishes' } },
    { id: 'announce_rank', type: 'score',
      instructions: 'How important is it to announce the latest change right now?',
      criteria: ['Do not announce', 'Optional', 'Normal info', 'Important', 'Must announce immediately'] },
    { id: 'scene_changed', type: 'noul',
      instructions: 'Does the current state represent a clearly different scene or screen from the previous frame?',
      criteria: { 'true': 'Scene differs clearly from previous frame', 'false': 'Same scene continues' } }
  ];
}

var TYPE_RETURN = {
  noul: '返回：noul（为 yes 的概率 0–1；无 confidence，把握度看 |2p−1|）',
  choice: '返回：choice + probabilities + confidence',
  score: '返回：score（可落在两档之间）+ legend + probabilities + confidence'
};

/* ---- 可测性接缝：三个纯函数 ---- */

/** 按契约拼请求体：{ model, state, questions } */
function buildRequest(cfg) {
  return { model: cfg.model, state: cfg.state, questions: cfg.questions };
}

/**
 * 真发送：POST endpoint，Bearer key，30s 超时。
 * 正常返回 { status, bodyText, elapsedMs, parsed }；网络失败返回 { status:0, bodyText:'<错误信息>' }。
 * 不吞错、不在前端重试（指数退避重试是 Kotlin 端的事）。
 */
function sendJev(cfg, fetchImpl) {
  var f = fetchImpl || (typeof fetch !== 'undefined' ? fetch : null);
  var t0 = Date.now();
  if (!f) { return Promise.resolve({ status: 0, bodyText: '此环境没有可用的 fetch 实现', elapsedMs: 0, parsed: null }); }
  var body = JSON.stringify(buildRequest(cfg));
  var init = {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + (cfg.apiKey || '') },
    body: body
  };
  var timer = null;
  if (typeof AbortController !== 'undefined') {
    var ac = new AbortController();
    init.signal = ac.signal;
    timer = setTimeout(function () { ac.abort(); }, 30000);
  }
  return f(cfg.endpoint, init).then(function (res) {
    return res.text().then(function (bodyText) {
      if (timer) clearTimeout(timer);
      var parsed = null;
      try { parsed = JSON.parse(bodyText); } catch (e) { parsed = null; }
      return { status: res.status, bodyText: bodyText, elapsedMs: Date.now() - t0, parsed: parsed };
    });
  }).catch(function (err) {
    if (timer) clearTimeout(timer);
    var msg = (err && err.message) ? err.message : String(err);
    return { status: 0, bodyText: msg, elapsedMs: Date.now() - t0, parsed: null };
  });
}

/** 错误渲染（纯函数）：状态码＋一行解释＋响应原文。必须消费 bodyText，不许写死文案遮盖原始返回。 */
function renderError(status, bodyText) {
  var head = (status === 0) ? '网络失败（HTTP 0）' : ('HTTP ' + status);
  var reason;
  if (status === 401) reason = 'API key 缺失或无效：去渠道控制台重新生成/粘贴 Key';
  else if (status === 400) reason = '请求体校验失败（400）：对照原文定位字段。OpenRouter 对坏请求用 400，EdgeOne 文档用 422，语义相同';
  else if (status === 422) reason = '请求体校验失败：通常是 criteria 缺失或问题格式不对，对着下面原文定位字段';
  else if (status === 429) reason = '触发限流：稍等再试；生产端应做指数退避重试';
  else if (status === 529) reason = '服务过载：稍等再试；生产端应做指数退避重试';
  else if (status === 0) reason = '请求没有到达服务器：检查断网 / 地址写错 / DNS';
  else reason = '未预期的状态码，看原文';
  return head + '\n' + reason + '\n原始返回：\n' + String(bodyText);
}

function noulConfidence(p) { return Math.abs(2 * p - 1); }

/** 聚合历史记录：每问的把握度分布（choice/score 用 confidence，noul 用 |2p−1|） */
function aggregateStats(history) {
  var byQ = {};
  (history || []).forEach(function (h) {
    if (!h || !h.parsed || !h.parsed.answers) return;
    Object.keys(h.parsed.answers).forEach(function (qid) {
      var a = h.parsed.answers[qid];
      var v = null;
      if (a.type === 'choice' || a.type === 'score') { if (typeof a.confidence === 'number') v = a.confidence; }
      else if (a.type === 'noul') { if (typeof a.noul === 'number') v = Math.abs(2 * a.noul - 1); }
      if (v === null) return;
      if (!byQ[qid]) byQ[qid] = { type: a.type, values: [] };
      byQ[qid].values.push(v);
    });
  });
  Object.keys(byQ).forEach(function (qid) {
    var vs = byQ[qid].values.slice().sort(function (x, y) { return x - y; });
    var n = vs.length, mid;
    mid = (n % 2 === 1) ? vs[(n - 1) / 2] : (vs[n / 2 - 1] + vs[n / 2]) / 2;
    byQ[qid].n = n; byQ[qid].min = vs[0]; byQ[qid].median = mid; byQ[qid].max = vs[n - 1];
    delete byQ[qid].values;
  });
  return byQ;
}

/* ================================================================
 * UI 区（只在浏览器里执行；node require 时不会触碰 document）
 * ================================================================ */
function initUI() {
  function $(id) { return document.getElementById(id); }

  /* ---------- 页签 ---------- */
  var tabBtns = document.querySelectorAll('.tab-btn');
  Array.prototype.forEach.call(tabBtns, function (b) {
    b.addEventListener('click', function () {
      Array.prototype.forEach.call(tabBtns, function (x) { x.classList.remove('active'); });
      b.classList.add('active');
      ['tab1', 'tab2', 'tab3', 'tab4'].forEach(function (t) { $(t).classList.add('hidden'); });
      $(b.getAttribute('data-tab')).classList.remove('hidden');
      if (b.getAttribute('data-tab') === 'tab3') { refreshRunnerSummary(); refreshHistory(); refreshStats(); }
      if (b.getAttribute('data-tab') === 'tab4') { refreshThresholds(); refreshExport(); }
    });
  });

  /* ---------- ② 判定设计器 ---------- */
  var stateInput = $('stateInput');

  function setStatus(el, ok, text) { el.textContent = text; el.className = 'status ' + (ok ? 'ok' : 'bad'); }

  function parseStateOrRaw(text) {
    var t = text.trim();
    if (!t) return '';
    try { return JSON.parse(t); } catch (e) { return text; }
  }

  stateInput.addEventListener('input', function () {
    try { JSON.parse(stateInput.value); setStatus($('stateStatus'), true, 'state JSON 合法 ✓'); }
    catch (e) {
      if (stateInput.value.trim() === '') setStatus($('stateStatus'), true, '（空 state：将以纯文本发送）');
      else setStatus($('stateStatus'), false, 'JSON 不合法，将以纯文本发送：' + e.message);
    }
    refreshPreview();
  });

  /* ---- 问题卡片 ---- */
  function el(tag, cls, text) {
    var d = document.createElement(tag);
    if (cls) d.className = cls;
    if (text !== undefined) d.textContent = text;
    return d;
  }

  function makeCriteriaEditor(type, criteria) {
    var box = el('div', 'c-box');
    if (type === 'noul') {
      var tVal = criteria && criteria['true'] ? criteria['true'] : '';
      var fVal = criteria && criteria['false'] ? criteria['false'] : '';
      var rt = el('label', 'fl'); rt.innerHTML = 'criteria（可选）：true = 1 的含义 <small>（留空则不下发 criteria）</small>';
      var it = el('input'); it.type = 'text'; it.className = 'c-noul-true'; it.value = tVal; it.spellcheck = false;
      var rf = el('label', 'fl'); rf.textContent = 'criteria：false = 0 的含义';
      var ifr = el('input'); ifr.type = 'text'; ifr.className = 'c-noul-false'; ifr.value = fVal; ifr.spellcheck = false;
      box.appendChild(rt); box.appendChild(it); box.appendChild(rf); box.appendChild(ifr);
    } else if (type === 'choice') {
      var rows = el('div', 'c-rows');
      var src = criteria || {};
      Object.keys(src).forEach(function (k) { rows.appendChild(makeChoiceRow(k, src[k])); });
      box.appendChild(rows);
      var add = el('button', 'mini', '＋ 加选项'); add.type = 'button';
      add.addEventListener('click', function () { rows.appendChild(makeChoiceRow('', '')); refreshPreview(); });
      box.appendChild(add);
      var hint = el('p', 'hint', '选项名发给模型做选项，描述是判定标准（rubric）；描述留空＝null。上限 255 个。');
      box.appendChild(hint);
    } else { /* score */
      var rows2 = el('div', 'c-rows');
      var arr = (criteria && criteria.length) ? criteria : ['level 0', 'level 1'];
      arr.forEach(function (d, i) { rows2.appendChild(makeScoreRow(i, d)); });
      box.appendChild(rows2);
      var add2 = el('button', 'mini', '＋ 加一档'); add2.type = 'button';
      add2.addEventListener('click', function () {
        var n = rows2.querySelectorAll('.c-row').length;
        if (n >= 10) { window.alert('score 最多 10 档'); return; }
        rows2.appendChild(makeScoreRow(n, '')); renumber(rows2); refreshPreview();
      });
      var del2 = el('button', 'mini', '－ 减一档'); del2.type = 'button';
      del2.addEventListener('click', function () {
        var rs = rows2.querySelectorAll('.c-row');
        if (rs.length <= 2) { window.alert('score 至少 2 档'); return; }
        rows2.removeChild(rs[rs.length - 1]); renumber(rows2); refreshPreview();
      });
      box.appendChild(add2); box.appendChild(del2);
      var hint2 = el('p', 'hint', '有序等级：从 0 档开始自下而上，顺序就是分数。最少 2 档，最多 10 档。');
      box.appendChild(hint2);
    }
    return box;
  }
  function renumber(rowsEl) {
    Array.prototype.forEach.call(rowsEl.querySelectorAll('.lv-idx'), function (s, i) { s.textContent = String(i); });
  }
  function makeChoiceRow(k, d) {
    var row = el('div', 'c-row');
    var ik = el('input'); ik.type = 'text'; ik.className = 'c-key'; ik.value = k; ik.placeholder = '选项名（英文更稳）'; ik.spellcheck = false;
    var id = el('input'); id.type = 'text'; id.className = 'c-desc'; id.value = (d === null || d === undefined) ? '' : d; id.placeholder = '判定标准描述（留空＝null）'; id.spellcheck = false;
    var del = el('button', 'mini', '删'); del.type = 'button';
    del.addEventListener('click', function () { row.parentNode.removeChild(row); refreshPreview(); });
    row.appendChild(ik); row.appendChild(id); row.appendChild(del);
    ik.addEventListener('input', refreshPreview); id.addEventListener('input', refreshPreview);
    return row;
  }
  function makeScoreRow(i, d) {
    var row = el('div', 'c-row');
    var idx = el('span', 'lv-idx', String(i));
    var id = el('input'); id.type = 'text'; id.className = 'c-desc'; id.value = d || ''; id.placeholder = '第 ' + i + ' 档的含义'; id.spellcheck = false;
    row.appendChild(idx); row.appendChild(id);
    id.addEventListener('input', refreshPreview);
    return row;
  }

  function makeQCard(q) {
    var card = el('div', 'qcard');
    var head = el('div', 'head');
    var idIn = el('input'); idIn.type = 'text'; idIn.className = 'q-id'; idIn.value = q.id; idIn.spellcheck = false;
    var sel = el('select'); sel.className = 'q-type';
    ['noul', 'choice', 'score'].forEach(function (t) {
      var o = el('option', null, t + (t === 'noul' ? '（是/否）' : t === 'choice' ? '（单选）' : '（评分）'));
      o.value = t; if (t === q.type) o.selected = true; sel.appendChild(o);
    });
    var del = el('button', 'mini q-del', '删除问题'); del.type = 'button';
    head.appendChild(idIn); head.appendChild(sel); head.appendChild(del);
    card.appendChild(head);

    var li = el('label', 'fl'); li.textContent = 'instructions（判断逻辑写在这里）';
    var it = el('textarea'); it.className = 'q-instructions'; it.value = q.instructions || ''; it.spellcheck = false; it.rows = 2;
    card.appendChild(li); card.appendChild(it);

    var lc = el('label', 'fl'); lc.textContent = 'criteria（判定标准）';
    card.appendChild(lc);
    var cbox = makeCriteriaEditor(q.type, q.criteria);
    card.appendChild(cbox);

    var ret = el('div', 'q-return', TYPE_RETURN[q.type]);
    card.appendChild(ret);

    idIn.addEventListener('input', refreshPreview);
    it.addEventListener('input', refreshPreview);
    sel.addEventListener('change', function () {
      var cur = collectCriteriaFrom(card, q.type);
      var newBox = makeCriteriaEditor(sel.value, cur);
      card.replaceChild(newBox, cbox); cbox = newBox;
      ret.textContent = TYPE_RETURN[sel.value];
      refreshPreview();
    });
    del.addEventListener('click', function () { card.parentNode.removeChild(card); refreshPreview(); });
    return card;
  }
  function collectCriteriaFrom(card, type) {
    if (type === 'noul') {
      var t = card.querySelector('.c-noul-true'), f = card.querySelector('.c-noul-false');
      var c = {};
      if (t && t.value.trim()) c['true'] = t.value.trim();
      if (f && f.value.trim()) c['false'] = f.value.trim();
      return Object.keys(c).length ? c : undefined;
    }
    if (type === 'choice') {
      var cc = {};
      card.querySelectorAll('.c-row').forEach(function (row) {
        var k = row.querySelector('.c-key').value.trim();
        if (!k) return;
        var d = row.querySelector('.c-desc').value.trim();
        cc[k] = d === '' ? null : d;
      });
      return cc;
    }
    var arr = [];
    card.querySelectorAll('.c-row .c-desc').forEach(function (inp) { arr.push(inp.value); });
    return arr;
  }

  function renderQuestions(list) {
    var box = $('qList'); box.innerHTML = '';
    list.forEach(function (q) { box.appendChild(makeQCard(q)); });
  }

  function readQuestions() {
    var out = [], seen = {};
    var dups = [];
    document.querySelectorAll('#qList .qcard').forEach(function (card) {
      var id = card.querySelector('.q-id').value.trim();
      var type = card.querySelector('.q-type').value;
      var ins = card.querySelector('.q-instructions').value;
      var cr = collectCriteriaFrom(card, type);
      if (!id) return;
      if (seen[id]) dups.push(id);
      seen[id] = true;
      var item = { type: type, instructions: ins };
      if (cr !== undefined && !(type === 'score' && (!cr || cr.length < 2))) item.criteria = cr;
      out.push({ id: id, q: item });
    });
    var warn = [];
    if (dups.length) warn.push('重复 id：' + dups.join(', ') + '（重复的只保留第一个）');
    document.querySelectorAll('#qList .qcard').forEach(function (card) {
      var id = card.querySelector('.q-id').value.trim();
      var type = card.querySelector('.q-type').value;
      if (!id) { warn.push('有一个问题没填 id'); return; }
      if (seen[id] !== true) return;
      if (type === 'score') {
        var lv = card.querySelectorAll('.c-row .c-desc').length;
        if (lv < 2) warn.push('「' + id + '」score 至少 2 档');
        if (lv > 10) warn.push('「' + id + '」score 最多 10 档');
      }
      if (type === 'choice' && card.querySelectorAll('.c-row').length === 0) warn.push('「' + id + '」choice 至少要有一个选项');
    });
    seen = null;
    var map = {}, used = {};
    out.forEach(function (it) { if (!used[it.id]) { used[it.id] = true; map[it.id] = it.q; } });
    return { map: map, warn: warn };
  }

  $('qAdd').addEventListener('click', function () {
    $('qList').appendChild(makeQCard({ id: 'new_question', type: 'noul', instructions: '', criteria: {} }));
    refreshPreview();
  });

  /* ---- 预览 ---- */
  function currentChannelCfg() {
    var key = $('chSelect').value;
    var ch = CHANNELS[key] || CHANNELS.custom;
    return {
      endpoint: $('endpointInput').value.trim() || ch.endpoint,
      model: $('modelInput').value.trim() || ch.model,
      apiKey: $('keyInput').value.trim()
    };
  }
  function refreshPreview() {
    var st = parseStateOrRaw(stateInput.value);
    var qs = readQuestions();
    $('qWarn').textContent = qs.warn.length ? '⚠ ' + qs.warn.join('；') : '';
    var body = { model: currentChannelCfg().model, state: st, questions: qs.map };
    $('previewBox').textContent = JSON.stringify(body, null, 2);
  }

  /* ---- 模板载入 ---- */
  $('tplSelect').addEventListener('change', function () {
    loadTemplate($('tplSelect').value);
  });
  function loadTemplate(name) {
    const lang = document.getElementById('langSelect').value;
    if (name === 'match3') { stateInput.value = JSON.stringify(SAMPLE_MATCH3, null, 2); renderQuestions(defaultQuestions(lang)); }
    else if (name === 'chess') { stateInput.value = JSON.stringify(SAMPLE_CHESS, null, 2); renderQuestions(defaultQuestions(lang)); }
    else if (name === 'axtree') { stateInput.value = JSON.stringify(SAMPLE_AXTREE, null, 2); renderQuestions(defaultQuestions(lang)); }
    else { stateInput.value = ''; renderQuestions([]); }
    setStatus($('stateStatus'), true, '模板已载入 ✓');
    refreshPreview();
  }
  document.getElementById('langSelect').addEventListener('change', function () {
    const lang = this.value;
    if ($('qList').children.length > 0) {
      renderQuestions(defaultQuestions(lang));
      refreshPreview();
    }
  });

  $('copyPreview').addEventListener('click', function () { copyText($('previewBox').textContent, this); });
  $('toRunner').addEventListener('click', function () {
    $('runState').value = stateInput.value;
    try { JSON.parse($('runState').value); setStatus($('runStateStatus'), true, 'state JSON 合法 ✓'); }
    catch (e) { setStatus($('runStateStatus'), false, 'JSON 不合法，将以纯文本发送'); }
    refreshRunnerSummary();
    document.querySelector('.tab-btn[data-tab="tab3"]').click();
  });

  /* ---------- ③ 试跑台 ---------- */
  var chSelect = $('chSelect');
  Object.keys(CHANNELS).forEach(function (k) {
    var o = el('option', null, CHANNELS[k].label); o.value = k; chSelect.appendChild(o);
  });
  chSelect.addEventListener('change', function () {
    var ch = CHANNELS[chSelect.value];
    if (ch && chSelect.value !== 'custom') { $('endpointInput').value = ch.endpoint; $('modelInput').value = ch.model; }
    else if (chSelect.value === 'custom') { $('modelInput').value = ch.model; }
    refreshPreview();
  });
  $('endpointInput').addEventListener('input', refreshPreview);
  $('modelInput').addEventListener('input', refreshPreview);
  $('endpointInput').addEventListener('input', function () { try { localStorage.setItem('jev_tool_endpoint', this.value); } catch (e) {} });
  $('modelInput').addEventListener('input', function () { try { localStorage.setItem('jev_tool_model', this.value); } catch (e) {} });
  $('keyInput').addEventListener('input', function () { try { localStorage.setItem('jev_tool_apikey', this.value); } catch (e) {} });

  $('runState').addEventListener('input', function () {
    try { JSON.parse($('runState').value); setStatus($('runStateStatus'), true, 'state JSON 合法 ✓'); }
    catch (e) { setStatus($('runStateStatus'), false, 'JSON 不合法，将以纯文本发送'); }
  });

  function refreshRunnerSummary() {
    var qs = readQuestions();
    $('qSummary').textContent = JSON.stringify(qs.map, null, 2);
  }

  var HISTORY_KEY = 'jev_tool_history';
  function loadHistory() {
    try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]'); } catch (e) { return []; }
  }
  function saveHistory(list) {
    try { localStorage.setItem(HISTORY_KEY, JSON.stringify(list.slice(-50))); }
    catch (e) { /* 存不下就只留在页面里，不静默丢数据：下方提示 */ }
  }

  function doSend(fakeKey) {
    var cfg = currentChannelCfg();
    if (fakeKey) cfg.apiKey = '';
    var st = parseStateOrRaw($('runState').value);
    var qs = readQuestions();
    var box = $('resultBox');
    if (!cfg.endpoint) { box.innerHTML = ''; box.appendChild(el('div', 'err-box', '没有 endpoint：请选择渠道或填写自定义 URL')); return; }
    if (Object.keys(qs.map).length === 0) { box.innerHTML = ''; box.appendChild(el('div', 'err-box', '没有可发送的问题：去「② 判定设计器」添加至少一个问题（问题 id 不能为空）')); return; }
    if (qs.warn.length) box.appendChild(el('p', 'hint', '⚠ 提交里带告警：' + qs.warn.join('；')));
    cfg.state = st; cfg.questions = qs.map;
    var sending = el('p', 'hint', fakeKey ? '假钥匙测试中……（空钥匙，预期 401 auth_missing）' : '发送中……');
    box.innerHTML = ''; box.appendChild(sending);
    var t0 = Date.now();
    sendJev(cfg, window.fetch).then(function (r) {
      renderResult(box, r, cfg, fakeKey, Date.now() - t0);
      var hist = loadHistory();
      hist.push({ ts: new Date().toISOString(), endpoint: cfg.endpoint, model: cfg.model, status: r.status, elapsedMs: r.elapsedMs, state: cfg.state, questions: cfg.questions, bodyText: r.bodyText, parsed: r.parsed });
      saveHistory(hist);
      refreshHistory(); refreshStats();
    });
  }

  function renderResult(box, r, cfg, fakeKey) {
    box.innerHTML = '';
    if (fakeKey) box.appendChild(el('p', 'hint', '假钥匙测试：预期看到 401 auth_missing 原样出现。'));
    var head = el('div', 'res-head');
    var b = el('span', 'badge ' + ((r.status >= 200 && r.status < 300) ? 'ok' : 'bad'), 'HTTP ' + r.status);
    head.appendChild(b);
    head.appendChild(document.createTextNode(' · 耗时 ' + r.elapsedMs + 'ms'));
    if (r.parsed && r.parsed.usage) head.appendChild(document.createTextNode(' · tokens 输入 ' + r.parsed.usage.input_tokens + ' / 输出 ' + r.parsed.usage.output_tokens));
    head.appendChild(document.createTextNode(' · model ' + (r.parsed && r.parsed.model ? r.parsed.model : '—')));
    box.appendChild(head);

    if (!(r.status >= 200 && r.status < 300)) {
      var eb = el('div', 'err-box', renderError(r.status, r.bodyText));
      box.appendChild(eb);
    }
    var det = el('details');
    var sum = el('summary', null, '原始返回体'); det.appendChild(sum);
    var pre = el('pre'); pre.textContent = r.bodyText || '（空）'; det.appendChild(pre);
    box.appendChild(det);

    if (r.parsed && r.parsed.answers) {
      Object.keys(r.parsed.answers).forEach(function (qid) {
        box.appendChild(renderAnswerCard(qid, r.parsed.answers[qid]));
      });
    } else if (r.status >= 200 && r.status < 300) {
      box.appendChild(el('p', 'hint', '返回 2xx 但没有 answers 字段——检查请求体是否符合 SystemOne 协议。'));
    }
  }

  function barRow(label, p, alt) {
    var row = el('div', 'bar-row');
    var lb = el('span', 'bar-label', label);
    var tr = el('div', 'bar-track');
    var fi = el('div', 'bar-fill' + (alt ? ' alt' : ''));
    fi.style.width = Math.max(0, Math.min(1, p)) * 100 + '%';
    var vv = el('span', 'bar-val', p.toFixed(3));
    tr.appendChild(fi); row.appendChild(lb); row.appendChild(tr); row.appendChild(vv);
    return row;
  }

  function renderAnswerCard(qid, a) {
    var card = el('div', 'ans-card');
    var t = el('div', 'a-title');
    t.appendChild(document.createTextNode(qid + ' '));
    t.appendChild(el('span', 'badge info', a.type));
    card.appendChild(t);
    if (a.type === 'noul') {
      var p = typeof a.noul === 'number' ? a.noul : 0;
      var kv = el('div', 'kv'); kv.textContent = '为「yes」的概率 p = ' + p.toFixed(3) + '；把握度 |2p−1| = ' + (Math.abs(2 * p - 1)).toFixed(3);
      card.appendChild(kv);
      card.appendChild(barRow('yes', p, false));
      card.appendChild(barRow('no', 1 - p, true));
    } else if (a.type === 'choice') {
      var kv1 = el('div', 'kv'); kv1.textContent = '判定 = ' + a.choice + '；confidence = ' + (typeof a.confidence === 'number' ? a.confidence.toFixed(3) : '—');
      card.appendChild(kv1);
      var probs = a.probabilities || {};
      var opts = Object.keys(probs).sort(function (x, y) { return probs[y] - probs[x]; });
      opts.forEach(function (k) {
        card.appendChild(barRow(k + (k === a.choice ? '  ← 判定' : ''), probs[k], k !== a.choice));
      });
    } else if (a.type === 'score') {
      var kv2 = el('div', 'kv'); kv2.textContent = '分值 = ' + a.score + '（可落在两档之间）；confidence = ' + (typeof a.confidence === 'number' ? a.confidence.toFixed(3) : '—');
      card.appendChild(kv2);
      var legend = a.legend || {};
      var probs2 = a.probabilities || {};
      var keys = Object.keys(probs2).sort(function (x, y) { return parseInt(x, 10) - parseInt(y, 10); });
      keys.forEach(function (k) {
        var lab = k + '档 ' + (legend[k] !== undefined ? legend[k] : '');
        card.appendChild(barRow(lab, probs2[k], parseInt(k, 10) !== Math.round(a.score)));
      });
    }
    return card;
  }

  $('btnSend').addEventListener('click', function () { doSend(false); });
  $('btnFakeKey').addEventListener('click', function () { doSend(true); });

  function refreshHistory() {
    var box = $('histBox'); box.innerHTML = '';
    var hist = loadHistory().slice().reverse();
    if (!hist.length) { box.appendChild(el('p', 'hint', '还没有历史记录。')); return; }
    hist.forEach(function (h) {
      var item = el('div', 'hist-item');
      var line = el('div');
      line.appendChild(el('span', 'badge ' + ((h.status >= 200 && h.status < 300) ? 'ok' : 'bad'), 'HTTP ' + h.status));
      line.appendChild(document.createTextNode(' ' + h.ts + ' · ' + h.elapsedMs + 'ms · ' + h.endpoint));
      item.appendChild(line);
      var ops = el('div');
      var view = el('button', 'mini', '详情'); view.type = 'button';
      var pre = el('pre', 'hist-pre'); pre.textContent = JSON.stringify({ state: h.state, answers: h.parsed && h.parsed.answers, bodyText: h.bodyText }, null, 2);
      view.addEventListener('click', function () {
        pre.style.display = (pre.style.display === 'block') ? 'none' : 'block';
      });
      var del = el('button', 'mini', '删除该条'); del.type = 'button';
      del.addEventListener('click', function () {
        var all = loadHistory();
        var idx = all.indexOf(h);
        if (idx >= 0) { all.splice(idx, 1); saveHistory(all); refreshHistory(); refreshStats(); }
      });
      ops.appendChild(view); ops.appendChild(del);
      item.appendChild(ops); item.appendChild(pre);
      box.appendChild(item);
    });
  }
  $('histClear').addEventListener('click', function () { saveHistory([]); refreshHistory(); refreshStats(); });
  $('histExport').addEventListener('click', function () {
    downloadFile('jev-history.json', JSON.stringify(loadHistory(), null, 2), 'application/json');
  });

  function refreshStats() {
    var box = $('statsBox'); box.innerHTML = '';
    var st = aggregateStats(loadHistory());
    var qids = Object.keys(st);
    if (!qids.length) { box.appendChild(el('p', 'hint', '还没有可统计的数据：发几次真请求后回来看。')); return; }
    var tb = el('table');
    var hr = el('tr');
    ['question', '类型', '样本数', '最小', '中位', '最大'].forEach(function (h) { hr.appendChild(el('th', null, h)); });
    tb.appendChild(hr);
    qids.forEach(function (qid) {
      var tr = el('tr');
      [qid, st[qid].type, String(st[qid].n), st[qid].min.toFixed(3), st[qid].median.toFixed(3), st[qid].max.toFixed(3)].forEach(function (v) {
        tr.appendChild(el('td', null, v));
      });
      tb.appendChild(tr);
    });
    box.appendChild(tb);
  }

  /* ---------- ④ 导出 ---------- */
  var thMap = null; // qid -> {high, mid, low}
  function defaultTh() { return { high: 0.9, mid: 0.5, low: 0.3 }; }

  function refreshThresholds() {
    var qs = readQuestions();
    if (!thMap) thMap = {};
    var box = $('thEditor'); box.innerHTML = '';
    Object.keys(qs.map).forEach(function (qid) {
      if (!thMap[qid]) thMap[qid] = defaultTh();
      var row = el('div', 'c-row');
      var lab = el('span', 'bar-label', qid + '（' + qs.map[qid].type + '）');
      var wrap = el('div'); wrap.style.display = 'flex'; wrap.style.gap = '6px'; wrap.style.flex = '1';
      ['high', 'mid', 'low'].forEach(function (k) {
        var inp = el('input'); inp.type = 'number'; inp.min = '0'; inp.max = '1'; inp.step = '0.05';
        inp.value = String(thMap[qid][k]);
        inp.setAttribute('data-qid', qid); inp.setAttribute('data-th', k);
        inp.addEventListener('input', function () { var v = parseFloat(inp.value); if (!isNaN(v)) thMap[qid][k] = v; refreshExport(); });
        wrap.appendChild(inp);
      });
      row.appendChild(lab); row.appendChild(wrap);
      box.appendChild(row);
    });
    var st = aggregateStats(loadHistory());
    var hints = [];
    Object.keys(qs.map).forEach(function (qid) {
      if (st[qid]) hints.push(qid + '：实测中位把握度 ' + st[qid].median.toFixed(3) + '（' + st[qid].n + ' 条历史）——照这个分布调阈值');
    });
    $('thHint').textContent = hints.length ? hints.join('；') : '（暂无实测数据：多打几帧真请求后回来看建议）';
  }

  function buildConfig() {
    var qs = readQuestions();
    var ch = currentChannelCfg();
    var th = {};
    Object.keys(qs.map).forEach(function (qid) { th[qid] = thMap && thMap[qid] ? thMap[qid] : defaultTh(); });
    return {
      channel: { endpoint: ch.endpoint, model: ch.model },
      questions: qs.map,
      thresholds: th,
      generated_at: new Date().toISOString()
    };
  }

  var lastCfg = null;
  function refreshExport() {
    var st = $('cfgStatus');
    try {
      var cfg = buildConfig();
      var rt = JSON.parse(JSON.stringify(cfg)); /* 导出前往返自检 */
      if (JSON.stringify(rt) !== JSON.stringify(cfg)) throw new Error('往返不一致');
      lastCfg = cfg;
      $('cfgBox').textContent = JSON.stringify(cfg, null, 2);
      setStatus(st, true, '自检通过 ✓（JSON 往返一致）');
    } catch (e) {
      lastCfg = null;
      setStatus(st, false, '自检失败，已禁止导出：' + e.message);
      $('cfgBox').textContent = '';
    }
    $('kotlinBox').textContent = buildKotlin(lastCfg);
  }

  $('cfgCopy').addEventListener('click', function () { copyText($('cfgBox').textContent, this); });
  $('ktCopy').addEventListener('click', function () { copyText($('kotlinBox').textContent, this); });
  $('cfgDownload').addEventListener('click', function () {
    if (!lastCfg) { window.alert('自检未通过，禁止导出'); return; }
    downloadFile('jev-config.json', JSON.stringify(lastCfg, null, 2), 'application/json');
  });
  $('ktDownload').addEventListener('click', function () {
    if (!lastCfg) { window.alert('自检未通过，禁止导出'); return; }
    downloadFile('JevHarness.kt', buildKotlin(lastCfg), 'text/x-kotlin');
  });

  function buildKotlin(cfg) {
    var endpoint = (cfg && cfg.channel && cfg.channel.endpoint) || 'https://ai-gateway.edgeone.link/v1/systemone';
    var model = (cfg && cfg.channel && cfg.channel.model) || '@makers/jev';
    var whitelist = 'announce_match';
    var thLines = '';
    if (cfg && cfg.questions) {
      var na = cfg.questions.next_action;
      if (na && na.criteria) whitelist = Object.keys(na.criteria).join(', ');
      var qs = Object.keys(cfg.questions);
      thLines = qs.map(function (qid) {
        var t = (cfg.thresholds && cfg.thresholds[qid]) || { high: 0.9, mid: 0.5, low: 0.3 };
        return '        "' + qid + '" to JevThresholds(high = ' + t.high + ', mid = ' + t.mid + ', low = ' + t.low + ')';
      }).join(',\n');
    }
    var wl = whitelist.split(', ').map(function (s) { return '"' + s + '"'; }).join(', ');
    return [
'// ===== 由「无障碍判定创作工具」导出：Jev 判定层 Harness 骨架 =====',
'// 铁律：Jev 判断，Harness 授权与执行；低置信绝不静默（必须 TTS 出声）。',
'// API 契约以 specs/jev-api-spec.md 为准（SystemOne 协议，不是 chat 协议）。',
'// 生成时间：' + ((cfg && cfg.generated_at) || ''),
'',
'data class JevThresholds(val high: Double, val mid: Double, val low: Double)',
'',
'sealed class JevAnswer {',
'    /** noul：为 yes 的概率 p（0..1），把握度 = |2p - 1| */',
'    data class Noul(val p: Double) : JevAnswer()',
'    data class Choice(val value: String, val probabilities: Map<String, Double>, val confidence: Double) : JevAnswer()',
'    data class Score(val score: Double, val legend: Map<String, String>, val probabilities: Map<String, Double>, val confidence: Double) : JevAnswer()',
'}',
'',
'data class JevUsage(val input_tokens: Int, val output_tokens: Int)',
'data class JevResponse(val model: String, val answers: Map<String, JevAnswer>, val usage: JevUsage?)',
'data class JevQuestion(val type: String, val instructions: String, val criteria: Any?)',
'',
'class JevApiError(val code: Int, val raw: String) : Exception("HTTP " + code + ": " + raw)',
'',
'object JevClient {',
'    private const val ENDPOINT = "' + endpoint + '"',
'    private const val MODEL = "' + model + '"',
'',
'    /**',
'     * 一帧一组原子问题，单次 POST 并行评估（加问题几乎不加延迟）。',
'     * 错误处理：429/529 → 指数退避重试（1s/2s/4s，最多 3 次）；401/422 → 原样上抛给上层展示，不许吞。',
'     */',
'    fun ask(apiKey: String, state: Any, questions: Map<String, JevQuestion>): JevResponse {',
'        val body = mapOf("model" to MODEL, "state" to state, "questions" to questions)',
'        var delayMs = 1000L',
'        repeat(3) {',
'            val conn = (java.net.URL(ENDPOINT).openConnection() as java.net.HttpURLConnection).apply {',
'                requestMethod = "POST"',
'                setRequestProperty("Content-Type", "application/json")',
'                setRequestProperty("Authorization", "Bearer " + apiKey)',
'                doOutput = true',
'                connectTimeout = 10_000',
'                readTimeout = 30_000',
'            }',
'            conn.outputStream.use { it.write(GSON.toJson(body).toByteArray()) }',
'            val code = conn.responseCode',
'            if (code == 429 || code == 529) {  // 限流/过载：指数退避后重试',
'                Thread.sleep(delayMs); delayMs *= 2; return@repeat',
'            }',
'            val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()',
'            if (code == 401 || code == 422) throw JevApiError(code, text)  // key 无效 / 请求不合规：原样上抛',
'            return GSON.fromJson(text, JevResponse::class.java)  // GSON 需为 JevAnswer 写类型适配器',
'        }',
'        throw JevApiError(429, "重试 3 次仍被限流（已做指数退避）")',
'    }',
'}',
'',
'/** 执行层：包着 AccessibilityService 的唯一出口。Jev 的判断必须经这里白名单授权才能落地。 */',
'class Harness(private val tts: (String) -> Unit, private val executor: (String) -> Boolean) {',
'',
'    private val thresholds = mapOf(',
thLines,
'    )',
'',
'    // 白名单：Jev 说"该按确认"不算数，只有这里列出的 next_action 才许触发 AccessibilityService',
'    private val whitelist = setOf(' + wl + ')',
'',
'    fun confidenceOf(a: JevAnswer): Double = when (a) {',
'        is JevAnswer.Noul   -> Math.abs(2 * a.p - 1)   // noul 没有 confidence，用 |2p-1|',
'        is JevAnswer.Choice -> a.confidence',
'        is JevAnswer.Score  -> a.confidence',
'    }',
'',
'    fun onFrame(state: Any, resp: JevResponse) {',
'        val na = resp.answers["next_action"] ?: return',
'        val c = confidenceOf(na)',
'        val th = thresholds["next_action"] ?: return',
'        when {',
'            c >= th.high -> {',
'                val act = (na as JevAnswer.Choice).value',
'                if (act in whitelist && executor(act)) {   // 白名单校验 → 执行 → 结果校验由 executor 内部复核画面变化',
'                    tts(announce(resp))                    // 播报按 announce_rank / danger_level 分级',
'                } else {',
'                    tts("这个动作不在授权范围内，已忽略。")',
'                }',
'            }',
'            c >= th.mid -> tts("我不太确定当前局面，建议让我重新扫描一遍再执行。")  // 中置信：先确认',
'            else        -> tts("我没看清当前局面，请让我重新扫描")                  // 低置信：绝不静默、绝不猜',
'        }',
'    }',
'',
'    private fun announce(resp: JevResponse): String {',
'        // TODO：按 announce_rank / danger_level / should_interrupt 组装播报文案与打断策略',
'        return "局面已更新"',
'    }',
'}'
    ].join('\n');
  }

  /* ---------- 小工具 ---------- */
  function copyText(text, btn) {
    var done = function () { if (btn) { var old = btn.textContent; btn.textContent = '已复制 ✓'; setTimeout(function () { btn.textContent = old; }, 1200); } };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text); done(); });
    } else { fallbackCopy(text); done(); }
  }
  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text; document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); } catch (e) {}
    document.body.removeChild(ta);
  }
  function downloadFile(name, text, mime) {
    var blob = new Blob([text], { type: mime + ';charset=utf-8' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = name;
    document.body.appendChild(a); a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); document.body.removeChild(a); }, 300);
  }

  /* ---------- 初始化 ---------- */
  try {
    var savedKey = localStorage.getItem('jev_tool_apikey'); if (savedKey) $('keyInput').value = savedKey;
    var savedEp = localStorage.getItem('jev_tool_endpoint'); if (savedEp) $('endpointInput').value = savedEp;
    var savedMd = localStorage.getItem('jev_tool_model'); if (savedMd) $('modelInput').value = savedMd;
  } catch (e) { /* localStorage 不可用也能用，只是不记住 */ }
  if (!$('endpointInput').value) $('endpointInput').value = CHANNELS.edgeone.endpoint;
  if (!$('modelInput').value) $('modelInput').value = CHANNELS.edgeone.model;
  loadTemplate('match3');
  refreshHistory();
  refreshStats();
}

/* 浏览器里才初始化 UI；node require 时只暴露纯函数做无头自检 */
if (typeof document !== 'undefined') { initUI(); }
if (typeof module !== 'undefined' && typeof module.exports !== 'undefined') { module.exports = { buildRequest: buildRequest, sendJev: sendJev, renderError: renderError }; }
