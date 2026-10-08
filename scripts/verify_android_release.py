#!/usr/bin/env python3
"""Check the actual signed/minified handoff, including ABI, compression, ELF alignment and audio."""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
from zipfile import ZipFile, ZIP_DEFLATED

from build_app_update_manifest import read_apk_metadata
from prepare_bundled_speech import catalog, expected

CERT = "5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68"
REQUIRED_LIBRARIES = {"libmapassist.so", "libmapassist_jni.so", "libondevice_asr_jni.so",
                      "libonnxruntime.so", "libpresentation_audio_jni.so", "libsherpa-onnx-c-api.so"}


def tool(name: str):
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    candidates = list((sdk / "build-tools").glob("*/" + name))
    if not candidates:
        raise ValueError(f"Android build tool unavailable: {name}")
    candidates.sort(key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r'(\d+)', p.parent.name)])
    return str(candidates[-1])


def elf_load_alignments(data: bytes):
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError("Expected little-endian ELF64")
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    result = []
    for index in range(count):
        at = offset + index * size
        if struct.unpack_from('<I', data, at)[0] == 1:
            fields = struct.unpack_from('<IIQQQQQQ', data, at)
            _, _, file_offset, virtual, _, _, _, alignment = fields
            if alignment < 16384 or file_offset % 16384 != virtual % 16384:
                raise ValueError("Native library does not meet ELF 16 KB load alignment")
            result.append(alignment)
    if not result:
        raise ValueError("Missing ELF load segments")
    return result


def verify(apk: Path, allow_fixture: bool):
    metadata = read_apk_metadata(apk, tool('aapt2'))
    app_id = "com.openkhub.sensefield.speechfixture" if allow_fixture else "com.openkhub.sensefield"
    version = "0.4.4-fixture" if allow_fixture else "0.4.4"
    if (metadata.package_name != app_id or metadata.version_code != 21
            or metadata.version_name != version or metadata.min_sdk_version != '29'
            or metadata.native_abis != ('arm64-v8a',)):
        raise ValueError(f"Unexpected APK identity: {metadata}")
    if apk.stat().st_size > 40_000_000:
        raise ValueError("APK exceeds 40 MB budget")
    environment = dict(os.environ)
    environment.setdefault('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')
    signature = subprocess.run([tool('apksigner'), 'verify', '--verbose', '--print-certs', str(apk)],
                               env=environment, check=True, capture_output=True, text=True).stdout
    certificates = re.findall(r'Signer #[0-9]+ certificate SHA-256 digest: ([a-f0-9]+)', signature)
    if certificates != [CERT]:
        raise ValueError("APK must use the existing 0.4.3 signing certificate")
    if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in signature:
        raise ValueError("APK must have a valid v2 signature")
    manifest = subprocess.run([tool('aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'],
                              check=True, capture_output=True, text=True).stdout
    if re.search(r'android:debuggable[^\n]*(?:0xffffffff|=true)', manifest):
        raise ValueError("Release must not be debuggable")
    if not re.search(r'android:extractNativeLibs[^\n]*(?:0xffffffff|=true)', manifest):
        raise ValueError("Compressed native libraries must be extracted at install time")
    subprocess.run([tool('zipalign'), '-c', '-P', '16', '4', str(apk)], check=True, capture_output=True)
    groups = defaultdict(int)
    libraries = {}
    with ZipFile(apk) as archive:
        present = {Path(x.filename).name for x in archive.infolist() if x.filename.startswith('lib/')}
        if present != REQUIRED_LIBRARIES:
            raise ValueError("Missing/unexpected native runtime")
        for info in archive.infolist():
            group = ('native' if info.filename.startswith('lib/') else 'dex' if info.filename.endswith('.dex')
                     else 'speech' if info.filename.startswith('assets/speech/') else 'other')
            groups[group] += info.compress_size
            if group in {'native', 'dex'} and info.compress_type != ZIP_DEFLATED:
                raise ValueError("Native and DEX files must use compressed packaging")
            if group == 'native':
                libraries[info.filename] = elf_load_alignments(archive.read(info))
        for stem in ['minimap-yolox-nano-320', 'minimap-yolox-nano-dual-512']:
            sidecar = json.loads(archive.read(f'assets/{stem}.metadata.json'))
            for ext in ['bin', 'param']:
                if hashlib.sha256(archive.read(f'assets/{stem}.{ext}')).hexdigest() != sidecar['runtime'][ext + '_sha256']:
                    raise ValueError("Minimap model checksum mismatch")
        audio = json.loads(archive.read('assets/speech/manifest.json'))
        if audio.get('test_fixture', False) != allow_fixture or not allow_fixture and not audio.get('source_selected_by_user'):
            raise ValueError("Real release cannot contain synthetic audio")
        wanted = expected(catalog())
        seen = set()
        for file in audio['files']:
            identity = (file['voice'], file['rate'], file['id'])
            if identity in seen or wanted.get(identity) != file['text']:
                raise ValueError("APK audio does not match current text catalog")
            seen.add(identity)
            data = archive.read('assets/speech/' + file['path'])
            if len(data) != file['size'] or hashlib.sha256(data).hexdigest() != file['sha256']:
                raise ValueError("APK audio checksum mismatch")
        if seen != set(wanted) or groups['speech'] > 15_000_000:
            raise ValueError("Incomplete or oversized APK speech library")
    return {"apk": str(apk), "bytes": apk.stat().st_size,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "certificate_sha256": CERT,
            "version_name": metadata.version_name, "version_code": metadata.version_code,
            "package_name": metadata.package_name, "test_fixture": allow_fixture,
            "compressed_groups": dict(groups), "elf_load_alignments": libraries,
            "audio_files": len(seen), "debuggable": False, "extract_native_libs": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--allow-fixture', action='store_true')
    args = parser.parse_args()
    result = verify(args.apk, args.allow_fixture)
    body = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(body, encoding='utf-8')
    print(body)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
