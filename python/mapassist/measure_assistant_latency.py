"""Measure assistant turn timing from an external audio/video recording.

The tool deliberately accepts only externally annotated physical audio timing.
Android speech/audio callbacks are useful diagnostics, but do not establish
when a person stopped speaking or when a useful answer became audible.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Iterable


REQUIRED_COLUMNS = {
    "participant_id",
    "session_id",
    "sample_id",
    "turn_id",
    "interrupted_audio_id",
    "answer_audio_id",
    "voice_start_ms",
    "voice_end_ms",
    "assistant_audio_start_ms",
    "assistant_audio_stop_ms",
    "actual_silence_ms",
    "first_useful_answer_ms",
    "timing_source",
    "source_note",
}
OPTIONAL_COLUMNS = {"final_recognition_ms"}

TIMESTAMP_COLUMNS = (
    "voice_start_ms",
    "voice_end_ms",
    "assistant_audio_start_ms",
    "assistant_audio_stop_ms",
    "actual_silence_ms",
    "first_useful_answer_ms",
    "final_recognition_ms",
)


def _optional_time(row: dict[str, str], name: str, line_number: int) -> float | None:
    raw = (row.get(name) or "").strip()
    if not raw:
        return None
    try:
        value = float(raw)
    except ValueError as error:
        raise ValueError(f"Line {line_number}: invalid {name}") from error
    if not math.isfinite(value) or value < 0:
        raise ValueError(f"Line {line_number}: {name} must be nonnegative and finite")
    return value


def _p95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(0.95 * len(ordered)) - 1)
    return round(ordered[index], 1)


def _metric(values: list[float], missing: int) -> dict[str, int | float | None]:
    return {
        "paired_samples": len(values),
        "missing_samples": missing,
        "p50_ms": _p95_for_quantile(values, 0.50),
        "p95_ms": _p95(values),
    }


def _p95_for_quantile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return round(ordered[index], 1)


def measure(rows: list[dict[str, str]]) -> dict[str, object]:
    """Validate external annotations and summarize paired physical timings.

    All ``*_ms`` columns are offsets from the start of one external recording.
    Missing physical events stay unpaired and are reported as missing; they are
    never converted to zero or replaced with application callback timestamps.
    """
    seen_samples: set[tuple[str, str]] = set()
    seen_turns: set[tuple[str, str]] = set()
    seen_audio: set[tuple[str, str]] = set()
    session_participant: dict[str, str] = {}

    missing_voice_answer = 0
    missing_silence_answer = 0
    missing_final_recognition = 0
    answer_before_voice_end = 0
    answer_before_silence = 0
    final_recognition_before_voice_end = 0
    by_session_rows: dict[str, list[dict[str, object]]] = defaultdict(list)

    for line_number, row in enumerate(rows, start=2):
        participant_id = (row.get("participant_id") or "").strip()
        session_id = (row.get("session_id") or "").strip()
        sample_id = (row.get("sample_id") or "").strip()
        turn_id = (row.get("turn_id") or "").strip()
        source_note = (row.get("source_note") or "").strip()
        timing_source = (row.get("timing_source") or "").strip()
        if not participant_id or not session_id or not sample_id or not turn_id:
            raise ValueError(
                f"Line {line_number}: participant_id, session_id, sample_id, and turn_id are required"
            )
        if not source_note:
            raise ValueError(f"Line {line_number}: source_note is required")
        if timing_source != "external_recording":
            raise ValueError(
                f"Line {line_number}: timing_source must be external_recording; app callbacks cannot replace physical audio annotation"
            )

        sample_key = (session_id, sample_id)
        turn_key = (session_id, turn_id)
        if sample_key in seen_samples:
            raise ValueError(f"Line {line_number}: duplicate sample_id in session {session_id!r}")
        if turn_key in seen_turns:
            raise ValueError(f"Line {line_number}: duplicate turn_id in session {session_id!r}")
        previous_participant = session_participant.setdefault(session_id, participant_id)
        if previous_participant != participant_id:
            raise ValueError(f"Line {line_number}: session_id is associated with multiple participant IDs")
        seen_samples.add(sample_key)
        seen_turns.add(turn_key)

        interrupted_audio_id = (row.get("interrupted_audio_id") or "").strip()
        answer_audio_id = (row.get("answer_audio_id") or "").strip()
        values = {name: _optional_time(row, name, line_number) for name in TIMESTAMP_COLUMNS}
        voice_start = values["voice_start_ms"]
        voice_end = values["voice_end_ms"]
        playback_start = values["assistant_audio_start_ms"]
        playback_stop = values["assistant_audio_stop_ms"]
        silence_start = values["actual_silence_ms"]
        answer_start = values["first_useful_answer_ms"]
        final_recognition = values["final_recognition_ms"]

        if voice_end is None:
            raise ValueError(f"Line {line_number}: voice_end_ms is required from external audio")
        if voice_start is not None and voice_start > voice_end:
            raise ValueError(f"Line {line_number}: voice_start_ms must not follow voice_end_ms")
        if silence_start is not None and silence_start < voice_end:
            raise ValueError(f"Line {line_number}: actual_silence_ms must be at or after voice_end_ms")

        has_interrupted_id = bool(interrupted_audio_id)
        has_playback_edges = playback_start is not None or playback_stop is not None
        if has_interrupted_id != (playback_start is not None and playback_stop is not None):
            raise ValueError(
                f"Line {line_number}: interrupted_audio_id and both external assistant playback timestamps must be supplied together"
            )
        barge_delay: float | None = None
        if has_playback_edges:
            if voice_start is None:
                raise ValueError(f"Line {line_number}: voice_start_ms is required for a barge-in sample")
            if not playback_start <= voice_start <= playback_stop:
                raise ValueError(
                    f"Line {line_number}: barge-in recording must show assistant audio active when user speech starts"
                )
            audio_key = (session_id, interrupted_audio_id)
            if audio_key in seen_audio:
                raise ValueError(f"Line {line_number}: duplicate audio ID in session")
            seen_audio.add(audio_key)
            barge_delay = playback_stop - voice_start

        if bool(answer_audio_id) != (answer_start is not None):
            raise ValueError(
                f"Line {line_number}: answer_audio_id and first_useful_answer_ms must be supplied together"
            )
        if answer_audio_id:
            audio_key = (session_id, answer_audio_id)
            if audio_key in seen_audio:
                raise ValueError(f"Line {line_number}: duplicate audio ID in session")
            seen_audio.add(audio_key)

        row_metrics: dict[str, object] = {
            "participant_id": participant_id,
            "session_id": session_id,
            "sample_id": sample_id,
            "turn_id": turn_id,
            "barge_in_stop_ms": None if barge_delay is None else round(barge_delay, 1),
            "voice_end_to_answer_ms": None,
            "silence_to_answer_ms": None,
            "voice_end_to_final_recognition_ms": None,
            "answer_before_voice_end": False,
            "answer_before_silence": False,
            "final_recognition_before_voice_end": False,
        }
        if final_recognition is None:
            missing_final_recognition += 1
        else:
            final_delay = final_recognition - voice_end
            row_metrics["voice_end_to_final_recognition_ms"] = round(final_delay, 1)
            row_metrics["final_recognition_before_voice_end"] = final_recognition < voice_end
            if final_recognition < voice_end:
                final_recognition_before_voice_end += 1

        if answer_start is None:
            missing_voice_answer += 1
            if silence_start is not None:
                missing_silence_answer += 1
        else:
            voice_delay = answer_start - voice_end
            row_metrics["voice_end_to_answer_ms"] = round(voice_delay, 1)
            row_metrics["answer_before_voice_end"] = answer_start < voice_end
            if answer_start < voice_end:
                answer_before_voice_end += 1
            if silence_start is None:
                missing_silence_answer += 1
            else:
                silence_delay = answer_start - silence_start
                row_metrics["silence_to_answer_ms"] = round(silence_delay, 1)
                row_metrics["answer_before_silence"] = answer_start < silence_start
                if answer_start < silence_start:
                    answer_before_silence += 1

        by_session_rows[session_id].append(row_metrics)

    if not rows:
        raise ValueError("At least one external annotation row is required")

    def summarize(selected: Iterable[dict[str, object]]) -> dict[str, object]:
        items = list(selected)
        barge = [float(item["barge_in_stop_ms"]) for item in items
                 if item["barge_in_stop_ms"] is not None]
        voice = [float(item["voice_end_to_answer_ms"]) for item in items
                 if item["voice_end_to_answer_ms"] is not None]
        silence = [float(item["silence_to_answer_ms"]) for item in items
                   if item["silence_to_answer_ms"] is not None]
        final_recognition = [float(item["voice_end_to_final_recognition_ms"]) for item in items
                             if item["voice_end_to_final_recognition_ms"] is not None]
        return {
            "samples": len(items),
            "barge_in_speech_start_to_audible_stop": _metric(barge, len(items) - len(barge)),
            "user_voice_end_to_final_recognition": _metric(
                final_recognition, len(items) - len(final_recognition)),
            "user_voice_end_to_first_useful_answer": _metric(voice, len(items) - len(voice)),
            "actual_silence_to_first_useful_answer": _metric(silence, len(items) - len(silence)),
        }

    # The aggregate lists carry one value per row. Build per-session summaries
    # from the already validated row values without filling missing pairs.
    per_session = {
        session_id: summarize(items)
        for session_id, items in sorted(by_session_rows.items())
    }
    overall_rows = [item for items in by_session_rows.values() for item in items]
    return {
        "schema_version": 1,
        "timebase": "milliseconds relative to the start of the external recording",
        "quantile_method": "nearest rank; ceil(q * n) - 1",
        "timing_source": "external_recording only",
        "overall": summarize(overall_rows),
        "by_session": per_session,
        "counts": {
            "samples": len(rows),
            "missing_final_recognition": missing_final_recognition,
            "final_recognition_before_user_voice_end": final_recognition_before_voice_end,
            "answer_before_user_voice_end": answer_before_voice_end,
            "answer_before_actual_silence": answer_before_silence,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_file", type=Path, help="Externally annotated audio/video timing CSV")
    parser.add_argument("--output", type=Path, help="Optional JSON report path")
    args = parser.parse_args()
    try:
        with args.csv_file.open(newline="", encoding="utf-8-sig") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames is None or not REQUIRED_COLUMNS.issubset(reader.fieldnames):
                missing = sorted(REQUIRED_COLUMNS - set(reader.fieldnames or []))
                raise ValueError(f"CSV is missing required columns: {', '.join(missing)}")
            report = json.dumps(measure(list(reader)), ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(report, encoding="utf-8")
        print(report, end="")
        return 0
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
