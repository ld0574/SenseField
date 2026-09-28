"""Parse and cross-check auditable Android capture-session logcat records."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path


KIND_BY_CODE = {1: "main_enemy", 2: "minimap_enemy", 3: "danger_ping",
                4: "player_dead", 5: "player_alive"}

# These values mirror CueRequest and CueDispatcher in the Android client.  Keep
# the parser closed over the producer's vocabulary: accepting an unknown bit or
# callback result would make a malformed audit record look like evidence.
CHANNEL_BITS = {
    "TONE": 1,
    "SPEECH": 1 << 1,
    "HAPTIC": 1 << 2,
    "VISUAL": 1 << 3,
}
KNOWN_CHANNEL_MASK = sum(CHANNEL_BITS.values())
DISPATCH_OUTCOMES = {"ACCEPTED", "DROPPED"}
PLAYBACK_RESULTS = {
    "STARTED",
    "COMPLETED",
    "FAILED",
    "EXPIRED",
    "PREEMPTED",
    "QUEUE_EVICTED",
    "UNAVAILABLE",
    "SETTINGS_DISABLED",
    "COOLDOWN",
}
TERMINAL_PLAYBACK_RESULTS = PLAYBACK_RESULTS - {"STARTED"}
# A renderer can report these before a channel has been accepted.  For
# example, SoundPool can fail synchronously and TTS can be unavailable when a
# request is submitted.  Other terminal callbacks describe a request that was
# queued and therefore must have an accepted channel.
IMMEDIATE_FAILURE_RESULTS = {"FAILED", "UNAVAILABLE"}
QUEUED_TERMINAL_RESULTS = TERMINAL_PLAYBACK_RESULTS - IMMEDIATE_FAILURE_RESULTS
ANDROID_CUE_CATEGORIES = {
    "VISION_MEMORY",
    "DANGER",
    "PLAYER_STATE",
    "SYSTEM",
}
ANDROID_HEALTH_STATES = {
    "HEALTHY",
    "STARVED",
    "RECOVERING",
    "FAILED",
    "REVOKED",
    "PAUSED",
}


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
    dispatch_records: list[dict[str, str]] = []
    playback_records: list[dict[str, str]] = []
    health_records: list[dict[str, str]] = []
    with path.open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            if "MapAssistCapture" not in line:
                continue
            for marker, destination in (
                ("SessionStart", starts),
                ("CueEvent", cue_records),
                ("CueDispatch", dispatch_records),
                ("CuePlayback", playback_records),
                ("CaptureHealth", health_records),
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
    for key in ("starvationCount", "recoveryAttempts", "recoverySuccesses"):
        if key in summary_raw:
            summary[key] = _integer(summary_raw, key, "SessionSummary")
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
    for key in ("starvationCount", "recoveryAttempts", "recoverySuccesses"):
        if key in summary and summary[key] < 0:
            raise ValueError(f"SessionSummary.{key} must be nonnegative")
    if ("recoveryAttempts" in summary and "recoverySuccesses" in summary and
            summary["recoverySuccesses"] > summary["recoveryAttempts"]):
        raise ValueError(
            "SessionSummary.recoverySuccesses cannot exceed recoveryAttempts"
        )

    cues: list[dict[str, object]] = []
    seen_cue_ids: set[str] = set()
    cue_by_id: dict[str, dict[str, object]] = {}
    for values in cue_records:
        cue_id = _required(values, "cueId", "CueEvent")
        if cue_id in seen_cue_ids:
            raise ValueError(f"Duplicate CueEvent cueId {cue_id!r}")
        expected_cue_id = f"{session_id}:{len(cues) + 1}"
        if not dispatch_records and cue_id != expected_cue_id:
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
        cue_by_id[cue_id] = cue

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
    dispatches: list[dict[str, object]] = []
    dispatch_by_id: dict[str, dict[str, object]] = {}
    session_end_ms = started_ms + summary["durationMs"]
    for values in dispatch_records:
        cue_id = _required(values, "cueId", "CueDispatch")
        if cue_id in dispatch_by_id:
            raise ValueError(f"Duplicate CueDispatch cueId {cue_id!r}")
        requested = _integer(values, "requestedMask", "CueDispatch")
        accepted = _integer(values, "acceptedMask", "CueDispatch")
        if (requested < 0 or accepted < 0 or
                requested & ~KNOWN_CHANNEL_MASK or
                accepted & ~KNOWN_CHANNEL_MASK or accepted & ~requested):
            raise ValueError(f"CueDispatch {cue_id!r} has invalid channel masks")
        outcome = _required(values, "outcome", "CueDispatch")
        if outcome not in DISPATCH_OUTCOMES:
            raise ValueError(
                f"CueDispatch {cue_id!r} has invalid outcome {outcome!r}"
            )
        if (outcome == "ACCEPTED") != (accepted != 0):
            raise ValueError(
                f"CueDispatch {cue_id!r} outcome does not match acceptedMask"
            )
        category = _required(values, "category", "CueDispatch")
        if category not in ANDROID_CUE_CATEGORIES:
            raise ValueError(
                f"CueDispatch {cue_id!r} has invalid category {category!r}"
            )
        priority = _integer(values, "priority", "CueDispatch")
        if priority < 0:
            raise ValueError(f"CueDispatch {cue_id!r} has negative priority")
        created_at_ms = _integer(values, "createdAtMs", "CueDispatch")
        expires_at_ms = _integer(values, "expiresAtMs", "CueDispatch")
        if created_at_ms < started_ms or created_at_ms > session_end_ms:
            raise ValueError(
                f"CueDispatch {cue_id!r} createdAtMs falls outside the session"
            )
        if expires_at_ms < created_at_ms:
            raise ValueError(
                f"CueDispatch {cue_id!r} expiresAtMs precedes createdAtMs"
            )
        item = {
            "cue_id": cue_id,
            "event_key": _required(values, "eventKey", "CueDispatch"),
            "kind": _required(values, "kind", "CueDispatch"),
            "category": category,
            "priority": priority,
            "created_at_ms": created_at_ms,
            "expires_at_ms": expires_at_ms,
            "requested_mask": requested,
            "accepted_mask": accepted,
            "outcome": outcome,
            "drop_reason": _required(values, "dropReason", "CueDispatch"),
        }
        dispatches.append(item)
        dispatch_by_id[cue_id] = item

    playbacks: list[dict[str, object]] = []
    playback_state: dict[tuple[str, str], dict[str, bool]] = {}
    for values in playback_records:
        cue_id = _required(values, "cueId", "CuePlayback")
        if cue_id not in dispatch_by_id:
            raise ValueError(f"CuePlayback references unknown CueDispatch {cue_id!r}")
        channel = _required(values, "channel", "CuePlayback")
        if channel not in CHANNEL_BITS:
            raise ValueError(
                f"CuePlayback {cue_id!r} has invalid channel {channel!r}"
            )
        at_ms = _integer(values, "atMs", "CuePlayback")
        if at_ms < 0:
            raise ValueError(f"CuePlayback {cue_id!r} has negative atMs")
        result = _required(values, "result", "CuePlayback")
        if result not in PLAYBACK_RESULTS:
            raise ValueError(
                f"CuePlayback {cue_id!r} has invalid result {result!r}"
            )
        dispatch = dispatch_by_id[cue_id]
        if at_ms < dispatch["created_at_ms"]:
            raise ValueError(
                f"CuePlayback {cue_id!r} occurs before CueDispatch creation"
            )
        if result == "STARTED" and at_ms > dispatch["expires_at_ms"]:
            raise ValueError(
                f"CuePlayback {cue_id!r} STARTED after CueDispatch expiry"
            )

        state_key = (cue_id, channel)
        state = playback_state.setdefault(
            state_key, {"started": False, "terminal": False}
        )
        if state["terminal"]:
            raise ValueError(
                f"CuePlayback {cue_id!r} has a callback after terminal result"
            )
        if result == "STARTED":
            if state["started"]:
                raise ValueError(
                    f"CuePlayback {cue_id!r} has duplicate STARTED callbacks"
                )
            state["started"] = True
        else:
            state["terminal"] = True

        if result == "COMPLETED" and not state["started"]:
            raise ValueError(
                f"CuePlayback {cue_id!r} COMPLETED without STARTED callback"
            )

        accepted = bool(dispatch["accepted_mask"] & CHANNEL_BITS[channel])
        requested = bool(dispatch["requested_mask"] & CHANNEL_BITS[channel])
        if not requested:
            raise ValueError(
                f"CuePlayback {cue_id!r} {channel} was not requested and is not accepted"
            )
        if result == "STARTED" or state["started"]:
            if not accepted or dispatch["outcome"] != "ACCEPTED":
                raise ValueError(
                    f"CuePlayback {cue_id!r} {channel} {result} is not accepted"
                )
        elif result in QUEUED_TERMINAL_RESULTS and not accepted:
            # EXPIRED, PREEMPTED, queue eviction, and the other queue
            # callbacks are emitted for requests accepted by a renderer.  A
            # synchronous FAILED/UNAVAILABLE callback is the only terminal
            # path that can legitimately have no accepted channel.
            raise ValueError(
                f"CuePlayback {cue_id!r} {channel} {result} is not accepted"
            )

        playbacks.append({
            "cue_id": cue_id,
            "channel": channel,
            "at_ms": at_ms,
            "result": result,
        })

    capture_health = []
    for values in health_records:
        state = _required(values, "state", "CaptureHealth")
        if state not in ANDROID_HEALTH_STATES:
            raise ValueError(f"CaptureHealth has invalid state {state!r}")
        attempt = _integer(values, "attempt", "CaptureHealth")
        reader_generation = _integer(values, "readerGeneration", "CaptureHealth")
        elapsed_since_frame = _integer(
            values, "elapsedSinceFrameMs", "CaptureHealth"
        )
        elapsed_since_processed = None
        if "elapsedSinceProcessedMs" in values:
            elapsed_since_processed = _integer(
                values, "elapsedSinceProcessedMs", "CaptureHealth"
            )
        if attempt < 0 or reader_generation < 0:
            raise ValueError("CaptureHealth counters must be nonnegative")
        # Android uses -1 as the documented 'no frame yet' sentinel during
        # startup.  Other negative elapsed values are malformed.
        if elapsed_since_frame < -1 or (
                elapsed_since_processed is not None and elapsed_since_processed < -1):
            raise ValueError("CaptureHealth elapsed values are invalid")
        item = {
            "state": state,
            "reason": _required(values, "reason", "CaptureHealth"),
            "attempt": attempt,
            "reader_generation": reader_generation,
            "elapsed_since_frame_ms": elapsed_since_frame,
        }
        if elapsed_since_processed is not None:
            item["elapsed_since_processed_ms"] = elapsed_since_processed
        capture_health.append(item)
    for cue_id, cue in cue_by_id.items():
        dispatch = dispatch_by_id.get(cue_id)
        if dispatch is None:
            continue
        expected_audio_queued = bool(
            dispatch["accepted_mask"] & (CHANNEL_BITS["TONE"] | CHANNEL_BITS["SPEECH"])
        )
        if cue["audio_queued"] != expected_audio_queued:
            raise ValueError(
                f"CueEvent {cue_id!r} audioQueued does not match CueDispatch"
            )
    enhanced = bool(dispatches or playbacks or capture_health)
    actual_audio_ids = []
    if enhanced:
        actual_audio_ids = list(dict.fromkeys(
            str(item["cue_id"]) for item in playbacks
            if item["channel"] in {"TONE", "SPEECH"} and item["result"] == "STARTED"
        ))
    return {
        "schema_version": 2 if enhanced else 1,
        "session_id": session_id,
        "start_id": _integer(start, "startId", "SessionStart"),
        "started_elapsed_realtime_ms": started_ms,
        "summary": summary,
        "landscape_span_ms": landscape_span_ms,
        "average_landscape_fps": round(average_fps, 3),
        "cues": cues,
        "non_stale_cue_ids": actual_audio_ids if enhanced else
        [str(item["cue_id"]) for item in non_stale],
        "dispatches": dispatches,
        "playbacks": playbacks,
        "capture_health": capture_health,
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
