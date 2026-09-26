"""Parse and cross-check auditable Android capture-session logcat records."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path


KIND_BY_CODE = {1: "main_enemy", 2: "minimap_enemy", 3: "danger_ping"}


def _fields(line: str, marker: str) -> dict[str, str] | None:
    token = marker + " "
    if token not in line:
        return None
    values: dict[str, str] = {}
    for part in line.split(token, 1)[1].strip().split():
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        values[key] = value
    return values


def _required(values: dict[str, str], key: str, marker: str) -> str:
    value = values.get(key)
    if value is None or not value:
        raise ValueError(f"{marker} record is missing {key}")
    return value


def _integer(values: dict[str, str], key: str, marker: str) -> int:
    value = _required(values, key, marker)
    try:
        return int(value)
    except ValueError as error:
        raise ValueError(f"{marker}.{key} must be an integer") from error


def _boolean(values: dict[str, str], key: str, marker: str) -> bool:
    value = _required(values, key, marker)
    if value not in {"true", "false"}:
        raise ValueError(f"{marker}.{key} must be true or false")
    return value == "true"


def _p95(values: list[int]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return float(ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)])


def parse_session_log(path: Path, session_id: str) -> dict[str, object]:
    """Return one internally consistent session from an adb logcat text file."""
    if not session_id or any(character.isspace() for character in session_id):
        raise ValueError("session_id must be nonempty and contain no whitespace")
    starts: list[dict[str, str]] = []
    summaries: list[dict[str, str]] = []
    cue_records: list[dict[str, str]] = []
    with path.open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            for marker, destination in (
                ("SessionStart", starts),
                ("CueEvent", cue_records),
                ("SessionSummary", summaries),
            ):
                values = _fields(line, marker)
                if values is not None and values.get("sessionId") == session_id:
                    destination.append(values)
                    break
    if len(starts) != 1:
        raise ValueError(
            f"Expected exactly one SessionStart for {session_id}, found {len(starts)}"
        )
    if len(summaries) != 1:
        raise ValueError(
            f"Expected exactly one SessionSummary for {session_id}, found {len(summaries)}"
        )

    start = starts[0]
    summary_raw = summaries[0]
    started_ms = _integer(start, "startedElapsedRealtimeMs", "SessionStart")
    summary_integer_fields = (
        "durationMs", "processedFrames", "landscapeProcessedFrames",
        "firstProcessedElapsedRealtimeMs", "lastProcessedElapsedRealtimeMs",
        "maxProcessedGapMs", "detected", "queued", "stale", "audioFailures",
    )
    summary = {
        key: _integer(summary_raw, key, "SessionSummary")
        for key in summary_integer_fields
    }
    summary["reason"] = _required(summary_raw, "reason", "SessionSummary")
    if any(summary[key] < 0 for key in (
        "durationMs", "processedFrames", "landscapeProcessedFrames",
        "maxProcessedGapMs", "detected", "queued", "stale", "audioFailures",
    )):
        raise ValueError("SessionSummary counters and durations must be nonnegative")
    if summary["landscapeProcessedFrames"] > summary["processedFrames"]:
        raise ValueError(
            "SessionSummary.landscapeProcessedFrames cannot exceed processedFrames"
        )

    cues: list[dict[str, object]] = []
    seen_cue_ids: set[str] = set()
    for values in cue_records:
        cue_id = _required(values, "cueId", "CueEvent")
        if cue_id in seen_cue_ids:
            raise ValueError(f"Duplicate CueEvent cueId {cue_id!r}")
        expected_cue_id = f"{session_id}:{len(cues) + 1}"
        if cue_id != expected_cue_id:
            raise ValueError(
                f"CueEvent cueId {cue_id!r} is not the expected sequence value "
                f"{expected_cue_id!r}"
            )
        seen_cue_ids.add(cue_id)
        kind_code = _integer(values, "kind", "CueEvent")
        if kind_code not in KIND_BY_CODE:
            raise ValueError(f"CueEvent has unknown kind code {kind_code}")
        stale = _boolean(values, "stale", "CueEvent")
        audio_queued = _boolean(values, "audioQueued", "CueEvent")
        if stale and audio_queued:
            raise ValueError(f"Stale cue {cue_id!r} cannot have audioQueued=true")
        direction = _integer(values, "direction", "CueEvent")
        if direction not in range(5):
            raise ValueError(f"CueEvent {cue_id!r} has invalid direction {direction}")
        cue = {
            "cue_id": cue_id,
            "kind": KIND_BY_CODE[kind_code],
            "kind_code": kind_code,
            "direction": direction,
            "observed_at_ms": _integer(values, "observedAtMs", "CueEvent"),
            "frame_age_ms": _integer(values, "frameAgeMs", "CueEvent"),
            "native_micros": _integer(values, "nativeMicros", "CueEvent"),
            "stale": stale,
            "audio_queued": audio_queued,
        }
        if any(cue[key] < 0 for key in (
            "observed_at_ms", "frame_age_ms", "native_micros",
        )):
            raise ValueError(f"CueEvent {cue_id!r} has a negative timing value")
        if not started_ms <= cue["observed_at_ms"] <= started_ms + summary["durationMs"]:
            raise ValueError(f"CueEvent {cue_id!r} falls outside the session duration")
        cues.append(cue)

    expected = {
        "detected": len(cues),
        "queued": sum(bool(item["audio_queued"]) for item in cues),
        "stale": sum(bool(item["stale"]) for item in cues),
        "audioFailures": sum(
            not item["stale"] and not item["audio_queued"] for item in cues
        ),
    }
    for key, value in expected.items():
        if summary[key] != value:
            raise ValueError(
                f"SessionSummary.{key}={summary[key]} does not match parsed cues {value}"
            )

    first_ms = summary["firstProcessedElapsedRealtimeMs"]
    last_ms = summary["lastProcessedElapsedRealtimeMs"]
    landscape_frames = summary["landscapeProcessedFrames"]
    if landscape_frames == 0:
        if first_ms != -1 or last_ms != -1:
            raise ValueError("Empty landscape session must use -1 first/last timestamps")
        landscape_span_ms = 0
        average_fps = 0.0
    else:
        if first_ms < started_ms or last_ms < first_ms:
            raise ValueError("Landscape processed-frame timestamps are inconsistent")
        if last_ms > started_ms + summary["durationMs"]:
            raise ValueError("Landscape processed-frame timestamps exceed session duration")
        landscape_span_ms = last_ms - first_ms
        if summary["maxProcessedGapMs"] > landscape_span_ms and landscape_frames > 1:
            raise ValueError("SessionSummary.maxProcessedGapMs exceeds landscape span")
        average_fps = (
            (landscape_frames - 1) * 1000.0 / landscape_span_ms
            if landscape_frames > 1 and landscape_span_ms > 0 else 0.0
        )

    non_stale = [item for item in cues if not item["stale"]]
    return {
        "schema_version": 1,
        "session_id": session_id,
        "start_id": _integer(start, "startId", "SessionStart"),
        "started_elapsed_realtime_ms": started_ms,
        "summary": summary,
        "landscape_span_ms": landscape_span_ms,
        "average_landscape_fps": round(average_fps, 3),
        "cues": cues,
        "non_stale_cue_ids": [str(item["cue_id"]) for item in non_stale],
        "native_p95_micros": _p95([
            int(item["native_micros"]) for item in non_stale
        ]),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logcat", type=Path)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = parse_session_log(args.logcat, args.session_id)
        rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
