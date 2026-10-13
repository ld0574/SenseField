export type Language = 'zh' | 'en';

const messages = {
  pageTitle: ['听野 — 视野有限，感知无界。', 'SenseField — Play beyond sight.'],
  description: ['听野，为视野狭窄和低视力玩家而设计。把游戏中难以持续观察的信息，转成声音、语音与触觉。安卓 10 及以上，免费开源。', 'SenseField is an open-source Android companion for players with low vision. Explore game information through sound, speech and touch. Android 10 or later.'],
  openMenu: ['打开导航菜单', 'Open navigation'],
  closeMenu: ['关闭导航菜单', 'Close navigation'],
  pauseMotion: ['暂停页面动效', 'Pause animations'],
  playMotion: ['开启页面动效', 'Resume animations'],
  audioOn: ['声音已开启 · 点击关闭', 'Sound is on · Turn off'],
  audioOff: ['开启声音体验', 'Try spatial sound'],
  audioHintOn: ['现在选择右侧方位；左右声道与音调会随方向变化。', 'Choose a direction. Stereo position and pitch change with each direction.'],
  audioHintOff: ['声音仅在你开启后播放，建议佩戴耳机。', 'Sound plays only after you turn it on. Headphones are recommended.'],
  audioError: ['当前浏览器无法播放声音，你仍可以选择方位查看视觉提示。', 'Audio is unavailable in this browser. You can still explore the visual directions.'],
  audioLimited: ['声音播放受限，可以继续查看视觉提示。', 'Audio playback is restricted. You can still use the visual cues.'],
  directionHint: ['选择方位，感受声音的方向。', 'Choose a direction to explore spatial sound.'],
  directionCue: ['{direction}，出现新的动向。', 'New activity {direction}.'],
  radarWaiting: ['等待你的探索', 'Ready to explore'],
  radarActive: ['感知已抵达', 'A cue has arrived'],
  gameLabel: ['游戏名称', 'Game name'],
  required: ['（必填）', '(required)'],
  optional: ['（选填）', '(optional)'],
  draftReady: ['反馈已整理。请在新打开的 GitHub 页面检查内容，再确认发布；如果页面没有打开，可使用下方链接。', 'Your draft is ready. Review and publish it on GitHub. If the new page did not open, use the link below.'],
  draftCopied: ['反馈已复制。你可以保存这段内容，或打开下方链接前往 GitHub 确认发布。', 'Your feedback has been copied. Save it or use the link below to review and publish it on GitHub.'],
  copyBlocked: ['浏览器未允许复制。可打开下方链接，在 GitHub 检查并提交已整理的反馈。', 'Clipboard access is unavailable. Use the link below to review and publish your draft on GitHub.'],
  releaseLoading: ['正在读取最新版本…', 'Checking the latest release…'],
  releaseVersion: ['最新版本 {version}', 'Latest release {version}'],
  releaseSize: ['约 {size} 兆字节', 'About {size} MB'],
  releaseError: ['暂时无法读取版本信息，可前往发行页面下载。', 'Release information is temporarily unavailable. Download from the releases page.'],
  releaseNotes: ['本次更新', 'Release notes · Chinese'],
} as const;

export type MessageKey = keyof typeof messages;
let language: Language = 'zh';
try {
  const requested = new URLSearchParams(location.search).get('lang');
  const saved = localStorage.getItem('sensefield-language');
  language = requested === 'en' || (requested !== 'zh' && saved === 'en') ? 'en' : 'zh';
} catch { /* Chinese remains the default when preference storage is unavailable. */ }

export const getLanguage = () => language;
export function t(key: MessageKey, values: Record<string, string> = {}) {
  let result: string = messages[key][language === 'en' ? 1 : 0];
  for (const [name, value] of Object.entries(values)) result = result.replaceAll(`{${name}}`, value);
  return result;
}

const directionLabels = {
  nw: ['左前方', 'ahead to your left'], n: ['前方', 'ahead'], ne: ['右前方', 'ahead to your right'],
  w: ['左侧', 'to your left'], e: ['右侧', 'to your right'],
  sw: ['左后方', 'behind to your left'], s: ['后方', 'behind'], se: ['右后方', 'behind to your right'],
} as const;
export type DirectionKey = keyof typeof directionLabels;
export const directionName = (key: DirectionKey) => directionLabels[key][language === 'en' ? 1 : 0];

/** Translation targets contain only text, so switching never replaces controls or icons. */
export function initLanguage(onChange: () => void) {
  const textTargets = [...document.querySelectorAll<HTMLElement>('[data-en]')].map((node) => ({ node, zh: node.textContent ?? '', en: node.dataset.en! }));
  const attributeTargets = [
    ['aria-label', 'data-en-label'], ['title', 'data-en-title'], ['placeholder', 'data-en-placeholder'], ['alt', 'data-en-alt'],
  ].flatMap(([attribute, translated]) => [...document.querySelectorAll<HTMLElement>(`[${translated}]`)].map((node) => ({
    node, attribute, zh: node.getAttribute(attribute) ?? '', en: node.getAttribute(translated)!,
  })));
  const button = document.querySelector<HTMLButtonElement>('.language-toggle')!;
  function apply() {
    document.documentElement.lang = language === 'en' ? 'en' : 'zh-CN';
    document.documentElement.dataset.language = language;
    textTargets.forEach(({ node, zh, en }) => { node.textContent = language === 'en' ? en : zh; });
    attributeTargets.forEach(({ node, attribute, zh, en }) => node.setAttribute(attribute, language === 'en' ? en : zh));
    button.querySelector('span')!.textContent = language === 'en' ? '中文' : 'EN';
    button.setAttribute('aria-label', language === 'en' ? 'Switch to Chinese' : '切换到英文');
    button.title = language === 'en' ? '切换到中文' : '切换到英文';
    document.title = t('pageTitle');
    document.querySelector('meta[name="description"]')?.setAttribute('content', t('description'));
    document.querySelector('meta[property="og:title"]')?.setAttribute('content', t('pageTitle'));
    document.querySelector('meta[property="og:locale"]')?.setAttribute('content', language === 'en' ? 'en_US' : 'zh_CN');
    onChange();
  }
  button.addEventListener('click', () => {
    language = language === 'en' ? 'zh' : 'en';
    try { localStorage.setItem('sensefield-language', language); } catch { /* The switch still works for this visit. */ }
    apply();
  });
  apply();
}
