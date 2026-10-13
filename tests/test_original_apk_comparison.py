import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))
from compare_original_apks import check_reference, pair_result


def report(apk="a"):
    return {"apk_sha256": apk * 64, "producer_sha256": "b" * 64, "contract_sha256": "c" * 64,
            "manifest_sha256": "d" * 64, "workload": "public-perception-sequence-v1", "frames": 1,
            "outputs": [{"position": {"kind": "ANIMAL"}, "ranked_moves": [{"from_col": 1}]}],
            "profiling": False, "independent": False, "warmup_rounds": 6, "measured_rounds": 15,
            "repeats_per_frame": 1, "repeats": 15, "wall_ns": [1000000] * 15,
            "worker_cpu_ns": [1000000] * 15, "batches": [{}] * 15, "process_cpu_batches_ms": [10] * 15}


class OriginalApkGateTests(unittest.TestCase):
    def test_reviewed_difference_requires_each_own_frozen_reference(self):
        before, after = report(), report("e")
        after["outputs"][0]["position"]["kind"] = "SURFACE"
        refs = {"baseline": copy.deepcopy(before), "candidate": copy.deepcopy(after)}
        self.assertTrue(pair_result(before, after, refs)["passed"])
        after["outputs"][0]["ranked_moves"][0]["from_col"] = 2
        with self.assertRaisesRegex(ValueError, "output differs"):
            pair_result(before, after, refs)

    def test_contract_producer_and_original_byte_changes_are_rejected(self):
        for key in ("contract_sha256", "apk_sha256", "producer_sha256", "manifest_sha256"):
            after = report();after[key] = "f" * 64
            with self.subTest(key=key), self.assertRaises(ValueError):
                check_reference(after, report())

    def test_no_relaxation_of_timing_or_sample_count(self):
        before, after = report(), report("e");refs = {"baseline": copy.deepcopy(before), "candidate": copy.deepcopy(after)}
        after["wall_ns"] = [1100100] * 15
        self.assertFalse(pair_result(before, after, refs)["passed"])
        after["wall_ns"] = [1000000] * 14
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            pair_result(before, after, refs)


if __name__ == "__main__":
    unittest.main()
