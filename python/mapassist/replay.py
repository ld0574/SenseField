"""Replay a local recording without storing decoded frames."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import statistics
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterator

from .native import Pipeline, default_library_path


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _file_record(path: Path) -> dict:
    resolved = path.resolve()
    return {"path": str(resolved), "sha256": _sha256(resolved)}


def _profile_assets(profile: Path) -> list[dict]:
    data = json.loads(profile.read_text(encoding="utf-8"))
    templates = data.get("templates", {})
    if not isinstance(templates, dict):
        raise ValueError("Profile templates must be an object")
    assets = []
    for role, value in sorted(templates.items()):
        if not value:
            continue
        if not isinstance(value, str):
            raise ValueError(f"Profile template {role} must be a path")
        path = (profile.parent / value).resolve()
        if not path.is_file():
            raise FileNotFoundError(f"Missing profile template: {path}")
        assets.append({"role": role, **_file_record(path)})
    return assets


def _write_json_atomic(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n",
                             encoding="utf-8")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def video_dimensions(path: Path) -> tuple[int, int]:
    process = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height", "-of", "json", str(path)],
        check=True, text=True, capture_output=True,
    )
    streams = json.loads(process.stdout).get("streams", [])
    if not streams:
        raise ValueError(f"No video stream in {path}")
    width, height = int(streams[0]["width"]), int(streams[0]["height"])
    if width <= height:
        raise ValueError("Expected a landscape recording; rotate it before calibration")
    return width, height


def decoded_frames(path: Path, width: int, height: int, fps: int) -> Iterator[bytes]:
    command = [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
        "-noautorotate", "-i", str(path), "-vf", f"fps={fps}",
        "-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1",
    ]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdout is not None
    frame_size = width * height * 4
    try:
        while True:
            frame = process.stdout.read(frame_size)
            if not frame:
                break
            if len(frame) != frame_size:
                raise RuntimeError("Truncated decoded video frame")
            yield frame
    finally:
        process.stdout.close()
        stderr = process.stderr.read().decode(errors="replace") if process.stderr else ""
        return_code = process.wait()
        if return_code:
            raise RuntimeError(f"ffmpeg failed ({return_code}): {stderr[-2000:]}")


def run(video: Path, profile: Path, output: Path, fps: int, library: Path | None = None,
        metadata: Path | None = None) -> dict:
    if fps < 1 or fps > 30:
        raise ValueError("fps must be between 1 and 30")
    video = video.resolve()
    profile = profile.resolve()
    library = (library or default_library_path()).resolve()
    for label, path in (("video", video), ("profile", profile), ("native library", library)):
        if not path.is_file():
            raise FileNotFoundError(f"Missing {label}: {path}")
    source_records = {
        "video": _file_record(video),
        "profile": _file_record(profile),
        "profile_assets": _profile_assets(profile),
        "native_library": _file_record(library),
    }
    width, height = video_dimensions(video)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(f".{output.name}.{os.getpid()}.tmp")
    total_frames = 0
    total_observations = 0
    total_cues = 0
    processing_ms = []
    try:
        with Pipeline(profile, library) as pipeline, temporary.open("w", encoding="utf-8") as stream:
            for index, frame in enumerate(decoded_frames(video, width, height, fps)):
                timestamp_ms = round(index * 1000 / fps)
                started = time.perf_counter()
                observations, cues = pipeline.step(frame, width, height, timestamp_ms)
                processing_ms.append((time.perf_counter() - started) * 1000)
                record = {"frame_index": index, "timestamp_ms": timestamp_ms,
                          "observations": observations, "cues": cues}
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")
                total_frames += 1
                total_observations += len(observations)
                total_cues += len(cues)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    ordered_ms = sorted(processing_ms)
    stats = {"frames": total_frames, "observations": total_observations,
             "cues": total_cues, "fps": fps, "width": width, "height": height,
             "profile_verified": bool(pipeline.profile_json.get("verified", False)),
             "desktop_processing_ms_p50": round(statistics.median(ordered_ms), 2) if ordered_ms else None,
             "desktop_processing_ms_p95": round(
                 ordered_ms[max(0, (95 * len(ordered_ms) + 99) // 100 - 1)], 2
             ) if ordered_ms else None}
    metadata = metadata or Path(f"{output}.meta.json")
    provenance = {"schema_version": 1, **source_records,
                  "predictions": _file_record(output), "stats": stats}
    _write_json_atomic(metadata, provenance)
    return {**stats, "metadata": str(metadata.resolve())}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("--profile", type=Path, default=Path("profiles/hok_android_unverified.json"))
    parser.add_argument("--output", type=Path, required=True, help="Private JSONL output")
    parser.add_argument("--fps", type=int, default=12)
    parser.add_argument("--library", type=Path)
    parser.add_argument("--metadata", type=Path,
                        help="Provenance JSON (default: <output>.meta.json)")
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.video, args.profile, args.output, args.fps, args.library,
                             args.metadata),
                         ensure_ascii=False, indent=2))
    except (ValueError, FileNotFoundError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
