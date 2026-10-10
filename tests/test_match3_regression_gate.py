import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

path = Path(__file__).resolve().parents[1] / "scripts/verify_match3_regression.py"
spec = importlib.util.spec_from_file_location("match3_regression", path)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class Match3GateTests(unittest.TestCase):
    def test_adb_zero_exit_does_not_make_a_failed_or_crashed_suite_pass(self):
        for output in ("INSTRUMENTATION_CODE: 0", "OK (0 tests)", "FAILURES!!!\nTests run: 14, Failures: 1",
                       "INSTRUMENTATION_RESULT: shortMsg=Process crashed.", "OK (14 tests)\nINSTRUMENTATION_FAILED"):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                gate.instrumentation_summary(output)

    def test_a_complete_suite_and_skipped_fixture_are_recorded_separately(self):
        self.assertEqual({"executed": 14, "skipped": 0}, gate.instrumentation_summary("OK (14 tests)\nINSTRUMENTATION_CODE: -1"))
        self.assertEqual({"executed": 14, "skipped": 1}, gate.instrumentation_summary("INSTRUMENTATION_STATUS_CODE: -3\nOK (14 tests)"))

    def test_failed_assumptions_are_skipped_even_when_junit_prints_ok(self):
        output = "\n".join((
            "INSTRUMENTATION_STATUS: stack=org.junit.AssumptionViolatedException: Emulator only",
            "INSTRUMENTATION_STATUS_CODE: -4",
            "INSTRUMENTATION_STATUS_CODE: -3",
            "INSTRUMENTATION_STATUS_CODE: 0",
            "OK (3 tests)", "INSTRUMENTATION_CODE: -1"))
        self.assertEqual({"executed": 3, "skipped": 2}, gate.instrumentation_summary(output))

    def test_automatic_regression_cannot_install_or_launch_on_a_players_phone(self):
        for serial in ("10.10.10.16:39175", "adb-6d5e44f1-KVD61h._adb-tls-connect._tcp", "device-id"):
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                gate.assert_emulator(serial)
        gate.assert_emulator("emulator-5554")

    def test_release_gate_cannot_skip_hud_truth_or_use_unreviewed_labels(self):
        with tempfile.TemporaryDirectory() as directory:
            holdout=Path(directory)/"holdout";holdout.mkdir()
            (holdout/"manifest.json").write_text(json.dumps({"independent":True,"frozen":True,
                "gates":{"min_coverage":.9},"session_id":"independent-session","frames":[{"file":"a.png"}]}))
            cell={"id":"a.png:r0c0","kind":"COIN","swappable":False,"reviewed":True}
            cases=[({"samples":[cell]},"explicit human HUD truth"),
                   ({"samples":[{**cell,"reviewed":False}],"goal_samples":[]},"explicit human review"),
                   ({"samples":[cell],"goal_samples":[{"id":"a.png:goal0","kind":"COIN","reviewed":True}]},"remaining count and completion state")]
            for i,(truth,reason) in enumerate(cases):
                (holdout/"labels.json").write_text(json.dumps(truth));output=Path(directory)/str(i)
                self.assertEqual(1,gate.main(["--mode","full","--holdout",str(holdout),"--output",str(output)]))
                report=json.loads((output/"report.json").read_text())
                self.assertFalse(report["passed"]);self.assertIn(reason,report["error"])
                self.assertEqual([],report["steps"])


if __name__ == "__main__":
    unittest.main()
