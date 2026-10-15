"""Audit existing Match3 exports without replaying the game or inferring player actions."""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
from typing import Any
import zipfile

ROOT = Path(__file__).resolve().parents[2]
KINDS = frozenset("RED BEAR CHICK FROG HIPPO CAT COIN SNOW ICE ICEFLOWER HONEY EGG COOKIE UNKNOWN POISON PENGUIN SNOW_BEAR GOLD_POD KEY FURBALL CAGE STAR_BOTTLE DYE_BABY CANNON MAGIC_STONE DOLPHIN SPRITE GEM STAR HONEY_COVER LOCK FLOWER GRAY_FURBALL".split())
MODES = frozenset("CLEAR HATCH RESCUE DELIVER CHARGE TRIGGER UNKNOWN UNLOCK".split())
ACTIONS = frozenset("SWAP ACTIVATE LINE_SWAP BIRD_COLOR BIRD_PAIR BIRD_LINE CHARGED_PAIR".split())
SPECIAL = ACTIONS - {"SWAP"}
GATES = frozenset("starting frame_error popup popup_closed geometry_unavailable geometry_confirming touch_read cascade visual_motion board_changing goal_confirming goals_complete no_swap ready".split())
CLEAR_REASONS = frozenset("GOAL_CHANGED GOAL_STALE CASCADE VISUAL_MOTION OBSERVATION_INVALID BOARD_MOTION HINT_EVIDENCE_CHANGED POINT_READ_MODE PROJECTION_STOPPED FRAME_ERROR POPUP POPUP_CLOSED CAPTURE_SIZE GEOMETRY_UNVERIFIED GEOMETRY_CHANGED FRAME_STALE SERVICE_STOPPED DISPLAY_CHANGED".split())
OUTCOMES = frozenset("replaced_without_attributable_outcome outcome_timeout geometry_changed mission_changed counter_reset_or_added_steps counter_coverage_incomplete multiple_actions_unattributed progress_unattributed below_lower_bound_rule_abstained visual_exchange_progress_observed visual_exchange_no_target_gain".split()) | CLEAR_REASONS
LIFECYCLE_CLEARS = frozenset("CLEAR_CATEGORY CLEAR_ALL PAUSED CLOSED REPLACED".split())
PLAYBACK = frozenset("STARTED COMPLETED FAILED CANCELLED EXPIRED SETTINGS_DISABLED COOLDOWN UNAVAILABLE QUEUE_REPLACED QUEUE_EVICTED PREEMPTED".split()) | {"CANCELLED_" + r for r in CLEAR_REASONS | LIFECYCLE_CLEARS}
DISPATCH_REASONS = frozenset("closed paused expired category_disabled channels_disabled deduplicated tone_unavailable haptic_cooldown speech_cooldown speech_unavailable_or_queue_full renderer_unavailable none".split())
CRITICAL = frozenset("Match3Ranking Match3GoalOutcome CueDispatch CuePlayback checkpoint Match3HintImageContext".split())
# Recorder writers include asynchronous TTS/context callbacks. Validate the
# engine lifecycle in file order; callbacks use their exact cue IDs and clocks.
STATE_CRITICAL = {"Match3Ranking", "Match3GoalOutcome"}
AUDITS = ("Match3Hint", "Match3HintCleared", "Match3RecommendationGate", "Match3GeometryInvalidated", "Match3GoalObserved")
SAFE_IMAGE = re.compile(r"images/(?:screen|map)-[A-Za-z0-9._-]+\.jpg\Z")
MAX_EVENTS_BYTES = 64 * 1024 * 1024
MAX_JSON_BYTES = 2 * 1024 * 1024
MAX_IMAGE_BYTES = 8 * 1024 * 1024
RANK_MAX_AGE_MS = 2400
OUTCOME_MAX_AGE_MS = 12000
GATE_MIN_MS = 10000


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def integer(value: Any, low: int = 0, high: int = 10**15) -> int | None:
    return value if type(value) is int and low <= value <= high else None


def label(value: Any, allowed: Any) -> str:
    return value if isinstance(value, str) and value in allowed else "unrecognized"


def fields(message: Any, marker: str) -> dict[str, str] | None:
    if not isinstance(message, str) or not message.startswith(marker + " "):
        return None
    return dict(token.split("=", 1) for token in message[len(marker) + 1:].split() if "=" in token)


def number(value: Any, low: int = 0, high: int = 10**15) -> int | None:
    if not isinstance(value, str) or not re.fullmatch(r"-?\d{1,15}", value):
        return None
    return integer(int(value), low, high)


