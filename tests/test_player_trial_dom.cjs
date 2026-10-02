// Offline DOM tests only: no browser navigation or network resources.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const {JSDOM, VirtualConsole} = require('jsdom');

const html = fs.readFileSync(path.join(__dirname, '../validation/player-trial.html'), 'utf8');
function setup(t) {
  const downloads = [];
  const errors = [];
  const vc = new VirtualConsole();
  vc.on('jsdomError', error => errors.push(error.message));
  const dom = new JSDOM(html, {
    runScripts: 'dangerously', virtualConsole: vc,
    beforeParse(w) {
      w.confirm = () => true;
      w.print = () => {};
      Object.defineProperty(w, 'isSecureContext', {value: true});
      w.URL.createObjectURL = blob => { downloads.push({blob}); return 'blob:offline-test'; };
      w.URL.revokeObjectURL = () => {};
      w.HTMLAnchorElement.prototype.click = function() { downloads.at(-1).name = this.download; };
      Object.defineProperty(w.navigator, 'clipboard', {value: {writeText: async () => {}}});
      w.fetch = () => { throw Error('Network prohibited in offline test'); };
      w.XMLHttpRequest = class { constructor() { throw Error('Network prohibited'); } };
      w.WebSocket = class { constructor() { throw Error('Network prohibited'); } };
    },
  });
  const w = dom.window, d = w.document;
  const click = id => d.getElementById(id).click();
  const fill = (selector, value) => {
    const field = d.querySelector(selector);
    field.value = value;
    field.dispatchEvent(new w.Event('input', {bubbles: true}));
  };
  const unsaved = () => {
    const event = new w.Event('beforeunload', {cancelable: true});
    w.dispatchEvent(event);
    return event.defaultPrevented;
  };
  const read = entry => new Promise((resolve, reject) => {
    const reader = new w.FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = reject;
    reader.readAsText(entry.blob);
  });
  const importJson = async value => {
    const text = JSON.stringify(value);
    const input = d.getElementById('import-json-file');
    Object.defineProperty(input, 'files', {configurable: true,
      value: [{size: Buffer.byteLength(text), text: async () => text}]});
    input.dispatchEvent(new w.Event('change', {bubbles: true}));
    await new Promise(resolve => setImmediate(resolve));
  };
  t.after(() => { assert.deepEqual(errors, []); w.close(); });
  return {w, d, click, fill, unsaved, downloads, read, importJson};
}

test('cards retain unique accessible labels after removal and re-addition', t => {
  const q = setup(t);
  q.click('add-event'); q.click('add-event');
  q.d.querySelector('[data-remove-event]').click();
  q.click('add-event'); q.click('add-feedback'); q.click('add-latency');
  const ids = [...q.d.querySelectorAll('[id]')].map(field => field.id);
  assert.equal(ids.length, new Set(ids).size);
  for (const label of q.d.querySelectorAll('label[for]')) assert.ok(q.d.getElementById(label.htmlFor));
  assert.equal(q.d.activeElement.dataset.latencyKey, 'event_id');
});

test('JSON and CSV exports independently preserve unsaved data protection', t => {
  const q = setup(t);
  q.fill('#session-code', 'P01'); q.click('add-latency');
  assert.ok(q.unsaved());
  q.click('export-json');
  assert.ok(q.unsaved(), 'unsaved CSV must remain protected');
  q.click('export-csv');
  assert.equal(q.unsaved(), false);
  q.fill('#change-request', '希望更清晰');
  q.click('export-csv');
  assert.ok(q.unsaved(), 'saving CSV cannot clear unsaved feedback');
  q.click('export-json');
  assert.equal(q.unsaved(), false);
});

test('copying summary does not mark full feedback as saved', async t => {
  const q = setup(t);
  q.fill('#change-request', '希望提示更容易理解');
  q.click('copy-text'); await new Promise(resolve => setImmediate(resolve));
  assert.ok(q.unsaved());
});

test('CSV rejects partial rows and missing source notes without losing dirty state', t => {
  const q = setup(t); q.click('add-latency');
  q.fill('[data-latency-key="event_id"]', 'E01');
  q.click('export-csv');
  assert.equal(q.downloads.length, 0);
  assert.match(q.d.getElementById('status').textContent, /CSV 未导出/);
  assert.ok(q.unsaved());
  for (const [key, value] of Object.entries({cue_id: 'session:1', kind: 'near_zone', evidence_ms: '100', audio_ms: '500'}))
    q.fill(`[data-latency-key="${key}"]`, value);
  q.click('export-csv');
  assert.equal(q.downloads.length, 0);
  assert.match(q.d.getElementById('status').textContent, /source_note/);
});

test('CSV preserves cue IDs and neutralizes formula-like free text', async t => {
  const q = setup(t); q.click('add-latency');
  const values = {event_id: 'E01', cue_id: 'session:1', kind: 'near_zone', evidence_ms: '100', audio_ms: '500', source_note: ' =1+1'};
  for (const [key, value] of Object.entries(values)) q.fill(`[data-latency-key="${key}"]`, value);
  q.click('export-csv');
  const csv = await q.read(q.downloads[0]);
  assert.ok(csv.includes('"session:1"'));
  assert.ok(csv.includes('"\' =1+1"'));
  assert.equal(q.unsaved(), false);
});

test('JSON round trip restores feedback safely and leaves latency data intact', async t => {
  const q = setup(t);
  q.fill('#session-code', 'P01'); q.fill('#app-version', '0.3.6');
  q.click('add-event'); q.fill('[data-event-key="cue_id"]', 'session:1');
  q.click('add-feedback'); q.fill('[data-feedback-key="feedback_theme"]', '<img src=x onerror=alert(1)>');
  q.click('export-json');
  const exported = JSON.parse(await q.read(q.downloads[0]));
  q.click('add-latency'); q.fill('[data-latency-key="event_id"]', 'E02');
  q.fill('#session-code', 'P02');
  await q.importJson(exported);
  assert.equal(q.d.getElementById('session-code').value, 'P01');
  assert.equal(q.d.querySelector('[data-feedback-key="feedback_theme"]').value, '<img src=x onerror=alert(1)>');
  assert.equal(q.d.querySelectorAll('img').length, 0);
  assert.equal(q.d.querySelector('[data-latency-key="event_id"]').value, 'E02');
  assert.ok(q.unsaved());
  q.click('export-json');
  assert.ok(q.unsaved(), 'main JSON save cannot clear latency changes');
});

test('invalid imported schema cannot overwrite existing records', async t => {
  const q = setup(t); q.fill('#session-code', 'P01');
  await q.importJson({schema_version: 'unknown', session: {session_code: 'bad'}});
  assert.equal(q.d.getElementById('session-code').value, 'P01');
  assert.match(q.d.getElementById('status').textContent, /导入失败/);
});
