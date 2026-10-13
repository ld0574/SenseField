#!/usr/bin/env python3
"""Opt-in emulator package pairs; declared processing workload, not phone heat."""
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
        workload = report.get("workload", "fixed-repeat-v1")
        if workload not in ("fixed-repeat-v1", "sequence-v1", "honor-sequence-v2"):
            raise ValueError("Unknown workload")
        repeats = 3 if workload == "fixed-repeat-v1" else 1
        if workload == "sequence-v1" and (report.get("repeats_per_frame") != 1
                or len(report.get("outputs", [])) != report["frames"]):
            raise ValueError("Incomplete sequence or changed frame repetition")
        if workload == "honor-sequence-v2" and (len(report.get("observations", [])) != report["frames"]
                or report.get("inferences", -1) + report.get("cache_reuses", -1) != report["frames"] * 15):
            raise ValueError("Incomplete inference workload")
        expected = report["frames"] * repeats * 15
        if (not expected or report["repeats"] != expected
                or len(report["wall_ns"]) != expected or len(report["worker_cpu_ns"]) != expected
                or len(report["process_cpu_batches_ms"]) != 15 or len(report["batches"]) != 15):
            raise ValueError("Incomplete processing workload")
        if sum(report["process_cpu_batches_ms"]) <= 0:
            raise ValueError("Require measured process CPU")
    if baseline["manifest_sha256"] != candidate["manifest_sha256"] or baseline["frames"] != candidate["frames"]:
        raise ValueError("Paired inputs differ")
    if baseline.get("workload", "fixed-repeat-v1") != candidate.get("workload", "fixed-repeat-v1"):
        raise ValueError("Cannot compare fixed repeated images with a sequence")
    if baseline.get("workload") == "sequence-v1" and baseline["outputs"] != candidate["outputs"]:
        raise ValueError("Sequence semantic or gate/coordinate/timing outputs changed")
    if baseline.get("workload") == "honor-sequence-v2":
        for key in ("model_bin_sha256", "profile_version", "locator_enabled", "input_size", "inferences", "cache_reuses"):
            if baseline[key] != candidate[key]:
                raise ValueError("Detector configuration or actual work differs")
        if honor_semantics(baseline) != honor_semantics(candidate):
            raise ValueError("Detector, tracking or event output changed")
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
    parser.add_argument("--workload", choices=("fixed-repeat-v1", "sequence-v1", "honor-sequence-v2"), default="fixed-repeat-v1")
    parser.add_argument("--allow-emulator-downgrade", action="store_true",
                        help="Allow an emulator-only reinstall of a lower versionCode during paired measurements")
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
    manifest = json.loads(manifest_path.read_text())
    if args.workload == "sequence-v1":
        validate_sequence(manifest)
    elif "workload" in manifest:
        raise ValueError("Sequence inputs require an explicit sequence workload")
    entries = manifest["frames"]
    directory, producer, output_name = {
        "fixed-repeat-v1": ("match3-holdout", "Match3PerceptionCostInstrumentedTest", "perception-cost.json"),
        "sequence-v1": ("match3-sequence", "Match3SequenceCostInstrumentedTest", "perception-cost.json"),
        "honor-sequence-v2": ("honor-development-replay", "HonorPerceptionReplayInstrumentedTest", "perception.json"),
    }[args.workload]
    remote = REMOTE.replace("match3-holdout", directory)
    names = [f["file"] for f in entries]
    if not names or len(names) != len(set(names)):
        raise ValueError("Require unique explicit frozen frames")
    for entry in entries:
        frame = (args.inputs / entry["file"]).resolve()
        if not frame.is_relative_to(args.inputs.resolve()) or sha(frame) != entry["sha256"]:
            raise ValueError("Frozen input bytes differ")
    command_prefix = [str(args.adb), "-s", args.serial]
    report = {"scope": "installed_emulator_APK_declared_processing_workload; excludes_capture_audio_io_and_game_heat",
              "kind": args.kind, "workload": args.workload, "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
              "packages": receipts, "manifest_sha256": manifest_sha, "steps": [], "pairs": [], "passed": False,
              "emulator_version_downgrade_allowed": args.allow_emulator_downgrade}

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
                reinstall = ["install", "-r"] + (["-d"] if args.allow_emulator_downgrade else [])
                run(name + "-install", [*reinstall, package["app"]["path"]])
                run(name + "-test-install", [*reinstall, "-t", package["test"]["path"]])
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
                run(name + "-clear", ["shell", "rm", "-rf", remote])
                run(name + "-inputs", ["push", str(args.inputs.resolve()), remote])
                text = run(name + "-measure", ["shell", "am", "instrument", "-w", "-r", "-e", "match3SourceSha256",
                    package["source_sha256"], "-e", "class", "com.openkhub.sensefield." + producer,
                    PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"])
                if instrumentation_summary(text) != {"executed": 1, "skipped": 0}:
                    raise ValueError("Measurement did not execute completely")
                data_path = out / (name + ".json")
                run(name + "-report", ["pull", remote + "/" + output_name, str(data_path)])
                data = json.loads(data_path.read_text())
                if data["manifest_sha256"] != manifest_sha or data["source_sha256"] != package["source_sha256"] or data["frames"] != len(entries):
                    raise ValueError("Measurement binding differs")
                if data.get("workload", "fixed-repeat-v1") != args.workload:
                    raise ValueError("Producer workload differs")
                if args.workload == "sequence-v1":
                    for entry, output in zip(entries, data["outputs"], strict=True):
                        for key in ("file", "sequence", "observed_at_ms"):
                            if entry[key] != output[key]:
                                raise ValueError("Producer changed the input timeline")
                if args.workload == "honor-sequence-v2":
                    for entry, output in zip(entries, data["observations"], strict=True):
                        if (entry["file"] != output["file"]
                                or entry["observed_at_ms"] + 1000000 != output["observed_at_ms"]):
                            raise ValueError("Producer changed the detector timeline")
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


def validate_sequence(manifest: dict) -> None:
    """Reject reordered, repeated or silently gap-filled images before any install."""
    if (manifest.get("schema") != "sensefield.match3-sequence/1"
            or manifest.get("workload") != "sequence-v1" or manifest.get("frozen") is not True
            or manifest.get("independent") is not False or manifest.get("warmup_rounds") != 6
            or manifest.get("measured_rounds") != 15 or manifest.get("repeats_per_frame") != 1):
        raise ValueError("Require the frozen development sequence protocol")
    previous = None
    seen = set()
    for frame in manifest["frames"]:
        at, index, sequence = frame["observed_at_ms"], frame["frame_index"], frame["sequence"]
        if type(at) is not int or at < 0 or type(index) is not int or index < 1 or not sequence:
            raise ValueError("Require original nonnegative observation time and frame index")
        reset = previous is None or sequence != previous["sequence"]
        if frame["reset_before"] is not reset:
            raise ValueError("Reset must coincide with the start of a sequence window")
        if reset:
            if sequence in seen or frame["delta_ms"] is not None or frame["missing_logged_frames_before"] is not None:
                raise ValueError("Interleaved windows or fabricated initial gap")
            seen.add(sequence)
        elif (at <= previous["observed_at_ms"] or index <= previous["frame_index"]
                or frame["delta_ms"] != at - previous["observed_at_ms"]
                or frame["missing_logged_frames_before"] != index - previous["frame_index"] - 1):
            raise ValueError("Reordered, repeated or gap-filled sequence")
        previous = frame
    if previous is None:
        raise ValueError("Empty sequence")


def honor_semantics(report: dict) -> list[dict]:
    """Compare all payload semantics; retain measured clocks in the raw reports.

    Native packet v2 slot 8 is wall cost; entity slot 10 is freshness derived
    from engine time. Removing these for comparison must not remove transitions,
    last-seen timestamps, detector evidence or relation/event decisions.
    """
    outputs = []
    for original in report["observations"]:
        row = {k: v for k, v in original.items() if k not in ("engine_age_ms", "native_packet")}
        packet = original["native_packet"].copy()
        if len(packet) < 24 or packet[:4] != [0x4e465231, 2, 24, 16] or not 0 <= packet[15] <= 16:
            raise ValueError("Unexpected detector packet schema")
        if len(packet) < 24 + packet[15] * 16 or original["engine_age_ms"] < 0:
            raise ValueError("Incomplete detector packet or missing engine clock")
        packet[8] = 0
        for i in range(packet[15]):
            base = 24 + i * 16
            last_seen = (packet[base + 13] & 0xffffffff) | ((packet[base + 14] & 0xffffffff) << 32)
            expected = original["observed_at_ms"] + original["engine_age_ms"] - last_seen
            if packet[base + 10] != expected:
                raise ValueError("Freshness is not explained by the recorded engine clock")
            packet[base + 10] = 0
        row["semantic_packet"] = packet
        outputs.append(row)
    return outputs


if __name__ == "__main__":
    raise SystemExit(main())
