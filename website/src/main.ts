import './style.css';
import { createIcons, ArrowRight, ArrowDown, ArrowUp, ArrowLeftRight, MoveUpRight, Pause, Play, Menu, X, ScanEye, VolumeX, Volume2, AudioLines, Speech, Vibrate, Headphones, Radio, Plus, Navigation, Mic, Smartphone, SlidersHorizontal, Hand, Download, Info, Asterisk, Circle, Diamond, Triangle, Flower2, Square, Expand, Copy, MessageCircle, ChevronDown } from 'lucide';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { createVoiceDraft, type VoiceKind } from './feedback';

const icons = { ArrowRight, ArrowDown, ArrowUp, ArrowLeftRight, MoveUpRight, Pause, Play, Menu, X, ScanEye, VolumeX, Volume2, AudioLines, Speech, Vibrate, Headphones, Radio, Plus, Navigation, Mic, Smartphone, SlidersHorizontal, Hand, Download, Info, Asterisk, Circle, Diamond, Triangle, Flower2, Square, Expand, Copy, MessageCircle, ChevronDown };
const renderIcons = () => createIcons({ icons, attrs: { 'aria-hidden': 'true' } });
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
let motionPaused = reducedMotion.matches;
try { motionPaused = reducedMotion.matches || localStorage.getItem('sensefield-motion') === 'paused'; } catch { /* Motion still follows the OS preference. */ }

// Preview symbols are Lucide icons, like every other interface icon.
const board = document.querySelector('.match-board')!;
const cellIcons = ['circle', 'diamond', 'triangle', 'flower-2', 'square'];
for (let i = 0; i < 49; i++) {
  const cell = document.createElement('span');
  cell.className = `match-cell${i === 17 || i === 18 ? ' highlight' : ''}`;
  cell.innerHTML = `<i data-lucide="${cellIcons[(i * 7 + Math.floor(i / 7)) % cellIcons.length]}"></i>`;
  board.append(cell);
}
const wave = document.querySelector('.assistant-wave')!;
for (let i = 0; i < 27; i++) {
  const bar = document.createElement('span');
  bar.style.setProperty('--bar-height', `${5 + Math.sin(i * 1.7) ** 2 * 23}px`);
  bar.style.setProperty('--bar-delay', `${-i * 0.1}s`);
  wave.append(bar);
}
renderIcons();

// Navigation stays native, including fragment links and keyboard operation.
const menuButton = document.querySelector<HTMLButtonElement>('.menu-button')!;
const mobileNavigation = document.querySelector<HTMLElement>('.mobile-nav')!;
function setMenu(open: boolean) {
  mobileNavigation.hidden = !open;
  menuButton.setAttribute('aria-expanded', String(open));
  menuButton.setAttribute('aria-label', open ? '关闭导航菜单' : '打开导航菜单');
  menuButton.innerHTML = `<i data-lucide="${open ? 'x' : 'menu'}"></i>`;
  renderIcons();
}
menuButton.addEventListener('click', () => setMenu(mobileNavigation.hidden));
mobileNavigation.querySelectorAll('a').forEach((link) => link.addEventListener('click', () => setMenu(false)));
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && !mobileNavigation.hidden) { setMenu(false); menuButton.focus(); }
});
document.addEventListener('click', (event) => {
  // The clicked SVG is replaced when the menu opens; use the original event path.
  if (!mobileNavigation.hidden && !event.composedPath().includes(document.querySelector('.site-header')!)) setMenu(false);
});
window.matchMedia('(min-width: 1001px)').addEventListener('change', (event) => { if (event.matches) setMenu(false); });

