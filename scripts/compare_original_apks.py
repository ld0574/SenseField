#!/usr/bin/env python3
"""Same reflection producer, exact original APKs, explicit development-only workloads."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess

from benchmark_match3_processing import honor_semantics, p95, validate_sequence
from verify_match3_regression import assert_emulator, instrumentation_summary

PACKAGE = "com.openkhub.sensefield"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files/original-apk-replay"
WORKLOADS = {"quality": "public-perception-quality-v1", "sequence": "public-perception-sequence-v1",
             "honor": "public-honor-sequence-v1"}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def semantics(data: dict):
    workload = data.get("workload")
    if workload == WORKLOADS["honor"]:
        return honor_semantics(data)
    if workload in (WORKLOADS["quality"], WORKLOADS["sequence"]):
        return data["outputs"]
    raise ValueError("Unknown explicit original-APK workload")


def check_reference(data: dict, reference: dict) -> None:
    for key in ("apk_sha256", "producer_sha256", "contract_sha256", "manifest_sha256", "frames", "workload"):
        if data[key] != reference[key]:
            raise ValueError("Frozen semantic reference binding differs: " + key)
    if semantics(data) != semantics(reference):
        raise ValueError("Actual retained production output differs from the frozen semantic reference")
    if data["workload"] == WORKLOADS["honor"]:
        for key in ("model_bin_sha256", "profile_version", "locator_enabled", "input_size", "inferences", "cache_reuses"):
            if data[key] != reference[key]:
                raise ValueError("Frozen detector workload differs: " + key)


def pair_result(before: dict, after: dict, references: dict) -> dict:
    # Functional differences must be reviewed before costs, never ignored by the gate.
    for mode, report in (("baseline", before), ("candidate", after)):
        check_reference(report, references[mode])
        if (report["profiling"] is not False or report["independent"] is not False
                or report["warmup_rounds"] != 6 or report["measured_rounds"] != 15
                or report["repeats_per_frame"] != 1):
            raise ValueError("Changed or profiled workload")
        expected = report["frames"] * 15
        if (not expected or report["repeats"] != expected or len(report["wall_ns"]) != expected
                or len(report["worker_cpu_ns"]) != expected or len(report["batches"]) != 15
                or len(report["process_cpu_batches_ms"]) != 15 or sum(report["process_cpu_batches_ms"]) <= 0
                or len(semantics(report)) != report["frames"]):
            raise ValueError("Incomplete original-APK measurement")
        if report["workload"] == WORKLOADS["honor"] and report["inferences"] + report["cache_reuses"] != expected:
            raise ValueError("Actual native work is incomplete")
    for key in ("manifest_sha256", "frames", "workload", "producer_sha256"):
        if before[key] != after[key]:
            raise ValueError("Paired inputs/producer/workload differ")
    out = {}
    for metric, key in (("wall", "wall_ns"), ("worker", "worker_cpu_ns")):
        b, a = p95(before[key]), p95(after[key])
        if b <= 0:
            raise ValueError("Baseline cost must be positive")
        out.update({f"baseline_{metric}_p95_ms": b, f"candidate_{metric}_p95_ms": a,
                    f"{metric}_p95_change_percent": 100 * (a / b - 1)})
    b, a = (sum(x["process_cpu_batches_ms"]) for x in (before, after))
    out.update(baseline_process_cpu_ms=b, candidate_process_cpu_ms=a, process_cpu_change_percent=100 * (a / b - 1))
    out["passed"] = all(out[k + "_p95_change_percent"] <= 10 for k in ("wall", "worker"))
    return out


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", type=Path, required=True)
    p.add_argument("--serial", default="emulator-5554")
    for key in ("baseline", "candidate", "producer", "restore-test", "inputs", "output"):
        p.add_argument("--" + key, type=Path, required=True)
    p.add_argument("--mode", choices=WORKLOADS, required=True)
    p.add_argument("--phase", choices=("reference", "cost"), required=True)
    p.add_argument("--baseline-reference", type=Path)
    p.add_argument("--candidate-reference", type=Path)
    a = p.parse_args()
    assert_emulator(a.serial)
    if a.phase == "cost" and (a.mode == "quality" or not a.baseline_reference or not a.candidate_reference):
        raise ValueError("Formal cost requires both pre-frozen semantic references and a sequence workload")
    out = a.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    packages = {k: {"path": str(getattr(a, k).resolve()), "sha256": sha(getattr(a, k))}
                for k in ("baseline", "candidate", "producer", "restore_test")}
    m = json.loads((a.inputs / "manifest.json").read_text())
    if m.get("frozen") is not True or m.get("independent") is not False:
        raise ValueError("Require frozen development inputs")
    if a.mode == "sequence":
        validate_sequence(m)
    names = [e["file"] for e in m["frames"]]
    if not names or len(set(names)) != len(names):
        raise ValueError("Require unique ordered frozen frames")
    for entry in m["frames"]:
        f = (a.inputs / entry["file"]).resolve()
        if not f.is_relative_to(a.inputs.resolve()) or sha(f) != entry["sha256"]:
            raise ValueError("Frozen frame bytes differ")
    references = {}
    if a.phase == "cost":
        for k in ("baseline", "candidate"):
            path = getattr(a, k + "_reference")
            references[k] = json.loads(path.read_text())
            if (references[k]["apk_sha256"] != packages[k]["sha256"]
                    or references[k]["producer_sha256"] != packages["producer"]["sha256"]
                    or references[k]["manifest_sha256"] != sha(a.inputs / "manifest.json")):
                raise ValueError("Reference must predate and bind this declared cost run")
    report = {"schema": "sensefield.original-apk-comparison/1", "scope": "original APKs; retained production stages on frozen developer pixels; no live service admission, UI/audio/capture or phone heat",
              "phase": a.phase, "mode": a.mode, "workload": WORKLOADS[a.mode], "kind": "functional",
              "orders": [["baseline", "candidate"], ["candidate", "baseline"], ["baseline", "candidate"]] if a.phase == "cost" else [["baseline", "candidate"]],
              "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "packages": packages,
              "manifest_sha256": sha(a.inputs / "manifest.json"), "steps": [], "pairs": [], "passed": False,
              "references": {k: {"path": str(getattr(a, k + "_reference").resolve()), "sha256": sha(getattr(a, k + "_reference"))} for k in references},
              "host": {"platform": platform.platform(), "load_average_start": os.getloadavg()}}
    prefix = [str(a.adb), "-s", a.serial]
    def save():
        (out / "receipt.json").write_text(json.dumps(report, indent=2) + "\n")
    def run(name, args):
        result = subprocess.run(prefix + args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
        (out / (name + ".txt")).write_text(result.stdout)
        report["steps"].append({"name": name, "exit_code": result.returncode, "log_sha256": hashlib.sha256(result.stdout.encode()).hexdigest()})
        save()
        if result.returncode:
            raise RuntimeError(name + " failed; preserve this receipt")
        return result.stdout
    def verify_installed(label, package, expected):
        paths = run(label + "-path", ["shell", "pm", "path", package]).strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise ValueError("Require one original APK")
        readback = out / (label + "-readback.apk")
        run(label + "-readback", ["pull", paths[0][8:], str(readback)])
        actual = sha(readback)
        if actual != expected:
            raise ValueError("Installed APK differs from the original bytes")
        readback.unlink()
    try:
        hardware = run("hardware", ["shell", "getprop", "ro.hardware"]).strip()
        if hardware not in ("ranchu", "goldfish"):
            raise ValueError("Require isolated emulator")
        run("fingerprint", ["shell", "getprop", "ro.build.fingerprint"])
        run("settings-before", ["shell", "find", f"/data/user/0/{PACKAGE}/shared_prefs", "-type", "f", "-exec", "sha256sum", "{}", "\\;"])
        run("producer-install", ["install", "-r", "-d", "-t", packages["producer"]["path"]])
        verify_installed("producer", PACKAGE + ".test", packages["producer"]["sha256"])
        for pair, order in enumerate(report["orders"], 1):
            data = {}
            for mode in order:
                name = f"pair-{pair}-{mode}" if a.phase == "cost" else mode
                print(name, flush=True)
                run(name + "-stop", ["shell", "am", "force-stop", PACKAGE])
                run(name + "-install", ["install", "-r", "-d", packages[mode]["path"]])
                verify_installed(name, PACKAGE, packages[mode]["sha256"])
                run(name + "-compile", ["shell", "cmd", "package", "compile", "-m", "speed", "-f", PACKAGE])
                run(name + "-compile-producer", ["shell", "cmd", "package", "compile", "-m", "speed", "-f", PACKAGE + ".test"])
                run(name + "-dexopt", ["shell", "dumpsys", "package", PACKAGE])
                run(name + "-clear-inputs", ["shell", "rm", "-rf", REMOTE])
                run(name + "-inputs", ["push", str(a.inputs.resolve()), REMOTE])
                text = run(name + "-measure", ["shell", "am", "instrument", "-w", "-r", "-e", "class", "com.openkhub.apkreplay.OriginalApkReplayTest",
                           "-e", "replayMode", a.mode, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"])
                if instrumentation_summary(text) != {"executed": 1, "skipped": 0}:
                    raise ValueError("Original APK workload did not execute completely")
                path = out / (name + ".json")
                run(name + "-report", ["pull", REMOTE + "/result.json", str(path)])
                r = json.loads(path.read_text())
                if (r["apk_sha256"] != packages[mode]["sha256"] or r["producer_sha256"] != packages["producer"]["sha256"]
                        or r["manifest_sha256"] != report["manifest_sha256"] or r["workload"] != WORKLOADS[a.mode]
                        or r["frames"] != len(names)):
                    raise ValueError("Production/producer/input output binding differs")
                if references:
                    check_reference(r, references[mode])
                data[mode] = r
            if a.phase == "cost":
                report["pairs"].append(pair_result(data["baseline"], data["candidate"], references));save()
        if a.phase == "cost":
            report.update(passed=len(report["pairs"]) == 3 and all(p["passed"] for p in report["pairs"]),
                          median_process_cpu_change_percent=statistics.median(p["process_cpu_change_percent"] for p in report["pairs"]))
        else:
            report["passed"] = True  # Producer execution, not accuracy or functional review approval.
    except Exception as error:
        report["error"] = str(error);save();raise
    finally:
        try:
            run("restore-stop", ["shell", "am", "force-stop", PACKAGE])
            run("restore-candidate", ["install", "-r", packages["candidate"]["path"]])
            run("restore-test", ["install", "-r", "-d", "-t", packages["restore_test"]["path"]])
            verify_installed("restored-candidate", PACKAGE, packages["candidate"]["sha256"])
            verify_installed("restored-test", PACKAGE + ".test", packages["restore_test"]["sha256"])
            run("settings-after", ["shell", "find", f"/data/user/0/{PACKAGE}/shared_prefs", "-type", "f", "-exec", "sha256sum", "{}", "\\;"])
            if (out / "settings-before.txt").read_text() != (out / "settings-after.txt").read_text():
                raise ValueError("Emulator settings changed during comparison")
            report["restored_original_candidate_and_standard_test"] = True
        except Exception as error:
            report["restoration_error"] = str(error);report["passed"] = False
        report["finished_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        report["host"]["load_average_end"] = os.getloadavg();save()
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
