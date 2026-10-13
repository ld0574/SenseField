import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseRelease, loadRelease } from '../src/release.ts';

const manifest = {
  schema_version: 1, package_name: 'com.openkhub.sensefield', version_name: '0.4.6', version_code: 23,
  apk_url: 'https://gitee.com/leda/SenseField/releases/download/0.4.6/sensefieldv0.4.6.apk',
  apk_bytes: 24556047, apk_sha256: '340e3b0f8547944287eea311207d5ebbee5ac28757f8009a982cbbcd84f9e56d',
  release_notes: '识别灰毛球。\n减少重复采集。',
};

test('the CDN manifest supplies version, size, notes and the current download URL', () => {
  assert.deepEqual(parseRelease(manifest), manifest);
  const next = parseRelease({ ...manifest, version_name: '0.5.0', version_code: 24, apk_url: 'https://888413.xyz/apk/sensefield-0.5.0.apk' });
  assert.equal(next.version_name, '0.5.0');
  assert.equal(next.apk_url, 'https://888413.xyz/apk/sensefield-0.5.0.apk');
});

test('download URLs reject scripts, cleartext HTTP, credentials and non-APK destinations', () => {
  for (const apk_url of ['javascript:alert(1)', 'http://888413.xyz/app.apk', 'https://user:password@example.com/app.apk', 'https://example.com/page.html']) {
    assert.throws(() => parseRelease({ ...manifest, apk_url }), /HTTPS APK/);
  }
});

test('unexpected packages, schemas and malformed release fields are rejected', () => {
  for (const patch of [{ package_name: 'another.app' }, { schema_version: 2 }, { version_name: '<script>' }, { version_code: 0 }, { apk_bytes: -1 }, { apk_sha256: 'bad' }, { release_notes: null }]) {
    assert.throws(() => parseRelease({ ...manifest, ...patch }));
  }
});

test('fetch reads JSON without a persistent browser cache', async () => {
  let requested = '';
  let options: RequestInit | undefined;
  const fetcher = async (input: string | URL | Request, init?: RequestInit) => {
    requested = String(input);
    options = init;
    return new Response(JSON.stringify(manifest), { headers: { 'Content-Type': 'application/json' } });
  };
  const result = await loadRelease('/SenseField/latest.json', fetcher as typeof fetch);
  assert.equal(requested, '/SenseField/latest.json');
  assert.equal(options?.cache, 'no-store');
  assert.ok(options?.signal instanceof AbortSignal);
  assert.equal(result.apk_url, manifest.apk_url);
});

test('a missing or invalid manifest cannot silently become a valid release', async () => {
  await assert.rejects(loadRelease('/latest.json', (async () => new Response('Unavailable', { status: 503 })) as typeof fetch), /HTTP 503/);
  await assert.rejects(loadRelease('/latest.json', (async () => new Response('{}')) as typeof fetch), /schema/);
});
