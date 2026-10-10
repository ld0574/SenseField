#!/usr/bin/env python3
"""Repeatable local Match3 gate. Full mode operates on an emulator only."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.openkhub.sensefield"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
DEBUG_CLASSES = ("Match3HintInstrumentedTest", "Match3AuditInstrumentedTest", "Match3UiInstrumentedTest",
                 "Match3MergeInstrumentedTest", "Match3Level43InstrumentedTest", "Match3DiagnosticReplayInstrumentedTest",
                 "Match3GoalVisionInstrumentedTest", "Match3GoalReplayInstrumentedTest",
                 "Match3LiveFeedbackInstrumentedTest", "Match3CrossLevelVisionInstrumentedTest", "Match3ElementEvidenceInstrumentedTest", "Match3TaskFeedbackInstrumentedTest", "Match3IceTaskInstrumentedTest", "Match3SparseTaskInstrumentedTest")
RELEASE_CLASSES = ("Match3ReleaseCaptureInstrumentedTest", "BundledAudioInstrumentedTest",
                   "DetectorReuseInstrumentedTest", "DiagnosticWorkInstrumentedTest", "ReleaseRuntimeInstrumentedTest",
                   "Match3UiInstrumentedTest", "DiagnosticContextInstrumentedTest", "Match3DiagnosticReplayInstrumentedTest",
                   "Match3GoalReplayInstrumentedTest", "Match3LiveFeedbackInstrumentedTest",
                   "Match3CrossLevelVisionInstrumentedTest", "Match3ElementEvidenceInstrumentedTest", "Match3TaskFeedbackInstrumentedTest", "Match3IceTaskInstrumentedTest", "Match3SparseTaskInstrumentedTest")

DEBUG_CLASSES += ("Match3FlywheelInstrumentedTest",)
RELEASE_CLASSES += ("Match3FlywheelInstrumentedTest",)


def instrumentation_summary(output: str) -> dict:
    # am instrument can exit 0 even when a test or the entire runner failed.
    ok = re.search(r"^OK \((\d+) tests?\)", output, re.M)
    failed = any(marker in output for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"))
    if not ok or failed or int(ok.group(1)) == 0:
        raise RuntimeError("Instrumentation did not report a successful completed suite")
    # AndroidJUnitRunner uses -3 for ignored tests and -4 for failed assumptions.
    # JUnit's OK count includes both; neither is evidence of a completed check.
    skipped = len(re.findall(r"^INSTRUMENTATION_STATUS_CODE: -(?:3|4)\s*$", output, re.M))
    return {"executed": int(ok.group(1)), "skipped": skipped}


def assert_emulator(serial: str) -> None:
    if not re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("Full regression accepts an emulator serial only; it must not interrupt a player's phone")


def source_digest() -> str:
    digest = hashlib.sha256()
    paths = sorted((ROOT / "android/app/src").rglob("*.java"))
    paths += [ROOT / "android/app/build.gradle", ROOT / "android/app/proguard-rules.pro", Path(__file__)]
    catalog = ROOT / "android/app/src/main/assets/match3-fixed-ui-v1.json"
    if catalog.is_file(): paths.append(catalog)
    gallery = ROOT / "android/app/src/main/assets/match3-gallery-v1.json"
    # Include absence as well: adding/deleting the asset changes the path set.
    if gallery.is_file(): paths.append(gallery)
    paths += sorted((ROOT / "scripts").glob("*match3*.py"))
    paths = sorted(set(paths))
    for path in paths:
        digest.update(str(path.relative_to(ROOT)).encode()); digest.update(path.read_bytes())
    return digest.hexdigest()


def jvm_summary() -> dict:
    reports = sorted((ROOT / "android/app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    if not reports:
        raise RuntimeError("No JVM result XML was produced")
    counts = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for path in reports:
        suite = ET.parse(path).getroot()
        for key in counts: counts[key] += int(suite.get(key, "0"))
    if counts["failures"] or counts["errors"] or not counts["tests"]:
        raise RuntimeError("JVM regression contains failures or errors")
    return counts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("jvm", "cost", "accuracy", "debug", "full"), default="jvm")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--fixtures", type=Path, help="Optional, explicitly supplied diagnostic JPEG directory (test APK only)")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--holdout", type=Path, help="Explicit frozen manifest.json + labels.json + frame directory")
    args = parser.parse_args(argv)
    now = dt.datetime.now(dt.timezone.utc)
    output = args.output or ROOT / "validation/private" / ("match3-regression-" + now.strftime("%Y%m%d-%H%M%S"))
    output = output.resolve(); output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    mac_java = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    if "JAVA_HOME" not in env and mac_java.is_dir(): env["JAVA_HOME"] = str(mac_java)
    mac_sdk = Path.home() / "Library/Android/sdk"
    if "ANDROID_HOME" not in env and mac_sdk.is_dir(): env["ANDROID_HOME"] = str(mac_sdk)
    sdk = Path(env.get("ANDROID_HOME", env.get("ANDROID_SDK_ROOT", "")))
    adb = sdk / "platform-tools/adb"
    report = {"started_utc": now.isoformat(), "mode": args.mode, "source_sha256": source_digest(),
              "fixtures_requested": args.fixtures is not None, "steps": [], "passed": False,
              "independent_player_evidence": False, "thermal_gate_passed": False, "acoustic_gate_passed": False}

    def holdout_inputs() -> tuple[dict, str]:
        if args.holdout is None: raise ValueError("Accuracy/Debug/Full gates require --holdout")
        directory=args.holdout.resolve()
        manifest=json.loads((directory / "manifest.json").read_text())
        labels=json.loads((directory / "labels.json").read_text()) if args.mode!="cost" else None
        if labels is not None and not labels.get("samples"): raise ValueError("Holdout requires nonempty human labels")
        if args.mode=="cost" and "max_cost_increase_percent" not in manifest.get("gates", {}):
            raise ValueError("Declare max_cost_increase_percent before the paired cost run")
        if args.mode!="cost" and "min_coverage" not in manifest.get("gates", {}):
            raise ValueError("Declare min_coverage in the frozen holdout manifest before running the gate")
        if args.mode=="full" and (manifest.get("independent") is not True or manifest.get("frozen") is not True):
            raise ValueError("Release accuracy requires an independent frozen holdout")
        if args.mode=="full":
            if not isinstance(labels.get("goal_samples"), list):
                raise ValueError("Release accuracy requires explicit human HUD truth, including empty HUD judgments")
            if any(item.get("reviewed") is not True for item in labels["samples"]+labels.get("goal_samples", [])):
                raise ValueError("Release accuracy requires explicit human review of cell and HUD truth")
            if any(not item.get("kind") or type(item.get("remaining")) is not int or
                   item["remaining"] < 0 or type(item.get("completed")) is not bool for item in labels["goal_samples"]):
                raise ValueError("Release HUD truth requires reviewed identity, remaining count and completion state")
            gallery=json.loads((ROOT/"android/app/src/main/assets/match3-gallery-v1.json").read_text())
            metadata=gallery.get("metadata",{})
            groups=set(manifest.get("source_groups",[manifest.get("session_id")]))
            if not groups or None in groups or not metadata.get("registry_sha256") or not metadata.get("split_sha256"):
                raise ValueError("Release gate needs gallery provenance and explicit holdout source sessions")
            if groups.intersection(metadata.get("training_groups",[])):
                raise ValueError("Training sessions overlap the accuracy holdout")
        names=[frame["file"] for frame in manifest["frames"]]
        if not names or len(set(names))!=len(names): raise ValueError("Holdout frame names must be unique")
        digest=hashlib.sha256()
        for name in ["manifest.json", *(["labels.json"] if labels is not None else []), *sorted(names)]:
            path=(directory/name).resolve()
            if not path.is_relative_to(directory): raise ValueError("Frame is outside the explicit holdout directory")
            digest.update(name.encode());digest.update(path.read_bytes())
        return manifest,digest.hexdigest()

    def run(name: str, command: list[str], *, timeout: int = 240, cwd: Path = ROOT) -> str:
        print(name, flush=True)
        log = output / (name + ".txt")
        step = {"name": name, "log": str(log), "passed": False}; report["steps"].append(step)
        try:
            completed = subprocess.run(command, cwd=cwd, env=env, timeout=timeout,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            log.write_text(completed.stdout, encoding="utf-8"); step["returncode"] = completed.returncode
            if completed.returncode:
                raise RuntimeError(name + " failed; see " + str(log))
            step["passed"] = True
            return completed.stdout
        except subprocess.TimeoutExpired as error:
            data = error.stdout or b""
            log.write_text(data.decode(errors="replace") if isinstance(data, bytes) else data, encoding="utf-8")
            step["reason"] = "timeout"
            raise RuntimeError(name + " timed out; see " + str(log)) from error

    def gradle(name: str, tasks: list[str], variant: str = "debug") -> None:
        properties = ["-PsensefieldAbi=arm64-v8a", "-PsensefieldTestBuildType=" + variant]
        if args.fixtures: properties.append("-PsensefieldCaptureScaleFixtureDir=" + str(args.fixtures.resolve()))
        run(name, [str(ROOT / "android/gradlew"), "--console=plain", *properties, *tasks], cwd=ROOT / "android", timeout=600)

    def instrument(name: str, classes: tuple[str, ...]) -> dict:
        stdout = run(name, [str(adb), "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                            ",".join(PACKAGE + "." + c for c in classes), RUNNER], timeout=300)
        try:
            summary = instrumentation_summary(stdout)
            expected = sum(len(re.findall(r"@Test\b", (ROOT / "android/app/src/androidTest/java/com/openkhub/sensefield" / (c + ".java")).read_text())) for c in classes)
            if summary["executed"] != expected or summary["skipped"]:
                raise RuntimeError("Instrumentation did not complete all selected tests without skips")
            return summary
        except RuntimeError:
            report["steps"][-1]["passed"] = False
            raise

    def accuracy(variant: str, manifest: dict) -> None:
        target="/sdcard/Android/data/"+PACKAGE+"/files/match3-holdout"
        run(variant+"-holdout-clear",[str(adb),"-s",args.serial,"shell","rm","-rf",target])
        run(variant+"-holdout-push",[str(adb),"-s",args.serial,"push",str(args.holdout.resolve()),target])
        stdout=run(variant+"-accuracy-engine",[str(adb),"-s",args.serial,"shell","am","instrument","-w","-r",
                   "-e","match3Strict","true","-e","class",PACKAGE+".Match3HoldoutAccuracyInstrumentedTest",RUNNER])
        if instrumentation_summary(stdout)!={"executed":1,"skipped":0}:
            raise RuntimeError("Accuracy producer did not run without skips")
        predictions=output/(variant+"-predictions.json")
        run(variant+"-accuracy-pull",[str(adb),"-s",args.serial,"pull",target+"/predictions.json",str(predictions)])
        observed=json.loads(predictions.read_text())
        directory=args.holdout.resolve()
        if observed.get("manifest_sha256")!=hashlib.sha256((directory/"manifest.json").read_bytes()).hexdigest():
            raise RuntimeError("Engine replayed another manifest")
        for frame in manifest["frames"]:
            if observed.get("frame_sha256",{}).get(frame["file"])!=hashlib.sha256((directory/frame["file"]).read_bytes()).hexdigest():
                raise RuntimeError("Engine replayed different frame bytes: "+frame["file"])
        expected={frame["file"] for frame in manifest["frames"]}
        actual=[frame["file"] for frame in observed.get("frame_results",[])]
        if len(actual)!=len(expected) or set(actual)!=expected: raise RuntimeError("Missing holdout frame results")
        gallery=ROOT/"android/app/src/main/assets/match3-gallery-v1.json"
        if gallery.is_file() and (observed.get("gallery_status")!="loaded" or
                observed.get("gallery_sha256")!=hashlib.sha256(gallery.read_bytes()).hexdigest()):
            raise RuntimeError("Tested engine did not load the candidate gallery bytes")
        if args.mode=="full" and observed.get("gallery_goals_pending_review",0):
            raise RuntimeError("Release gallery still has goal icons pending explicit review")
        scored=output/(variant+"-accuracy.json")
        run(variant+"-accuracy-score",[sys.executable,str(ROOT/"scripts/evaluate_match3_accuracy.py"),
                "--predictions",str(predictions),"--labels",str(args.holdout.resolve()/"labels.json"),"--strict",
                "--min-coverage",str(manifest["gates"]["min_coverage"]),"--json",str(scored)])
        report[variant+"_accuracy"]=json.loads(scored.read_text())
        report["accuracy_independent"]=manifest.get("independent") is True

    def cost(manifest: dict) -> None:
        target="/sdcard/Android/data/"+PACKAGE+"/files/match3-holdout"
        run("cost-input-clear",[str(adb),"-s",args.serial,"shell","rm","-rf",target])
        run("cost-input-push",[str(adb),"-s",args.serial,"push",str(args.holdout.resolve()),target])
        error=None
        try: report["cost_instrumentation"]=instrument("cost-instrumentation",("Match3FlywheelCostInstrumentedTest",))
        except RuntimeError as failure: error=failure
        artifact=output/"flywheel-cost.json"
        run("cost-pull",[str(adb),"-s",args.serial,"pull",target+"/flywheel-cost.json",str(artifact)])
        measured=json.loads(artifact.read_text());report["software_cost"]=measured
        if measured.get("manifest_sha256")!=hashlib.sha256((args.holdout/"manifest.json").read_bytes()).hexdigest():
            raise RuntimeError("Cost inputs do not match replay")
        for frame in manifest["frames"]:
            if measured.get("frame_sha256",{}).get(frame["file"])!=hashlib.sha256((args.holdout/frame["file"]).read_bytes()).hexdigest():
                raise RuntimeError("Cost frame bytes do not match replay")
        gallery=ROOT/"android/app/src/main/assets/match3-gallery-v1.json"
        if measured.get("gallery_sha256")!=hashlib.sha256(gallery.read_bytes()).hexdigest():
            raise RuntimeError("Cost test loaded another gallery")
        report["debug_apk_sha256"]=hashlib.sha256((ROOT/"android/app/build/outputs/apk/debug/app-debug.apk").read_bytes()).hexdigest()
        if error is not None: raise error
        if measured.get("passed") is not True: raise RuntimeError("Paired software P95 gate failed")

    try:
        manifest=None
        if args.mode != "jvm":
            manifest,report["holdout_sha256"]=holdout_inputs()
            assert_emulator(args.serial)
            required = ("SENSEFIELD_KEYSTORE_PATH", "SENSEFIELD_KEY_ALIAS", "SENSEFIELD_KEYSTORE_PASSWORD", "SENSEFIELD_KEY_PASSWORD")
            if args.mode=="full" and not all(env.get(key) for key in required): raise RuntimeError("Full mode needs the existing Release signing environment variables")
            if not adb.is_file(): raise RuntimeError("Set ANDROID_HOME to the Android SDK")
            if run("emulator-online", [str(adb), "-s", args.serial, "get-state"]).strip() != "device":
                raise RuntimeError("Emulator is not online")
            services = run("emulator-preflight", [str(adb), "-s", args.serial, "shell", "dumpsys", "activity", "services", PACKAGE])
            if re.search(r"ServiceRecord.*(?:Match3LiveService|CaptureService|AssistantVoiceService)", services):
                raise RuntimeError("An assistance session is running; finish it before automated instrumentation")
        gradle("jvm", [":app:testDebugUnitTest"])
        report["jvm"] = jvm_summary()
        if args.mode != "jvm":
            gradle("debug-build", [":app:assembleDebug", ":app:assembleDebugAndroidTest"])
            for variant, apk in (("debug", "app-debug.apk"), ("androidTest/debug", "app-debug-androidTest.apk")):
                run("install-" + variant.replace("/", "-"), [str(adb), "-s", args.serial, "install", "-r",
                    str(ROOT / "android/app/build/outputs/apk" / variant / apk)])
            run("emulator-overlay", [str(adb), "-s", args.serial, "shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow"])
            if args.mode=="cost": cost(manifest)
            else:
                if args.mode!="accuracy": report["debug_instrumentation"] = instrument("debug-instrumentation", DEBUG_CLASSES)
                accuracy("debug",manifest)
        if args.mode == "full":
            # Stop only the isolated test target before replacing it with the minified package.
            run("emulator-after-debug", [str(adb), "-s", args.serial, "shell", "am", "force-stop", PACKAGE])
            gradle("release-build-lint", [":app:assembleRelease", ":app:assembleReleaseAndroidTest", ":app:lintRelease"], "release")
            apk = ROOT / "android/app/build/outputs/apk/release/app-release.apk"
            run("release-package", [sys.executable, str(ROOT / "scripts/verify_android_release.py"), str(apk),
                                     "--output", str(output / "release-package.json")])
            report["release_package"] = json.loads((output / "release-package.json").read_text())
            run("install-release", [str(adb), "-s", args.serial, "install", "-r", str(apk)])
            run("install-release-test", [str(adb), "-s", args.serial, "install", "-r",
                    str(ROOT / "android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk")])
            report["release_instrumentation"] = instrument("release-instrumentation", RELEASE_CLASSES)
            accuracy("release",manifest)
            capture = "/sdcard/Android/data/" + PACKAGE + "/files/match3-release-capture"
            run("capture-evidence", [str(adb), "-s", args.serial, "pull", capture, str(output / "capture")])
        if source_digest() != report["source_sha256"]:
            raise RuntimeError("Source files changed during this run; the current checkout needs a fresh regression")
        if manifest is not None and holdout_inputs()[1]!=report["holdout_sha256"]:
            raise RuntimeError("Holdout inputs changed during this run")
        report["passed"] = True
    except (RuntimeError, ValueError, OSError, ET.ParseError) as error:
        report["error"] = str(error); print(str(error), file=sys.stderr)
    finally:
        report["completed_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Report: " + str(output / "report.json"), flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
