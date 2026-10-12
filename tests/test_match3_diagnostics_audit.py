from __future__ import annotations

import json
from pathlib import Path
import zipfile

import pytest

from match3.diagnostics_audit import analyze, goals, load_source, main, sha, summarize


SESSION = "test-session"
GOALS = "-1:8|0:SNOW:1:false::CLEAR:reviewed_hud_rule"
KEY = "SWAP:0,0:0,1:ordinary_uncovered_animal@1"


def event(kind, at, data, session=SESSION):
    return {"type": kind, "session_id": session, "at_ms": 1000 + at, "elapsed_ms": at, "data": data}


def audit(at, text):
    return event("audit", at, {"message": text})


def candidate(action="SWAP", units=0, contacts=0):
    return {"action_type": action, "from_row": 0, "from_col": 0, "to_row": 0, "to_col": 1,
            "rule_id": "combo_bird_color" if action == "BIRD_COLOR" else "ordinary_uncovered_animal", "rule_version": 1,
            "direct_units": units, "physical_lower_bounds": {"SNOW": units}, "contacts": {"SNOW": contacts},
            "unknown_reasons": ["gravity_topology_unobserved"]}


def ranking(at=0, key=GOALS, candidates=None):
    return event("Match3Ranking", at, {"board_revision": 1, "goal_key": key, "admitted_moves": 2,
                "top_candidates": candidates if candidates is not None else [candidate()], "omitted_action_reasons": []})


def hint(at=10, revision=1, key=KEY, target=GOALS):
    return audit(at, f"Match3Hint revision={revision} action={key} goal_key={target}")


def outcome(at=1000, key=KEY, target=GOALS, evidence="not_observed", reason="progress_unattributed", spent=1):
    return event("Match3GoalOutcome", at, {"hint_revision": 1, "action_key": key, "goal_modes": target,
                 "action_evidence": evidence, "outcome": reason, "steps_spent": spent,
                 "goal_deltas": {"SNOW": 1}, "expected_lower_bounds": {"SNOW": 1}})


