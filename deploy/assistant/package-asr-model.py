#!/usr/bin/env python3
"""Prepare pinned ASR resources as <100 MB Gitee Release attachments."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from pathlib import Path
import shutil
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RELEASE = 'https://gitee.com/leda/SenseField/releases/download/0.4.1'
PART_BYTES = 90_000_000  # below a decimal 100 MB limit as well as 100 MiB
ARCHIVE_NAME = 'sensevoice-int8-v1.zip'


def digest_file(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def package_model(metadata: dict, assets: Path, output: Path, release_url: str = DEFAULT_RELEASE,
                  part_bytes: int = PART_BYTES, existing_archive: Path | None = None) -> dict:
    parsed = urlsplit(release_url)
    if (parsed.scheme != 'https' or parsed.hostname != 'gitee.com' or parsed.port not in (None, 443)
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or not re.fullmatch(r'/leda/SenseField/releases/download/[A-Za-z0-9][A-Za-z0-9._-]*/?', parsed.path)):
        raise ValueError('Use this project\'s HTTPS Gitee Release download route.')
    if not 1 <= part_bytes <= PART_BYTES:
        raise ValueError('Part size must be between 1 and 90,000,000 bytes.')
    if set(metadata['files']) != {'model.int8.onnx', 'tokens.txt'}:
        raise ValueError('Unexpected pinned model files.')
    output.mkdir(parents=True, exist_ok=True)
    archive_path = output / ARCHIVE_NAME
    if existing_archive is not None:
        expected = metadata['download']
        if (existing_archive.stat().st_size != expected['archive_bytes']
                or digest_file(existing_archive) != expected['archive_sha256']):
            raise ValueError('Existing ZIP does not match the pinned archive.')
        if existing_archive.resolve() != archive_path.resolve():
            shutil.copyfile(existing_archive, archive_path)
    else:
        with zipfile.ZipFile(archive_path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, expected in metadata['files'].items():
                path = assets / name
                if path.stat().st_size != expected['size'] or digest_file(path) != expected['sha256']:
                    raise ValueError(f'{name} does not match its pinned metadata.')
                info = zipfile.ZipInfo(name, (2024, 7, 17, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100600 << 16
                archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
    # Validate the actual extraction content too. Splitting changes transport,
    # never the model, tokens, revision or runtime.
    with zipfile.ZipFile(archive_path) as archive:
        if len(archive.infolist()) != 2 or set(archive.namelist()) != set(metadata['files']):
            raise ValueError('ZIP entries do not match the two pinned model files.')
        for name, expected in metadata['files'].items():
            if archive.getinfo(name).file_size != expected['size']:
                raise ValueError(f'ZIP size mismatch: {name}')
            with archive.open(name) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != expected['sha256']:
                    raise ValueError(f'ZIP digest mismatch: {name}')
    size = archive_path.stat().st_size
    if (size + part_bytes - 1) // part_bytes > 8:
        raise ValueError('The client supports at most eight parts.')
    parts = []
    with archive_path.open('rb') as stream:
        while block := stream.read(part_bytes):
            filename = f'{ARCHIVE_NAME}.part{len(parts) + 1:02d}'
            (output / filename).write_bytes(block)
            parts.append({'name': filename, 'url': release_url.rstrip('/') + '/' + filename,
                          'bytes': len(block), 'sha256': hashlib.sha256(block).hexdigest()})
    result = copy.deepcopy(metadata)
    result['download'] = {'format': 'concat-zip-v1', 'archive_bytes': size,
                          'archive_sha256': digest_file(archive_path), 'parts': parts}
    (output / 'model.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    instructions = ['将以下分包上传到同一个 Gitee Release（0.4.1），文件名保持不变：']
    instructions.extend(f"{p['name']}（{p['bytes']:,} bytes）" for p in parts)
    instructions += ['', '原 ZIP 超过100MB，只用于本地留档，不上传。分包不能分别解压；APP自动拼接、逐包及整包校验后解压。',
                     '模型只上传一次；后续 APK 沿用此模型地址，不在每个 Release 重复上传。模型更新另建资源版本。',
                     '先上传分包，再上传最终 APK，最后更新 https://888413.xyz/apk/latest.json。不要使用旧APK对应的清单。',
                     '模型、tokens、revision和许可不变；下载、合并、解压在后台执行，已有验证缓存直接复用。']
    (output / '上传说明.txt').write_text('\n'.join(instructions) + '\n')
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'output/releases/0.4.1/model-gitee-2026-10-06')
    parser.add_argument('--release-url', default=DEFAULT_RELEASE)
    parser.add_argument('--part-bytes', type=int, default=PART_BYTES)
    parser.add_argument('--archive', type=Path, help='Reuse the pinned original ZIP byte-for-byte.')
    args = parser.parse_args()
    assets = ROOT / 'android/app/src/main/assets'
    metadata_path = assets / 'sensevoice.metadata.json'
    result = package_model(json.loads(metadata_path.read_text()), assets / 'sensevoice', args.output_dir,
                           args.release_url, args.part_bytes, args.archive)
    metadata_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result['download'], ensure_ascii=False))


if __name__ == '__main__':
    main()
