import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseRelease, loadRelease, RELEASE_SOURCE } from '../src/release.ts';

const manifest = {
  schema_version: 1, package_name: 'com.openkhub.sensefield', version_name: '1.2.3', version_code: 123,
  apk_url: 'https://example.com/sensefield-1.2.3.apk',
  apk_bytes: 24_000_000, apk_sha256: 'a'.repeat(64),
  release_notes: '识别灰毛球。\n减少重复采集。',
};

test('the CDN manifest supplies version, size, notes and the current download URL', () => {
  assert.deepEqual(parseRelease(manifest), manifest);
  const next = parseRelease({ ...manifest, version_name: '1.2.4', version_code: 124, apk_url: 'https://example.com/sensefield-1.2.4.apk' });
  assert.equal(next.version_name, '1.2.4');
  assert.equal(next.apk_url, 'https://example.com/sensefield-1.2.4.apk');
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

test('every request reads the authoritative CDN without browser cache or credentials', async () => {
  let requested = '';
  let options: RequestInit | undefined;
  const fetcher = async (input: string | URL | Request, init?: RequestInit) => {
    requested = String(input);
    options = init;
    return new Response(JSON.stringify(manifest), { headers: { 'Content-Type': 'application/json' } });
  };
  const before = Date.now();
  const result = await loadRelease(fetcher as typeof fetch);
  const url = new URL(requested);
  assert.equal(`${url.origin}${url.pathname}`, RELEASE_SOURCE);
  assert.ok(Number(url.searchParams.get('check')) >= before);
  assert.ok(Number(url.searchParams.get('check')) <= Date.now());
  assert.equal(options?.cache, 'no-store');
  assert.equal(options?.credentials, 'omit');
  assert.ok(options?.signal instanceof AbortSignal);
  assert.equal(result.apk_url, manifest.apk_url);
});

test('a missing or invalid manifest cannot silently become a valid release', async () => {
  await assert.rejects(loadRelease((async () => new Response('Unavailable', { status: 503 })) as typeof fetch), /HTTP 503/);
  await assert.rejects(loadRelease((async () => new Response('{}')) as typeof fetch), /schema/);
});

test('a new version and download URL take effect on the next request without a website build', async () => {
  let current = manifest;
  const fetcher = (async () => new Response(JSON.stringify(current))) as typeof fetch;
  assert.deepEqual(await loadRelease(fetcher), manifest);
  current = { ...manifest, version_name: '1.2.4', version_code: 124, apk_url: 'https://example.com/sensefield-1.2.4.apk' };
  assert.deepEqual(await loadRelease(fetcher), current);
});

test('same-version corrections to notes, size and checksum are not ignored', async () => {
  let current = manifest;
  const fetcher = (async () => new Response(JSON.stringify(current))) as typeof fetch;
  assert.deepEqual(await loadRelease(fetcher), manifest);
  current = { ...manifest, apk_bytes: 24_030_410, apk_sha256: 'b'.repeat(64), release_notes: '同版本修订后的更新说明。' };
  assert.deepEqual(await loadRelease(fetcher), current);
});

test('a later CORS or network failure cannot return a bundled or previously loaded version', async () => {
  const fetcher = (async () => new Response(JSON.stringify(manifest))) as typeof fetch;
  await loadRelease(fetcher);
  await assert.rejects(loadRelease((async () => { throw new TypeError('Failed to fetch'); }) as typeof fetch), /Failed to fetch/);
});
