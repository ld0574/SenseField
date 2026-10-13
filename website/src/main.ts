import './style.css';
import { createIcons, ArrowRight, ArrowDown, ArrowUp, ArrowLeftRight, MoveUpRight, Pause, Play, Menu, X, ScanEye, VolumeX, Volume2, AudioLines, Speech, Vibrate, Headphones, Radio, Navigation, Mic, Smartphone, SlidersHorizontal, Hand, Download, Info, Circle, Diamond, Triangle, Flower2, Square, Copy, MessageCircle, ChevronDown, Languages } from 'lucide';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { createVoiceDraft, type VoiceKind } from './feedback';
import { initLanguage, getLanguage, t, directionName, type DirectionKey, type MessageKey } from './i18n';
import { loadRelease, type Release } from './release';

const icons = { ArrowRight, ArrowDown, ArrowUp, ArrowLeftRight, MoveUpRight, Pause, Play, Menu, X, ScanEye, VolumeX, Volume2, AudioLines, Speech, Vibrate, Headphones, Radio, Navigation, Mic, Smartphone, SlidersHorizontal, Hand, Download, Info, Circle, Diamond, Triangle, Flower2, Square, Copy, MessageCircle, ChevronDown, Languages };
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
  menuButton.setAttribute('aria-label', t(open ? 'closeMenu' : 'openMenu'));
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
window.matchMedia('(min-width: 1101px)').addEventListener('change', (event) => { if (event.matches) setMenu(false); });

// Visitors review and publish in GitHub; the static site never claims to send a message itself.
const voiceForm = document.querySelector<HTMLFormElement>('#voices-form')!;
const voiceKind = document.querySelector<HTMLSelectElement>('#voice-kind')!;
const voiceGame = document.querySelector<HTMLInputElement>('#voice-game')!;
const voiceMessage = document.querySelector<HTMLTextAreaElement>('#voice-message')!;
const voiceStatus = document.querySelector<HTMLElement>('#voice-status')!;
const draftLink = document.querySelector<HTMLAnchorElement>('#voice-draft-link')!;
let voiceStatusKey: MessageKey | null = null;
function updateVoiceLabel() {
  const gameRequest = voiceKind.value === 'game';
  voiceGame.required = gameRequest;
  const label = document.querySelector('#voice-game-label')!;
  label.textContent = `${t('gameLabel')} `;
  const requirement = document.createElement('span');
  requirement.textContent = t(gameRequest ? 'required' : 'optional');
  label.append(requirement);
}
voiceKind.addEventListener('change', updateVoiceLabel);
function prepareVoice() {
  if (!voiceForm.reportValidity()) return null;
  try {
    const draft = createVoiceDraft(voiceKind.value as VoiceKind, voiceGame.value, voiceMessage.value, getLanguage());
    draftLink.href = draft.url;
    draftLink.hidden = false;
    return draft;
  } catch (error) {
    voiceStatusKey = null;
    voiceStatus.textContent = (error as Error).message;
    return null;
  }
}
voiceForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const draft = prepareVoice();
  if (!draft) return;
  window.open(draft.url, '_blank', 'noopener,noreferrer');
  voiceStatusKey = 'draftReady';
  voiceStatus.textContent = t(voiceStatusKey);
});
document.querySelector('#copy-voice')!.addEventListener('click', async () => {
  const draft = prepareVoice();
  if (!draft) return;
  try {
    await navigator.clipboard.writeText(draft.text);
    voiceStatusKey = 'draftCopied';
    voiceStatus.textContent = t(voiceStatusKey);
  } catch {
    voiceStatusKey = 'copyBlocked';
    voiceStatus.textContent = t(voiceStatusKey);
  }
});

