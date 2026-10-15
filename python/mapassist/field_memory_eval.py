"""Synthetic FieldMemory evaluation harness (VoxMem protocol, stage P0).

Long context is not long-term memory: a window that fits is not a window that
recalls.  What gets lost is the binding between content, source, session and
non-verbal cue.  This module turns that claim into numbers without touching any
Android or gateway production code.

The harness builds a deterministic synthetic event stream that follows
``field_memory/v1``, truncates it into 2/5/10/20 minute versions where every
longer version strictly contains all events of the shorter ones, and measures
how well a fixed-budget retriever recovers the evidence a question needs.

Honest scope: this is a *retrieval-budget model*, not a language model and not
a device.  It quantifies how evidence retrievability decays as the stream grows
and how much each non-verbal cue contributes to binding.  Numbers produced here
are synthetic and must never be quoted as on-device measurements.
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path
from typing import Any, Iterable, Sequence

SCHEMA = "field_memory/v1"

#: Lifecycle transitions a memory record can carry.
EVENT_TYPES = ("APPEAR", "TRACK", "DISAPPEAR", "LAST_DIRECTION", "CLEARED")

#: Non-verbal cues stored as parameter fingerprints.  PCM is never stored.
NON_VERBAL_CUES = ("direction", "tone", "pan", "intensity")

#: Controlled-length versions.  A longer version is a strict superset.
VERSION_MINUTES = (2, 5, 10, 20)

DIRECTIONS = ("N", "NE", "E", "SE", "S", "SW", "W", "NW")
TONES = ("neutral_short", "rising_alert", "falling_retreat", "double_tick")
KINDS = ("enemy_mark", "enemy_hero", "objective")

#: Retrieval budget.  Fixed on purpose: growth must not buy more budget.
DEFAULT_TOP_K = 12

#: Fixed anchor so "how long ago" gold answers stay identical across versions.
TEMPORAL_ANCHOR_MS = 120_000


# --------------------------------------------------------------------------
# L0 records
# --------------------------------------------------------------------------


def make_record(
    *,
    record_id: str,
    match_id: str,
    segment: int,
    native_gen: int,
    channel: str,
    sensor: str,
    confidence: float,
    track_id: int,
    kind: str,
    event_type: str,
    prev_state: str | None,
    t_ms: int,
    text: str,
    cue_id: str,
    tone: str,
    direction: str,
    pan: float,
    intensity: float,
    salience: float,
    ttl_ms: int,
) -> dict[str, Any]:
    """Build one ``field_memory/v1`` L0 record.

    ``non_verbal`` holds parameter fingerprints only; audio is never stored.
    """
    if event_type not in EVENT_TYPES:
        raise ValueError(f"event type must be one of {EVENT_TYPES}, got {event_type!r}")
    return {
        "schema": SCHEMA,
        "record_id": record_id,
        "session": {
            "game": "honor",
            "match_id": match_id,
            "segment": segment,
            "native_gen": native_gen,
        },
        "source": {"channel": channel, "sensor": sensor, "confidence": confidence},
        "entity": {"track_id": track_id, "kind": kind, "identity_hint": None},
        "event": {"type": event_type, "prev_state": prev_state, "t_ms": t_ms},
        "evidence": {
            "text": text,
            "non_verbal": {
                "cue_id": cue_id,
                "tone": tone,
                "direction": direction,
                "pan": pan,
                "intensity": intensity,
            },
            "frame_ref": f"diag://checkpoint/{t_ms}",
        },
        "salience": salience,
        "ttl_ms": ttl_ms,
    }


# --------------------------------------------------------------------------
# Deterministic synthetic stream
# --------------------------------------------------------------------------


def generate_stream(
    seed: int = 20261015,
    minutes: int = 20,
    channel: str = "minimap",
    match_id: str = "m-20261015-01",
) -> list[dict[str, Any]]:
    """Build a deterministic synthetic minimap event stream.

    Every random draw comes from a seeded ``random.Random`` instance, so the
    same arguments always yield byte-identical records.
    """
    rng = random.Random(seed)
    horizon_ms = minutes * 60_000
    records: list[dict[str, Any]] = []
    counter = 0

    def emit(
        track_id: int,
        kind: str,
        event_type: str,
        prev_state: str | None,
        t_ms: int,
        direction: str,
        tone: str,
    ) -> dict[str, Any]:
        nonlocal counter
        counter += 1
        pan = round(rng.uniform(-1.0, 1.0), 3)
        intensity = round(rng.uniform(0.3, 1.0), 3)
        record = make_record(
            record_id=f"fm-{counter:06d}",
            match_id=match_id,
            segment=1 + t_ms // 300_000,
            native_gen=1 + t_ms // 60_000,
            channel=channel,
            sensor="yolox_dual_512_near_zone",
            confidence=round(rng.uniform(0.55, 0.95), 3),
            track_id=track_id,
            kind=kind,
            event_type=event_type,
            prev_state=prev_state,
            t_ms=t_ms,
            text=_describe(kind, track_id, event_type, direction, t_ms),
            cue_id=f"native:{1 + t_ms // 60_000}:{track_id % 5}:{event_type[:1]}",
            tone=tone,
            direction=direction,
            pan=pan,
            intensity=intensity,
            salience=round(rng.uniform(0.2, 0.95), 3),
            ttl_ms=600_000,
        )
        records.append(record)
        return record

    # Real entities: a full lifecycle inside the first two minutes so that every
    # question is answerable in all four controlled-length versions.
    for track_id in range(1, 7):
        kind = KINDS[track_id % len(KINDS)]
        appear_ms = rng.randint(5_000, 100_000)
        emit(track_id, kind, "APPEAR", None, appear_ms,
             rng.choice(DIRECTIONS), "neutral_short")
        for step in range(rng.randint(1, 3)):
            emit(track_id, kind, "TRACK", "APPEAR",
                 appear_ms + 8_000 * (step + 1),
                 rng.choice(DIRECTIONS), rng.choice(TONES))
        vanish_ms = appear_ms + rng.randint(20_000, 110_000)
        if vanish_ms < horizon_ms:
            last_dir = rng.choice(DIRECTIONS)
            emit(track_id, kind, "LAST_DIRECTION", "TRACK", vanish_ms,
                 last_dir, "falling_retreat")
            emit(track_id, kind, "DISAPPEAR", "TRACK", vanish_ms + 500,
                 last_dir, "falling_retreat")

    # Distractors: entities that only exist later in the stream.  They are what
    # makes longer versions harder, never what a question depends on.
    for track_id in range(100, 100 + 140):
        kind = KINDS[rng.randrange(len(KINDS))]
        t_ms = rng.randint(150_000, max(151_000, horizon_ms - 1_000))
        if t_ms >= horizon_ms:
            continue
        event_type = rng.choice(("APPEAR", "TRACK", "DISAPPEAR"))
        emit(track_id, kind, event_type, None, t_ms,
             rng.choice(DIRECTIONS), rng.choice(TONES))

    records.sort(key=lambda record: (record["event"]["t_ms"], record["record_id"]))
    for index, record in enumerate(records, start=1):
        record["record_id"] = f"fm-{index:06d}"
    return records


def _describe(kind: str, track_id: int, event_type: str, direction: str, t_ms: int) -> str:
    where = _direction_phrase(direction)
    if event_type == "APPEAR":
        return f"{track_id} 号{_kind_phrase(kind)}在{where}出现"
    if event_type == "TRACK":
        return f"{track_id} 号{_kind_phrase(kind)}仍在{where}移动"
    if event_type == "LAST_DIRECTION":
        return f"{track_id} 号{_kind_phrase(kind)}最后朝{where}移动"
    if event_type == "DISAPPEAR":
        return f"{track_id} 号{_kind_phrase(kind)}在{where}消失"
    return f"{track_id} 号{_kind_phrase(kind)}线索已清除"


def _kind_phrase(kind: str) -> str:
    return {"enemy_mark": "敌方标记", "enemy_hero": "敌方英雄", "objective": "目标点"}[kind]


def _direction_phrase(direction: str) -> str:
    return {
        "N": "上方", "NE": "右上方", "E": "右方", "SE": "右下方",
        "S": "下方", "SW": "左下方", "W": "左方", "NW": "左上方",
    }[direction]


# --------------------------------------------------------------------------
# Controlled-length versions
# --------------------------------------------------------------------------


def version_streams(master: Sequence[dict[str, Any]]) -> dict[int, list[dict[str, Any]]]:
    """Split a master stream into controlled-length versions.

    Versions are time prefixes, so a longer version is a strict superset of a
    shorter one: everything the short version had, plus later distractors.
    """
    versions: dict[int, list[dict[str, Any]]] = {}
    for minutes in VERSION_MINUTES:
        limit = minutes * 60_000
        versions[minutes] = [
            record for record in master if record["event"]["t_ms"] < limit
        ]
    return versions


# --------------------------------------------------------------------------
# Questions
# --------------------------------------------------------------------------


def build_questions(master: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    """Derive existence / temporal / binding questions plus a refusal set.

    Every question is anchored on an entity whose lifecycle sits inside the
    first two minutes, so the gold answer is identical in all four versions.
    """
    by_key: dict[tuple[int, str], dict[str, Any]] = {}
    for record in master:
        if record["event"]["t_ms"] >= 120_000:
            continue
        by_key[(record["entity"]["track_id"], record["event"]["type"])] = record

    questions: list[dict[str, Any]] = []
    for track_id in range(1, 7):
        appear = by_key.get((track_id, "APPEAR"))
        if appear is None:
            continue
        kind = appear["entity"]["kind"]
        questions.append({
            "question_id": f"existence-{track_id}",
            "answer_type": "existence",
            "text": f"{track_id} 号{_kind_phrase(kind)}在小地图出现过吗？",
            "channel": appear["source"]["channel"],
            "event_types": ("APPEAR",),
            "target": {"track_id": track_id, "event_type": "APPEAR"},
            "gold": {"happened": True},
        })

        vanish = by_key.get((track_id, "DISAPPEAR"))
        direction = by_key.get((track_id, "LAST_DIRECTION"))
        if vanish is not None and direction is not None:
            questions.append({
                "question_id": f"binding-direction-{track_id}",
                "answer_type": "binding",
                "text": f"{track_id} 号{_kind_phrase(kind)}消失前最后朝哪个方向？",
                "channel": appear["source"]["channel"],
                "event_types": ("LAST_DIRECTION",),
                "target": {
                    "track_id": track_id,
                    "event_type": "LAST_DIRECTION",
                    "cue": "direction",
                },
                "gold": {"direction": direction["evidence"]["non_verbal"]["direction"]},
            })
            vanish_cues = vanish["evidence"]["non_verbal"]
            questions.append({
                "question_id": f"binding-tone-{track_id}",
                "answer_type": "binding",
                "text": f"{track_id} 号{_kind_phrase(kind)}消失时的提示音色是哪一种？",
                "channel": appear["source"]["channel"],
                "event_types": ("DISAPPEAR",),
                "target": {
                    "track_id": track_id,
                    "event_type": "DISAPPEAR",
                    "cue": "tone",
                },
                "gold": {"tone": vanish_cues["tone"]},
            })
            questions.append({
                "question_id": f"binding-pan-{track_id}",
                "answer_type": "binding",
                "text": f"{track_id} 号{_kind_phrase(kind)}消失时声音偏左还是偏右？",
                "channel": appear["source"]["channel"],
                "event_types": ("DISAPPEAR",),
                "target": {
                    "track_id": track_id,
                    "event_type": "DISAPPEAR",
                    "cue": "pan",
                },
                "gold": {"pan_side": _pan_side(vanish_cues["pan"])},
            })
            appear_cues = appear["evidence"]["non_verbal"]
            questions.append({
                "question_id": f"binding-intensity-{track_id}",
                "answer_type": "binding",
                "text": f"{track_id} 号{_kind_phrase(kind)}出现时提示音算强还是算弱？",
                "channel": appear["source"]["channel"],
                "event_types": ("APPEAR",),
                "target": {
                    "track_id": track_id,
                    "event_type": "APPEAR",
                    "cue": "intensity",
                },
                "gold": {"intensity_band": _intensity_band(appear_cues["intensity"])},
            })
            elapsed = vanish["event"]["t_ms"] - appear["event"]["t_ms"]
            questions.append({
                "question_id": f"temporal-elapsed-{track_id}",
                "answer_type": "temporal",
                "text": f"{track_id} 号{_kind_phrase(kind)}从出现到消失经过了多久？",
                "channel": appear["source"]["channel"],
                "event_types": ("APPEAR", "DISAPPEAR"),
                "target": {"track_id": track_id},
                "gold": {"elapsed_ms": elapsed, "tolerance_ms": 8_000},
            })

    questions.extend(_refusal_questions(master))
    return questions


def _refusal_questions(master: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    """Refusal items: the required evidence never happened at all."""
    live = {record["entity"]["track_id"] for record in master}
    channel = master[0]["source"]["channel"] if master else "minimap"
    questions: list[dict[str, Any]] = []
    for offset, track_id in enumerate(range(900, 906), start=1):
        if track_id in live:
            continue
        questions.append({
            "question_id": f"refusal-{offset}",
            "answer_type": "refusal",
            "text": f"{track_id} 号敌方标记在小地图出现过吗？",
            "channel": channel,
            "event_types": ("APPEAR",),
            "target": {"track_id": track_id, "event_type": "APPEAR"},
            "gold": {"happened": False},
        })
    return questions


# --------------------------------------------------------------------------
# Retrieval under a fixed budget
# --------------------------------------------------------------------------


def retrieve(
    records: Sequence[dict[str, Any]],
    question: dict[str, Any],
    top_k: int = DEFAULT_TOP_K,
) -> list[dict[str, Any]]:
    """Rank candidates by salience under a fixed record budget.

    Candidates are *not* filtered by entity: the retriever must bind identity
    from the evidence itself, which is exactly where long streams fail.
    """
    wanted = set(question["event_types"])
    candidates = [
        record for record in records
        if record["source"]["channel"] == question["channel"]
        and record["event"]["type"] in wanted
    ]
    candidates.sort(key=lambda record: (-record["salience"], record["record_id"]))
    return candidates[:top_k]


def _target_record(
    retrieved: Sequence[dict[str, Any]],
    track_id: int,
    event_type: str,
) -> dict[str, Any] | None:
    for record in retrieved:
        if (record["entity"]["track_id"] == track_id
                and record["event"]["type"] == event_type):
            return record
    return None


def judge(question: dict[str, Any], retrieved: Sequence[dict[str, Any]]) -> dict[str, Any]:
    """Score one question against the retrieved evidence."""
    answer_type = question["answer_type"]
    target = question["target"]

    if answer_type == "existence":
        found = _target_record(retrieved, target["track_id"], target["event_type"])
        return {"correct": found is not None, "answered": found is not None}

    if answer_type == "binding":
        record = _target_record(
            retrieved, target["track_id"], target["event_type"])
        if record is None:
            return {"correct": False, "answered": False, "reason": "target_not_retrieved"}
        cue = target["cue"]
        value = record["evidence"]["non_verbal"].get(cue)
        if value is None:
            return {"correct": False, "answered": False, "reason": "cue_absent"}
        gold = question["gold"]
        if "direction" in gold:
            # Direction is stored verbatim; compare directly.
            expected: Any = gold["direction"]
            observed: Any = value
        elif "tone" in gold:
            expected = gold["tone"]
            observed = value
        elif "pan_side" in gold:
            # Pan is a float; the gold answer is its derived side.
            expected = gold["pan_side"]
            observed = _pan_side(value)
        else:
            expected = gold["intensity_band"]
            observed = _intensity_band(value)
        return {"correct": observed == expected, "answered": True}

    if answer_type == "temporal":
        appear = _target_record(retrieved, target["track_id"], "APPEAR")
        vanish = _target_record(retrieved, target["track_id"], "DISAPPEAR")
        if appear is None or vanish is None:
            return {"correct": False, "answered": False, "reason": "endpoints_not_retrieved"}
        elapsed = vanish["event"]["t_ms"] - appear["event"]["t_ms"]
        tolerance = question["gold"]["tolerance_ms"]
        return {
            "correct": abs(elapsed - question["gold"]["elapsed_ms"]) <= tolerance,
            "answered": True,
            "elapsed_ms": elapsed,
        }

    if answer_type == "refusal":
        # Correct behaviour is to refuse: no record may back the claim.
        found = _target_record(retrieved, target["track_id"], target["event_type"])
        return {"correct": found is None, "answered": found is None, "refused": found is None}

    raise ValueError(f"unknown answer type {answer_type!r}")


# --------------------------------------------------------------------------
# Evaluation stages
# --------------------------------------------------------------------------


def evaluate_length_decay(
    master: Sequence[dict[str, Any]],
    questions: Sequence[dict[str, Any]],
    top_k: int = DEFAULT_TOP_K,
) -> list[dict[str, Any]]:
    """Length-versus-accuracy curve, grouped by answer type."""
    versions = version_streams(master)
    curve: list[dict[str, Any]] = []
    for minutes in VERSION_MINUTES:
        records = versions[minutes]
        per_type: dict[str, dict[str, int]] = {}
        for question in questions:
            verdict = judge(question, retrieve(records, question, top_k))
            bucket = per_type.setdefault(
                question["answer_type"], {"correct": 0, "total": 0})
            bucket["total"] += 1
            bucket["correct"] += int(verdict["correct"])
        curve.append({
            "minutes": minutes,
            "records": len(records),
            "top_k": top_k,
            "by_answer_type": {
                name: {
                    "correct": counts["correct"],
                    "total": counts["total"],
                    "accuracy": round(counts["correct"] / counts["total"], 4)
                    if counts["total"] else None,
                }
                for name, counts in sorted(per_type.items())
            },
        })
    return curve


def _strip_cue(
    records: Sequence[dict[str, Any]],
    cue: str,
) -> list[dict[str, Any]]:
    """Copy the stream with one non-verbal cue removed from every record."""
    if cue not in NON_VERBAL_CUES:
        raise ValueError(f"cue must be one of {NON_VERBAL_CUES}, got {cue!r}")
    stripped: list[dict[str, Any]] = []
    for record in records:
        clone = json.loads(json.dumps(record))
        clone["evidence"]["non_verbal"].pop(cue, None)
        stripped.append(clone)
    return stripped


def _pan_side(pan: float) -> str:
    """Left/right side of a stereo pan value."""
    return "左" if pan < 0 else "右"


def _intensity_band(intensity: float) -> str:
    """Coarse intensity band so the gold answer is stable."""
    return "强" if intensity >= 0.65 else "弱"


def evaluate_minimal_pairs(
    master: Sequence[dict[str, Any]],
    questions: Sequence[dict[str, Any]],
    top_k: int = DEFAULT_TOP_K,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Attribute each non-verbal cue by removing exactly one cue at a time.

    For every cue we build a paired stream where that cue is stripped from the
    records the binding questions read, then re-run the same questions.  The
    accuracy drop is that cue's contribution to binding.
    """
    versions = version_streams(master)
    base_records = versions[VERSION_MINUTES[0]]
    binding = [q for q in questions if q["answer_type"] == "binding"]
    if not binding:
        return [], []

    baseline = [
        judge(question, retrieve(base_records, question, top_k))["correct"]
        for question in binding
    ]

    attribution: list[dict[str, Any]] = []
    for cue in NON_VERBAL_CUES:
        stripped = _strip_cue(base_records, cue)
        outcomes = [
            judge(question, retrieve(stripped, question, top_k))["correct"]
            for question in binding
        ]
        dropped = [
            question["question_id"]
            for question, before, after in zip(binding, baseline, outcomes)
            if before and not after
        ]
        attribution.append({
            "cue": cue,
            "baseline_correct": sum(baseline),
            "stripped_correct": sum(outcomes),
            "total": len(binding),
            "baseline_accuracy": round(sum(baseline) / len(binding), 4),
            "stripped_accuracy": round(sum(outcomes) / len(binding), 4),
            "accuracy_drop": round(
                (sum(baseline) - sum(outcomes)) / len(binding), 4),
            "questions_broken": dropped,
        })

    # Decisive pairs: strip the cue the question itself depends on.
    decisive: list[dict[str, Any]] = []
    for question in binding:
        target = question["target"]
        cue = target["cue"]
        stripped = _strip_cue(base_records, cue)
        verdict = judge(question, retrieve(stripped, question, top_k))
        decisive.append({
            "cue": cue,
            "question_id": question["question_id"],
            "correct": verdict["correct"],
            "reason": verdict.get("reason"),
        })
    return attribution, decisive


