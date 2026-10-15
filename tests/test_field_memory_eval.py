"""Unit tests for the synthetic FieldMemory evaluation harness.

The three hard assertions guard the properties the whole protocol rests on:
longer controlled-length versions must strictly contain shorter ones, minimal
pairs must differ in exactly one non-verbal cue, and the refusal set must be
refused.  If any of these silently breaks, every number the harness prints
becomes meaningless.
"""

from __future__ import annotations

import json

import pytest

from mapassist import field_memory_eval as fm


def _record_keys(records):
    return {
        (record["entity"]["track_id"], record["event"]["type"], record["event"]["t_ms"])
        for record in records
    }


# --------------------------------------------------------------------------
# Assertion 1: controlled-length versions are strict supersets
# --------------------------------------------------------------------------


def test_longer_version_strictly_contains_all_shorter_events():
    master = fm.generate_stream(seed=20261015, minutes=20)
    versions = fm.version_streams(master)

    assert tuple(sorted(versions)) == fm.VERSION_MINUTES

    shorter_keys = None
    for minutes in sorted(versions):
        keys = _record_keys(versions[minutes])
        if shorter_keys is not None:
            missing = shorter_keys - keys
            assert not missing, (
                f"{minutes}-minute version lost {len(missing)} events from a "
                f"shorter version: {sorted(missing)[:5]}"
            )
        shorter_keys = keys

    # "Strictly" also means longer versions really do add events.
    counts = [len(versions[minutes]) for minutes in sorted(versions)]
    assert counts == sorted(counts) and counts[0] < counts[-1], counts


def test_version_prefix_is_time_bounded():
    master = fm.generate_stream(seed=20261015, minutes=20)
    versions = fm.version_streams(master)
    for minutes, records in versions.items():
        limit = minutes * 60_000
        assert all(record["event"]["t_ms"] < limit for record in records)


# --------------------------------------------------------------------------
# Assertion 2: minimal pairs differ in exactly one non-verbal cue
# --------------------------------------------------------------------------


@pytest.mark.parametrize("cue", fm.NON_VERBAL_CUES)
def test_minimal_pair_removes_exactly_one_cue(cue):
    master = fm.generate_stream(seed=20261015, minutes=20)
    base = fm.version_streams(master)[fm.VERSION_MINUTES[0]]
    stripped = fm._strip_cue(base, cue)

    assert len(stripped) == len(base)
    for original, paired in zip(base, stripped):
        before = original["evidence"]["non_verbal"]
        after = paired["evidence"]["non_verbal"]
        assert set(before) - set(after) == {cue}
        assert set(after) <= set(before)
        for name, value in after.items():
            assert value == before[name], f"cue {name} changed, expected only {cue}"


def test_strip_cue_rejects_unknown_cue():
    master = fm.generate_stream(seed=20261015, minutes=2)
    with pytest.raises(ValueError):
        fm._strip_cue(master, "pcm_samples")


def test_removing_the_depended_on_cue_breaks_that_binding_question():
    master = fm.generate_stream(seed=20261015, minutes=20)
    questions = fm.build_questions(master)
    base = fm.version_streams(master)[fm.VERSION_MINUTES[0]]

    binding = [q for q in questions if q["answer_type"] == "binding"]
    assert binding, "no binding questions were generated"

    for question in binding:
        cue = question["target"]["cue"]
        stripped = fm._strip_cue(base, cue)
        verdict = fm.judge(question, fm.retrieve(stripped, question))
        assert verdict["correct"] is False, (
            f"{question['question_id']} still answered correctly without {cue}"
        )
        assert verdict.get("reason") == "cue_absent"


def test_baseline_binding_questions_are_answerable():
    """Without stripping, every binding question must be answerable."""
    master = fm.generate_stream(seed=20261015, minutes=20)
    questions = fm.build_questions(master)
    base = fm.version_streams(master)[fm.VERSION_MINUTES[0]]

    binding = [q for q in questions if q["answer_type"] == "binding"]
    for question in binding:
        verdict = fm.judge(question, fm.retrieve(base, question))
        assert verdict["correct"] is True, (
            f"{question['question_id']} failed on the unmodified stream"
        )


# --------------------------------------------------------------------------
# Assertion 3: the refusal set must be refused
# --------------------------------------------------------------------------


def test_refusal_set_is_refused_in_every_length_version():
    master = fm.generate_stream(seed=20261015, minutes=20)
    questions = fm.build_questions(master)
    refusal = [q for q in questions if q["answer_type"] == "refusal"]
    assert refusal, "no refusal questions were generated"

    versions = fm.version_streams(master)
    for minutes in sorted(versions):
        for question in refusal:
            verdict = fm.judge(question, fm.retrieve(versions[minutes], question))
            assert verdict["correct"] is True, (
                f"{question['question_id']} was not refused in the "
                f"{minutes}-minute version"
            )
            assert verdict["refused"] is True


def test_refusal_questions_target_entities_that_never_appear():
    master = fm.generate_stream(seed=20261015, minutes=20)
    questions = fm.build_questions(master)
    live = {record["entity"]["track_id"] for record in master}

    for question in questions:
        if question["answer_type"] != "refusal":
            continue
        assert question["target"]["track_id"] not in live
        assert question["gold"]["happened"] is False


# --------------------------------------------------------------------------
# Protocol shape and red lines
# --------------------------------------------------------------------------


def test_records_follow_the_field_memory_schema():
    master = fm.generate_stream(seed=20261015, minutes=2)
    assert master
    for record in master:
        assert record["schema"] == "field_memory/v1"
        assert set(record) == {
            "schema", "record_id", "session", "source", "entity", "event",
            "evidence", "salience", "ttl_ms",
        }
        assert record["event"]["type"] in fm.EVENT_TYPES
        non_verbal = record["evidence"]["non_verbal"]
        assert set(non_verbal) == set(fm.NON_VERBAL_CUES) | {"cue_id"}
        # Privacy red line: parameter fingerprints only, never audio samples.
        assert not any(
            key in non_verbal for key in ("pcm", "samples", "audio", "waveform")
        )


def test_generation_is_deterministic_for_a_fixed_seed():
    first = fm.generate_stream(seed=4242, minutes=5)
    second = fm.generate_stream(seed=4242, minutes=5)
    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)


def test_answer_types_are_grouped_not_pooled():
    payload = fm.run_evaluation(seed=20261015, minutes=20)
    counts = payload["answer_type_counts"]
    assert set(counts) == {"existence", "temporal", "binding", "refusal"}
    assert all(value > 0 for value in counts.values())

    for row in payload["length_decay"]:
        assert set(row["by_answer_type"]) == set(counts)
        for stats in row["by_answer_type"].values():
            assert 0 <= stats["correct"] <= stats["total"]
            assert 0.0 <= stats["accuracy"] <= 1.0


def test_minimal_pair_attribution_covers_every_cue():
    payload = fm.run_evaluation(seed=20261015, minutes=20)
    cues = [row["cue"] for row in payload["minimal_pair_attribution"]]
    assert sorted(cues) == sorted(fm.NON_VERBAL_CUES)
    for row in payload["minimal_pair_attribution"]:
        assert row["total"] > 0
        assert row["accuracy_drop"] >= 0.0


def test_jsonl_round_trip_is_lossless(tmp_path):
    master = fm.generate_stream(seed=20261015, minutes=2)
    path = tmp_path / "field_memory.jsonl"
    written = fm.write_jsonl(path, master)
    assert written == len(master)
    restored = fm.read_jsonl(path)
    assert json.dumps(restored, sort_keys=True) == json.dumps(master, sort_keys=True)
