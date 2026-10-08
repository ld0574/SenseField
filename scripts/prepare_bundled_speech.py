#!/usr/bin/env python3
"""Export the live Java catalog, import user-selected website WAVs, encode/verify offline OGG.

The website is operated through its UI, never by reverse-engineering/calling its private API.
Synthetic fixtures have a separate application id and cannot pass the release handoff gate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import wave
from urllib.parse import parse_qs, urlparse
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
JAVA_SOURCE = ROOT / "android/app/src/main/java/com/openkhub/sensefield"
ASSETS = ROOT / "android/app/src/main/assets/speech"
VOICES = {"xiaoxiao": "zh-CN-XiaoxiaoNeural", "yunxi": "zh-CN-YunxiNeural"}
FISH_VOICES = {
    "game_female": ("49076f44a1d94065897bc0856aa70412", "游戏向导", "xiaoxiao-fixed"),
    "game_male": ("6bc140b33c39420b9cd7180f76e03846", "王者游戏解说男声", "yunxi-fixed"),
}
RATES = range(80, 241, 10)
BUDGET = 15_000_000


def run(args: list[str], **kwargs):
    return subprocess.run(args, check=True, capture_output=True, **kwargs)


def java_binary(name: str) -> str:
    candidates = [Path(os.getenv("JAVA_HOME", "/nonexistent")) / "bin" / name,
                  Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin") / name]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    binary = shutil.which(name)
    if not binary:
        raise ValueError(f"Missing JDK binary: {name}")
    return binary


def catalog() -> dict:
    names = ["BundledSpeechCatalog", "AlertSpeechCache", "NearZoneRouting", "CueRouting",
             "CueRequest", "ReminderGuide", "ReminderGuideCatalog", "ReminderGuideSections"]
    with tempfile.TemporaryDirectory(prefix="sensefield-speech-catalog-") as directory:
        run([java_binary("javac"), "-encoding", "UTF-8", "-d", directory,
             *[str(JAVA_SOURCE / f"{name}.java") for name in names]])
        result = run([java_binary("java"), "-cp", directory,
                      "com.openkhub.sensefield.BundledSpeechCatalog"], text=True)
    return json.loads(result.stdout)


def batches(inventory: dict):
    return [("xiaoxiao-fixed", "xiaoxiao", "fixed", inventory["fixed"]),
            ("yunxi-fixed", "yunxi", "fixed", inventory["fixed"]),
            ("xiaoxiao-guide", "xiaoxiao", "guide", inventory["guide"])]


def export_web(destination: Path, inventory: dict):
    destination.mkdir(parents=True, exist_ok=True)
    for name, voice, group, items in batches(inventory):
        body = '<break time="3000ms"/>'.join(escape(item["text"]) for item in items)
        ssml = ('<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="zh-CN">'
                f'<voice name="{VOICES[voice]}"><prosody rate="0%" pitch="0%">{body}</prosody></voice></speak>')
        (destination / f"{name}.ssml.txt").write_text(ssml, encoding="utf-8")
        (destination / f"{name}.json").write_text(json.dumps(items, ensure_ascii=False, indent=2) + "\n",
                                                encoding="utf-8")
    (destination / "catalog.json").write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n",
                                             encoding="utf-8")
    print(json.dumps({"directory": str(destination), "fixed": len(inventory["fixed"]),
                      "guide": len(inventory["guide"]),
                      "characters": sum(len(x["text"]) for x in inventory["fixed"]) * 2
                      + sum(len(x["text"]) for x in inventory["guide"])}, ensure_ascii=False))


def wav_segments(path: Path, count: int):
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise ValueError("ffmpeg is required")
    result = run([ffmpeg, "-hide_banner", "-i", str(path), "-af",
                  "silencedetect=noise=-55dB:d=2", "-f", "null", "-"], text=True)
    with wave.open(str(path), "rb") as source:
        if source.getsampwidth() != 2 or source.getnchannels() != 1 or source.getframerate() != 16000:
            raise ValueError("Website export must be 16 kHz, 16-bit mono WAV")
        pcm = source.readframes(source.getnframes())
    duration = len(pcm) / 32000
    starts = [float(x) for x in re.findall(r"silence_start: ([0-9.]+)", result.stderr)]
    ends = [float(x) for x in re.findall(r"silence_end: ([0-9.]+)", result.stderr)]
    gaps = [(a, b) for a, b in zip(starts, ends) if a > 0.5 and b < duration - 0.2]
    if len(gaps) != count - 1:
        raise ValueError(f"{path.name}: expected {count - 1} separators, found {len(gaps)}; do not guess alignment")
    boundaries = [0.0, *[end - 0.025 for _, end in gaps]]
    tails = [*[start + 0.025 for start, _ in gaps], duration]
    chunks = []
    for start, end in zip(boundaries, tails):
        if end <= start:
            raise ValueError("Invalid audio separator")
        chunks.append(pcm[round(start * 16000) * 2:round(end * 16000) * 2])
    return chunks


def write_wav(path: Path, pcm: bytes):
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(16000)
        output.writeframes(pcm)


def encode(source: Path, target: Path, rate: int):
    target.parent.mkdir(parents=True, exist_ok=True)
    # atempo preserves pitch. Chain factors to stay within the conservative 0.5..2 range.
    speed = rate / 100
    factors = []
    while speed > 2:
        factors.append(2); speed /= 2
    factors.append(speed)
    filters = ','.join(f'atempo={factor:.8f}' for factor in factors)
    filters += ',silenceremove=start_periods=1:start_threshold=-65dB:start_silence=0.025'
    run([shutil.which("ffmpeg") or "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-i", str(source), "-af", filters, "-ar", "16000", "-ac", "1",
         "-c:a", "libopus", "-b:a", "32k", "-vbr", "on", "-application", "voip", str(target)])
    probe = run([shutil.which("ffprobe") or "ffprobe", "-v", "error", "-show_entries",
                 "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", str(target)], text=True)
    return float(probe.stdout)


def entry(item: dict, voice: str, rate: int, path: Path, assets: Path, duration: float):
    data = path.read_bytes()
    return {**item, "voice": voice, "rate": rate, "path": str(path.relative_to(assets)),
            "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
            "duration_ms": round(duration * 1000), "sample_rate": 48000, "channels": 1,
            "codec": "opus", "pcm_sample_rate": 16000}


def expected(inventory: dict):
    return {(voice, rate, item["id"]): item["text"] for voice in inventory.get("voices", VOICES) for rate in RATES
            for item in inventory["fixed"]} | {
                ("xiaoxiao", 100, item["id"]): item["text"] for item in inventory["guide"]}


def verify(assets: Path, inventory: dict, allow_fixture: bool = False):
    manifest_path = assets / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError("Missing built-in recordings. Run web-batches and import-web-batches first.")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    fixture = manifest.get("test_fixture", False)
    if manifest.get("schema") != 1 or fixture and not allow_fixture:
        raise ValueError("Test audio cannot be handed off as a release")
    if not fixture and not manifest.get("source_selected_by_user"):
        raise ValueError("Missing user-selected audio source provenance")
    wanted = expected(inventory)
    seen = set()
    total = manifest_path.stat().st_size
    for audio in manifest["files"]:
        identity = (audio["voice"], audio["rate"], audio["id"])
        if identity in seen or wanted.get(identity) != audio["text"]:
            raise ValueError("Duplicate, changed or unexpected speech text")
        seen.add(identity)
        if hashlib.sha256(audio["text"].encode()).hexdigest() != audio["id"]:
            raise ValueError("Speech text checksum mismatch")
        relative = audio["path"]
        canonical = (f"guide/{audio['id']}.ogg" if audio["text"] in {x["text"] for x in inventory["guide"]}
                     else f"fixed/{audio['voice']}/{audio['rate']:03}/{audio['id']}.ogg")
        if relative != canonical:
            raise ValueError("Invalid audio path")
        data = (assets / relative).read_bytes()
        if len(data) != audio["size"] or hashlib.sha256(data).hexdigest() != audio["sha256"]:
            raise ValueError(f"Audio checksum mismatch: {relative}")
        header = data.find(b'OpusHead', 0, 4096)
        if not data.startswith(b'OggS') or header < 0 or data[header + 9] != 1:
            raise ValueError(f"Audio must be mono OGG/Opus: {relative}")
        limit = 30000 if relative.startswith("guide/") else 6000
        if (not 50 <= audio["duration_ms"] <= limit or audio["sample_rate"] != 48000
                or audio["channels"] != 1 or audio.get("codec") != "opus"
                or audio.get("pcm_sample_rate") != 16000):
            raise ValueError(f"Audio format/duration exceeds budget: {relative}")
        total += len(data)
    if seen != set(wanted):
        raise ValueError(f"Incomplete recordings: missing {len(set(wanted) - seen)}")
    actual = {str(p.relative_to(assets)) for p in assets.rglob("*.ogg")}
    if actual != {x["path"] for x in manifest["files"]}:
        raise ValueError("Orphan or missing audio files")
    if total > BUDGET:
        raise ValueError(f"Audio exceeds 15 MB budget: {total}")
    print(json.dumps({"verified_files": len(seen), "bytes": total, "test_fixture": fixture}))
    return manifest


def import_web(source: Path, assets: Path, inventory: dict):
    if set(inventory.get("voices", VOICES)) != set(VOICES) and assets.resolve() == ASSETS.resolve():
        raise ValueError("Neutral website batches must use a staging destination; import-fish-fixed supplies the active game voice")
    if assets.exists() and any(assets.iterdir()):
        raise ValueError("Destination must be empty; preserve previous assets before replacing")
    # Validate every batch first; a failed import must not leave partial main assets.
    prepared = [(name, voice, group, items, wav_segments(source / f"{name}.wav", len(items)))
                for name, voice, group, items in batches(inventory)]
    assets.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".speech-import-", dir=assets.parent) as temporary:
        staged = Path(temporary) / "speech"
        staged.mkdir()
        render_web(source, staged, prepared)
        verify(staged, {**inventory, "voices": list(VOICES)})
        if assets.exists():
            assets.rmdir()  # Only the empty destination accepted above.
        staged.rename(assets)


def render_web(source: Path, assets: Path, prepared):
    files = []
    sources = []
    for name, voice, group, items, chunks in prepared:
        url = "https://tts.wangwangit.com/" if group == "guide" else "https://www.text-to-speech.cn/"
        sources.append({"batch": name, "voice": VOICES[voice], "url": url,
                        "wav_sha256": hashlib.sha256((source / f"{name}.wav").read_bytes()).hexdigest(),
                        "ssml_sha256": hashlib.sha256((source / f"{name}.ssml.txt").read_bytes()).hexdigest()})
        with tempfile.TemporaryDirectory(prefix="sensefield-web-speech-") as directory:
            for item, pcm in zip(items, chunks):
                base = Path(directory) / (item["id"] + ".wav")
                write_wav(base, pcm)
                for rate in RATES if group == "fixed" else [100]:
                    relative = f"fixed/{voice}/{rate:03}/{item['id']}.ogg" if group == "fixed" else f"guide/{item['id']}.ogg"
                    target = assets / relative
                    duration = encode(base, target, rate)
                    files.append(entry(item, voice, rate, target, assets, duration))
    manifest = {"schema": 1, "source_selected_by_user": True, "test_fixture": False,
                "sources": sources,
                "source_rights": "User-selected website UI exports for this noncommercial hackathon trial. "
                "VoiceCraft declares research/learning/noncommercial use only; voice/audio rights remain "
                "with the original provider. This is not evidence of official Azure or commercial distribution authorization.",
                "files": files}
    (assets / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fish_sources(source: Path, inventory: dict):
    """Each recording has one exact catalog phrase; never split approximate pause tags."""
    metadata = json.loads((source / "source.json").read_text(encoding="utf-8"))
    selected = FISH_VOICES.get(metadata.get("voice"))
    if (not selected or metadata.get("model_id") != selected[0]
            or metadata.get("source_speed") != 0.9
            or not metadata.get("style_selected_by_user")):
        raise ValueError("Missing selected Fish voice/settings provenance")
    wanted = {item["id"]: item for item in inventory["fixed"]}
    seen = set()
    recordings = []
    for recording in metadata["files"]:
        identity = recording["text_sha256"]
        if identity in seen or wanted.get(identity, {}).get("text") != recording["text"]:
            raise ValueError("Duplicate, changed or unexpected Fish source text")
        spoken_text = recording["text"]
        if metadata["voice"] == "game_male":
            spoken_text = spoken_text.replace("，", "").replace(",", "")
        if recording.get("request_text") != f"[emphasis]{spoken_text}。":
            raise ValueError("Fish request text differs from selected phrase/style/pause settings")
        seen.add(identity)
        filename = recording["source_file"]
        if (Path(filename).name != filename or not filename.endswith(".mp3")
                or not re.fullmatch(r"(?:audio_)?[0-9a-f]{32}", recording.get("generation_id", ""))):
            raise ValueError("Invalid Fish source file/generation id")
        # The current UI shares a raw taskId; older exports used audio_<id>.
        # Bind the raw ID to its observed page URL rather than inventing an audio_ prefix.
        if not recording["generation_id"].startswith("audio_"):
            page = urlparse(recording.get("source_url", ""))
            params = parse_qs(page.query)
            if (page.scheme != "https" or page.netloc != "fish.audio"
                    or params.get("taskId") != [recording["generation_id"]]
                    or params.get("modelId") != [metadata["model_id"]]):
                raise ValueError("Fish task URL does not match the selected recording/voice")
        path = source / filename
        if hashlib.sha256(path.read_bytes()).hexdigest() != recording["sha256"]:
            raise ValueError("Fish source checksum mismatch")
        recordings.append((wanted[identity], path))
    if seen != set(wanted):
        raise ValueError(f"Incomplete Fish source recordings: missing {len(set(wanted) - seen)}")
    return metadata, recordings


def import_fish(source: Path, base_assets: Path, assets: Path, inventory: dict,
                additional_sources=()):
    """Preserve other recordings; atomically replace selected fixed voices."""
    if assets.exists() and any(assets.iterdir()):
        raise ValueError("Destination must be empty; preserve previous assets before replacing")
    if assets.resolve() == base_assets.resolve():
        raise ValueError("Fish import needs a separate staging destination")
    incoming = []
    incoming_voices = set()
    for directory in (source, *additional_sources):
        metadata, recordings = fish_sources(directory, inventory)
        voice = metadata["voice"]
        if voice in incoming_voices or voice not in inventory.get("voices", VOICES):
            raise ValueError("Duplicate or inactive Fish target voice")
        incoming_voices.add(voice)
        incoming.append((directory, metadata, recordings))
    baseline = json.loads((base_assets / "manifest.json").read_text(encoding="utf-8"))
    if baseline.get("schema") != 1 or baseline.get("test_fixture") or not baseline.get("source_selected_by_user"):
        raise ValueError("Fish import requires real baseline recordings")
    wanted = expected(inventory)
    assets.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".speech-import-", dir=assets.parent) as temporary:
        staged = Path(temporary) / "speech"
        staged.mkdir()
        files = []
        for audio in baseline["files"]:
            identity = (audio["voice"], audio["rate"], audio["id"])
            if audio["voice"] in incoming_voices or wanted.get(identity) != audio["text"]:
                continue
            relative = audio["path"]
            canonical = (f"guide/{audio['id']}.ogg" if audio["text"] in {x["text"] for x in inventory["guide"]}
                         else f"fixed/{audio['voice']}/{audio['rate']:03}/{audio['id']}.ogg")
            if relative != canonical:
                raise ValueError("Invalid baseline recording path")
            data = (base_assets / relative).read_bytes()
            if len(data) != audio["size"] or hashlib.sha256(data).hexdigest() != audio["sha256"]:
                raise ValueError("Baseline recording checksum mismatch")
            target = staged / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            files.append(audio)
        replaced_batches = set()
        for directory, metadata, recordings in incoming:
            voice = metadata["voice"]
            replaced_batches.update({FISH_VOICES[voice][2], f"fish-{voice.replace('_', '-')}-fixed"})
            for item, path in recordings:
                for rate in RATES:
                    target = staged / f"fixed/{voice}/{rate:03}/{item['id']}.ogg"
                    duration = encode(path, target, rate)
                    files.append(entry(item, voice, rate, target, staged, duration))
        sources = [record for record in baseline.get("sources", [])
                   if record.get("batch") not in replaced_batches]
        for directory, metadata, recordings in incoming:
            voice = metadata["voice"]
            sources.append({"batch": f"fish-{voice.replace('_', '-')}-fixed", "voice": FISH_VOICES[voice][1],
                        "model": metadata["model"], "model_id": metadata["model_id"],
                        "source_speed": metadata["source_speed"], "url": metadata["generation_page"],
                        "request_style": "emphasis", "added_pauses": False,
                        "strip_commas": voice == "game_male",
                        "source_ui": metadata.get("source_ui", "Fish Asset Library > More actions > Download"),
                        "source_manifest_sha256": hashlib.sha256((directory / "source.json").read_bytes()).hexdigest(),
                        "recordings": [{key: recording[key] for key in
                            ("text", "text_sha256", "request_text", "generation_id", "sha256", "source_url")
                            if key in recording} for recording in metadata["files"]]})
        manifest = {**baseline, "sources": sources, "files": files,
                    "source_rights": " ".join([baseline.get("source_rights", ""),
                                              *[metadata["source_rights"] for _, metadata, _ in incoming]])}
        (staged / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        verify(staged, inventory)
        if assets.exists():
            assets.rmdir()
        staged.rename(assets)


def fixture(assets: Path, inventory: dict):
    if assets.exists() and any(assets.iterdir()):
        verify(assets, inventory, True)
        return
    assets.mkdir(parents=True, exist_ok=True)
    files = []
    with tempfile.TemporaryDirectory(prefix="sensefield-speech-fixture-") as directory:
        base = Path(directory) / "synthetic.wav"
        pcm = b''.join(struct.pack('<h', int(2500 * math.sin(i * 2 * math.pi * 440 / 16000))) for i in range(4800))
        write_wav(base, pcm)
        rendered = Path(directory) / "synthetic.ogg"
        duration = encode(base, rendered, 100)
        for voice, rate, identity in expected(inventory):
            text = expected(inventory)[(voice, rate, identity)]
            group = "fixed" if text in {x["text"] for x in inventory["fixed"]} else "guide"
            relative = f"fixed/{voice}/{rate:03}/{identity}.ogg" if group == "fixed" else f"guide/{identity}.ogg"
            target = assets / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(rendered, target)
            files.append(entry({"id": identity, "text": text}, voice, rate, target, assets, duration))
    (assets / "manifest.json").write_text(json.dumps({"schema": 1, "source_selected_by_user": False,
            "test_fixture": True, "source": "synthetic test sine wave", "files": files}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    verify(assets, inventory, True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["catalog", "web-batches", "import-web-batches", "import-fish-fixed", "verify", "fixture"])
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument("--additional-source-dir", type=Path, action="append", default=[])
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--base-assets", type=Path, default=ASSETS)
    parser.add_argument("--allow-fixture", action="store_true")
    args = parser.parse_args()
    inventory = catalog()
    if args.action == "catalog":
        print(json.dumps(inventory, ensure_ascii=False, indent=2))
    elif args.action == "web-batches":
        export_web(args.destination or ROOT / "output/speech-source/text-to-speech-cn", inventory)
    elif args.action == "import-web-batches":
        if not args.source_dir:
            parser.error("import-web-batches requires --source-dir")
        import_web(args.source_dir, args.destination or ASSETS, inventory)
    elif args.action == "fixture":
        fixture(args.destination or ROOT / "output/speech-fixture/assets/speech", inventory)
    elif args.action == "import-fish-fixed":
        if not args.source_dir or not args.destination:
            parser.error("import-fish-fixed requires --source-dir and a separate --destination")
        import_fish(args.source_dir, args.base_assets, args.destination, inventory,
                    args.additional_source_dir)
    else:
        verify(args.destination or ASSETS, inventory, args.allow_fixture)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stderr.decode() if isinstance(error.stderr, bytes) else error.stderr)
        raise SystemExit(str(error))
