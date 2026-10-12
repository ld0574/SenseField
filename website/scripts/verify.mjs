import { readFileSync, existsSync } from 'node:fs';
import assert from 'node:assert/strict';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8');
const code = readFileSync(new URL('../src/main.ts', import.meta.url), 'utf8');
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
assert(html.includes('https://github.com/ld0574/SenseField/releases/download/v0.4.5/sensefieldv0.4.5.apk'), 'Keep a verified public APK download.');
assert(html.includes('lang="zh-CN"'), 'Declare the page language.');
assert(html.includes('class="skip-link"'), 'Keep keyboard skip navigation.');
assert(code.includes("reducedMotion.matches"), 'Honor the OS motion preference.');
assert(!html.includes('<svg'), 'Use Lucide for interface icons.');
console.log('Website content checks passed: product colors, no emoji, navigation, assets, Lucide, public download and accessibility hooks.');
