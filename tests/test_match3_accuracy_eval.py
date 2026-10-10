import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "scripts/evaluate_match3_accuracy.py"
spec = importlib.util.spec_from_file_location("match3_accuracy", path)
scorer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scorer)

EXAMPLES = ROOT / "validation/match3/holdout/examples"


class LabelFreeTests(unittest.TestCase):
    def test_coverage_counts_unknown_surface_and_uncovered_faces_as_abstained(self):
        preds = {
            "a": {"kind": "ANIMAL", "swap_permission": "YES"},   # resolved
            "b": {"kind": "ANIMAL", "swap_permission": "NO"},    # resolved (identity known)
            "c": {"kind": "ANIMAL", "swap_permission": "UNKNOWN"},  # face only -> abstained
            "d": {"kind": "SURFACE"},                            # abstained
            "e": {"kind": "UNKNOWN"},                            # abstained
            "f": {"kind": "COIN"},                               # resolved
        }
        free = scorer.label_free(preds)
        self.assertEqual(6, free["total"])
        self.assertEqual(3, free["resolved"])
        self.assertAlmostEqual(0.5, free["coverage"])
        self.assertEqual(3, free["abstained"])


class LabeledTests(unittest.TestCase):
    def test_miss_misclassify_false_swap_and_swap_miss_are_scored_independently(self):
        report = scorer.evaluate(EXAMPLES / "predictions.json", EXAMPLES / "labels.json")
        free = report["label_free"]
        self.assertEqual(6, free["total"])
        self.assertEqual(5, free["resolved"])
        self.assertEqual({"ANIMAL": 4, "COIN": 1, "UNKNOWN": 1}, free["kind_histogram"])

        overall = report["labeled"]["overall"]
        self.assertEqual(6, overall["n"])
        self.assertEqual(3, overall["correct"])       # r0c0, r0c1, r0c5
        self.assertEqual(1, overall["miss"])           # r0c2 abstained on a known animal
        self.assertEqual(2, overall["misclassify"])    # r0c3 G->R, r0c4 cookie->animal
        self.assertEqual(1, overall["false_swap"])     # r0c4 cookie granted a swap
        self.assertEqual(2, overall["swap_miss"])      # r0c2 missed, r0c5 identity-only
        self.assertEqual(0, report["labeled"]["labels_without_prediction"])

        cookie = report["labeled"]["per_family"]["COOKIE"]
        self.assertEqual(1, cookie["false_swap"])
        self.assertEqual(1, cookie["misclassify"])

    def test_labels_without_a_prediction_are_reported_not_silently_dropped(self):
        with tempfile.TemporaryDirectory() as tmp:
            preds = Path(tmp) / "p.json"
            labels = Path(tmp) / "l.json"
            preds.write_text(json.dumps({"samples": [
                {"id": "x", "kind": "ANIMAL", "color": "R", "swap_permission": "YES"}]}), encoding="utf-8")
            labels.write_text(json.dumps({"samples": [
                {"id": "x", "family": "ANIMAL:R", "kind": "ANIMAL", "color": "R", "swappable": True},
                {"id": "y", "family": "ANIMAL:B", "kind": "ANIMAL", "color": "B", "swappable": True}]}), encoding="utf-8")
            report = scorer.evaluate(preds, labels)
            self.assertEqual(1, report["labeled"]["labels_without_prediction"])
            self.assertEqual(1, report["labeled"]["overall"]["correct"])


class GateTests(unittest.TestCase):
    def test_phase0_records_without_failing_by_default(self):
        self.assertEqual(0, scorer.main([
            "--predictions", str(EXAMPLES / "predictions.json"),
            "--labels", str(EXAMPLES / "labels.json")]))

    def test_optional_gates_fail_when_thresholds_are_exceeded(self):
        self.assertEqual(1, scorer.main([
            "--predictions", str(EXAMPLES / "predictions.json"),
            "--labels", str(EXAMPLES / "labels.json"),
            "--max-false-swap", "0"]))
        self.assertEqual(1, scorer.main([
            "--predictions", str(EXAMPLES / "predictions.json"),
            "--min-coverage", "0.99"]))


if __name__ == "__main__":
    unittest.main()
