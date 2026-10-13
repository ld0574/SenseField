import { writeFile } from 'node:fs/promises';
import { loadRelease, RELEASE_SOURCE, parseRelease } from '../src/release.ts';

const output = new URL('../public/latest.json', import.meta.url);
let release;
for (let attempt = 1; attempt <= 3; attempt++) {
  try { release = await loadRelease(RELEASE_SOURCE); break; }
  catch (error) { if (attempt === 3) throw error; }
}
await writeFile(output, `${JSON.stringify(release, null, 2)}\n`);
console.log(`Synchronized SenseField ${release.version_name} from the CDN manifest.`);

// Scheduled runs compare against the published snapshot and skip unchanged builds.
if (process.env.GITHUB_OUTPUT) {
  let updated = true;
  if (process.env.GITHUB_EVENT_NAME === 'schedule') {
    try {
      const live = await loadRelease('https://ld0574.github.io/SenseField/latest.json');
      updated = JSON.stringify(parseRelease(live)) !== JSON.stringify(release);
    } catch { /* A missing published snapshot needs an initial deployment. */ }
  }
  await writeFile(process.env.GITHUB_OUTPUT, `updated=${updated}\n`, { flag: 'a' });
}