def export(root, rows, *, overrides=None, images=None):
    root.mkdir(parents=True, exist_ok=True)
    (root / "events.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
    (root / "metadata.json").write_text(json.dumps({"session_id": SESSION, "started_elapsed_ms": 1000,
                                                 "version_name": "0.4.6", "version_code": 23,
                                                 "manufacturer": "PRIVATE_DEVICE_SENTINEL"}))
    duration = max(r.get("elapsed_ms", 0) for r in rows)
    summary = {"session_id": SESSION, "duration_ms": duration, "ended_at_ms": 1000 + duration,
               "end_observed": True, "interrupted": False, "dropped_events": 0,
               "frame_count": sum(r["type"] == "frame" for r in rows), "image_count": sum(r["type"] == "image" for r in rows)}
    summary.update(overrides or {})
    (root / "summary.json").write_text(json.dumps(summary))
    for name, content in (images or {}).items():
        path = root / name
        path.parent.mkdir(exist_ok=True)
        path.write_bytes(content)
    return root


def single(root):
    return analyze([root])[0]["recordings"][0]


def test_last_goal_and_better_logged_special_are_review_findings_not_player_benefit(tmp_path):
    source = export(tmp_path, [ranking(candidates=[candidate(), candidate("BIRD_COLOR", units=1, contacts=1)]), hint()])
    result = single(source)
    flags = result["rankings"][0]["flags"]
    assert "low_remaining_top1_without_logged_task_contact" in flags
    assert "special_alternative_higher_certain_units_review" in flags
    assert "special_alternative_more_task_contacts_review" in flags
    assert result["hints"][0]["ranking_line"] == 1
    assert result["player_adoption_rate"] is None and result["task_benefit_rate"] is None


def test_cookie_contacts_do_not_become_certain_task_units(tmp_path):
    key = "-1:8|0:COOKIE:1:false::UNKNOWN:icon_identity_only"
    c = candidate()
    c.update(contacts={"COOKIE": 2}, physical_lower_bounds={"COOKIE": 0}, cookie_contact_cells=2)
    result = single(export(tmp_path, [ranking(key=key, candidates=[c]), hint(target=key)]))
    row = result["rankings"][0]
    assert row["logged_candidates"][0]["direct_units"] == 0
    assert "goal_event_mode_unknown" in row["flags"]
    assert "low_remaining_top1_without_logged_task_contact" not in row["flags"]


def test_review_episodes_merge_only_adjacent_matching_snapshots_within_one_geometry(tmp_path):
    changed = GOALS.replace(":8|", ":7|")
    rows = [ranking(0), ranking(800), audit(900, "Match3GeometryInvalidated reason=GEOMETRY_CHANGED"),
            ranking(1000), ranking(1800, key=changed), ranking(5000, key=changed)]
    episodes = single(export(tmp_path, rows))["low_remaining_review_episodes"]
    assert [e["ranking_lines"] for e in episodes] == [[1, 2], [4], [5], [6]]
    assert episodes[0]["last_elapsed_ms"] == 800


def test_dynamic_damage_reasons_are_derived_from_finite_rules_and_missing_lists_remain_unknown(tmp_path):
    c = candidate()
    c["unknown_reasons"] = ["unknown_damage_or_trigger_unknown", "magic_bird_damage_or_trigger_unknown", "private_damage_or_trigger_unknown"]
    source = single(export(tmp_path, [ranking(candidates=[c])]))
    reasons = source["rankings"][0]["logged_candidates"][0]["unknown_reasons"]
    assert reasons == ["magic_bird_damage_or_trigger_unknown", "unknown_damage_or_trigger_unknown", "unrecognized"]
    c["unknown_reasons"] = None
    source = single(export(tmp_path, [ranking(candidates=[c])]))
    assert not source["rankings"][0]["logged_candidates"][0]["unknown_reasons_recorded"]


@pytest.mark.parametrize("key", ["unread", "-1:8|0:SNOW:-1:false::CLEAR:reviewed_hud_rule", GOALS + "|1:UNKNOWN:9:false:unseen:UNKNOWN:icon_identity_only"])
def test_unknown_goals_or_counts_cannot_establish_the_last_task(tmp_path, key):
    row = single(export(tmp_path, [ranking(key=key)]))["rankings"][0]
    assert not row["low_remaining_single_task"]


@pytest.mark.parametrize("changed", [GOALS + "|0:SNOW:1:false::CLEAR:reviewed_hud_rule", GOALS + "|1:SNOW:2:false::CLEAR:reviewed_hud_rule", "-1:8|0:SNOW:1:true::CLEAR:reviewed_hud_rule", "-1:8|0:SNOW:1:false::BOGUS:source"])
def test_conflicting_targets_modes_and_duplicate_slots_are_not_merged(changed):
    assert goals(changed) is None


@pytest.mark.parametrize("rows", [
    [ranking(), hint(at=2501)],
    [ranking(), audit(5, "Match3GeometryInvalidated reason=GEOMETRY_CHANGED"), hint()],
    [ranking(), hint(target="-1:7|0:SNOW:1:false::CLEAR:reviewed_hud_rule")],
    [ranking(), hint(key="SWAP:0,0:1,0:ordinary_uncovered_animal@1")],
])
def test_stale_different_goal_or_different_action_rankings_are_not_borrowed(tmp_path, rows):
    result = single(export(tmp_path, rows))
    assert result["hints"][0]["ranking_line"] is None


@pytest.mark.parametrize("change", [{"key": "BIRD_COLOR:0,0:0,1:combo_bird_color@1"}, {"target": "-1:7|0:SNOW:1:false::CLEAR:reviewed_hud_rule"}, {"at": 13000}])
def test_outcomes_need_matching_action_baseline_and_time(tmp_path, change):
    row = single(export(tmp_path, [ranking(), hint(), outcome(**change)]))["goal_outcomes"][0]
    assert not row["hint_linked_by_revision_action_goals_and_time"]
    assert row["player_followed_hint"] is None


@pytest.mark.parametrize("evidence,reason,spent", [("not_observed", "progress_unattributed", 1), ("exact_visual_exchange_only", "visual_exchange_progress_observed", 1), ("not_observed", "multiple_actions_unattributed", 2), ("not_observed", "counter_reset_or_added_steps", -1)])
def test_counter_progress_visual_exchange_multi_step_and_reset_keep_their_evidence(tmp_path, evidence, reason, spent):
    result = single(export(tmp_path, [ranking(), hint(), outcome(evidence=evidence, reason=reason, spent=spent)]))
    row = result["goal_outcomes"][0]
    assert row["hint_linked_by_revision_action_goals_and_time"]
    assert row["action_evidence"] == evidence and row["outcome"] == reason and row["steps_spent"] == spent
    assert row["player_followed_hint"] is None


def test_callbacks_are_linked_by_exact_cue_and_do_not_prove_sound(tmp_path):
    cue = SESSION + ":hint:1:1010"
    rows = [ranking(), hint(), event("CueDispatch", 20, {"event_key": "m3live:hint:1", "cue_id": cue, "outcome": "ACCEPTED", "accepted_channels": 2}),
            event("CuePlayback", 50, {"event_key": "m3live:hint:1", "cue_id": cue, "channel": "SPEECH", "result": "STARTED"}),
            audit(100, "Match3HintCleared revision=1 reason=BOARD_MOTION"),
            event("CuePlayback", 110, {"event_key": "m3live:hint:1", "cue_id": cue, "channel": "SPEECH", "result": "CANCELLED_BOARD_MOTION"})]
    result = single(export(tmp_path, rows))
    assert result["totals"]["speech_callbacks"] == {"CANCELLED_BOARD_MOTION": 1, "STARTED": 1}
    assert result["player_adoption_rate"] is None


def test_orphan_or_other_session_cues_are_not_assigned_by_proximity(tmp_path):
    rows = [ranking(), hint(), event("CueDispatch", 20, {"event_key": "m3live:hint:1", "cue_id": "other-session:hint:1:1010", "outcome": "ACCEPTED"}),
            event("CuePlayback", 50, {"event_key": "m3live:hint:1", "cue_id": "other-session:hint:1:1010", "channel": "SPEECH", "result": "STARTED"})]
    result = single(export(tmp_path, rows))
    assert result["totals"]["speech_callbacks"] == {}
    assert result["totals"]["unlinked"] == {"dispatch": 1, "playback": 1}


def test_async_callback_written_one_ms_after_newer_context_keeps_exact_cue_link(tmp_path):
    cue = SESSION + ":hint:1:1010"
    rows = [ranking(), hint(), event("CueDispatch", 20, {"event_key": "m3live:hint:1", "cue_id": cue, "outcome": "ACCEPTED"}),
            event("Match3HintImageContext", 51, {"hint_revision": 1, "window_id": 0, "status": "RATE_LIMITED"}),
            event("CuePlayback", 50, {"event_key": "m3live:hint:1", "cue_id": cue, "channel": "SPEECH", "result": "STARTED"})]
    result = single(export(tmp_path, rows))
    assert result["totals"]["speech_callbacks"] == {"STARTED": 1}


def test_callback_clock_before_dispatch_cannot_be_attached_to_that_dispatch(tmp_path):
    cue = SESSION + ":hint:1:1010"
    rows = [ranking(), hint(), event("CueDispatch", 50, {"event_key": "m3live:hint:1", "cue_id": cue, "outcome": "ACCEPTED"}),
            event("CuePlayback", 20, {"event_key": "m3live:hint:1", "cue_id": cue, "channel": "SPEECH", "result": "STARTED"})]
    assert single(export(tmp_path, rows))["totals"]["unlinked"]["playback"] == 1


def gate_rows(frames=True):
    rows = [audit(0, "Match3RecommendationGate reason=no_swap")]
    if frames:
        rows += [event("frame", at, {"frame_index": i}) for i, at in enumerate(range(0, 12001, 800), 1)]
    rows += [audit(12000, "Match3RecommendationGate reason=ready")]
    return rows


def test_long_non_ready_gate_has_frozen_frame_support_and_does_not_prove_silence(tmp_path):
    row = single(export(tmp_path, gate_rows()))["long_non_ready_gates"][0]
    assert row["duration_ms"] == 12000 and row["sampled_frames"] == 16
    assert row["coverage"] == "sampled_frames_and_gate_log"
    assert row["audible_silence_proven"] is False


@pytest.mark.parametrize("override", [{"dropped_events": 1}, {"frame_count": 999}, {"interrupted": True}, {"end_observed": False}])
def test_drops_truncation_or_unobserved_end_prevent_complete_gate_claims(tmp_path, override):
    result = single(export(tmp_path, gate_rows(), overrides=override))
    assert not result["coverage"]["normal_end_and_no_logged_drops"]
    assert result["long_non_ready_gates"][0]["coverage"] == "incomplete"


def test_missing_frames_do_not_establish_continuous_gate_coverage(tmp_path):
    row = single(export(tmp_path, gate_rows(False)))["long_non_ready_gates"][0]
    assert row["coverage"] == "incomplete"


def test_images_join_explicit_windows_and_freeze_bytes_not_nearest_screen(tmp_path):
    rows = [ranking(), hint(),
            {"type": "image", "file": "images/screen-1-1000.jpg", "observed_at_ms": 1000, "window_id": 1, "frame_index": 1},
            {"type": "image", "file": "images/screen-2-1020.jpg", "observed_at_ms": 1020, "window_id": 99, "frame_index": 2},
            event("Match3HintImageContext", 20, {"hint_revision": 1, "window_id": 1, "status": "ACCEPTED"})]
    result = single(export(tmp_path, rows, images={"images/screen-1-1000.jpg": b"first", "images/screen-2-1020.jpg": b"second"}))
    context = result["hints"][0]["image_windows"][0]
    assert len(context["images"]) == 1 and context["saved_before"] == 1 and context["saved_after"] == 0
    assert context["images"][0]["sha256"] is not None


def test_missing_images_remain_missing_without_becoming_successful_windows(tmp_path):
    rows = [ranking(), hint(), {"type": "image", "file": "images/screen-1-1000.jpg", "observed_at_ms": 1000, "window_id": 1},
            event("Match3HintImageContext", 20, {"hint_revision": 1, "window_id": 1, "status": "ACCEPTED"})]
    result = single(export(tmp_path, rows))
    context = result["hints"][0]["image_windows"][0]
    assert context["images"][0]["status"] == "missing" and context["saved_before"] == 0


def test_repeated_import_and_zip_copy_are_idempotent(tmp_path):
    root = export(tmp_path / "raw", [ranking(), hint()])
    archive = tmp_path / "copy.zip"
    with zipfile.ZipFile(archive, "w") as zip_:
        for path in root.iterdir():
            zip_.write(path, path.name)
    first, _ = analyze([root])
    duplicate, _ = analyze([root, archive, root])
    assert first == duplicate


def test_conflicting_copies_are_rejected_instead_of_double_counted(tmp_path):
    a = export(tmp_path / "first", [ranking(), hint()])
    b = export(tmp_path / "second", [ranking(), hint(at=11)])
    with pytest.raises(ValueError, match="Conflicting"):
        analyze([a, b])


@pytest.mark.parametrize("rows", [[ranking(), event("checkpoint", 10, {}, session="other-session")], [ranking(10), hint(5)]])
def test_cross_session_and_regressing_critical_clock_are_rejected(tmp_path, rows):
    with pytest.raises(ValueError):
        load_source(export(tmp_path, rows))


def test_elapsed_clock_reset_cannot_be_sorted_into_an_action(tmp_path):
    rows = [ranking(), hint()]
    rows[-1]["elapsed_ms"] = 0
    with pytest.raises(ValueError, match="clock"):
        load_source(export(tmp_path, rows))


def test_metadata_clock_cannot_override_the_event_clock(tmp_path):
    root = export(tmp_path, [ranking(), hint()])
    metadata = json.loads((root / "metadata.json").read_text())
    metadata["started_elapsed_ms"] = 2000
    (root / "metadata.json").write_text(json.dumps(metadata))
    with pytest.raises(ValueError, match="clocks disagree"):
        load_source(root)


def test_unsafe_zip_members_are_never_extracted_or_read(tmp_path):
    archive = tmp_path / "malicious.zip"
    with zipfile.ZipFile(archive, "w") as zip_:
        zip_.writestr("../secret", "secret")
        zip_.writestr("events.jsonl", json.dumps(ranking()))
    with pytest.raises(ValueError, match="Unsafe"):
        load_source(archive)


def test_public_report_excludes_private_identifiers_paths_and_untrusted_strings(tmp_path):
    c = candidate()
    c["unknown_reasons"].append("PRIVATE_TOKEN_SENTINEL")
    c["rule_id"] = "PRIVATE_RULE_SENTINEL"
    root = export(tmp_path / "PRIVATE_PATH_SENTINEL", [ranking(candidates=[c]), hint(), audit(11, "other arbitrary PRIVATE_AUDIT_SENTINEL")])
    public, private = analyze([root])
    text = json.dumps(public)
    for value in [SESSION, "PRIVATE_TOKEN_SENTINEL", "PRIVATE_DEVICE_SENTINEL", "PRIVATE_RULE_SENTINEL", "PRIVATE_AUDIT_SENTINEL", "PRIVATE_PATH_SENTINEL"]:
        assert value not in text
    assert private["recording-01"]["session_id"] == SESSION


def test_cli_creates_reproducible_report_and_separate_private_manifest(tmp_path):
    root = export(tmp_path / "source", [ranking(), hint()])
    out = tmp_path / "output"
    assert main([str(root), "--output", str(out)]) == 0
    initial = (out / "report.json").read_bytes()
    main([str(root), "--output", str(out)])
    assert initial == (out / "report.json").read_bytes()
    assert (out / "report.md").exists() and (out / "inputs.private.json").exists()
    public = json.loads((out / "summary.json").read_text())
    assert public["full_report_sha256"] == sha(initial)
    assert public["recordings"][0]["player_adoption_rate"] is None


def test_cli_cannot_overwrite_an_original_export_summary(tmp_path):
    root = export(tmp_path / "source", [ranking(), hint()])
    original = (root / "summary.json").read_bytes()
    with pytest.raises(SystemExit) as error:
        main([str(root), "--output", str(root)])
    assert error.value.code == 1
    assert (root / "summary.json").read_bytes() == original
    assert not (root / "report.json").exists()


def test_compact_summary_retains_counts_and_three_review_examples_without_inventing_a_rate(tmp_path):
    report, _ = analyze([export(tmp_path, [ranking(), hint(), outcome(evidence="exact_visual_exchange_only", reason="visual_exchange_progress_observed")])])
    summary = summarize(report)
    assert summary["recordings"][0]["logged_visual_exchange_with_single_step_progress"] == 1
    assert summary["recordings"][0]["first_review_episodes"][0]["ranking_lines"] == [1]
    assert summary["recordings"][0]["task_benefit_rate"] is None
