"""Event-level evaluation against held-out match annotations."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


KINDS = ("main_enemy", "minimap_enemy", "danger_ping")


def _p95(values: list[int]) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, (95 * len(ordered) + 99) // 100 - 1)]


def read_predictions(path: Path) -> list[dict]:
    predictions = []
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            record = json.loads(line)
            predictions.extend(record.get("cues", []))
    return predictions


def read_labels(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("Expected annotation schema_version 1")
    events = data["events"]
    for event in events:
        if (event["kind"] not in KINDS or event["start_ms"] < 0 or
                event["start_ms"] >= event["end_ms"] or
                event.get("direction") not in (None, "left", "right", "up", "down")):
            raise ValueError(f"Invalid event: {event}")
    return events


def _match_events(emitted: list[dict], ground_truth: list[dict], tolerance_ms: int) -> list[tuple[dict, dict]]:
    """Find the largest one-to-one set of temporally compatible cue/label pairs."""
    candidates = []
    for cue in emitted:
        time = cue["emitted_at_ms"]
        compatible = [
            index for index, event in enumerate(ground_truth)
            if event["start_ms"] <= time <= event["end_ms"] + tolerance_ms
        ]
        compatible.sort(key=lambda index: (abs(time - ground_truth[index]["start_ms"]), index))
        candidates.append(compatible)

    # An augmenting path can reassign an earlier cue when a later cue has fewer choices.
    label_to_cue: dict[int, int] = {}
    for root in sorted(range(len(emitted)), key=lambda index: (
        len(candidates[index]), emitted[index]["emitted_at_ms"], index
    )):
        queue = [root]
        seen_cues = {root}
        seen_labels: set[int] = set()
        predecessor: dict[int, int] = {}
        incoming_label: dict[int, int] = {}
        free_label = None
        for cue_index in queue:
            for label_index in candidates[cue_index]:
                if label_index in seen_labels:
                    continue
                seen_labels.add(label_index)
                predecessor[label_index] = cue_index
                owner = label_to_cue.get(label_index)
                if owner is None:
                    free_label = label_index
                    break
                if owner not in seen_cues:
                    seen_cues.add(owner)
                    incoming_label[owner] = label_index
                    queue.append(owner)
            if free_label is not None:
                break
        while free_label is not None:
            cue_index = predecessor[free_label]
            label_to_cue[free_label] = cue_index
            free_label = incoming_label.get(cue_index)

    return [(emitted[cue_index], ground_truth[label_index])
            for label_index, cue_index in sorted(label_to_cue.items(), key=lambda pair: pair[1])]


def evaluate(predictions: list[dict], labels: list[dict], tolerance_ms: int = 300) -> dict:
    if tolerance_ms < 0:
        raise ValueError("tolerance_ms must be nonnegative")
    report = {}
    all_matches = []
    for kind in KINDS:
        ground_truth = [event for event in labels if event["kind"] == kind]
        emitted = [cue for cue in predictions if cue["kind"] == kind]
        matches = _match_events(emitted, ground_truth, tolerance_ms)
        all_matches.extend(matches)
        true_positive = len(matches)
        false_positive = len(emitted) - true_positive
        false_negative = len(ground_truth) - true_positive
        precision = true_positive / len(emitted) if emitted else (1.0 if not ground_truth else 0.0)
        recall = true_positive / len(ground_truth) if ground_truth else 1.0
        directed = [(cue, label) for cue, label in matches if label.get("direction") is not None]
        direction_accuracy = (
            sum(cue.get("direction") == label["direction"] for cue, label in directed) / len(directed)
            if directed else None
        )
        report[kind] = {
            "tp": true_positive, "fp": false_positive, "fn": false_negative,
            "precision": round(precision, 4), "recall": round(recall, 4),
            "direction_accuracy": round(direction_accuracy, 4) if direction_accuracy is not None else None,
            "onset_to_cue_p95_ms": _p95([
                cue["emitted_at_ms"] - label["start_ms"] for cue, label in matches
            ]),
        }
    report["overall"] = {
        "tp": sum(report[k]["tp"] for k in KINDS),
        "fp": sum(report[k]["fp"] for k in KINDS),
        "fn": sum(report[k]["fn"] for k in KINDS),
    }
    total = report["overall"]
    report["overall"]["precision"] = round(
        total["tp"] / (total["tp"] + total["fp"]), 4
    ) if total["tp"] + total["fp"] else None
    report["overall"]["recall"] = round(
        total["tp"] / (total["tp"] + total["fn"]), 4
    ) if total["tp"] + total["fn"] else None
    directed = [(cue, label) for cue, label in all_matches if label.get("direction") is not None]
    report["overall"]["direction_accuracy"] = round(
        sum(cue.get("direction") == label["direction"] for cue, label in directed) / len(directed), 4
    ) if directed else None
    report["overall"]["onset_to_cue_p95_ms"] = _p95([
        cue["emitted_at_ms"] - label["start_ms"] for cue, label in all_matches
    ])
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("predictions", type=Path, help="JSONL from mapassist-replay")
    parser.add_argument("labels", type=Path, help="JSON annotations for a held-out match")
    parser.add_argument("--tolerance-ms", type=int, default=300)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = evaluate(read_predictions(args.predictions), read_labels(args.labels), args.tolerance_ms)
    data = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(data + "\n", encoding="utf-8")
    print(data)


if __name__ == "__main__":
    main()
