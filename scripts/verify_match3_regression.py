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
DEBUG_CLASSES = ("Match3HintInstrumentedTest", "Match3AuditInstrumentedTest", "Match3UiInstrumentedTest", "Match3MergeInstrumentedTest")
RELEASE_CLASSES = ("Match3ReleaseCaptureInstrumentedTest", "BundledAudioInstrumentedTest",
                   "DetectorReuseInstrumentedTest", "DiagnosticWorkInstrumentedTest", "ReleaseRuntimeInstrumentedTest",
                   "Match3UiInstrumentedTest", "DiagnosticContextInstrumentedTest")


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
    parser.add_argument("--mode", choices=("jvm", "full"), default="jvm")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--fixtures", type=Path, help="Optional, explicitly supplied diagnostic JPEG directory (test APK only)")
    parser.add_argument("--output", type=Path)
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

    try:
        if args.mode == "full":
            assert_emulator(args.serial)
            required = ("SENSEFIELD_KEYSTORE_PATH", "SENSEFIELD_KEY_ALIAS", "SENSEFIELD_KEYSTORE_PASSWORD", "SENSEFIELD_KEY_PASSWORD")
            if not all(env.get(key) for key in required): raise RuntimeError("Full mode needs the existing Release signing environment variables")
            if not adb.is_file(): raise RuntimeError("Set ANDROID_HOME to the Android SDK")
            if run("emulator-online", [str(adb), "-s", args.serial, "get-state"]).strip() != "device":
                raise RuntimeError("Emulator is not online")
            services = run("emulator-preflight", [str(adb), "-s", args.serial, "shell", "dumpsys", "activity", "services", PACKAGE])
            if re.search(r"ServiceRecord.*(?:Match3LiveService|CaptureService|AssistantVoiceService)", services):
                raise RuntimeError("An assistance session is running; finish it before automated instrumentation")
        gradle("jvm", [":app:testDebugUnitTest"])
        report["jvm"] = jvm_summary()
        if args.mode == "full":
            gradle("debug-build", [":app:assembleDebug", ":app:assembleDebugAndroidTest"])
            for variant, apk in (("debug", "app-debug.apk"), ("androidTest/debug", "app-debug-androidTest.apk")):
                run("install-" + variant.replace("/", "-"), [str(adb), "-s", args.serial, "install", "-r",
                    str(ROOT / "android/app/build/outputs/apk" / variant / apk)])
            run("emulator-overlay", [str(adb), "-s", args.serial, "shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow"])
            report["debug_instrumentation"] = instrument("debug-instrumentation", DEBUG_CLASSES)
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
            capture = "/sdcard/Android/data/" + PACKAGE + "/files/match3-release-capture"
            run("capture-evidence", [str(adb), "-s", args.serial, "pull", capture, str(output / "capture")])
        if source_digest() != report["source_sha256"]:
            raise RuntimeError("Source files changed during this run; the current checkout needs a fresh regression")
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
