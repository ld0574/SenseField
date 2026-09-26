from __future__ import annotations

from pathlib import Path

import pytest

from mapassist.android_session_log import parse_session_log


SESSION_ID = "session-42"


def _session_log(*cue_lines: str, summary_overrides: str = "") -> str:
    return "\n".join(
        [
            f"I/MapAssist: SessionStart sessionId={SESSION_ID} startId=7 "
            "startedElapsedRealtimeMs=1000",
            *cue_lines,
            "I/MapAssist: SessionSummary "
            f"sessionId={SESSION_ID} durationMs=900 processedFrames=8 "
            "landscapeProcessedFrames=5 firstProcessedElapsedRealtimeMs=1000 "
            "lastProcessedElapsedRealtimeMs=1400 maxProcessedGapMs=73 "
            "detected=3 queued=2 stale=1 audioFailures=0 reason=stopped "
            f"{summary_overrides}",
        ]
    ) + "\n"


def _cue(
    cue_id: str | None,
    *,
    kind: int = 1,
    stale: bool = False,
    audio_queued: bool = True,
    observed_at_ms: int = 1100,
    frame_age_ms: int = 12,
    native_micros: int = 8000,
) -> str:
    cue_id_field = "" if cue_id is None else f"cueId={cue_id} "
    return (
        "I/MapAssist: CueEvent "
        f"sessionId={SESSION_ID} {cue_id_field}kind={kind} direction=1 "
        f"observedAtMs={observed_at_ms} frameAgeMs={frame_age_ms} "
        f"nativeMicros={native_micros} stale={str(stale).lower()} "
        f"audioQueued={str(audio_queued).lower()}"
    )


def _write_log(tmp_path: Path, content: str) -> Path:
    path = tmp_path / "logcat.txt"
    path.write_text(content, encoding="utf-8")
    return path


def test_parse_complete_session_counts_cues_and_landscape_metrics(
    tmp_path: Path,
) -> None:
    path = _write_log(
        tmp_path,
        _session_log(
            _cue("session-42:1", kind=1, audio_queued=True, native_micros=7000),
            _cue("session-42:2", kind=2, audio_queued=True, native_micros=9000),
            _cue("session-42:3", kind=3, stale=True, audio_queued=False),
        ),
    )

    parsed = parse_session_log(path, SESSION_ID)

    assert parsed["schema_version"] == 1
    assert parsed["start_id"] == 7
    assert parsed["landscape_span_ms"] == 400
    assert parsed["average_landscape_fps"] == 10.0
    assert parsed["native_p95_micros"] == 9000.0
    assert parsed["non_stale_cue_ids"] == ["session-42:1", "session-42:2"]
    assert parsed["summary"]["maxProcessedGapMs"] == 73
    assert [cue["kind"] for cue in parsed["cues"]] == [
        "main_enemy",
        "minimap_enemy",
        "danger_ping",
    ]


@pytest.mark.parametrize(
    ("cue_lines", "message"),
    [
        (
            [_cue("session-42:1"), _cue("session-42:1"), _cue("session-42:3", stale=True, audio_queued=False)],
            "Duplicate CueEvent cueId",
        ),
        (
            [_cue(None), _cue("session-42:2"), _cue("session-42:3", stale=True, audio_queued=False)],
            "CueEvent record is missing cueId",
        ),
    ],
)
def test_rejects_duplicate_or_missing_cue_id(
    tmp_path: Path, cue_lines: list[str], message: str
) -> None:
    path = _write_log(tmp_path, _session_log(*cue_lines))

    with pytest.raises(ValueError, match=message):
        parse_session_log(path, SESSION_ID)


@pytest.mark.parametrize("field", ["detected", "queued", "stale", "audioFailures"])
def test_rejects_summary_count_that_disagrees_with_cues(
    tmp_path: Path, field: str
) -> None:
    expected = {"detected": 3, "queued": 2, "stale": 1, "audioFailures": 0}
    path = _write_log(
        tmp_path,
        _session_log(
            _cue("session-42:1"),
            _cue("session-42:2"),
            _cue("session-42:3", stale=True, audio_queued=False),
            summary_overrides=f"{field}={expected[field] + 1}",
        ),
    )

    with pytest.raises(ValueError, match=rf"SessionSummary\.{field}"):
        parse_session_log(path, SESSION_ID)


def test_keeps_unqueued_audio_failure_and_stale_cue_out_of_non_stale_ids(
    tmp_path: Path,
) -> None:
    path = _write_log(
        tmp_path,
        _session_log(
            _cue("session-42:1", audio_queued=False),
            _cue("session-42:2", stale=True, audio_queued=False),
            _cue("session-42:3", audio_queued=True),
            summary_overrides="queued=1 stale=1 audioFailures=1",
        ),
    )

    parsed = parse_session_log(path, SESSION_ID)

    assert parsed["summary"]["queued"] == 1
    assert parsed["summary"]["stale"] == 1
    assert parsed["summary"]["audioFailures"] == 1
    assert parsed["non_stale_cue_ids"] == ["session-42:1", "session-42:3"]
    assert parsed["native_p95_micros"] == 8000.0


def test_rejects_expired_cue_that_was_queued(
    tmp_path: Path,
) -> None:
    path = _write_log(
        tmp_path,
        _session_log(
            _cue("session-42:1", stale=True, audio_queued=True),
            _cue("session-42:2"),
            _cue("session-42:3", stale=True, audio_queued=False),
            summary_overrides="queued=2 stale=2 audioFailures=0",
        ),
    )

    with pytest.raises(ValueError, match="cannot have audioQueued=true"):
        parse_session_log(path, SESSION_ID)
