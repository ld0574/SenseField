from __future__ import annotations

from pathlib import Path

import pytest

from mapassist.android_session_log import parse_session_log


SESSION_ID = "session-42"


def _session_log(*cue_lines: str, summary_overrides: str = "") -> str:
    return "\n".join(
        [
            f"I/MapAssistCapture: SessionStart sessionId={SESSION_ID} startId=7 "
            "startedElapsedRealtimeMs=1000",
            *cue_lines,
            "I/MapAssistCapture: SessionSummary "
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
        "I/MapAssistCapture: CueEvent "
        f"sessionId={SESSION_ID} {cue_id_field}kind={kind} direction=1 "
        f"observedAtMs={observed_at_ms} frameAgeMs={frame_age_ms} "
        f"nativeMicros={native_micros} stale={str(stale).lower()} "
        f"audioQueued={str(audio_queued).lower()}"
    )


def _write_log(tmp_path: Path, content: str) -> Path:
    tmp_path.mkdir(parents=True, exist_ok=True)
    path = tmp_path / "logcat.txt"
    path.write_text(content, encoding="utf-8")
    return path


def _dispatch(
    *,
    cue_id: str = "session-42:1",
    requested: int = 1,
    accepted: int = 1,
    outcome: str = "ACCEPTED",
    created: int = 1050,
    expires: int = 1850,
    category: str = "VISION_MEMORY",
    kind: str = "VISION_APPEAR",
    drop_reason: str = "none",
) -> str:
    return (
        "I/MapAssistCapture: CueDispatch "
        f"sessionId={SESSION_ID} cueId={cue_id} eventKey=event:1 "
        f"kind={kind} "
        f"category={category} priority=40 createdAtMs={created} "
        f"expiresAtMs={expires} requestedMask={requested} "
        f"acceptedMask={accepted} outcome={outcome} dropReason={drop_reason}"
    )


def _playback(
    channel: str = "TONE", at_ms: int = 1060, result: str = "STARTED",
    cue_id: str = "session-42:1",
) -> str:
    return (
        "I/MapAssistCapture: CuePlayback "
        f"sessionId={SESSION_ID} cueId={cue_id} channel={channel} "
        f"atMs={at_ms} result={result}"
    )


def _schema2_log(
    dispatch: str,
    *playbacks: str,
    summary_overrides: str = "",
    fill_unmatched_cue_dispatches: bool = True,
) -> str:
    content = _session_log(
        _cue("session-42:1"),
        _cue("session-42:2"),
        _cue("session-42:3", kind=3, stale=True, audio_queued=False),
        summary_overrides=summary_overrides,
    )
    dispatches = [dispatch]
    if fill_unmatched_cue_dispatches:
        dispatch_id = dispatch.split(" cueId=", 1)[1].split(" ", 1)[0]
        defaults = [
            _dispatch(cue_id="session-42:1"),
            _dispatch(cue_id="session-42:2"),
            _dispatch(
                cue_id="session-42:3", accepted=0, outcome="DROPPED",
                created=1100, expires=1100, category="DANGER",
                kind="DANGER_PING", drop_reason="expired",
            ),
        ]
        dispatches.extend(
            record for record in defaults
            if record.split(" cueId=", 1)[1].split(" ", 1)[0] != dispatch_id
        )
    records = "\n".join((*dispatches, *playbacks))
    return content.replace(
        "I/MapAssistCapture: SessionSummary",
        records + "\nI/MapAssistCapture: SessionSummary",
    )


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


def test_ignores_records_from_other_logcat_tags(tmp_path: Path) -> None:
    content = _session_log(
        _cue("session-42:1"),
        _cue("session-42:2"),
        _cue("session-42:3", stale=True, audio_queued=False),
    ).replace("MapAssistCapture", "UnrelatedApp")
    path = _write_log(tmp_path, content)

    with pytest.raises(ValueError, match="found 0"):
        parse_session_log(path, SESSION_ID)


def test_parses_enhanced_dispatch_playback_and_capture_health(tmp_path: Path) -> None:
    records = "\n".join((
        _dispatch(
            cue_id="session-42:1", requested=6, accepted=6,
            category="PLAYER_STATE", kind="PLAYER_DEAD",
        ),
        _dispatch(
            cue_id="session-42:2", requested=6, accepted=6,
            category="PLAYER_STATE", kind="PLAYER_ALIVE",
        ),
        _dispatch(
            cue_id="session-42:3", requested=1, accepted=0, outcome="DROPPED",
            created=1100, expires=1100, category="DANGER", kind="DANGER_PING",
            drop_reason="expired",
        ),
        "I/MapAssistCapture: CuePlayback sessionId=session-42 cueId=session-42:1 "
        "channel=SPEECH atMs=1060 result=STARTED",
        "I/MapAssistCapture: CaptureHealth sessionId=session-42 state=RECOVERING "
        "reason=frame_starvation attempt=1 readerGeneration=2 elapsedSinceFrameMs=1100 "
        "elapsedSinceProcessedMs=1180",
    ))
    content = _session_log(
        _cue("session-42:1", kind=4),
        _cue("session-42:2", kind=5),
        _cue("session-42:3", kind=3, stale=True, audio_queued=False),
    ).replace(
        "I/MapAssistCapture: SessionSummary",
        records + "\nI/MapAssistCapture: SessionSummary",
    ).replace("reason=stopped ",
              "reason=stopped starvationCount=1 recoveryAttempts=1 recoverySuccesses=0 ")
    path = _write_log(tmp_path, content)

    parsed = parse_session_log(path, SESSION_ID)

    assert parsed["schema_version"] == 2
    assert parsed["non_stale_cue_ids"] == ["session-42:1"]
    assert parsed["dispatches"][0]["category"] == "PLAYER_STATE"
    assert parsed["capture_health"][0]["state"] == "RECOVERING"
    assert parsed["capture_health"][0]["elapsed_since_processed_ms"] == 1180


def test_rejects_schema2_session_with_missing_cue_dispatch(tmp_path: Path) -> None:
    path = _write_log(
        tmp_path,
        _schema2_log(_dispatch(), fill_unmatched_cue_dispatches=False),
    )

    with pytest.raises(
        ValueError, match="CueEvent 'session-42:2' is missing its CueDispatch record"
    ):
        parse_session_log(path, SESSION_ID)


@pytest.mark.parametrize(
    ("dispatch", "message"),
    [
        (_dispatch(requested=1, accepted=2), "invalid channel masks"),
        (_dispatch(accepted=1, outcome="DROPPED"), "outcome does not match"),
        (_dispatch(category="UNKNOWN"), "invalid category"),
        (_dispatch(created=1901), "falls outside the session"),
        (_dispatch(created=1200, expires=1199), "precedes createdAtMs"),
    ],
)
def test_rejects_invalid_dispatch_schema2_records(
    tmp_path: Path, dispatch: str, message: str
) -> None:
    path = _write_log(tmp_path, _schema2_log(dispatch))

    with pytest.raises(ValueError, match=message):
        parse_session_log(path, SESSION_ID)


def test_allows_synchronous_renderer_failures_without_accepted_channel(
    tmp_path: Path,
) -> None:
    path = _write_log(
        tmp_path,
        _schema2_log(
            _dispatch(
                cue_id="system:1", requested=1, accepted=0, outcome="DROPPED"
            ),
            _playback(result="FAILED", cue_id="system:1"),
        ),
    )

    parsed = parse_session_log(path, SESSION_ID)

    assert parsed["playbacks"][0]["result"] == "FAILED"
    assert parsed["non_stale_cue_ids"] == []


def test_rejects_playback_for_channel_that_was_not_requested(
    tmp_path: Path,
) -> None:
    path = _write_log(
        tmp_path,
        _schema2_log(
            _dispatch(requested=1, accepted=0, outcome="DROPPED"),
            _playback(channel="SPEECH", result="UNAVAILABLE"),
        ),
    )

    with pytest.raises(ValueError, match="was not requested"):
        parse_session_log(path, SESSION_ID)


def test_requires_started_channel_to_be_accepted_and_before_expiry(
    tmp_path: Path,
) -> None:
    missing_channel = _write_log(
        tmp_path / "missing-channel",
        _schema2_log(_dispatch(), _playback(channel="SPEECH")),
    )
    with pytest.raises(ValueError, match="is not accepted"):
        parse_session_log(missing_channel, SESSION_ID)

    late_start = _write_log(
        tmp_path / "late-start",
        _schema2_log(
            _dispatch(expires=1060),
            _playback(at_ms=1061),
        ),
    )
    with pytest.raises(ValueError, match="STARTED after"):
        parse_session_log(late_start, SESSION_ID)


def test_allows_terminal_callback_after_expiry_but_rejects_bad_lifecycle(
    tmp_path: Path,
) -> None:
    valid = _write_log(
        tmp_path / "valid",
        _schema2_log(
            _dispatch(expires=1100),
            _playback(at_ms=1060),
            _playback(at_ms=2000, result="COMPLETED"),
        ),
    )
    assert parse_session_log(valid, SESSION_ID)["playbacks"][-1]["at_ms"] == 2000

    no_start = _write_log(
        tmp_path / "no-start",
        _schema2_log(_dispatch(), _playback(result="COMPLETED")),
    )
    with pytest.raises(ValueError, match="without STARTED"):
        parse_session_log(no_start, SESSION_ID)

    duplicate = _write_log(
        tmp_path / "duplicate",
        _schema2_log(_dispatch(), _playback(), _playback()),
    )
    with pytest.raises(ValueError, match="duplicate STARTED"):
        parse_session_log(duplicate, SESSION_ID)


def test_rejects_invalid_capture_health_recovery_counters(tmp_path: Path) -> None:
    path = _write_log(
        tmp_path,
        _schema2_log(
            _dispatch(),
            summary_overrides=(
                "starvationCount=1 recoveryAttempts=1 recoverySuccesses=2"
            ),
        ),
    )

    with pytest.raises(ValueError, match="recoverySuccesses"):
        parse_session_log(path, SESSION_ID)