// Visitors review and publish in GitHub; the static site never claims to send a message itself.
const voiceForm = document.querySelector<HTMLFormElement>('#voices-form')!;
const voiceKind = document.querySelector<HTMLSelectElement>('#voice-kind')!;
const voiceGame = document.querySelector<HTMLInputElement>('#voice-game')!;
const voiceMessage = document.querySelector<HTMLTextAreaElement>('#voice-message')!;
const voiceStatus = document.querySelector<HTMLElement>('#voice-status')!;
const draftLink = document.querySelector<HTMLAnchorElement>('#voice-draft-link')!;
voiceKind.addEventListener('change', () => {
  const gameRequest = voiceKind.value === 'game';
  voiceGame.required = gameRequest;
  document.querySelector('#voice-game-label')!.innerHTML = `游戏名称 <span>（${gameRequest ? '必填' : '选填'}）</span>`;
});
function prepareVoice() {
  if (!voiceForm.reportValidity()) return null;
  try {
    const draft = createVoiceDraft(voiceKind.value as VoiceKind, voiceGame.value, voiceMessage.value);
    draftLink.href = draft.url;
    draftLink.hidden = false;
    return draft;
  } catch (error) {
    voiceStatus.textContent = (error as Error).message;
    return null;
  }
}
voiceForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const draft = prepareVoice();
  if (!draft) return;
  window.open(draft.url, '_blank', 'noopener,noreferrer');
  voiceStatus.textContent = '反馈已整理。请在新打开的 GitHub 页面检查内容，再确认发布；如果页面没有打开，可使用下方链接。';
});
document.querySelector('#copy-voice')!.addEventListener('click', async () => {
  const draft = prepareVoice();
  if (!draft) return;
  try {
    await navigator.clipboard.writeText(draft.text);
    voiceStatus.textContent = '反馈已复制。你可以保存这段内容，或打开下方链接前往 GitHub 确认发布。';
  } catch {
    voiceStatus.textContent = '浏览器未允许复制。可打开下方链接，在 GitHub 检查并提交已整理的反馈。';
  }
});

// Feature disclosures work independently of the visual illustrations.
const featureItems = [...document.querySelectorAll<HTMLElement>('.feature-item')];
featureItems.forEach((item) => {
  const trigger = item.querySelector<HTMLButtonElement>('.feature-trigger')!;
  trigger.addEventListener('click', () => {
    const wasOpen = trigger.getAttribute('aria-expanded') === 'true';
    featureItems.forEach((feature) => {
      const open = feature === item && !wasOpen;
      feature.classList.toggle('is-active', open);
      feature.querySelector('button')!.setAttribute('aria-expanded', String(open));
      feature.querySelector<HTMLElement>('.feature-body')!.hidden = !open;
    });
    if (!wasOpen) {
      document.querySelectorAll<HTMLElement>('[data-preview]').forEach((preview) => {
        const active = preview.dataset.preview === item.dataset.feature;
        preview.hidden = !active;
        if (active && !motionPaused) gsap.fromTo(preview, { opacity: 0, y: 15 }, { opacity: 1, y: 0, duration: 0.5, ease: 'power2.out' });
      });
      document.querySelector('#visual-index')!.textContent = `SENSEFIELD / 0${featureItems.indexOf(item) + 1}`;
    }
    ScrollTrigger.refresh();
  });
});

// Audio requires explicit opt-in. One short cue plays at a time.
type Direction = { name: string; pan: number; angle: number; frequency: number };
const directions: Record<string, Direction> = {
  nw: { name: '左前方', pan: -0.72, angle: -135, frequency: 640 },
  n: { name: '前方', pan: 0, angle: -90, frequency: 740 },
  ne: { name: '右前方', pan: 0.72, angle: -45, frequency: 640 },
  w: { name: '左侧', pan: -1, angle: -180, frequency: 520 },
  e: { name: '右侧', pan: 1, angle: 0, frequency: 520 },
  sw: { name: '左后方', pan: -0.72, angle: 135, frequency: 400 },
  s: { name: '后方', pan: 0, angle: 90, frequency: 340 },
  se: { name: '右后方', pan: 0.72, angle: 45, frequency: 400 },
};
const audioToggle = document.querySelector<HTMLButtonElement>('#audio-toggle')!;
const audioLabel = document.querySelector('#audio-toggle-label')!;
const audioNote = document.querySelector('#audio-note')!;
const feedback = document.querySelector('#direction-feedback')!;
const radar = document.querySelector<HTMLElement>('.radar')!;
const radarPath = document.querySelector<HTMLElement>('.radar-path')!;
let audio: AudioContext | null = null;
let soundEnabled = false;
let oscillator: OscillatorNode | null = null;
let speechTimer: ReturnType<typeof setTimeout> | null = null;
let cueToken = 0;

