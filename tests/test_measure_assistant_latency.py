"""Physical assistant timing stays paired to external recordings and IDs."""

from __future__ import annotations

import pytest

import csv
import json
import sys

from mapassist.measure_assistant_latency import REQUIRED_COLUMNS, main, measure


def row(
    sample_id: str = "sample-1",
    *,
    session_id: str = "session-1",
    participant_id: str = "pseudonym-1",
    turn_id: str | None = None,
    voice_start_ms: str = "1000",
    voice_end_ms: str = "1500",
    assistant_audio_start_ms: str = "900",
    assistant_audio_stop_ms: str = "1300",
    interrupted_audio_id: str = "cue-old",
    actual_silence_ms: str = "1550",
    first_useful_answer_ms: str = "2100",
    answer_audio_id: str = "cue-answer",
    timing_source: str = "external_recording",
    final_recognition_ms: str | None = None,
) -> dict[str, str]:
    result = {
        "participant_id": participant_id,
        "session_id": session_id,
        "sample_id": sample_id,
        "turn_id": turn_id or f"turn-{sample_id}",
        "interrupted_audio_id": interrupted_audio_id,
        "answer_audio_id": answer_audio_id,
        "voice_start_ms": voice_start_ms,
        "voice_end_ms": voice_end_ms,
        "assistant_audio_start_ms": assistant_audio_start_ms,
        "assistant_audio_stop_ms": assistant_audio_stop_ms,
        "actual_silence_ms": actual_silence_ms,
        "first_useful_answer_ms": first_useful_answer_ms,
        "timing_source": timing_source,
        "source_note": "external recording rec-01, annotated offsets from recording start",
    }
    if final_recognition_ms is not None:
        result["final_recognition_ms"] = final_recognition_ms
    return result


def test_reports_nearest_rank_p95_for_paired_physical_audio_samples():
    rows = []
    for index, delay in enumerate(range(10, 201, 10), start=1):
        rows.append(row(
            sample_id=f"s-{index}",
            voice_start_ms="1000",
            voice_end_ms="1500",
            assistant_audio_start_ms="900",
            assistant_audio_stop_ms=str(1000 + delay),
            interrupted_audio_id=f"old-{index}",
            actual_silence_ms="1600",
            first_useful_answer_ms=str(1500 + delay),
            answer_audio_id=f"answer-{index}",
            final_recognition_ms=str(1500 + delay),
        ))

    report = measure(rows)
    overall = report["overall"]
    assert overall["barge_in_speech_start_to_audible_stop"] == {
        "paired_samples": 20, "missing_samples": 0, "p50_ms": 100.0, "p95_ms": 190.0}
    assert overall["user_voice_end_to_first_useful_answer"]["p95_ms"] == 190.0
    assert overall["user_voice_end_to_final_recognition"] == {
        "paired_samples": 20, "missing_samples": 0, "p50_ms": 100.0, "p95_ms": 190.0,
    }
    assert overall["actual_silence_to_first_useful_answer"]["p95_ms"] == 90.0


def test_missing_physical_audio_pairs_stay_unknown_and_are_counted():
    missing = row(
        "no-answer",
        voice_start_ms="",
        assistant_audio_start_ms="",
        assistant_audio_stop_ms="",
        interrupted_audio_id="",
        actual_silence_ms="",
        first_useful_answer_ms="",
        answer_audio_id="",
    )
    report = measure([row("paired"), missing])
    overall = report["overall"]
    assert overall["barge_in_speech_start_to_audible_stop"]["missing_samples"] == 1
    assert overall["user_voice_end_to_first_useful_answer"]["paired_samples"] == 1
    assert overall["user_voice_end_to_first_useful_answer"]["missing_samples"] == 1
    assert overall["actual_silence_to_first_useful_answer"]["paired_samples"] == 1
    assert overall["actual_silence_to_first_useful_answer"]["missing_samples"] == 1
    assert overall["user_voice_end_to_final_recognition"]["missing_samples"] == 2
    assert report["counts"]["missing_final_recognition"] == 2


