export type VoiceKind = 'feedback' | 'game' | 'idea';

const kindLabels: Record<VoiceKind, string> = {
  feedback: '使用体验',
  game: '游戏适配请求',
  idea: '新的想法',
};

export function createVoiceDraft(kind: VoiceKind, gameInput: string, messageInput: string) {
  const label = kindLabels[kind];
  const game = gameInput.trim();
  const message = messageInput.trim();
  if (!label) throw new Error('请选择反馈类型。');
  if (message.length < 8 || message.length > 700) throw new Error('请填写 8–700 字的心声或需求。');
  if (game.length > 60) throw new Error('游戏名称请控制在 60 字以内。');
  if (kind === 'game' && !game) throw new Error('请写下希望适配的游戏名称。');
  const title = `[${label}] ${game || '听野用户心声'}`;
  const body = `### 反馈类型\n${label}\n\n### 游戏\n${game || '未指定'}\n\n### 我的心声或需求\n${message}\n\n---\n通过听野官网整理，由反馈者确认发布。`;
  const url = new URL('https://github.com/ld0574/SenseField/issues/new');
  url.searchParams.set('title', title);
  url.searchParams.set('body', body);
  return { title, body, text: `${title}\n\n${body}`, url: url.toString() };
}