def goals(key: Any) -> dict | None:
    if not isinstance(key, str) or len(key) > 4096:
        return None
    parts = key.split("|")
    head = parts[0].split(":")
    if len(head) != 2 or number(head[0], -1, 99999) is None or number(head[1], -1, 999) is None:
        return None
    targets, slots = {}, set()
    for part in parts[1:]:
        values = part.split(":")
        if len(values) != 7:
            return None
        slot, kind, count, complete, _, mode, _ = values
        slot_num, count_num = number(slot, 0, 7), number(count, -1, 9999)
        if slot_num is None or slot_num in slots or kind not in KINDS or mode not in MODES or count_num is None or complete not in {"true", "false"}:
            return None
        if complete == "true" and count_num != 0:
            return None
        slots.add(slot_num)
        target = {"kind": kind, "remaining": count_num, "mode": mode}
        if kind in targets and targets[kind] != target:
            return None
        targets[kind] = target
    if not targets:
        return None
    return {"level": int(head[0]), "steps": int(head[1]), "targets": list(targets.values())}


def gain_map(value: Any, *, signed: bool = False) -> dict[str, int]:
    if not isinstance(value, dict):
        return {}
    return {k: v for k, v in value.items() if k in KINDS and integer(v, -9999 if signed else 0, 9999) is not None}


def strings(value: Any) -> list[str]:
    return [v for v in value if isinstance(v, str)] if isinstance(value, list) else []


def digest_field(value: Any) -> str | None:
    return value if isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) else None


def action_identity(value: dict) -> tuple | None:
    coords = [integer(value.get(k), 0, 19) for k in ("from_row", "from_col", "to_row", "to_col")]
    if value.get("action_type") not in ACTIONS or any(v is None for v in coords):
        return None
    return (value["action_type"], *coords, value.get("rule_id"), value.get("rule_version"))


def action_key(key: Any) -> tuple | None:
    if not isinstance(key, str):
        return None
    match = re.fullmatch(r"([A-Z_]+):(\d+),(\d+):(\d+),(\d+):([a-z0-9_]+)@(\d+)", key)
    if not match:
        return None
    typ, r1, c1, r2, c2, rule, version = match.groups()
    return action_identity(dict(action_type=typ, from_row=int(r1), from_col=int(c1), to_row=int(r2), to_col=int(c2), rule_id=rule, rule_version=int(version)))


@dataclass
class Source:
    path: Path
    events: list[dict]
    metadata: dict
    summary: dict
    members: dict[str, str]
    images: list[dict]
    origin: int
    session_id: str


def load_source(path: Path) -> Source:
    path = path.resolve()
    archive = zipfile.ZipFile(path) if path.is_file() and path.suffix == ".zip" else None
    directory = path if path.is_dir() else path.parent
    event_name = "events.jsonl" if path.is_dir() or archive else path.name
    prefix = ""
    try:
        if archive:
            names = archive.namelist()
            if len(set(names)) != len(names) or any(PurePosixPath(n).is_absolute() or ".." in PurePosixPath(n).parts or "\\" in n for n in names):
                raise ValueError("Unsafe or duplicated ZIP member")
            candidates = [n for n in names if n == "events.jsonl" or n.endswith("/events.jsonl")]
            if len(candidates) != 1:
                raise ValueError("Expected exactly one events.jsonl per export")
            prefix = candidates[0][:-len("events.jsonl")]

        def read(name: str, limit: int) -> bytes | None:
            if archive:
                try:
                    info = archive.getinfo(prefix + name)
                except KeyError:
                    return None
                if info.file_size > limit:
                    raise ValueError("Export member exceeds size limit")
                return archive.read(info)
            candidate = directory / name
            if not candidate.exists():
                return None
            if not candidate.resolve().is_relative_to(directory.resolve()) or candidate.stat().st_size > limit:
                raise ValueError("Export member escapes its directory or exceeds size limit")
            return candidate.read_bytes()

        raw = read(event_name, MAX_EVENTS_BYTES)
        if raw is None:
            raise ValueError("Missing events.jsonl")
        members = {"events.jsonl": sha(raw)}
        extras = {}
        for name in ("metadata.json", "summary.json"):
            payload = read(name, MAX_JSON_BYTES)
            extras[name] = json.loads(payload) if payload is not None else {}
            if not isinstance(extras[name], dict):
                raise ValueError("Export metadata/summary must be objects")
            if payload is not None:
                members[name] = sha(payload)
        events, ids, offsets = [], set(), set()
        last_critical = -1
        for line, text in enumerate(raw.decode("utf-8").splitlines(), 1):
            if not text.strip():
                continue
            row = json.loads(text)
            if not isinstance(row, dict) or not isinstance(row.get("type"), str) or not isinstance(row.get("data", {}), dict):
                raise ValueError(f"Invalid event schema at line {line}")
            if "session_id" in row:
                if not isinstance(row["session_id"], str) or not row["session_id"]:
                    raise ValueError("Invalid session ID")
                ids.add(row["session_id"])
            at, elapsed = integer(row.get("at_ms")), integer(row.get("elapsed_ms"))
            critical = row["type"] in CRITICAL or row["type"] == "audit" and any(fields(row.get("data", {}).get("message"), m) is not None for m in AUDITS)
            state_critical = row["type"] in STATE_CRITICAL or row["type"] == "audit" and any(fields(row.get("data", {}).get("message"), m) is not None for m in AUDITS)
            if critical and at is None:
                raise ValueError(f"Missing critical event timestamp at line {line}")
            if at is not None and elapsed is not None:
                offsets.add(at - elapsed)
            if state_critical:
                if at < last_critical:
                    raise ValueError("Critical event clock regressed; do not combine clock epochs")
                last_critical = at
            events.append({**row, "_line": line})
        for data in extras.values():
            if "session_id" in data:
                if not isinstance(data["session_id"], str) or not data["session_id"]:
                    raise ValueError("Invalid metadata session ID")
                ids.add(data["session_id"])
        if len(ids) != 1 or len(offsets) > 1 or not events:
            raise ValueError("Ambiguous session or inconsistent elapsed clock")
        metadata, summary = extras["metadata.json"], extras["summary.json"]
        origin = next(iter(offsets)) if offsets else integer(metadata.get("started_elapsed_ms"))
        if origin is None:
            origin = min(e["at_ms"] for e in events if integer(e.get("at_ms")) is not None)
        declared_origin = integer(metadata.get("started_elapsed_ms"))
        if declared_origin is not None and declared_origin != origin:
            raise ValueError("Metadata and event elapsed clocks disagree")
        images, cached = [], {}
        for row in events:
            if row["type"] != "image":
                continue
            name = row.get("file", "")
            if not isinstance(name, str) or not SAFE_IMAGE.fullmatch(name):
                raise ValueError("Unsafe image reference")
            if name not in cached:
                payload = read(name, MAX_IMAGE_BYTES)
                cached[name] = None if payload is None else sha(payload)
                if payload is not None:
                    members[name] = cached[name]
            observed = integer(row.get("observed_at_ms"))
            images.append({"line": row["_line"], "elapsed_ms": None if observed is None else observed - origin,
                           "window_id": integer(row.get("window_id")), "frame_index": integer(row.get("frame_index")),
                           "status": "present" if cached[name] else "missing", "sha256": cached[name]})
        return Source(path, events, metadata, summary, members, images, origin, next(iter(ids)))
    finally:
        if archive:
            archive.close()