function stopCue() {
  cueToken++;
  if (speechTimer) { clearTimeout(speechTimer); speechTimer = null; }
  if (oscillator) { try { oscillator.stop(); } catch { /* It may already be stopped. */ } oscillator = null; }
  if ('speechSynthesis' in window) speechSynthesis.cancel();
}
async function playCue(direction: Direction) {
  if (!audio || !soundEnabled) return;
  stopCue();
  const token = cueToken;
  await audio.resume();
  if (!soundEnabled || token !== cueToken) return;
  const osc = audio.createOscillator();
  const gain = audio.createGain();
  const panner = audio.createStereoPanner();
  osc.type = 'sine';
  osc.frequency.setValueAtTime(direction.frequency, audio.currentTime);
  osc.frequency.exponentialRampToValueAtTime(direction.frequency * 0.78, audio.currentTime + 0.28);
  panner.pan.value = direction.pan;
  gain.gain.setValueAtTime(0, audio.currentTime);
  gain.gain.linearRampToValueAtTime(0.14, audio.currentTime + 0.035);
  gain.gain.exponentialRampToValueAtTime(0.001, audio.currentTime + 0.32);
  osc.connect(gain).connect(panner).connect(audio.destination);
  oscillator = osc;
  osc.start();
  osc.stop(audio.currentTime + 0.36);
  osc.onended = () => { osc.disconnect(); gain.disconnect(); panner.disconnect(); if (oscillator === osc) oscillator = null; };
  if ('speechSynthesis' in window) {
    speechTimer = setTimeout(() => {
      if (!soundEnabled || token !== cueToken) return;
      const utterance = new SpeechSynthesisUtterance(`${direction.name}，出现新的动向。`);
      utterance.lang = 'zh-CN';
      utterance.volume = 0.65;
      utterance.rate = 1.1;
      const voice = speechSynthesis.getVoices().find((candidate) => candidate.lang.startsWith('zh'));
      if (voice) utterance.voice = voice;
      speechSynthesis.speak(utterance);
    }, 420);
  }
}
function updateAudioUI() {
  audioToggle.setAttribute('aria-pressed', String(soundEnabled));
  audioLabel.textContent = soundEnabled ? '声音已开启 · 点击关闭' : '开启声音体验';
  audioToggle.querySelector('.audio-icon')!.innerHTML = `<i data-lucide="${soundEnabled ? 'volume-2' : 'volume-x'}"></i>`;
  audioNote.textContent = soundEnabled ? '现在选择右侧方位；左右声道与音调会随方向变化。' : '声音仅在你开启后播放，建议佩戴耳机。';
  renderIcons();
}
audioToggle.addEventListener('click', async () => {
  if (soundEnabled) { soundEnabled = false; stopCue(); updateAudioUI(); return; }
  try {
    audio ??= new AudioContext();
    await audio.resume();
    soundEnabled = true;
    updateAudioUI();
    const selected = document.querySelector<HTMLElement>('.direction-point[aria-pressed="true"]');
    if (selected) await playCue(directions[selected.dataset.direction!]);
  } catch {
    soundEnabled = false;
    audioNote.textContent = '当前浏览器无法播放声音，你仍可以选择方位查看视觉提示。';
  }
});
document.querySelectorAll<HTMLButtonElement>('.direction-point').forEach((point) => {
  point.addEventListener('click', () => {
    const direction = directions[point.dataset.direction!];
    document.querySelectorAll('.direction-point').forEach((node) => node.setAttribute('aria-pressed', String(node === point)));
    radar.classList.add('has-signal');
    radarPath.style.transform = `rotate(${direction.angle}deg)`;
    feedback.textContent = `${direction.name}，出现新的动向。`;
    document.querySelector('.radar-online')!.innerHTML = '<span class="status-dot"></span>感知已抵达';
    if (soundEnabled) void playCue(direction).catch(() => { audioNote.textContent = '声音播放受限，可以继续查看视觉提示。'; });
  });
});
document.addEventListener('visibilitychange', () => { if (document.hidden) stopCue(); });
const labVisibility = new IntersectionObserver(([entry]) => { if (!entry.isIntersecting) stopCue(); }, { threshold: 0 });
labVisibility.observe(document.querySelector('#lab')!);

