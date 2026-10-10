#!/usr/bin/env python3
"""Score the Match3 recognizer against a labeled holdout.

Phase 0 of the recognition flywheel: turn "准确率不行 / 识别不到" into measured
numbers. This module only *scores* — it never re-implements recognition. The
real engine's per-cell output is produced on an emulator by
Match3HoldoutAccuracyInstrumentedTest and handed here as predictions.json.

Two report halves:
  * label-free (always): coverage / abstention of the current engine on real
    boards. Needs no ground truth, so it is honest to run today.
  * labeled (when labels.json is supplied): 漏认 miss / 认错 misclassify /
    误放 false-swap / 漏换 swap-miss, overall and per family.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List, Optional

# Match3Position.Kind values that leave a cell non-playable ('#'/'.'): the
# engine saw something but could not commit to a swappable identity. From the
# player's view these are exactly "识别不到".
ABSTAINED_KINDS = {"UNKNOWN", "SURFACE", "SPECIAL"}


def _resolved(sample: dict) -> bool:
    kind = sample.get("kind", "UNKNOWN")
    if kind in ABSTAINED_KINDS:
        return False
    # A recognized face without a confirmed cover/permission is still unplayable.
    if kind == "ANIMAL" and sample.get("swap_permission") == "UNKNOWN":
        return False
    return True


def _family(sample: dict) -> str:
    if sample.get("family"):
        return str(sample["family"])
    kind = sample.get("kind", "UNKNOWN")
    color = sample.get("color") or ""
    return f"{kind}:{color}" if color else kind


def load_samples(path: Optional[Path]) -> Dict[str, dict]:
    if path is None:
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    out: Dict[str, dict] = {}
    for sample in data.get("samples", []):
        sample_id = sample.get("id")
        if not sample_id:
            raise ValueError("every sample needs an id")
        if sample_id in out:
            raise ValueError(f"duplicate sample id {sample_id}")
        out[sample_id] = sample
    return out


def label_free(predictions: Dict[str, dict]) -> dict:
    total = len(predictions)
    kinds: Dict[str, int] = {}
    resolved = 0
    frames: Dict[str, Dict[str, int]] = {}
    for sample_id, sample in predictions.items():
        kind = sample.get("kind", "UNKNOWN")
        kinds[kind] = kinds.get(kind, 0) + 1
        frame = sample_id.split(":", 1)[0]
        bucket = frames.setdefault(frame, {"total": 0, "resolved": 0})
        bucket["total"] += 1
        if _resolved(sample):
            resolved += 1
            bucket["resolved"] += 1
    per_frame = {name: {**b, "coverage": (b["resolved"] / b["total"]) if b["total"] else 0.0}
                 for name, b in sorted(frames.items())}
    return {
        "total": total,
        "resolved": resolved,
        "coverage": (resolved / total) if total else 0.0,
        "abstained": total - resolved,
        "kind_histogram": dict(sorted(kinds.items())),
        "per_frame": per_frame,
    }


def _truth_swappable(label: dict) -> Optional[bool]:
    value = label.get("swappable")
    return None if value is None else bool(value)


def labeled(predictions: Dict[str, dict], labels: Dict[str, dict]) -> dict:
    families: Dict[str, Dict[str, int]] = {}

    def bucket(name: str) -> Dict[str, int]:
        return families.setdefault(name, {"n": 0, "correct": 0, "miss": 0,
                                          "misclassify": 0, "false_swap": 0, "swap_miss": 0})

    missing_prediction = 0
    for sample_id, label in labels.items():
        pred = predictions.get(sample_id)
        if pred is None:
            missing_prediction += 1
            continue
        stats = bucket(_family(label))
        stats["n"] += 1
        truth_known = _family(label) not in ABSTAINED_KINDS and label.get("kind") not in ABSTAINED_KINDS
        if truth_known:
            if not _resolved(pred):
                stats["miss"] += 1
            elif _family(pred) != _family(label):
                stats["misclassify"] += 1
            else:
                stats["correct"] += 1
        swap_truth = _truth_swappable(label)
        pred_swap = pred.get("swap_permission") == "YES"
        if swap_truth is False and pred_swap:
            stats["false_swap"] += 1
        elif swap_truth is True and not pred_swap:
            stats["swap_miss"] += 1

    overall = {"n": 0, "correct": 0, "miss": 0, "misclassify": 0, "false_swap": 0, "swap_miss": 0}
    for stats in families.values():
        for key in overall:
            overall[key] += stats[key]
    return {
        "overall": overall,
        "per_family": dict(sorted(families.items())),
        "labels_without_prediction": missing_prediction,
    }


def render(report: dict) -> str:
    lines: List[str] = []
    free = report["label_free"]
    lines.append("== 识别覆盖（label-free，当前引擎在真实棋盘上的放弃率）==")
    lines.append(f"  样本 total={free['total']}  已定 resolved={free['resolved']}  "
                 f"覆盖 coverage={free['coverage']:.3f}  放弃 abstained={free['abstained']}")
    lines.append(f"  kind 分布: {free['kind_histogram']}")
    if len(free.get("per_frame", {})) > 1:
        lines.append("  per-board coverage:")
        for name, b in free["per_frame"].items():
            lines.append(f"    {name}: {b['resolved']}/{b['total']} = {b['coverage']:.3f}")
    lab = report.get("labeled")
    if lab:
        o = lab["overall"]
        lines.append("== 带标签准确率（需人工真值）==")
        lines.append(f"  计数 n={o['n']}  正确 correct={o['correct']}  "
                     f"漏认 miss={o['miss']}  认错 misclassify={o['misclassify']}")
        lines.append(f"  误放 false_swap={o['false_swap']}  漏换 swap_miss={o['swap_miss']}")
        if lab["labels_without_prediction"]:
            lines.append(f"  （有标签但无预测: {lab['labels_without_prediction']}）")
        lines.append("  per-family: family n correct miss misclassify false_swap swap_miss")
        for name, s in lab["per_family"].items():
            lines.append(f"    {name}: {s['n']} {s['correct']} {s['miss']} "
                         f"{s['misclassify']} {s['false_swap']} {s['swap_miss']}")
    else:
        lines.append("== 带标签准确率: 未提供 labels.json（仅记录覆盖基线）==")
    return "\n".join(lines)


def evaluate(predictions_path: Path, labels_path: Optional[Path]) -> dict:
    predictions = load_samples(predictions_path)
    if not predictions:
        raise ValueError("predictions.json has no samples")
    labels = load_samples(labels_path)
    report = {"label_free": label_free(predictions)}
    if labels:
        report["labeled"] = labeled(predictions, labels)
    return report


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Score the Match3 recognizer against a holdout")
    parser.add_argument("--predictions", required=True, type=Path, help="engine output from the instrumented producer")
    parser.add_argument("--labels", type=Path, help="human ground truth; omit for coverage-only")
    parser.add_argument("--json", type=Path, help="write the raw report here")
    parser.add_argument("--max-false-swap", type=int, default=None,
                        help="fail if 误放 exceeds this (off by default; Phase 0 only records)")
    parser.add_argument("--min-coverage", type=float, default=None,
                        help="fail if coverage falls below this (off by default)")
    args = parser.parse_args(argv)

    report = evaluate(args.predictions, args.labels)
    print(render(report))
    if args.json:
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    failed = False
    if args.min_coverage is not None and report["label_free"]["coverage"] < args.min_coverage:
        print(f"GATE FAIL: coverage {report['label_free']['coverage']:.3f} < {args.min_coverage}")
        failed = True
    if args.max_false_swap is not None and report.get("labeled"):
        false_swap = report["labeled"]["overall"]["false_swap"]
        if false_swap > args.max_false_swap:
            print(f"GATE FAIL: false_swap {false_swap} > {args.max_false_swap}")
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
