import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createVoiceDraft } from '../src/feedback.ts';

test('Chinese feedback and reserved URL characters survive the GitHub handoff', () => {
  const message = '希望播报棋盘边缘的目标。\n需要保留 &、?、# 和换行。';
  const draft = createVoiceDraft('game', '开心消消乐 & 新游戏?', message);
  const url = new URL(draft.url);
  assert.equal(url.origin, 'https://github.com');
  assert.equal(url.pathname, '/ld0574/SenseField/issues/new');
  assert.equal(url.searchParams.get('title'), '[游戏适配请求] 开心消消乐 & 新游戏?');
  assert.equal(url.searchParams.get('body'), draft.body);
  assert.ok(draft.body.includes(message));
  assert.deepEqual([...url.searchParams.keys()], ['title', 'body']);
});

test('game requests require a game name, while general feedback does not', () => {
  assert.throws(() => createVoiceDraft('game', '   ', '希望增加更多不同的游戏。'), /游戏名称/);
  assert.equal(createVoiceDraft('feedback', '', '声音提示帮我减少了寻找。').title, '[使用体验] 听野用户心声');
});

test('whitespace cannot satisfy the minimum feedback length', () => {
  assert.throws(() => createVoiceDraft('feedback', '', '        '), /8–700/);
  assert.throws(() => createVoiceDraft('idea', '', '想法'), /8–700/);
});

test('large messages are rejected instead of silently truncated in the public URL', () => {
  assert.throws(() => createVoiceDraft('feedback', '', '字'.repeat(701)), /8–700/);
  assert.throws(() => createVoiceDraft('game', '名'.repeat(61), '希望得到方向声音辅助。'), /60/);
  const draft = createVoiceDraft('game', '名'.repeat(60), '字'.repeat(700));
  assert.ok(draft.url.length < 8192);
});

test('only the explicit category, game and message enter a feedback draft', () => {
  const draft = createVoiceDraft('idea', '  游戏  ', '  想试着把提醒频率再减少。  ');
  assert.equal(draft.title, '[新的想法] 游戏');
  assert.ok(draft.body.includes('想试着把提醒频率再减少。'));
  assert.ok(!/userAgent|screenWidth|localhost|token|cookie/.test(draft.body));
});
