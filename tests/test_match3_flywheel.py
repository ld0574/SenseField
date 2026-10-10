import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / (name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


flywheel = module("match3_flywheel")
labels = module("match3_apply_cell_labels")
score = module("evaluate_match3_accuracy")
ranking = module("evaluate_match3_ranking")


class SafetyTests(unittest.TestCase):
    def test_unknown_mechanic_does_not_grant_exchange_permission(self):
        truth = labels.record("R", "unknown", None)
        self.assertIsNone(truth["swappable"])
        self.assertEqual("unverified", truth["rule"])
        with self.assertRaises(ValueError):
            labels.record("R", "unknown", True)
        with self.assertRaises(ValueError):
            labels.record("honey", "ordinary", True)

    def test_unknown_truth_cannot_be_granted_ordinary_exchange_permission(self):
        report = score.labeled({"x": {"kind": "ANIMAL", "color": "R", "swap_permission": "YES"}},
                               {"x": {"kind": "UNKNOWN", "family": "UNKNOWN", "swappable": None}})
        self.assertEqual(1, report["overall"]["false_swap"])

    def test_ice_and_hud_errors_are_independent_of_piece_identity(self):
        report = score.labeled({"x": {"kind": "SURFACE", "ice": 0}},
                               {"x": {"kind": "SURFACE", "ice": 1, "swappable": False}})
        self.assertEqual(1, report["ice"]["misclassify"])
        goals = score.goal_accuracy([{"id": "x", "kind": "COIN", "remaining": 4}],
                                    [{"id": "x", "kind": "COIN", "remaining": 47}])
        self.assertEqual(1, goals["count_wrong"])

    def test_strict_gate_rejects_absent_truth_and_missing_predictions(self):
        with tempfile.TemporaryDirectory() as directory:
            p, t = Path(directory) / "p.json", Path(directory) / "t.json"
            p.write_text(json.dumps({"samples": [{"id": "x", "kind": "COIN"}]}))
            t.write_text(json.dumps({"samples": [{"id": "x", "kind": "COIN", "swappable": False},
                                                {"id": "y", "kind": "COIN", "swappable": False}]}))
            with self.assertRaises(SystemExit):
                score.main(["--predictions", str(p), "--max-false-swap", "0"])
            self.assertEqual(1, score.main(["--predictions", str(p), "--labels", str(t), "--strict"]))

    def test_strict_gate_rejects_revoked_truth_but_preserves_legacy_human_labels(self):
        with tempfile.TemporaryDirectory() as directory:
            p, t = Path(directory) / "p.json", Path(directory) / "t.json"
            predicted = {"samples": [{"id": "x", "kind": "COIN", "swap_permission": "NO"}],
                         "goal_samples": [{"id": "x:goal0", "kind": "COIN"}]}
            truth = {"samples": [{"id": "x", "kind": "COIN", "swappable": False}],
                     "goal_samples": [{"id": "x:goal0", "kind": "COIN"}]}
            p.write_text(json.dumps(predicted))
            t.write_text(json.dumps(truth))
            self.assertEqual(0, score.main(["--predictions", str(p), "--labels", str(t), "--strict"]))
            for section in ("samples", "goal_samples"):
                revoked = copy.deepcopy(truth)
                revoked[section][0]["reviewed"] = False
                t.write_text(json.dumps(revoked))
                self.assertEqual(1, score.main(["--predictions", str(p), "--labels", str(t), "--strict"]))


class RegistryTests(unittest.TestCase):
    def example(self, group="train-session", color=0xff123456):
        return flywheel.harvest({"samples": [{"id": "a.png:r0c0", "envelope": [color] * 256,
                                               "patch": [color] * 256}]},
                                {"session_id": group, "frames": [{"file": "a.png"}]}, {"families": []})

    def test_family_ids_and_old_reviews_survive_incremental_harvest(self):
        registry = self.example()
        family_id = registry["families"][0]["family"]
        registry["families"][0]["review"] = {"identity": "honey"}
        flywheel.harvest({"samples": [{"id": "b.png:r0c0", "envelope": [0xfffafafa] * 256}]},
                         {"session_id": "new", "frames": [{"file": "b.png"}]}, registry)
        old = next(f for f in registry["families"] if f["family"] == family_id)
        self.assertEqual("honey", old["review"]["identity"])

    def test_review_is_bound_to_exact_registry_revision(self):
        registry = self.example()
        with self.assertRaises(ValueError):
            flywheel.apply_review(registry, {"registry_sha256": "old"})

    def test_revoking_review_cannot_leave_old_exchange_permission_in_the_compiler(self):
        registry = self.example();family = registry["families"][0]
        family["review"] = {**labels.record("R", "ordinary", True), "identity": "R", "reviewed": True}
        flywheel.apply_review(registry, {"registry_sha256": flywheel.digest(registry),
                                       "reviewed": {family["family"]: False}})
        self.assertNotIn("review", family)
        with self.assertRaises(ValueError):
            flywheel.build_gallery(registry, {"groups": {"train-session": "train", "holdout": "test"}})

    def test_only_explicitly_reviewed_training_samples_enter_gallery(self):
        registry = self.example()
        family_id = registry["families"][0]["family"]
        review = {"registry_sha256": flywheel.digest(registry), "labels": {family_id: "honey"},
                  "archetypes": {family_id: "single"}, "swappable": {family_id: False},
                  "reviewed": {family_id: True}}
        flywheel.apply_review(registry, review)
        test = copy.deepcopy(registry["families"][0]["samples"][0])
        test["group"], test["source"] = "test-session", "test:x"
        registry["families"][0]["samples"].append(test)
        gallery = flywheel.build_gallery(registry, {"groups": {"train-session": "train", "test-session": "test"}})
        self.assertEqual(1, len(gallery["cells"]))
        self.assertEqual("honey", gallery["cells"][0]["kind"])
        self.assertEqual(1536, len(gallery["cells"][0]["pixels_rgb"]))
        self.assertNotIn("test:x", json.dumps(gallery))
        with self.assertRaises(ValueError):
            flywheel.build_gallery(registry, {"groups": {"train-session": "train"}})
        with self.assertRaises(ValueError):
            flywheel.build_gallery(registry, {"groups": {"train-session": "train", "test-session": "test"}}, max_bytes=10)

    def test_quadrant_cover_does_not_merge_with_plain_appearance(self):
        ordinary = [0xff123456] * 256
        covered = ordinary.copy()
        for y in range(8):
            for x in range(8):
                covered[y * 16 + x] = 0xffffffff
        self.assertGreater(flywheel.body_distance(covered, ordinary), .12)

    def test_compiler_covers_all_supported_roles_without_inventing_rules(self):
        registry = {"families": []}
        for i, (role, identity, arch, swap) in enumerate([
                ("cell", "O", "ordinary", True), ("cell", "coin", "single", False),
                ("cell", "snow", "single", False), ("cell", "iceflower", "multistage", False),
                ("cell", "honey", "single", False), ("cell", "egg", "spawner", False),
                ("cell", "ice", "cover", False), ("object", "cookie", "large", False),
                ("goal", "iceflower", "multistage", False)]):
            truth = labels.record(identity, arch, swap)
            registry["families"].append({"family": str(i), "role": role,
                "review": {**truth, "identity": identity, "reviewed": True},
                "samples": [{"group": "train", "source": str(i), "pixels": [0x123456] * 256, "patch": [0x654321] * 256}]})
        gallery = flywheel.build_gallery(registry, {"groups": {"train": "train", "holdout": "test"}})
        self.assertEqual({"animal_O", "coin", "snow1", "iceflower", "honey", "egg"}, {x["kind"] for x in gallery["cells"]})
        self.assertEqual(1, len(gallery["ice_surfaces"]))
        self.assertEqual("identity_only_2x2", gallery["large_objects"][0]["rule"])
        self.assertEqual("1" * 128 + "0" * 128, gallery["goals"][0]["mask_bits"])

    def test_source_reuse_cannot_silently_change_pixels(self):
        registry = self.example()
        with self.assertRaises(ValueError):
            flywheel.harvest({"samples": [{"id": "a.png:r0c0", "envelope": [0xffffff] * 256}]},
                             {"session_id": "train-session", "frames": [{"file": "a.png"}]}, registry)

    def test_mixed_goal_split_invalidates_both_reviews_and_masks_counters(self):
        first, second = [0x123456] * 256, [0x123456] * 128 + [0xffffff] * 128
        registry = flywheel.harvest({"goal_cards": {"a": [{"pixels": first}, {"pixels": second}]}},
                                   {"session_id": "s", "frames": [{"file": "a"}]}, {"families": []})
        family = registry["families"][0]
        self.assertEqual(1, len(registry["families"]))
        family["review"] = {"reviewed": True}
        flywheel.split_family(registry, family["family"], {family["samples"][1]["source"]})
        self.assertEqual(2, len(registry["families"]))
        self.assertTrue(all("review" not in f and f["anchor"][128:] == [0x1e2a58] * 128 for f in registry["families"]))

    def test_export_only_requires_review_for_requested_partition_and_rejects_goal_collision(self):
        registry = self.example()
        exported = flywheel.export_labels(registry, {"groups": {"train-session": "train"}}, "test")
        self.assertEqual([], exported["samples"])
        goal = {"family": "g", "role": "goal", "review": {**labels.record("coin", "single", False),
                "identity": "coin", "reviewed": True}, "samples": [{"id": "a:goal0", "group": "a", "source": "a"},
                                                                         {"id": "a:goal0", "group": "b", "source": "b"}]}
        with self.assertRaises(ValueError):
            flywheel.export_labels({"families": [goal]}, {"groups": {"a": "test", "b": "test"}}, "test")


class RankingTests(unittest.TestCase):
    def test_frozen_replay_must_match_reviewed_manifest_and_frame_bytes(self):
        p = {"rankings": [{"id": "a", "candidates": [[0, 0, 0, 1]]}], "independent": True, "frozen": True,
             "manifest_sha256": "a" * 64, "frame_sha256": {"a": "b" * 64}}
        t = {"boards": [{"id": "a", "reviewed": True, "relevant_swaps": [[0, 0, 0, 1]]}],
             "independent": True, "manifest_sha256": "a" * 64, "frame_sha256": {"a": "b" * 64}}
        self.assertEqual(1, ranking.evaluate(p, t, True)["top1_task_relevance"])
        p["frame_sha256"]["a"] = "c" * 64
        with self.assertRaises(ValueError):
            ranking.evaluate(p, t, True)
        p["manifest_sha256"] = "d" * 64
        with self.assertRaises(ValueError):
            ranking.evaluate(p, t)

    def test_empty_opportunity_does_not_improve_relevance_and_conflicts_fail(self):
        p = {"rankings": [{"id": "a", "candidates": []}]}
        t = {"boards": [{"id": "a", "reviewed": True, "relevant_swaps": [], "no_relevant_move": True}]}
        self.assertIsNone(ranking.evaluate(p, t)["top1_task_relevance"])
        t["boards"][0]["relevant_swaps"] = [[0, 0, 0, 1]]
        with self.assertRaises(ValueError):
            ranking.evaluate(p, t)
    def test_top_three_includes_missed_top_one_and_abstention_stays_in_denominator(self):
        p = {"rankings": [{"id": "a", "candidates": [[0, 0, 0, 1], [1, 0, 1, 1]]},
                           {"id": "b", "candidates": []}]}
        t = {"boards": [{"id": "a", "reviewed": True, "relevant_swaps": [[1, 1, 1, 0]]},
                        {"id": "b", "reviewed": True, "relevant_swaps": [[0, 0, 0, 1]]}]}
        result = ranking.evaluate(p, t)
        self.assertEqual(0, result["top1_task_relevance"])
        self.assertEqual(.5, result["top3_task_relevance"])
        self.assertEqual(1, result["abstained"])
        with self.assertRaises(ValueError):
            ranking.evaluate(p, t, True)


if __name__ == "__main__":
    unittest.main()