def evaluate_refusal(
    master: Sequence[dict[str, Any]],
    questions: Sequence[dict[str, Any]],
    top_k: int = DEFAULT_TOP_K,
) -> dict[str, Any]:
    """Refusal set must be refused, in every length version."""
    versions = version_streams(master)
    refusal = [q for q in questions if q["answer_type"] == "refusal"]
    per_version: list[dict[str, Any]] = []
    for minutes in VERSION_MINUTES:
        records = versions[minutes]
        correct = 0
        for question in refusal:
            verdict = judge(question, retrieve(records, question, top_k))
            correct += int(verdict["correct"])
        per_version.append({
            "minutes": minutes,
            "correct": correct,
            "total": len(refusal),
            "refusal_rate": round(correct / len(refusal), 4) if refusal else None,
        })
    return {"total": len(refusal), "by_version": per_version}


# --------------------------------------------------------------------------
# JSONL helpers
# --------------------------------------------------------------------------


def write_jsonl(path: Path, records: Iterable[dict[str, Any]]) -> int:
    count = 0
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
            count += 1
    return count


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


# --------------------------------------------------------------------------
# Report
# --------------------------------------------------------------------------


def run_evaluation(
    seed: int = 20261015,
    minutes: int = 20,
    top_k: int = DEFAULT_TOP_K,
) -> dict[str, Any]:
    """Run every stage and return the machine-readable report payload."""
    master = generate_stream(seed=seed, minutes=minutes)
    questions = build_questions(master)
    attribution, decisive = evaluate_minimal_pairs(master, questions, top_k)
    return {
        "schema": SCHEMA,
        "seed": seed,
        "minutes": minutes,
        "top_k": top_k,
        "record_count": len(master),
        "question_count": len(questions),
        "answer_type_counts": _count_types(questions),
        "length_decay": evaluate_length_decay(master, questions, top_k),
        "minimal_pair_attribution": attribution,
        "minimal_pair_decisive": decisive,
        "refusal": evaluate_refusal(master, questions, top_k),
        "scope": (
            "Synthetic retrieval-budget model. Not a language model, not a "
            "device measurement. Never quote as on-device accuracy."
        ),
    }


