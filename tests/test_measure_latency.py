"""Current Android cues can be measured from independent audible annotations."""

import pytest

from mapassist.measure_latency import measure


def row(kind="near_zone", cue_id="session:1", audio_ms="1430"):
    return {"event_id": cue_id, "cue_id": cue_id, "kind": kind,
            "evidence_ms": "1000", "audio_ms": audio_ms,
            "source_note": "external unedited recording, frames 30 and 43"}


def test_current_near_cue_latency_and_absent_audio_remain_distinct():
    result = measure([row(), row(cue_id="session:2", audio_ms="")])
    assert result["by_kind"]["near_zone"] == {
        "paired_events": 1, "missing_audio": 1, "p95_ms": 430.0}
    assert result["overall"]["missing_audio"] == 1


@pytest.mark.parametrize("kind", ["player_dead", "player_alive", "radar_status"])
def test_android_status_cue_can_be_annotated_without_inventing_quality_metrics(kind):
    assert measure([row(kind)])["by_kind"][kind]["p95_ms"] == 430.0


def test_unknown_or_reused_cue_is_rejected():
    with pytest.raises(ValueError, match="unknown kind"):
        measure([row("automatic_attack")])
    duplicate = row()
    duplicate["event_id"] = "other-event"
    with pytest.raises(ValueError, match="duplicate cue_id"):
        measure([row(), duplicate])