def audit_source(source: Source, alias: str, tokens: set[str], rules: set[str]) -> dict:
    def ref(row: dict) -> dict:
        return {"line": row["_line"], "elapsed_ms": row.get("at_ms", source.origin) - source.origin}

    def candidate(data: dict) -> dict:
        identity = action_identity(data)
        return {"action_type": label(data.get("action_type"), ACTIONS),
                "coordinates": list(identity[1:5]) if identity else None,
                "rule_id": label(data.get("rule_id"), rules), "rule_version": integer(data.get("rule_version"), 1, 999),
                "direct_units": integer(data.get("direct_units"), 0, 9999),
                "physical_lower_bounds": gain_map(data.get("physical_lower_bounds")),
                "contacts": gain_map(data.get("contacts")),
                "cookie_contact_cells": integer(data.get("cookie_contact_cells"), 0, 400),
                "unknown_reasons_recorded": isinstance(data.get("unknown_reasons"), list),
                "unknown_reasons": sorted({label(u, tokens) for u in strings(data.get("unknown_reasons"))})}

    hints, rankings, gates, outcomes = {}, [], [], []
    last_rank = None
    geometry_epoch = 0
    cue_hints = {}
    cue_attempts = {}
    playback_rows = []
    hint_actions, hint_goals = {}, {}
    unlinked = Counter()
    frame_times = []
    counts = Counter()
    unknowns = Counter()
    for row in source.events:
        typ, data = row["type"], row.get("data", {})
        counts[typ] += 1
        if typ == "frame" and integer(row.get("at_ms")) is not None:
            frame_times.append(row["at_ms"] - source.origin)
        if typ == "Match3Ranking":
            raw_candidates = data.get("top_candidates", [])
            if not isinstance(raw_candidates, list) or any(not isinstance(c, dict) for c in raw_candidates):
                raise ValueError("Invalid ranking candidate list")
            parsed = goals(data.get("goal_key"))
            candidates = [candidate(c) for c in raw_candidates]
            active = [] if parsed is None else [t for t in parsed["targets"] if t["remaining"] != 0 and t["kind"] != "UNKNOWN"]
            fully_known = parsed is not None and all(t["kind"] != "UNKNOWN" and t["remaining"] >= 0 for t in parsed["targets"])
            low_remaining = fully_known and len(active) == 1 and 0 < active[0]["remaining"] <= 3
            flags = []
            if parsed is None:
                flags.append("goal_unread")
            elif any(t["mode"] == "UNKNOWN" for t in active):
                flags.append("goal_event_mode_unknown")
            if parsed is not None:
                if any(t["kind"] == "UNKNOWN" for t in parsed["targets"]):
                    flags.append("goal_identity_unknown")
                if any(t["remaining"] < 0 for t in parsed["targets"]):
                    flags.append("goal_count_unknown")
            if candidates and candidates[0]["direct_units"] == 0 and active:
                flags.append("top1_zero_certain_task_units")
                contacts = candidates[0]["contacts"]
                if low_remaining and all(t["kind"] in contacts and contacts[t["kind"]] == 0 for t in active):
                    flags.append("low_remaining_top1_without_logged_task_contact")
            if not candidates:
                flags.append("no_admitted_candidate_logged")
            if candidates:
                selected = candidates[0]
                for alt in candidates[1:]:
                    if alt["action_type"] not in SPECIAL:
                        continue
                    if selected["direct_units"] is not None and alt["direct_units"] is not None and alt["direct_units"] > selected["direct_units"]:
                        flags.append("special_alternative_higher_certain_units_review")
                    kinds = [t["kind"] for t in active]
                    if kinds and all(k in selected["contacts"] and k in alt["contacts"] and alt["contacts"][k] >= selected["contacts"][k] for k in kinds) and any(alt["contacts"][k] > selected["contacts"][k] for k in kinds):
                        flags.append("special_alternative_more_task_contacts_review")
            for c in candidates:
                unknowns.update(c["unknown_reasons"])
            omitted = strings(data.get("omitted_action_reasons"))
            record = {**ref(row), "board_revision": integer(data.get("board_revision")), "geometry_epoch": geometry_epoch, "goals": parsed,
                      "low_remaining_single_task": low_remaining, "admitted_moves": integer(data.get("admitted_moves")),
                      "logged_candidates": candidates, "omitted_action_reasons": sorted({label(v, tokens) for v in omitted}),
                      "omitted_action_reasons_recorded": isinstance(data.get("omitted_action_reasons"), list),
                      "flags": sorted(set(flags)), "candidate_scope": "logged_candidates_only; not a complete board or action search"}
            rankings.append(record)
            last_rank = (row, record)
        elif typ == "audit":
            message = data.get("message")
            if (f := fields(message, "Match3GeometryInvalidated")) is not None:
                last_rank = None
                geometry_epoch += 1
            elif (f := fields(message, "Match3Hint")) is not None:
                revision = number(f.get("revision"), 1)
                if revision is None or revision in hints:
                    raise ValueError("Missing or duplicate hint revision")
                rank = None
                if last_rank is not None:
                    rank_row, record = last_rank
                    raw_top = rank_row["data"].get("top_candidates", [])
                    if 0 <= row["at_ms"] - rank_row["at_ms"] <= RANK_MAX_AGE_MS and raw_top and action_key(f.get("action")) == action_identity(raw_top[0]) and f.get("goal_key") == rank_row["data"].get("goal_key"):
                        rank = record["line"]
                hints[revision] = {**ref(row), "revision": revision, "ranking_line": rank, "goals": goals(f.get("goal_key")),
                                   "cleared": None, "dispatches": [], "playback": [], "outcomes": [], "image_windows": []}
                hint_actions[revision] = action_key(f.get("action"))
                hint_goals[revision] = f.get("goal_key")
                if rank is None:
                    unlinked["hint_ranking"] += 1
            elif (f := fields(message, "Match3HintCleared")) is not None:
                revision = number(f.get("revision"), 1)
                if revision in hints and hints[revision]["cleared"] is None:
                    hints[revision]["cleared"] = {**ref(row), "reason": label(f.get("reason"), CLEAR_REASONS)}
                else:
                    unlinked["hint_clear"] += 1
            elif (f := fields(message, "Match3RecommendationGate")) is not None:
                gates.append({**ref(row), "reason": label(f.get("reason"), GATES)})
        elif typ == "CueDispatch":
            match = re.fullmatch(r"m3live:hint:(\d+)", str(data.get("event_key", "")))
            revision = int(match.group(1)) if match else None
            hint = hints.get(revision)
            cue_id = data.get("cue_id")
            cue_matches = isinstance(cue_id, str) and re.fullmatch(re.escape(source.session_id) + f":hint:{revision}:" + r"\d+", cue_id)
            created, expires = integer(data.get("created_at_ms")), integer(data.get("expires_at_ms"))
            valid_clock = (created is not None and expires is not None
                           and source.origin + (hint["elapsed_ms"] if hint else 0) <= created <= row["at_ms"]
                           and created <= expires)
            start = created - source.origin if valid_clock else ref(row)["elapsed_ms"]
            if hint and cue_matches and cue_id not in cue_hints and hint["elapsed_ms"] <= start and (hint["cleared"] is None or start <= hint["cleared"]["elapsed_ms"]):
                cue_hints[cue_id] = revision
                attempt = {**ref(row), "attempt": len(hint["dispatches"]) + 1,
                           "outcome": label(data.get("outcome"), {"ACCEPTED", "DROPPED", "REJECTED"}),
                           "accepted_channels": integer(data.get("accepted_channels"), 0, 15),
                           "reason": label(data.get("reason"), DISPATCH_REASONS),
                           "created_elapsed_ms": created - source.origin if valid_clock else None,
                           "expires_elapsed_ms": expires - source.origin if valid_clock else None,
                           "clock_evidence": "request" if valid_clock else "invalid_request" if created is not None or expires is not None else "dispatch_only",
                           "playback": [], "callback_anomalies": []}
                cue_attempts[cue_id] = (attempt, start)
                hint["dispatches"].append(attempt)
            else:
                unlinked["dispatch"] += 1
        elif typ == "CuePlayback":
            # Renderers may synchronously call back before submit() reports its
            # result. Join by exact cue after collecting dispatches, never by
            # proximity, and require the actual request clock for early callbacks.
            playback_rows.append(row)
        elif typ == "Match3GoalOutcome":
            revision = integer(data.get("hint_revision"), 1)
            hint = hints.get(revision)
            linked = hint is not None and hint_actions[revision] is not None and action_key(data.get("action_key")) == hint_actions[revision] and data.get("goal_modes") == hint_goals[revision] and 0 <= ref(row)["elapsed_ms"] - hint["elapsed_ms"] <= OUTCOME_MAX_AGE_MS
            record = {**ref(row), "hint_revision": revision, "hint_linked_by_revision_action_goals_and_time": linked,
                      "outcome": label(data.get("outcome"), OUTCOMES),
                      "action_evidence": label(data.get("action_evidence"), {"not_observed", "exact_visual_exchange_only"}),
                      "steps_spent": integer(data.get("steps_spent"), -1, 999), "goal_deltas": gain_map(data.get("goal_deltas"), signed=True),
                      "expected_lower_bounds": gain_map(data.get("expected_lower_bounds")), "player_followed_hint": None}
            outcomes.append(record)
            if linked:
                hint["outcomes"].append(record["line"])
            else:
                unlinked["outcome"] += 1
        elif typ == "Match3HintImageContext":
            hint = hints.get(integer(data.get("hint_revision"), 1))
            window = integer(data.get("window_id"), 1)
            if hint and window is not None and data.get("status") == "ACCEPTED":
                images = [i for i in source.images if i["window_id"] == window]
                hint["image_windows"].append({**ref(row), "window_id": window, "images": images,
                    "saved_before": sum(i["status"] == "present" and i["elapsed_ms"] is not None and i["elapsed_ms"] <= hint["elapsed_ms"] for i in images),
                    "saved_after": sum(i["status"] == "present" and i["elapsed_ms"] is not None and i["elapsed_ms"] > hint["elapsed_ms"] for i in images),
                    "scope": "explicit_window_only; images do not establish action adoption"})
            elif data.get("status") == "ACCEPTED":
                unlinked["image_window"] += 1

    for row in playback_rows:
        data = row.get("data", {})
        cue_id = data.get("cue_id")
        revision = cue_hints.get(cue_id) if isinstance(cue_id,str) else None
        observed = integer(data.get("at_ms"))
        callback_at = row["at_ms"] if observed is None else observed
        if (revision is not None and ("at_ms" not in data or observed is not None)
                and source.origin + cue_attempts[cue_id][1] <= callback_at <= row["at_ms"]
                and data.get("event_key") == f"m3live:hint:{revision}" and data.get("channel") == "SPEECH"):
            attempt = cue_attempts[cue_id][0]
            record = {**ref(row), "callback_elapsed_ms": callback_at - source.origin,
                      "callback_clock_recorded": observed is not None,
                      "result": label(data.get("result"), PLAYBACK), "attempt": attempt["attempt"]}
            attempt["playback"].append(record)
            hints[revision]["playback"].append(record)
        else:
            unlinked["playback"] += 1
    terminal_states = {"COMPLETED": "completed", "FAILED": "failed", "EXPIRED": "expired",
                       "SETTINGS_DISABLED": "settings_disabled", "COOLDOWN": "cooldown", "UNAVAILABLE": "unavailable",
                       "QUEUE_REPLACED": "replaced", "QUEUE_EVICTED": "evicted", "PREEMPTED": "preempted"}
    attempt_states = Counter()
    for hint in hints.values():
        for attempt in hint["dispatches"]:
            terminal, started = None, False
            for callback in sorted(attempt["playback"], key=lambda p: (p["callback_elapsed_ms"], p["line"])):
                result = callback["result"]
                if result == "STARTED":
                    if terminal: attempt["callback_anomalies"].append("start_after_terminal")
                    elif started: attempt["callback_anomalies"].append("duplicate_start")
                    started = True
                    if (attempt["expires_elapsed_ms"] is not None and callback["callback_elapsed_ms"] > attempt["expires_elapsed_ms"]
                            or hint["cleared"] is not None and callback["callback_elapsed_ms"] > hint["cleared"]["elapsed_ms"]):
                        attempt["callback_anomalies"].append("start_after_validity_window")
                elif result in terminal_states or result.startswith("CANCELLED"):
                    if terminal: attempt["callback_anomalies"].append("duplicate_terminal")
                    else: terminal = "cancelled" if result.startswith("CANCELLED") else terminal_states[result]
            channels = attempt["accepted_channels"]
            attempt["speech_state"] = terminal or ("started_without_terminal" if started else
                "unrecognized_callback" if attempt["playback"] else
                "not_accepted" if attempt["outcome"] in {"DROPPED", "REJECTED"} else
                "accepted_without_callback" if attempt["outcome"] == "ACCEPTED" and channels is not None and channels & 2 else
                "speech_not_accepted" if attempt["outcome"] == "ACCEPTED" and channels is not None else "unknown_admission")
            attempt_states[attempt["speech_state"]] += 1

    summary = source.summary
    timed = [r["at_ms"] - source.origin for r in source.events if integer(r.get("at_ms")) is not None]
    duration = integer(summary.get("duration_ms"))
    end = duration if duration is not None and duration >= max(timed, default=0) else max(timed, default=0)
    ended_at = integer(summary.get("ended_at_ms"))
    complete = (summary.get("end_observed") is True and summary.get("interrupted") is False
                and integer(summary.get("dropped_events")) == 0
                and integer(summary.get("frame_count")) == counts["frame"]
                and integer(summary.get("image_count")) == len(source.images)
                and ended_at is not None and duration is not None
                and abs(ended_at - source.origin - duration) <= 1
                and 0 <= duration - max(timed, default=0) <= 2400)
    for hint in hints.values():
        for attempt in hint["dispatches"]:
            attempt["right_censored"] = not complete and attempt["speech_state"] in {"accepted_without_callback", "started_without_terminal"}
    long_gates = []
    for index, gate in enumerate(gates):
        stop = gates[index + 1]["elapsed_ms"] if index + 1 < len(gates) else end
        start = gate["elapsed_ms"]
        if stop - start < GATE_MIN_MS or gate["reason"] == "ready":
            continue
        frames = sorted(t for t in frame_times if start <= t <= stop)
        points = [start, *frames, stop]
        supported = bool(frames) and max(b - a for a, b in zip(points, points[1:])) <= 2400
        overlap = [h["revision"] for h in hints.values() if h["elapsed_ms"] < stop and (h["cleared"]["elapsed_ms"] if h["cleared"] else end) > start]
        long_gates.append({**gate, "end_elapsed_ms": stop, "duration_ms": stop - start, "sampled_frames": len(frames),
                           "overlapping_hint_revisions": overlap, "coverage": "sampled_frames_and_gate_log" if supported and complete else "incomplete",
                           "right_censored": index + 1 == len(gates) and not complete, "audible_silence_proven": False})
    flags = Counter(flag for r in rankings for flag in r["flags"])
    callbacks = Counter(p["result"] for h in hints.values() for p in h["playback"])
    member_manifest = json.dumps(source.members, sort_keys=True, separators=(",", ":")).encode()
    episodes = []
    # Group only adjacent matching low-remaining snapshots in one known epoch.
    # Different steps, geometry, flags or a sampling gap begin a new episode.
    prior_rank = None
    for rank in rankings:
        review = rank["low_remaining_single_task"] and "low_remaining_top1_without_logged_task_contact" in rank["flags"]
        prior_review = prior_rank is not None and prior_rank["low_remaining_single_task"] and "low_remaining_top1_without_logged_task_contact" in prior_rank["flags"]
        merge = review and prior_review and rank["geometry_epoch"] == prior_rank["geometry_epoch"] and rank["goals"] == prior_rank["goals"] and rank["flags"] == prior_rank["flags"] and rank["elapsed_ms"] - prior_rank["elapsed_ms"] <= RANK_MAX_AGE_MS
        if review:
            if merge:
                episodes[-1]["last_elapsed_ms"] = rank["elapsed_ms"]
                episodes[-1]["ranking_lines"].append(rank["line"])
            else:
                episodes.append({"first_elapsed_ms": rank["elapsed_ms"], "last_elapsed_ms": rank["elapsed_ms"],
                                 "geometry_epoch": rank["geometry_epoch"], "goals": rank["goals"], "ranking_lines": [rank["line"]]})
        prior_rank = rank
    return {"source": alias, "events_sha256": source.members["events.jsonl"], "members_sha256": sha(member_manifest),
            "version_name": source.metadata.get("version_name") if re.fullmatch(r"\d{1,3}\.\d{1,3}\.\d{1,3}", str(source.metadata.get("version_name", ""))) else None,
            "version_code": integer(source.metadata.get("version_code"), 1, 999999),
            "recorded_apk_sha256": digest_field(source.metadata.get("apk_sha256")),
            "recorded_gallery_sha256": digest_field(source.metadata.get("match3_gallery_sha256")),
            "recorded_cover_catalog_sha256": digest_field(source.metadata.get("match3_cover_catalog_sha256")),
            "coverage": {"normal_end_and_no_logged_drops": complete, "duration_ms": duration, "events": len(source.events),
                         "frames": counts["frame"], "image_records": len(source.images), "present_image_members": sum(n.startswith("images/") for n in source.members),
                         "missing_image_records": sum(i["status"] == "missing" for i in source.images)},
            "totals": {"rankings": len(rankings), "hints": len(hints), "goal_outcomes": len(outcomes), "speech_callbacks": dict(sorted(callbacks.items())),
                       "speech_attempt_states": dict(sorted(attempt_states.items())),
                       "dispatch_reasons": dict(sorted(Counter(d["reason"] for h in hints.values() for d in h["dispatches"]).items())),
                       "attempts_missing_request_clock": sum(d["clock_evidence"] != "request" for h in hints.values() for d in h["dispatches"]),
                       "hints_without_dispatch": sum(not h["dispatches"] for h in hints.values()),
                       "hints_with_speech_start": sum(any(p["result"] == "STARTED" for p in h["playback"]) for h in hints.values()),
                       "hints_with_speech_completion": sum(any(p["result"] == "COMPLETED" for p in h["playback"]) for h in hints.values()),
                       "ranking_flags": dict(sorted(flags.items())), "unknown_reasons": dict(sorted(unknowns.items())), "unlinked": dict(sorted(unlinked.items()))},
            "rankings": rankings, "hints": list(hints.values()), "goal_outcomes": outcomes, "long_non_ready_gates": long_gates,
            "low_remaining_review_episodes": episodes,
            "player_adoption_rate": None, "task_benefit_rate": None, "independent_accuracy": None}