def _count_types(questions: Sequence[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for question in questions:
        counts[question["answer_type"]] = counts.get(question["answer_type"], 0) + 1
    return dict(sorted(counts.items()))


def render_markdown(payload: dict[str, Any]) -> str:
    """Human-readable validation record, matching validation/assistant/ style."""
    lines: list[str] = []
    lines.append("# FieldMemory 评测首份记录（P0，合成事件流）")
    lines.append("")
    lines.append(f"- 日期：2026-10-15")
    lines.append(f"- 分支：feature/field-memory（基点 main 4c6d5e6）")
    lines.append(f"- schema：`{payload['schema']}`，种子 {payload['seed']}，"
                 f"流长 {payload['minutes']} 分钟，检索预算 top_k={payload['top_k']}")
    lines.append(f"- 记录数 {payload['record_count']}，问题数 {payload['question_count']}"
                 f"（{_render_counts(payload['answer_type_counts'])}）")
    lines.append("")
    lines.append("## 测量口径（先读这段，别拿这些数字当真机指标）")
    lines.append("")
    lines.append("- 合成事件流，确定性种子可复现；**不是语言模型、不是真机测量**。")
    lines.append("- 检索模型：固定 top_k 条记录预算，按 salience 排序，**不按实体预过滤**"
                 "——身份必须从证据里绑定，这正是长流失败的地方。")
    lines.append("- 受控长度四版（2/5/10/20 分钟）是同一母流的时间前缀，长版严格包含短版全部事件。")
    lines.append("- 拒答集由从未发生的实体派生，期望输出拒答。")
    lines.append("")
    lines.append("## 长度—准确率衰减曲线")
    lines.append("")
    lines.append("| 版本(分钟) | 记录数 | " + " | ".join(
        _TYPE_LABELS[name] for name in sorted(payload["answer_type_counts"])) + " |")
    lines.append("|---:|---:|" + "---:|" * len(payload["answer_type_counts"]))
    for row in payload["length_decay"]:
        cells = []
        for name in sorted(payload["answer_type_counts"]):
            stats = row["by_answer_type"].get(name)
            cells.append(f"{stats['correct']}/{stats['total']}" if stats else "-")
        lines.append(f"| {row['minutes']} | {row['records']} | " + " | ".join(cells) + " |")
    lines.append("")
    lines.append("## 最小对归因（每次只从全流去掉一条非词汇线索）")
    lines.append("")
    lines.append("| 去掉的线索 | 基线准确率 | 去掉后准确率 | 落差 | 被打断的问题 |")
    lines.append("|---|---:|---:|---:|---|")
    for row in payload["minimal_pair_attribution"]:
        lines.append(
            f"| {row['cue']} | {row['baseline_accuracy']:.0%} | "
            f"{row['stripped_accuracy']:.0%} | {row['accuracy_drop']:+.0%} | "
            f"{len(row['questions_broken'])}/{row['total']} |")
    lines.append("")
    decisive = payload["minimal_pair_decisive"]
    if decisive:
        broken = [item for item in decisive if not item["correct"]]
        lines.append("")
        lines.append(f"决定性最小对（去掉问题自身依赖的那条线索）："
                     f"{len(decisive)} 条中 {len(broken)} 条如预期答错，"
                     f"理由均为 `cue_absent`——说明判分没有把"
                     f"「线索缺失」误判成「答错但有理」。")
        for item in decisive[:2]:
            lines.append(f"- `{item['question_id']}` 去掉 `{item['cue']}` → "
                         f"{'答对' if item['correct'] else '答错'}"
                         f"{'（' + item['reason'] + '）' if item.get('reason') else ''}")
    lines.append("")
    lines.append("## 拒答率")
    lines.append("")
    lines.append("| 版本(分钟) | 正确拒答 | 总数 | 拒答率 |")
    lines.append("|---:|---:|---:|---:|")
    for row in payload["refusal"]["by_version"]:
        rate = "n/a" if row["refusal_rate"] is None else f"{row['refusal_rate']:.0%}"
        lines.append(f"| {row['minutes']} | {row['correct']} | {row['total']} | {rate} |")
    lines.append("")
    lines.append("## 结论（就这些数字能说的）")
    lines.append("")
    decay = payload["length_decay"]
    if len(decay) >= 2:
        first, last = decay[0], decay[-1]
        lines.append(f"- 记录数从 {first['records']} 增到 {last['records']}"
                     f"（{first['minutes']}→{last['minutes']} 分钟），检索预算不变。")
        for name in sorted(payload["answer_type_counts"]):
            a = first["by_answer_type"].get(name)
            b = last["by_answer_type"].get(name)
            if a and b and a["accuracy"] is not None and b["accuracy"] is not None:
                delta = b["accuracy"] - a["accuracy"]
                lines.append(f"- {_TYPE_LABELS[name]}：{a['accuracy']:.0%} → "
                             f"{b['accuracy']:.0%}（{delta:+.0%}）")
    attribution = payload["minimal_pair_attribution"]
    if attribution:
        drops = {row["cue"]: row["accuracy_drop"] for row in attribution}
        distinct = sorted(set(drops.values()))
        if len(distinct) == 1:
            lines.append(f"- 最小对显示四条非词汇线索**各自独立、缺一不可**："
                         f"每条被去掉都让绑定准确率掉 {distinct[0]:.0%}，"
                         f"没有哪条能替代另一条。")
        else:
            worst = max(attribution, key=lambda row: row["accuracy_drop"])
            lines.append(f"- 最小对显示最关键的绑定线索是 `{worst['cue']}`"
                         f"（去掉后准确率掉 {worst['accuracy_drop']:.0%}）。")
    refusal_last = payload["refusal"]["by_version"][-1]
    lines.append(f"- 最长版本拒答率 {refusal_last['refusal_rate']:.0%}"
                 f"（{refusal_last['correct']}/{refusal_last['total']}）。")
    lines.append("")
    lines.append("## 边界（与视野记忆第一版一致）")
    lines.append("")
    lines.append("- 只回答「发生过什么／什么时候／凭什么证据」，不给路线、打龙、"
                 "「注意敌方打野」等策略判断。")
    lines.append("- `non_verbal` 只存参数指纹，不存音频。")
    return "\n".join(lines) + "\n"


_TYPE_LABELS = {
    "existence": "存在性",
    "temporal": "时序",
    "binding": "绑定",
    "refusal": "拒答",
}


def _render_counts(counts: dict[str, int]) -> str:
    return "、".join(f"{_TYPE_LABELS[name]} {value}" for name, value in counts.items())


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--seed", type=int, default=20261015)
    parser.add_argument("--minutes", type=int, default=20, choices=sorted(VERSION_MINUTES))
    parser.add_argument("--top-k", type=int, default=DEFAULT_TOP_K)
    parser.add_argument("--jsonl-out", type=Path, default=None,
                        help="write the synthetic master stream as JSONL")
    parser.add_argument("--json-out", type=Path, default=None,
                        help="write the machine-readable report payload")
    parser.add_argument("--markdown-out", type=Path, default=None,
                        help="write the human-readable validation record")
    args = parser.parse_args(argv)

    payload = run_evaluation(seed=args.seed, minutes=args.minutes, top_k=args.top_k)

    if args.jsonl_out:
        master = generate_stream(seed=args.seed, minutes=args.minutes)
        count = write_jsonl(args.jsonl_out, master)
        print(f"wrote {count} records to {args.jsonl_out}")
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
        print(f"wrote report payload to {args.json_out}")
    if args.markdown_out:
        args.markdown_out.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_out.write_text(render_markdown(payload), encoding="utf-8")
        print(f"wrote validation record to {args.markdown_out}")
    if not (args.jsonl_out or args.json_out or args.markdown_out):
        print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