// Audio requires explicit opt-in. One short cue plays at a time.
type Direction = { pan: number; angle: number; frequency: number };
const directions: Record<DirectionKey, Direction> = {
  nw: { pan: -0.72, angle: -135, frequency: 640 },
  n: { pan: 0, angle: -90, frequency: 740 },
  ne: { pan: 0.72, angle: -45, frequency: 640 },
  w: { pan: -1, angle: -180, frequency: 520 },
  e: { pan: 1, angle: 0, frequency: 520 },
  sw: { pan: -0.72, angle: 135, frequency: 400 },
  s: { pan: 0, angle: 90, frequency: 340 },
  se: { pan: 0.72, angle: 45, frequency: 400 },
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
let selectedDirection: DirectionKey | null = null;
const directionCue = (key: DirectionKey) => t('directionCue', { direction: directionName(key) });

function stopCue() {
  cueToken++;
  if (speechTimer) { clearTimeout(speechTimer); speechTimer = null; }
  if (oscillator) { try { oscillator.stop(); } catch { /* It may already be stopped. */ } oscillator = null; }
  if ('speechSynthesis' in window) speechSynthesis.cancel();
}
async function playCue(key: DirectionKey) {
  if (!audio || !soundEnabled) return;
  const direction = directions[key];
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
      const utterance = new SpeechSynthesisUtterance(directionCue(key));
      utterance.lang = getLanguage() === 'en' ? 'en-US' : 'zh-CN';
      utterance.volume = 0.65;
      utterance.rate = 1.1;
      const voice = speechSynthesis.getVoices().find((candidate) => candidate.lang.startsWith(getLanguage()));
      if (voice) utterance.voice = voice;
      speechSynthesis.speak(utterance);
    }, 420);
  }
}
function updateAudioUI() {
  audioToggle.setAttribute('aria-pressed', String(soundEnabled));
  audioLabel.textContent = t(soundEnabled ? 'audioOn' : 'audioOff');
  audioToggle.querySelector('.audio-icon')!.innerHTML = `<i data-lucide="${soundEnabled ? 'volume-2' : 'volume-x'}"></i>`;
  audioNote.textContent = t(soundEnabled ? 'audioHintOn' : 'audioHintOff');
  renderIcons();
}
audioToggle.addEventListener('click', async () => {
  if (soundEnabled) { soundEnabled = false; stopCue(); updateAudioUI(); return; }
  try {
    audio ??= new AudioContext();
    await audio.resume();
    soundEnabled = true;
    updateAudioUI();
    if (selectedDirection) await playCue(selectedDirection);
  } catch {
    soundEnabled = false;
    audioNote.textContent = t('audioError');
  }
});
document.querySelectorAll<HTMLButtonElement>('.direction-point').forEach((point) => {
  point.addEventListener('click', () => {
    const key = point.dataset.direction as DirectionKey;
    const direction = directions[key];
    selectedDirection = key;
    document.querySelectorAll('.direction-point').forEach((node) => node.setAttribute('aria-pressed', String(node === point)));
    radar.classList.add('has-signal');
    radarPath.style.transform = `rotate(${direction.angle}deg)`;
    feedback.textContent = directionCue(key);
    document.querySelector('#radar-status')!.textContent = t('radarActive');
    if (soundEnabled) void playCue(key).catch(() => { audioNote.textContent = t('audioLimited'); });
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
  motionButton.setAttribute('aria-label', t(motionPaused ? 'playMotion' : 'pauseMotion'));
  motionButton.title = t(motionPaused ? 'playMotion' : 'pauseMotion');
  motionButton.innerHTML = `<i data-lucide="${motionPaused ? 'play' : 'pause'}"></i>`;
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
    .from('.hero-kicker', { y: 16, opacity: 0, duration: 0.85 }, 0.3)
    .from('.hero-description, .hero-links, .hero-release', { y: 15, opacity: 0, duration: 0.8, stagger: 0.1 }, 0.5);
}
function stopAnimations() {
  revealAnimations.forEach((tween) => { tween.scrollTrigger?.kill(); tween.kill(); });
  revealAnimations = [];
  heroAnimation?.progress(1).kill();
  gsap.set('.reveal, .hero-topline, .hero-line, .hero-kicker, .hero-description, .hero-links, .hero-release', { clearProps: 'transform,opacity' });
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

// The CDN does not currently send CORS headers. Actions supplies a fresh, same-origin
// JSON snapshot automatically; release data and APK URLs never live in page source.
let release: Release | null = null;
let releaseFailed = false;
function renderRelease() {
  const versionText = release ? t('releaseVersion', { version: release.version_name }) : t(releaseFailed ? 'releaseError' : 'releaseLoading');
  document.querySelectorAll('[data-release-version]').forEach((node) => { node.textContent = versionText; });
  document.querySelectorAll<HTMLAnchorElement>('[data-download]').forEach((link) => {
    if (release) link.href = release.apk_url;
  });
  document.querySelector('[data-release-size]')!.textContent = release ? t('releaseSize', { size: (release.apk_bytes / 1_000_000).toFixed(1) }) : (getLanguage() === 'en' ? 'Free and open source' : '免费开源');
  document.querySelector('#release-notes-title')!.textContent = t('releaseNotes');
  const notes = document.querySelector<HTMLElement>('#release-notes-content')!;
  notes.lang = release ? 'zh-CN' : document.documentElement.lang;
  const lines = release ? release.release_notes.split('\n').filter((line) => line.trim()) : [versionText];
  notes.replaceChildren(...lines.map((line) => { const paragraph = document.createElement('p'); paragraph.textContent = line; return paragraph; }));
  if (release) {
    const schema = document.querySelector<HTMLScriptElement>('#software-data')!;
    const value = JSON.parse(schema.textContent!);
    value.softwareVersion = release.version_name;
    value.downloadUrl = release.apk_url;
    schema.textContent = JSON.stringify(value);
  }
}
async function refreshRelease() {
  try {
    release = await loadRelease(`${import.meta.env.BASE_URL}latest.json?check=${Date.now()}`);
    releaseFailed = false;
  } catch { releaseFailed = !release; }
  renderRelease();
  ScrollTrigger.refresh();
  updateProgress();
}
initLanguage(() => {
  stopCue();
  setMenu(!mobileNavigation.hidden);
  updateMotionUI();
  updateAudioUI();
  updateVoiceLabel();
  feedback.textContent = selectedDirection ? directionCue(selectedDirection) : t('directionHint');
  document.querySelector('#radar-status')!.textContent = t(selectedDirection ? 'radarActive' : 'radarWaiting');
  if (voiceStatusKey) voiceStatus.textContent = t(voiceStatusKey);
  else voiceStatus.textContent = '';
  renderRelease();
  ScrollTrigger.refresh();
  updateProgress();
});
void refreshRelease();
setInterval(() => { if (!document.hidden) void refreshRelease(); }, 15 * 60_000);

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
