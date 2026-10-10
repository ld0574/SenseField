#!/usr/bin/env python3
"""Score real Match3MoveRanker outputs against reviewed task-relevant swaps."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
from pathlib import Path


def swap_key(value):
    if isinstance(value, dict):
        value = [value[key] for key in ("from_row", "from_col", "to_row", "to_col")]
    if len(value) != 4 or any(type(v) is not int or v < 0 for v in value):
        raise ValueError("swap coordinates must be four nonnegative integers")
    first, second = tuple(value[:2]), tuple(value[2:])
    if abs(first[0] - second[0]) + abs(first[1] - second[1]) != 1:
        raise ValueError("swap endpoints must be adjacent")
    return tuple(sorted((first, second)))


def evaluate(predictions, labels, require_independent=False):
    def index(items):
        out = {}
        for item in items:
            if not item.get("id") or item["id"] in out:
                raise ValueError("boards need unique ids")
            out[item["id"]] = item
        return out
    predicted, expected = index(predictions["rankings"]), index(labels["boards"])
    if not expected or predicted.keys() != expected.keys():
        raise ValueError("predicted and reviewed boards must match exactly")
    declared = labels.get("manifest_sha256")
    if declared is not None and (not re.fullmatch(r"[0-9a-f]{64}", declared) or declared != predictions.get("manifest_sha256")):
        raise ValueError("reviewed manifest hash differs from the actual replay")
    if require_independent:
        if labels.get("independent") is not True or predictions.get("independent") is not True or predictions.get("frozen") is not True or not declared:
            raise ValueError("independent evaluation needs matching frozen manifest provenance")
        frame_hashes = predictions.get("frame_sha256", {})
        if any(not re.fullmatch(r"[0-9a-f]{64}", frame_hashes.get(board, "")) for board in expected):
            raise ValueError("independent replay needs actual frame hashes")
        if labels.get("frame_sha256") != {board: frame_hashes[board] for board in expected}:
            raise ValueError("reviewed frame hashes differ from the actual replay")
    positive, top1, top3, abstained = 0, 0, 0, 0
    per_board = []
    for board_id, truth in expected.items():
        if truth.get("reviewed") is not True:
            raise ValueError("all task-relevance labels need explicit human review")
        relevant = {swap_key(swap) for swap in truth["relevant_swaps"]}
        candidates = [swap_key(swap) for swap in predicted[board_id]["candidates"]]
        if len(candidates) != len(set(candidates)):
            raise ValueError("ranked candidates contain duplicate swaps")
        geometry = predictions.get("boards", {}).get(board_id)
        if geometry and any(r >= geometry["rows"] or c >= geometry["cols"] for swap in relevant | set(candidates) for r, c in swap):
            raise ValueError("swap outside the replayed board")
        if relevant and truth.get("no_relevant_move") is True:
            raise ValueError("no_relevant_move contradicts relevant_swaps")
        if not relevant:
            if truth.get("no_relevant_move") is not True:
                raise ValueError("empty truth requires an explicit no_relevant_move judgment")
            per_board.append({"id": board_id, "opportunity": False, "candidates": len(candidates)})
            continue
        positive += 1
        hit1 = bool(candidates and candidates[0] in relevant)
        hit3 = any(swap in relevant for swap in candidates[:3])
        top1 += hit1
        top3 += hit3
        abstained += not candidates
        per_board.append({"id": board_id, "opportunity": True, "top1": hit1, "top3": hit3,
                          "abstained": not candidates})
    return {"boards": len(expected), "opportunity_boards": positive, "no_opportunity_boards": len(expected) - positive,
            "top1_hits": top1, "top3_hits": top3, "abstained": abstained,
            "top1_task_relevance": top1 / positive if positive else None,
            "top3_task_relevance": top3 / positive if positive else None,
            "independent": labels.get("independent") is True, "per_board": per_board,
            "manifest_sha256": predictions.get("manifest_sha256"),
            "scope": "one_move_task_relevance; no_cascade_prediction_or_guaranteed_clearance"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--predictions", required=True, type=Path)
    parser.add_argument("--labels", required=True, type=Path)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--require-independent", action="store_true")
    parser.add_argument("--min-top1", type=float)
    parser.add_argument("--min-top3", type=float)
    args = parser.parse_args(argv)
    report = evaluate(json.loads(args.predictions.read_text()), json.loads(args.labels.read_text()), args.require_independent)
    report["predictions_sha256"] = hashlib.sha256(args.predictions.read_bytes()).hexdigest()
    report["labels_sha256"] = hashlib.sha256(args.labels.read_bytes()).hexdigest()
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.json:
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    failed = False
    for key, minimum in (("top1_task_relevance", args.min_top1), ("top3_task_relevance", args.min_top3)):
        if minimum is not None and (report[key] is None or report[key] < minimum):
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
