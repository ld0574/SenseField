#!/usr/bin/env python3
"""Package the pinned phone ASR files for a manual CDN upload."""
import argparse
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, default=ROOT / 'output/releases/0.4.1/model-cdn-2026-10-06')
args = parser.parse_args()
assets = ROOT / 'android/app/src/main/assets' 
metadata_path = assets / 'sensevoice.metadata.json'
metadata = json.loads(metadata_path.read_text())
output = args.output_dir
output.mkdir(parents=True, exist_ok=True)
archive_path = output / 'sensevoice-int8-v1.zip'
with zipfile.ZipFile(archive_path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for name, expected in metadata['files'].items():
        path = assets / 'sensevoice' / name
        assert path.stat().st_size == expected['size']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == expected['sha256']
        info = zipfile.ZipInfo(name, (2024, 7, 17, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100600 << 16
        archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
metadata['download'] = {
    'url': 'https://888413.xyz/apk/models/sensevoice-int8-v1.zip',
    'archive_bytes': archive_path.stat().st_size,
    'archive_sha256': digest,
}
metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
(output / '上传说明.txt').write_text('将 sensevoice-int8-v1.zip 上传到 https://888413.xyz/apk/models/sensevoice-int8-v1.zip。不要解压后再上传。下载校验固定本模型版本；更换内容时需重新生成清单和APK，不能用同一个地址放其他模型。\n')
(output / 'model.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(metadata['download'], ensure_ascii=False))