// Prefer native scroll physics; animations accompany, never hijack, scrolling.
gsap.registerPlugin(ScrollTrigger);
const motionButton = document.querySelector<HTMLButtonElement>('.motion-button')!;
let revealAnimations: gsap.core.Tween[] = [];
let heroAnimation: gsap.core.Timeline | null = null;
function updateMotionUI() {
  document.body.classList.toggle('motion-paused', motionPaused);
  document.body.classList.toggle('motion-enabled', !motionPaused);
  motionButton.setAttribute('aria-pressed', String(motionPaused));
  motionButton.setAttribute('aria-label', motionPaused ? '开启页面动效' : '暂停页面动效');
  motionButton.title = motionPaused ? '开启页面动效' : '暂停页面动效';
  motionButton.innerHTML = `<i data-lucide="${motionPaused ? 'play' : 'pause'}"></i>`;
  const fieldButton = document.querySelector<HTMLButtonElement>('.field-interact');
  if (fieldButton) { fieldButton.disabled = motionPaused; fieldButton.title = motionPaused ? '动效已暂停，可在页首开启' : '释放粒子，感受感知场的扩散与重聚'; }
  renderIcons();
}
function setupReveals() {
  if (motionPaused) return;
  revealAnimations = [...document.querySelectorAll<HTMLElement>('.reveal')].map((element) => gsap.fromTo(element, { y: 28, opacity: 0 }, {
    y: 0, opacity: 1, duration: 0.85, ease: 'power3.out',
    scrollTrigger: { trigger: element, start: 'top 92%', once: true },
  }));
  heroAnimation = gsap.timeline({ defaults: { ease: 'power3.out' } })
    .from('.hero-topline', { opacity: 0, duration: 0.8 }, 0)
    .from('.hero-line', { y: 38, opacity: 0, duration: 1, stagger: 0.13 }, 0.1)
    .from('.hero-english', { y: 20, opacity: 0, duration: 0.85 }, 0.45)
    .from('.hero-description, .hero-links', { y: 15, opacity: 0, duration: 0.8, stagger: 0.12 }, 0.6);
}
function stopAnimations() {
  revealAnimations.forEach((tween) => { tween.scrollTrigger?.kill(); tween.kill(); });
  revealAnimations = [];
  heroAnimation?.progress(1).kill();
  gsap.set('.reveal, .hero-topline, .hero-line, .hero-english, .hero-description, .hero-links', { clearProps: 'transform,opacity' });
}
updateMotionUI();
setupReveals();
motionButton.addEventListener('click', () => {
  motionPaused = !motionPaused;
  if (motionPaused) stopAnimations();
  try { localStorage.setItem('sensefield-motion', motionPaused ? 'paused' : 'enabled'); } catch { /* Optional preference persistence. */ }
  updateMotionUI();
});
reducedMotion.addEventListener('change', (event) => {
  motionPaused = event.matches;
  if (motionPaused) stopAnimations();
  updateMotionUI();
});

let ticking = false;
const progress = document.querySelector<HTMLElement>('.scroll-progress')!;
function updateProgress() {
  const distance = document.documentElement.scrollHeight - window.innerHeight;
  progress.style.transform = `scaleX(${distance > 0 ? window.scrollY / distance : 0})`;
  ticking = false;
}
window.addEventListener('scroll', () => { if (!ticking) { ticking = true; requestAnimationFrame(updateProgress); } }, { passive: true });
updateProgress();

document.querySelectorAll<HTMLElement>('.magnetic').forEach((button) => {
  button.addEventListener('pointermove', (event) => {
    if (motionPaused || event.pointerType !== 'mouse') return;
    const bounds = button.getBoundingClientRect();
    gsap.to(button, { x: (event.clientX - bounds.left - bounds.width / 2) * 0.1, y: (event.clientY - bounds.top - bounds.height / 2) * 0.15, duration: 0.5, ease: 'power3.out' });
  });
  button.addEventListener('pointerleave', () => gsap.to(button, { x: 0, y: 0, duration: motionPaused ? 0 : 0.7, ease: 'elastic.out(1, 0.5)' }));
});

// The renderer is a separate chunk. Essential content works even if it fails.
void import('./field').then(({ mountField }) => {
  const dispose = mountField(document.querySelector<HTMLCanvasElement>('#field-canvas')!, () => motionPaused);
  window.addEventListener('pagehide', (event: PageTransitionEvent) => {
    stopCue();
    if (event.persisted) void audio?.suspend();
    else { dispose?.(); void audio?.close(); }
  });
}).catch(() => { /* Keep the static sculpture visible. */ });

window.addEventListener('load', () => { ScrollTrigger.refresh(); updateProgress(); });
