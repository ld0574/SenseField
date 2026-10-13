#!/usr/bin/env python3
"""Opt-in emulator package pairs; fixed processing workload, not phone heat."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import subprocess

from verify_match3_regression import assert_emulator, instrumentation_summary

PACKAGE = "com.openkhub.sensefield"
REMOTE = "/sdcard/Android/data/" + PACKAGE + "/files/match3-holdout"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def p95(samples: list[int]) -> float:
    if not samples or any(type(x) is not int or x < 0 for x in samples):
        raise ValueError("Require nonempty nonnegative integer cost samples")
    return sorted(samples)[math.ceil(.95 * len(samples)) - 1] / 1e6


def pair_result(baseline: dict, candidate: dict) -> dict:
    for report in (baseline, candidate):
        if report.get("profiling") is not False:
            raise ValueError("Profiled or legacy measurements cannot enter this gate")
        if report.get("warmup_rounds") != 6 or report.get("measured_rounds") != 15:
            raise ValueError("Workload must retain six warmups and 15 measured batches")
        expected = report["frames"] * 3 * 15
        if (not expected or report["repeats"] != expected
                or len(report["wall_ns"]) != expected or len(report["worker_cpu_ns"]) != expected
                or len(report["process_cpu_batches_ms"]) != 15 or len(report["batches"]) != 15):
            raise ValueError("Incomplete processing workload")
        if sum(report["process_cpu_batches_ms"]) <= 0:
            raise ValueError("Require measured process CPU")
    if baseline["manifest_sha256"] != candidate["manifest_sha256"] or baseline["frames"] != candidate["frames"]:
        raise ValueError("Paired inputs differ")
    out = {}
    for metric, key in (("wall", "wall_ns"), ("worker", "worker_cpu_ns")):
        before, after = p95(baseline[key]), p95(candidate[key])
        if before <= 0:
            raise ValueError("Require positive baseline costs")
        out.update({f"baseline_{metric}_p95_ms": before, f"candidate_{metric}_p95_ms": after,
                    f"{metric}_p95_change_percent": 100 * (after / before - 1)})
    before, after = (sum(x["process_cpu_batches_ms"]) for x in (baseline, candidate))
    out.update(baseline_process_cpu_ms=before, candidate_process_cpu_ms=after,
               process_cpu_change_percent=100 * (after / before - 1))
    return out


def summarize(pairs: list[dict], kind: str) -> dict:
    if len(pairs) != 3 or kind not in ("control", "optimization", "functional"):
        raise ValueError("Declare exactly three pairs and their purpose before running")
    cpu = statistics.median(p["process_cpu_change_percent"] for p in pairs)
    timing = all(p[metric + "_p95_change_percent"] <= 10 for p in pairs for metric in ("wall", "worker"))
    cpu_ok = abs(cpu) <= 10 if kind == "control" else cpu <= -10 if kind == "optimization" else True
    return {"kind": kind, "pairs": pairs, "median_process_cpu_change_percent": cpu,
            "median_wall_p95_change_percent": statistics.median(p["wall_p95_change_percent"] for p in pairs),
            "timing_passed": timing, "cpu_passed": cpu_ok, "passed": timing and cpu_ok}


def read_receipt(path: Path) -> dict:
    receipt = json.loads(path.read_text())
    if receipt.get("variant") not in ("debug", "release") or not re.fullmatch(r"[0-9a-f]{64}", receipt.get("source_sha256", "")):
        raise ValueError("Require explicit source digest and package variant")
    for key in ("app", "test"):
        entry = receipt[key]
        entry["path"] = str((path.parent / entry["path"]).resolve())
        if sha(Path(entry["path"])) != entry["sha256"]:
            raise ValueError("Package bytes differ from frozen receipt")
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--kind", choices=("control", "optimization", "functional"), required=True)
    args = parser.parse_args()
    assert_emulator(args.serial)
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)  # Never replace failed receipts.
    receipts = {"baseline": read_receipt(args.baseline), "candidate": read_receipt(args.candidate)}
    if receipts["baseline"]["variant"] != receipts["candidate"]["variant"]:
        raise ValueError("Pair the same package variant")
    if receipts["baseline"]["test"]["sha256"] != receipts["candidate"]["test"]["sha256"]:
        raise ValueError("Require the same measurement producer APK")
    manifest_path = args.inputs / "manifest.json"
    manifest_sha = sha(manifest_path)
    entries = json.loads(manifest_path.read_text())["frames"]
    names = [f["file"] for f in entries]
    if not names or len(names) != len(set(names)):
        raise ValueError("Require unique explicit frozen frames")
    for entry in entries:
        frame = (args.inputs / entry["file"]).resolve()
        if not frame.is_relative_to(args.inputs.resolve()) or sha(frame) != entry["sha256"]:
            raise ValueError("Frozen input bytes differ")
    command_prefix = [str(args.adb), "-s", args.serial]
    report = {"scope": "installed_emulator_APK_fixed_input_processing; excludes_capture_audio_io_and_game_heat",
              "kind": args.kind, "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
              "packages": receipts, "manifest_sha256": manifest_sha, "steps": [], "pairs": [], "passed": False}

    def save():
        (out / "receipt.json").write_text(json.dumps(report, indent=2) + "\n")

    def run(name, arguments):
        result = subprocess.run(command_prefix + arguments, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=600)
        (out / (name + ".txt")).write_text(result.stdout)
        report["steps"].append({"name": name, "exit_code": result.returncode,
                                "log_sha256": hashlib.sha256(result.stdout.encode()).hexdigest()})
        save()
        if result.returncode:
            raise RuntimeError(name + " failed; retain the receipt")
        return result.stdout

    try:
        hardware = run("hardware", ["shell", "getprop", "ro.hardware"]).strip()
        if hardware not in ("ranchu", "goldfish"):
            raise ValueError("Require an isolated emulator")
        run("fingerprint", ["shell", "getprop", "ro.build.fingerprint"])
        for pair in range(1, 4):
            order = ["baseline", "candidate"] if pair % 2 else ["candidate", "baseline"]
            observations = {}
            for mode in order:
                name = f"pair-{pair}-{mode}"
                package = receipts[mode]
                print(name, flush=True)
                run(name + "-stop", ["shell", "am", "force-stop", PACKAGE])
                run(name + "-install", ["install", "-r", package["app"]["path"]])
                run(name + "-test-install", ["install", "-r", "-t", package["test"]["path"]])
                installed = run(name + "-path", ["shell", "pm", "path", PACKAGE]).strip().splitlines()
                if len(installed) != 1 or not installed[0].startswith("package:"):
                    raise ValueError("Require one installed APK")
                readback = out / "installed.apk"
                run(name + "-readback", ["pull", installed[0][8:], str(readback)])
                if sha(readback) != package["app"]["sha256"]:
                    raise ValueError("Installed APK digest differs")
                readback.unlink()
                # ART keeps debuggable packages at verify; record the actual
                # filter instead of assuming a successful command implies AOT.
                run(name + "-compile", ["shell", "cmd", "package", "compile", "-m", "speed", "-f", PACKAGE])
                run(name + "-compile-test", ["shell", "cmd", "package", "compile", "-m", "speed", "-f", PACKAGE + ".test"])
                dump = run(name + "-dexopt", ["shell", "dumpsys", "package", PACKAGE])
                filters = re.findall(r"\[status=([^\]]+)\]", dump)
                if filters != ["verify" if package["variant"] == "debug" else "speed"]:
                    raise ValueError("Unexpected compilation state: " + str(filters))
                run(name + "-clear", ["shell", "rm", "-rf", REMOTE])
                run(name + "-inputs", ["push", str(args.inputs.resolve()), REMOTE])
                text = run(name + "-measure", ["shell", "am", "instrument", "-w", "-r", "-e", "match3SourceSha256",
                    package["source_sha256"], "-e", "class", "com.openkhub.sensefield.Match3PerceptionCostInstrumentedTest",
                    PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"])
                if instrumentation_summary(text) != {"executed": 1, "skipped": 0}:
                    raise ValueError("Measurement did not execute completely")
                data_path = out / (name + ".json")
                run(name + "-report", ["pull", REMOTE + "/perception-cost.json", str(data_path)])
                data = json.loads(data_path.read_text())
                if data["manifest_sha256"] != manifest_sha or data["source_sha256"] != package["source_sha256"] or data["frames"] != len(entries):
                    raise ValueError("Measurement binding differs")
                observations[mode] = data
            result = pair_result(observations["baseline"], observations["candidate"])
            result.update(pair=pair, order=order)
            report["pairs"].append(result)
            save()
        report.update(summarize(report["pairs"], args.kind))
    except Exception as error:
        report["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        report["finished_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        save()
    print(json.dumps({k: v for k, v in report.items() if k not in ("steps", "packages")}, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
