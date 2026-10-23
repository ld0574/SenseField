#!/usr/bin/env python3
"""Compare the relation layer on private diagnostic snapshots, without model requests.

This is a development replay of stored associations, not hero identity truth,
earliest enemy visibility, acoustic latency or a measured survival benefit.
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import math
from collections import Counter
from pathlib import Path

from mapassist import native


def distance(data: dict, enemy: dict, body: list[float]) -> float | None:
    players = [e for e in data["entities"] if e["kind"] == 6 and e["state"] == 1
               and 0 <= e["freshness_ms"] <= 500]
    if len(players) != 1 or enemy["state"] != 1 or not 0 <= enemy["freshness_ms"] <= 500:
        return None
    own, other = players[0]["bbox_xywh"], enemy["bbox_xywh"]
    width, height = data["frame_width"], data["frame_height"]
    edge = min(body[2] * width, body[3] * height)
    return math.hypot((other[0] + other[2] / 2 - own[0] - own[2] / 2) * width / edge,
                      (other[1] + other[3] / 2 - own[1] - own[3] / 2) * height / edge)


def replay(events: list[dict], library: Path) -> tuple[list[dict], list[dict], list[float]]:
    profile = next(e["data"] for e in events if e["type"] == "profile")
    config = native.RelationConfig(*profile["relation_floats"], *profile["relation_ints"])
    body = profile["rois_xywh"][12:16]
    if len(body) != 4 or min(body[2:]) <= 0:
        raise ValueError("Diagnostics must include the calibrated map body")
    frames, outputs = [], []
    with native.Relation(native.load_library(library), config) as relation:
        for event in events:
            if event["type"] != "frame":
                continue
            data = event["data"]
            entities = []
            for stored in data["entities"]:
                item = native.TrackedEntity()
                item.entity_kind, item.track_id, item.state = stored["kind"], stored["track_id"], stored["state"]
                item.bbox = native.Rect(*stored["bbox_xywh"])
                item.confidence = stored["confidence"]
                item.freshness_ms, item.last_seen_ms = stored["freshness_ms"], stored["last_seen_ms"]
                entities.append(item)
            if len(entities) > native.MA_MAX_TRACKED_ENTITIES:
                raise ValueError("Snapshot exceeds the producer's bounded entity capacity")
            buffer = (native.TrackedEntity * max(1, len(entities)))(*entities)
            outputs.append(relation.update(buffer, len(entities), native.Rect(*body),
                                           data["locator_state"] != 0, data["frame_width"],
                                           data["frame_height"], data["engine_at_ms"]))
            frames.append(data)
    return frames, outputs, body


def compare(path: Path, baseline: Path, candidate: Path) -> dict:
    events = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    frames, before, body = replay(events, baseline)
    _, after, _ = replay(events, candidate)
    radius = next(e["data"]["relation_floats"][0] for e in events if e["type"] == "profile")
    approaches = []
    for index, result in enumerate(after):
        if result["event"] != "APPROACH":
            continue
        frame = frames[index]
        candidates = [(e, d) for e in frame["entities"] if e["kind"] == 2
                      and (d := distance(frame, e, body)) is not None]
        # The packet reports the nearest member of a coalesced batch; only
        # accept a unique matching stored association, never infer hero identity.
        matches = [e for e, d in candidates if abs(d - result["nearest_distance"]) < .001]
        row = {"frame_index": frame["frame_index"], "observed_at_ms": frame["observed_at_ms"],
               "distance": result["nearest_distance"], "sector": result["sector"],
               "track_id": matches[0]["track_id"] if len(matches) == 1 else None,
               "first_observed_entry_lead_ms": None, "baseline_cue_lead_ms": None,
               "followup": "not_resolved"}
        if row["track_id"] is not None:
            last_new_sighting = frame["observed_at_ms"]
            for future_index in range(index + 1, len(frames)):
                future = frames[future_index]
                gap = future["observed_at_ms"] - frame["observed_at_ms"]
                if gap > 3000:
                    if row["first_observed_entry_lead_ms"] is None:
                        row["followup"] = "no_entry_observed_within_3s"
                    break
                enemy = next((e for e in future["entities"] if e["kind"] == 2
                              and e["track_id"] == row["track_id"]), None)
                if not future["relation"]["reliable"] or enemy is None or enemy["state"] != 1:
                    row["followup"] = "censored_by_tracking_or_reliability_gap"
                    break
                d = distance(future, enemy, body)
                if d is None or enemy["last_seen_ms"] - last_new_sighting > 800:
                    row["followup"] = "censored_by_tracking_or_reliability_gap"
                    break
                last_new_sighting = max(last_new_sighting, enemy["last_seen_ms"])
                if d <= radius and row["first_observed_entry_lead_ms"] is None:
                    row["first_observed_entry_lead_ms"] = gap
                    row["followup"] = "entry_observed"
                if before[future_index]["event"] == "NEAR_ENTER" and abs(
                        d - before[future_index]["nearest_distance"]) < .001:
                    row["baseline_cue_lead_ms"] = gap
                    break
        approaches.append(row)
    baseline_events = Counter(r["event"] for r in before if r["event"])
    candidate_events = Counter(r["event"] for r in after if r["event"])
    return {"schema": "sensefield.honor_approach_replay/1",
            "scope": "Stored development associations; no independent motion, survival or acoustic truth",
            "events_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "baseline_library_sha256": hashlib.sha256(baseline.read_bytes()).hexdigest(),
            "candidate_library_sha256": hashlib.sha256(candidate.read_bytes()).hexdigest(),
            "frame_count": len(frames), "baseline_events": dict(baseline_events),
            "candidate_events": dict(candidate_events), "approaches": approaches}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--events", required=True, type=Path)
    parser.add_argument("--baseline-library", required=True, type=Path)
    parser.add_argument("--candidate-library", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = compare(args.events, args.baseline_library, args.candidate_library)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("frame_count", "baseline_events", "candidate_events")}))


if __name__ == "__main__":
    main()
