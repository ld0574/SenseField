"""Summarize externally annotated visible-evidence to audible-cue latency."""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from pathlib import Path

from .android_session_log import KIND_BY_CODE

# Audible annotation follows the Android output vocabulary, not the older
# three-class visual-detection evaluator. This includes current near-zone cues
# without changing the independent quality gate's supported event schema.
KINDS = tuple(dict.fromkeys(KIND_BY_CODE.values()))

REQUIRED_COLUMNS = {
    "event_id", "cue_id", "kind", "evidence_ms", "audio_ms", "source_note",
}


def _p95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(0.95 * len(ordered)) - 1)
    return round(ordered[index], 1)


def measure(rows: list[dict[str, str]]) -> dict:
    delays: dict[str, list[float]] = {kind: [] for kind in KINDS}
    missing: dict[str, int] = {kind: 0 for kind in KINDS}
    event_ids: set[str] = set()
    cue_ids: set[str] = set()
    for line_number, row in enumerate(rows, start=2):
        event_id = (row.get("event_id") or "").strip()
        cue_id = (row.get("cue_id") or "").strip()
        source_note = (row.get("source_note") or "").strip()
        if not event_id:
            raise ValueError(f"Line {line_number}: event_id is required")
        if event_id in event_ids:
            raise ValueError(f"Line {line_number}: duplicate event_id {event_id!r}")
        if not cue_id:
            raise ValueError(f"Line {line_number}: cue_id is required")
        if cue_id in cue_ids:
            raise ValueError(f"Line {line_number}: duplicate cue_id {cue_id!r}")
        if not source_note:
            raise ValueError(f"Line {line_number}: source_note is required")
        event_ids.add(event_id)
        cue_ids.add(cue_id)
        kind = (row.get("kind") or "").strip()
        if kind not in KINDS:
            raise ValueError(f"Line {line_number}: unknown kind {kind!r}")
        try:
            evidence_ms = float(row["evidence_ms"])
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError(f"Line {line_number}: invalid evidence_ms") from error
        if not math.isfinite(evidence_ms) or evidence_ms < 0:
            raise ValueError(f"Line {line_number}: evidence_ms must be nonnegative and finite")
        audio_text = (row.get("audio_ms") or "").strip()
        if not audio_text:
            missing[kind] += 1
            continue
        try:
            audio_ms = float(audio_text)
        except ValueError as error:
            raise ValueError(f"Line {line_number}: invalid audio_ms") from error
        if not math.isfinite(audio_ms) or audio_ms < evidence_ms:
            raise ValueError(f"Line {line_number}: audio_ms precedes evidence or is not finite")
        delays[kind].append(audio_ms - evidence_ms)

    per_kind = {
        kind: {"paired_events": len(delays[kind]), "missing_audio": missing[kind],
               "p95_ms": _p95(delays[kind])}
        for kind in KINDS
    }
    all_delays = [value for group in delays.values() for value in group]
    return {"by_kind": per_kind,
            "overall": {"paired_events": len(all_delays),
                        "missing_audio": sum(missing.values()),
                        "p95_ms": _p95(all_delays)}}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_file", type=Path, help="External recording event times in milliseconds")
    parser.add_argument("--output", type=Path, help="Optional JSON report")
    args = parser.parse_args()
    try:
        with args.csv_file.open(newline="", encoding="utf-8-sig") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames is None or not REQUIRED_COLUMNS.issubset(reader.fieldnames):
                raise ValueError(
                    "CSV needs event_id,cue_id,kind,evidence_ms,audio_ms,source_note columns"
                )
            result = measure(list(reader))
        report = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(report, encoding="utf-8")
        print(report, end="")
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
