import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from benchmark_match3_processing import pair_result, p95, read_receipt, sha, summarize


def measurement():
    return {"profiling": False, "warmup_rounds": 6, "measured_rounds": 15,
            "frames": 1, "repeats": 45, "wall_ns": [1000000] * 45,
            "worker_cpu_ns": [1000000] * 45, "process_cpu_batches_ms": [100] * 15,
            "batches": [{}] * 15, "manifest_sha256": "a" * 64}


class ProcessingBenchmarkTest(unittest.TestCase):
    def test_nearest_rank_and_invalid_samples(self):
        self.assertEqual(p95(list(range(1, 101))), 95 / 1e6)
        for samples in ([], [-1], [False], [1.0]):
            with self.subTest(samples=samples), self.assertRaises(ValueError):
                p95(samples)

    def test_optimization_needs_cpu_reduction_and_every_pair_p95(self):
        before, after = measurement(), measurement()
        after["process_cpu_batches_ms"] = [85] * 15
        pairs = [pair_result(before, after) for _ in range(3)]
        self.assertTrue(summarize(pairs, "optimization")["passed"])
        pairs[2]["wall_p95_change_percent"] = 10.01
        self.assertFalse(summarize(pairs, "optimization")["passed"])
        pairs[2]["wall_p95_change_percent"] = 0
        for pair in pairs:
            pair["process_cpu_change_percent"] = -9.99
        self.assertFalse(summarize(pairs, "optimization")["passed"])
        self.assertTrue(summarize(pairs, "functional")["passed"])

    def test_control_and_incomplete_pairs_cannot_claim_optimization(self):
        pairs = [pair_result(measurement(), measurement()) for _ in range(3)]
        self.assertTrue(summarize(pairs, "control")["passed"])
        self.assertFalse(summarize(pairs, "optimization")["passed"])
        with self.assertRaises(ValueError):
            summarize(pairs[:2], "optimization")

    def test_profiled_incomplete_or_different_workload_is_rejected(self):
        for key, value in (("profiling", True), ("profiling", None), ("warmup_rounds", 7),
                           ("repeats", 44), ("wall_ns", [1]), ("batches", []),
                           ("process_cpu_batches_ms", [0] * 15), ("manifest_sha256", "b" * 64)):
            after = copy.deepcopy(measurement());after[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                pair_result(measurement(), after)

    def test_mutated_apk_cannot_reuse_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary);apk = root / "app.apk";apk.write_bytes(b"frozen")
            entry = {"path": "app.apk", "sha256": sha(apk)}
            receipt = root / "receipt.json"
            receipt.write_text(json.dumps({"variant": "debug", "source_sha256": "a" * 64,
                                           "app": entry, "test": entry}))
            self.assertEqual(read_receipt(receipt)["app"]["path"], str(apk.resolve()))
            apk.write_bytes(b"changed")
            with self.assertRaises(ValueError):
                read_receipt(receipt)


if __name__ == "__main__":
    unittest.main()