def analyze(paths: list[Path]) -> tuple[dict, dict]:
    rule_path = ROOT / "android/app/src/main/assets/match3-rules-v1.json"
    reference = ROOT / "docs/research/开心消消乐元素规则参考.md"
    catalog = json.loads(rule_path.read_text())
    rule_ids = {r["rule_id"] for r in catalog["rules"]}
    # The engine appends this suffix to finite rule IDs, rather than logging
    # arbitrary strings. Preserve those reasons without exporting free text.
    tokens = {r + "_damage_or_trigger_unknown" for r in rule_ids | {"unknown"}}
    reasons_digest = hashlib.sha256()
    for name in ("Match3Action", "Match3RuleEngine", "Match3Search", "Match3MoveValue"):
        text = (ROOT / f"android/app/src/main/java/com/openkhub/sensefield/{name}.java").read_bytes()
        reasons_digest.update(name.encode())
        reasons_digest.update(text)
        tokens.update(re.findall(r'"([a-z][a-z0-9_]{0,95})"', text.decode()))
    sources, ids = [], {}
    for path in paths:
        source = load_source(path)
        if source.session_id in ids:
            prior = ids[source.session_id]
            if prior.members != source.members:
                raise ValueError("Conflicting exports for one session; audit separately rather than merge")
            continue
        ids[source.session_id] = source
        sources.append(source)
    if not sources:
        raise ValueError("Provide at least one diagnostic export")
    records = [audit_source(s, f"recording-{index:02d}", tokens, rule_ids) for index, s in enumerate(sources, 1)]
    report = {"schema": "sensefield.match3.diagnostics_audit.v1", "scope": "existing_development_logs; software_predictions_and_callbacks_only",
              "configuration": {"ranking_max_age_ms": RANK_MAX_AGE_MS, "outcome_max_age_ms": OUTCOME_MAX_AGE_MS, "long_gate_min_ms": GATE_MIN_MS, "low_remaining_max_units": 3},
              "provenance": {"tool_sha256": sha(Path(__file__).read_bytes()), "audit_rules_sha256": sha(rule_path.read_bytes()), "audit_reference_sha256": sha(reference.read_bytes()), "reason_sources_sha256": reasons_digest.hexdigest()},
              "recordings": records, "publication_ready": False}
    private = {r["source"]: {"input_path": str(s.path), "session_id": s.session_id, "members": s.members} for r, s in zip(records, sources)}
    return report, private