def test_final_recognition_must_be_annotated_from_external_recording():
    report = measure([row(final_recognition_ms="1725")])
    assert report["overall"]["user_voice_end_to_final_recognition"] == {
        "paired_samples": 1, "missing_samples": 0, "p50_ms": 225.0, "p95_ms": 225.0,
    }
    assert measure([row(final_recognition_ms="1400")])["counts"][
        "final_recognition_before_user_voice_end"
    ] == 1


@pytest.mark.parametrize("field,value", [
    ("voice_end_ms", "-1"),
    ("actual_silence_ms", "nan"),
    ("first_useful_answer_ms", "inf"),
])
def test_negative_or_nonfinite_external_timestamps_are_rejected(field, value):
    sample = row()
    sample[field] = value
    with pytest.raises(ValueError, match="nonnegative and finite"):
        measure([sample])


def test_app_callbacks_cannot_be_used_as_a_physical_timing_source():
    with pytest.raises(ValueError, match="app callbacks cannot replace physical audio"):
        measure([row(timing_source="tts_callback")])


@pytest.mark.parametrize("overrides,expected", [
    ({"first_useful_answer_ms": "2200", "answer_audio_id": ""}, "answer_audio_id"),
    ({"first_useful_answer_ms": "", "answer_audio_id": "answer-1"}, "answer_audio_id"),
    ({"interrupted_audio_id": "old-1", "assistant_audio_stop_ms": ""}, "interrupted_audio_id"),
    ({"interrupted_audio_id": "", "assistant_audio_stop_ms": "1300"}, "interrupted_audio_id"),
])
def test_audio_ids_must_pair_with_physical_timestamps(overrides, expected):
    with pytest.raises(ValueError, match=expected):
        measure([row(**overrides)])


def test_cross_row_session_turn_and_audio_id_mismatches_are_rejected():
    first = row("one", turn_id="same-turn")
    second = row("two", turn_id="same-turn")
    with pytest.raises(ValueError, match="duplicate turn_id"):
        measure([first, second])

    first = row("one", answer_audio_id="same-audio")
    second = row("two", answer_audio_id="same-audio")
    with pytest.raises(ValueError, match="duplicate audio ID"):
        measure([first, second])

    first = row("one")
    second = row("two", participant_id="pseudonym-2")
    with pytest.raises(ValueError, match="multiple participant IDs"):
        measure([first, second])


def test_annotation_clocks_and_barge_sample_must_be_consistent():
    sample = row(voice_start_ms="1600", voice_end_ms="1500")
    with pytest.raises(ValueError, match="voice_start_ms must not follow"):
        measure([sample])

    sample = row(assistant_audio_start_ms="1400", assistant_audio_stop_ms="1550")
    with pytest.raises(ValueError, match="assistant audio active"):
        measure([sample])

    sample = row(actual_silence_ms="1499")
    with pytest.raises(ValueError, match="at or after voice_end_ms"):
        measure([sample])


def test_cli_writes_and_prints_a_json_report_from_external_annotations(tmp_path, monkeypatch, capsys):
    annotations = tmp_path / "annotations.csv"
    with annotations.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=sorted(REQUIRED_COLUMNS))
        writer.writeheader()
        writer.writerow(row())
        writer.writerow(row(
            sample_id="missing-answer",
            voice_start_ms="",
            assistant_audio_start_ms="",
            assistant_audio_stop_ms="",
            interrupted_audio_id="",
            actual_silence_ms="",
            first_useful_answer_ms="",
            answer_audio_id="",
        ))

    output = tmp_path / "reports" / "assistant-latency.json"
    monkeypatch.setattr(sys, "argv", [
        "mapassist-measure-assistant-latency", str(annotations), "--output", str(output),
    ])

    assert main() == 0
    report = json.loads(output.read_text(encoding="utf-8"))
    printed = json.loads(capsys.readouterr().out)
    assert report == printed
    assert report["overall"]["user_voice_end_to_first_useful_answer"] == {
        "paired_samples": 1, "missing_samples": 1, "p50_ms": 600.0, "p95_ms": 600.0,
    }
