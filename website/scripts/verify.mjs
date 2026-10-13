import { readFileSync, existsSync } from 'node:fs';
import assert from 'node:assert/strict';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8');
const code = readFileSync(new URL('../src/main.ts', import.meta.url), 'utf8');
const release = readFileSync(new URL('../src/release.ts', import.meta.url), 'utf8');
const field = readFileSync(new URL('../src/field.ts', import.meta.url), 'utf8');
const visibleCopy = html.replace(/<script[\s\S]*?<\/script>/g, '').replace(/<[^>]*>/g, '');

assert(!/\p{Extended_Pictographic}/u.test(visibleCopy), 'Interface copy must not contain emoji.');
assert(!html.includes('arrow-up-right'), 'Hyperlinks use horizontal Lucide arrows.');
for (const [name, color] of Object.entries({ page: '#fbf7f1', ink: '#193139', primary: '#236874', soft: '#e7f2f2', peach: '#f0c6b2' })) {
  assert(css.includes(`--${name}: ${color}`), `Preserve the product's ${name} color ${color}.`);
}
const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]));
for (const [, fragment] of html.matchAll(/href="#([^"]*)"/g)) {
  assert(!fragment || ids.has(fragment), `Missing navigation target #${fragment}.`);
}
for (const [, image] of html.matchAll(/(?:src|href)="\/images\/([^"]+)"/g)) {
  assert(existsSync(new URL(`../public/images/${image}`, import.meta.url)), `Missing public image ${image}.`);
}
const importedIcons = new Set(code.match(/import \{([^}]+)\} from 'lucide'/)[1].split(',').map((icon) => icon.trim().toLowerCase()));
for (const [, icon] of html.matchAll(/data-lucide="([^"]+)"/g)) {
  assert(importedIcons.has(icon.replaceAll('-', '')), `Lucide icon ${icon} must be imported.`);
}
for (const [, attributes] of html.matchAll(/<a\s([^>]+)>/g)) {
  if (attributes.includes('target="_blank"')) assert(attributes.includes('rel="noopener noreferrer"'), 'External tabs must have a safe rel attribute.');
}
assert(!html.includes('releases/download/'), 'APK URLs come from the dynamic release manifest.');
assert(release.includes('https://888413.xyz/apk/latest.json'), 'Use the project CDN as the release source.');
assert((html.match(/data-download\s/g) ?? []).length === 2, 'Keep download buttons in the hero and download section.');
assert(!field.includes('THREE.Points') && !field.includes('./particles'), 'The hero uses a continuous perception lens, without particles.');
assert(!html.includes('feature-trigger'), 'Feature descriptions remain visible while scrolling.');
const faqs = [...html.matchAll(/<details class="faq-item reveal"([^>]*)>/g)];
assert(faqs.length === 5 && faqs.every(([, attrs]) => !/\bopen\b/.test(attrs)), 'The five FAQs are native disclosures, collapsed by default.');
assert(!html.includes('体验版') && !html.includes('Preview feature') && html.includes('已测通 100 关'), 'Reflect the completed 100-level Anipop playthrough without preview labels.');
assert((html.match(/100 关/g) ?? []).length === 1 && (html.match(/100 levels/g) ?? []).length === 1, 'Mention the 100-level Anipop result only once in each language.');
assert((html.match(/class="hero-channel"/g) ?? []).length === 4 && html.includes('data-en="Visual"'), 'Present sound, speech, touch and visual highlights in the hero.');
assert((html.match(/class="feature-row reveal"/g) ?? []).length === 3, 'Show all three features.');
assert(html.includes('少一点寻找。') && html.includes('多一点尽兴。') && !html.includes('多一点，尽兴。'), 'Keep the feature headline parallel.');
assert(html.includes('images/openkhub-logo.png') && !html.includes('footer-word'), 'Keep both footer identities in a compact layout.');
assert(html.includes('https://gitee.com/leda/SenseField'), 'Include the Gitee project.');
assert(html.includes('https://lcnh7zi6opm8.feishu.cn/docx/BADbdE0laoeN8nx5Ut4c7UTVnDq'), 'Link the product user guide.');
assert(!html.includes('安卓手游场景示意') && !html.includes('阅读我们的出发点'), 'Remove the redundant scene caption and story link.');
assert(!/ACCESSIBLE PLAY|THE WHY|THE SENSORY|BUILT FOR YOUR PLAY|MORE PLAY/.test(visibleCopy), 'Default copy is Chinese.');
assert(html.includes('class="language-toggle"') && code.includes('initLanguage'), 'Offer a persisted English language switch.');
assert(html.includes('lang="zh-CN"'), 'Declare the page language.');
assert(html.includes('class="skip-link"'), 'Keep keyboard skip navigation.');
assert(code.includes("reducedMotion.matches"), 'Honor the OS motion preference.');
assert(!html.includes('<svg'), 'Use Lucide for interface icons.');
console.log('Website checks passed: brand colors, bilingual controls, perception lens, expanded features, collapsed FAQs, 100-level Anipop copy, dynamic downloads, assets, Lucide and accessibility hooks.');