def summarize(report: dict) -> dict:
    """Compact public index; full event details and paths stay in local files."""
    records = []
    for recording in report["recordings"]:
        records.append({k: recording[k] for k in (
            "source", "events_sha256", "members_sha256", "version_name", "version_code", "recorded_apk_sha256",
            "recorded_gallery_sha256", "recorded_cover_catalog_sha256", "coverage", "totals")})
        records[-1].update({
            "low_remaining_review_episode_count": len(recording["low_remaining_review_episodes"]),
            "first_review_episodes": recording["low_remaining_review_episodes"][:3],
            "long_non_ready_gate_count": len(recording["long_non_ready_gates"]),
            "largest_non_ready_gates": sorted(recording["long_non_ready_gates"], key=lambda g: -g["duration_ms"])[:3],
            "logged_visual_exchange_with_single_step_progress": sum(o["action_evidence"] == "exact_visual_exchange_only" and o["steps_spent"] == 1 and o["outcome"] == "visual_exchange_progress_observed" and o["hint_linked_by_revision_action_goals_and_time"] for o in recording["goal_outcomes"]),
            "bird_in_logged_candidates": sum(any(c["action_type"].startswith("BIRD") for c in r["logged_candidates"]) for r in recording["rankings"]),
            "bird_at_logged_top1": sum(bool(r["logged_candidates"]) and r["logged_candidates"][0]["action_type"].startswith("BIRD") for r in recording["rankings"]),
            "player_adoption_rate": None, "task_benefit_rate": None,
        })
    return {"schema": "sensefield.match3.diagnostics_audit.summary.v1", "scope": report["scope"],
            "configuration": report["configuration"], "provenance": report["provenance"],
            "full_report_sha256": sha((json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()),
            "recordings": records, "publication_ready": False}


def markdown(report: dict) -> str:
    lines = ["# 消消乐已有实局自动复盘", "", "这里只报告已有开发日志中的预测、门禁和软件回调。采纳率、真实任务收益及独立准确率均未测量；候选只覆盖实际记录的部分。", "", "| 记录 | 排名 | 提示 | 收益观察 | 语音开始／完成 | ≥10秒非ready区间 |", "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for r in report["recordings"]:
        totals = r["totals"]
        cb = totals["speech_callbacks"]
        lines.append(f"| {r['source']} | {totals['rankings']} | {totals['hints']} | {totals['goal_outcomes']} | {cb.get('STARTED',0)}／{cb.get('COMPLETED',0)} | {len(r['long_non_ready_gates'])} |")
    for r in report["recordings"]:
        lines.extend(["", f"## {r['source']}", "", f"事件 SHA：`{r['events_sha256']}`；正常结束且无已记录丢事件：{r['coverage']['normal_end_and_no_logged_drops']}。"])
        lines.append("\n逐次语音请求状态：" + "、".join(f"`{state}` {count}" for state, count in r["totals"]["speech_attempt_states"].items()) + "。缺回调表示缺证据，不证明未发声；软件完成回调不证明实际听见。")
        lines.append(f"\n缺请求时钟的旧分发：{r['totals']['attempts_missing_request_clock']} 次，保留分发时钟口径。")
        for flag, count in r["totals"]["ranking_flags"].items():
            lines.append(f"\n- `{flag}`：{count} 次。")
        for gate in r["long_non_ready_gates"]:
            lines.append(f"\n- 非 ready 门禁 `{gate['reason']}`，{gate['elapsed_ms']/1000:.1f}～{gate['end_elapsed_ms']/1000:.1f} 秒，{gate['duration_ms']/1000:.1f} 秒；事件行 {gate['line']}，覆盖 `{gate['coverage']}`。不能据此声称整局无声音。")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="An export ZIP, extracted directory, or events JSONL")
    parser.add_argument("--output", required=True, type=Path, help="Local output directory; inputs.private.json remains private")
    args = parser.parse_args(argv)
    try:
        destination = args.output.resolve()
        for input_path in args.inputs:
            resolved_input = input_path.resolve()
            input_directory = resolved_input if resolved_input.is_dir() else resolved_input.parent
            if destination.is_relative_to(input_directory):
                raise ValueError("Choose an output directory outside the input exports; their original summary.json must be preserved")
        report, private = analyze(args.inputs)
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        (args.output / "summary.json").write_text(json.dumps(summarize(report), ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        (args.output / "report.md").write_text(markdown(report))
        (args.output / "inputs.private.json").write_text(json.dumps(private, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        print(json.dumps({"recordings": len(report["recordings"]), "rankings": sum(r["totals"]["rankings"] for r in report["recordings"]), "hints": sum(r["totals"]["hints"] for r in report["recordings"])}, ensure_ascii=False))
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Audit failed: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
