import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from benchmark_match3_processing import pair_result, p95, read_receipt, sha, summarize, validate_sequence, honor_semantics


def measurement():
    return {"profiling": False, "warmup_rounds": 6, "measured_rounds": 15,
            "frames": 1, "repeats": 45, "wall_ns": [1000000] * 45,
            "worker_cpu_ns": [1000000] * 45, "process_cpu_batches_ms": [100] * 15,
            "batches": [{}] * 15, "manifest_sha256": "a" * 64}


class ProcessingBenchmarkTest(unittest.TestCase):
    def test_detector_parity_preserves_events_and_requires_explained_freshness(self):
        packet=[0]*40;packet[:4]=[0x4e465231,2,24,16];packet[15]=1
        packet[24+13]=1000;packet[24+10]=80;packet[8]=80000
        first={"observations":[{"file":"a.png","observed_at_ms":1000,"engine_age_ms":80,"native_packet":packet}]}
        second=copy.deepcopy(first);second["observations"][0]["engine_age_ms"]=100
        second["observations"][0]["native_packet"][8]=100000
        second["observations"][0]["native_packet"][34]=100
        self.assertEqual(honor_semantics(first),honor_semantics(second))
        second["observations"][0]["native_packet"][17]=1
        self.assertNotEqual(honor_semantics(first),honor_semantics(second))
        second["observations"][0]["native_packet"][34]=1
        with self.assertRaises(ValueError): honor_semantics(second)

    def test_sequence_preserves_time_gaps_and_rejects_interleaved_windows(self):
        manifest = {"schema": "sensefield.match3-sequence/1", "workload": "sequence-v1",
                    "frozen": True, "independent": False, "warmup_rounds": 6,
                    "measured_rounds": 15, "repeats_per_frame": 1, "frames": [
                        {"sequence": "a", "reset_before": True, "observed_at_ms": 1000,
                         "frame_index": 1, "delta_ms": None, "missing_logged_frames_before": None},
                        {"sequence": "a", "reset_before": False, "observed_at_ms": 10000,
                         "frame_index": 12, "delta_ms": 9000, "missing_logged_frames_before": 10}]}
        validate_sequence(manifest)
        for key, value in (("reset_before", True), ("observed_at_ms", 1000), ("frame_index", 1),
                           ("delta_ms", 800), ("missing_logged_frames_before", 0)):
            bad=copy.deepcopy(manifest);bad["frames"][1][key]=value
            with self.subTest(key=key), self.assertRaises(ValueError): validate_sequence(bad)
        bad=copy.deepcopy(manifest);bad["frames"] += [dict(manifest["frames"][0], sequence="b"), manifest["frames"][0]]
        with self.assertRaises(ValueError): validate_sequence(bad)

    def test_sequence_is_a_distinct_workload_and_requires_exact_semantics(self):
        sequence=measurement();sequence.update(workload="sequence-v1", repeats_per_frame=1,
                repeats=15, wall_ns=[1000000]*15, worker_cpu_ns=[1000000]*15,
                outputs=[{"file": "a.jpg", "observed_at_ms": 1000, "gate": "ready", "new_hint_probes": [[.1,.2]]}])
        self.assertEqual(pair_result(sequence, sequence)["process_cpu_change_percent"], 0)
        changed=copy.deepcopy(sequence);changed["outputs"][0]["gate"]="no_swap"
        with self.assertRaises(ValueError): pair_result(sequence, changed)
        changed=copy.deepcopy(sequence);changed["repeats_per_frame"]=3
        with self.assertRaises(ValueError): pair_result(sequence, changed)
        with self.assertRaises(ValueError): pair_result(measurement(), sequence)

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
