export const RELEASE_SOURCE = 'https://888413.xyz/apk/latest.json';
export const RELEASE_PAGE = 'https://gitee.com/leda/SenseField/releases';

export type Release = {
  schema_version: 1;
  package_name: 'com.openkhub.sensefield';
  version_name: string;
  version_code: number;
  apk_url: string;
  apk_bytes: number;
  apk_sha256: string;
  release_notes: string;
};

/** Validate the public CDN manifest before rendering its fields or download URL. */
export function parseRelease(value: unknown): Release {
  if (!value || typeof value !== 'object') throw new Error('Invalid release manifest.');
  const data = value as Record<string, unknown>;
  if (data.schema_version !== 1 || data.package_name !== 'com.openkhub.sensefield') throw new Error('Unexpected release schema or Android package.');
  if (typeof data.version_name !== 'string' || !/^[0-9][a-zA-Z0-9.+_-]{0,39}$/.test(data.version_name)) throw new Error('Invalid release version.');
  if (!Number.isSafeInteger(data.version_code) || (data.version_code as number) < 1) throw new Error('Invalid version code.');
  if (!Number.isSafeInteger(data.apk_bytes) || (data.apk_bytes as number) < 1 || (data.apk_bytes as number) > 2_147_483_648) throw new Error('Invalid APK size.');
  if (typeof data.apk_url !== 'string') throw new Error('Missing APK URL.');
  const url = new URL(data.apk_url);
  if (url.protocol !== 'https:' || url.username || url.password || !url.pathname.toLowerCase().endsWith('.apk')) throw new Error('APK downloads must use an HTTPS APK URL.');
  if (typeof data.apk_sha256 !== 'string' || !/^[a-f0-9]{64}$/i.test(data.apk_sha256)) throw new Error('Invalid APK checksum.');
  if (typeof data.release_notes !== 'string' || data.release_notes.length > 12_000) throw new Error('Invalid release notes.');
  return {
    schema_version: 1, package_name: 'com.openkhub.sensefield', version_name: data.version_name,
    version_code: data.version_code as number, apk_url: url.href, apk_bytes: data.apk_bytes as number,
    apk_sha256: data.apk_sha256.toLowerCase(), release_notes: data.release_notes,
  };
}

export async function loadRelease(fetcher: typeof fetch = fetch): Promise<Release> {
  const url = new URL(RELEASE_SOURCE);
  url.searchParams.set('check', String(Date.now()));
  const response = await fetcher(url.href, {
    cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(8_000),
  });
  if (!response.ok) throw new Error(`Release manifest returned HTTP ${response.status}.`);
  return parseRelease(await response.json());
}
