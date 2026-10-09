#!/usr/bin/env python3
"""Collect private APP records after an authorized ADB trial, using a same-signature test helper.

No export UI, root, diagnostic upload, settings change, or production debug endpoint.
Instrumentation restarts the target process, so an active assistance service blocks collection.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import zipfile


ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.openkhub.sensefield"
HELPER = "com.openkhub.sensefield.DiagnosticExportInstrumentedTest"
ACTIVE_SERVICE = re.compile(r"ServiceRecord\{[^\n]*com\.openkhub\.sensefield/[^\n]*(?:Match3LiveService|CaptureService)")


def adb_command(args):
    adb = str(args.adb or shutil.which("adb") or Path.home() / "Library/Android/sdk/platform-tools/adb")
    return [adb, "-s", args.serial]


def wait_for_session_end(args):
    """Watch one authorized trial; never treat an ADB disconnect as a stopped session."""
    destination = args.output.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + args.watch_timeout
    saw_session = False
    quiet_since = None
    while time.monotonic() < deadline:
        try:
            result = subprocess.run(adb_command(args) + ["shell", "dumpsys", "activity", "services", PACKAGE],
                                    capture_output=True, text=True, timeout=15)
            online = result.returncode == 0
        except subprocess.TimeoutExpired:
            online = False
        if not online:
            quiet_since = None
            phase = "device_unavailable"
        elif ACTIVE_SERVICE.search(result.stdout):
            saw_session = True
            quiet_since = None
            phase = "assistance_active"
        elif saw_session:
            phase = "waiting_for_finished_records"
            if quiet_since is None:
                quiet_since = time.monotonic()
            elif time.monotonic() - quiet_since >= 15:
                return
        else:
            phase = "waiting_for_assistance_start"
        (destination / "watch-state.json").write_text(json.dumps({
            "phase": phase, "saw_session": saw_session,
            "at_utc": datetime.now(timezone.utc).isoformat()
        }, indent=2) + "\n")
        time.sleep(5)
    raise RuntimeError("No completed trial was observed within the watch window; phone records remain unchanged.")


def collect(args):
    command = adb_command(args)
    destination = args.output.resolve()
    destination.mkdir(parents=True, exist_ok=True)

    def run(name, tail, timeout=30):
        result = subprocess.run(command + tail, capture_output=True, text=True, timeout=timeout)
        (destination / name).write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError(f"ADB {name} failed; see {destination / name}")
        return result.stdout

    services = run("services-before-export.txt", ["shell", "dumpsys", "activity", "services", PACKAGE])
    if ACTIVE_SERVICE.search(services):
        raise RuntimeError("Assistance is still active. Finish the assistance session before collecting records.")
    if not args.test_apk.is_file():
        raise RuntimeError("Build the signed Release test APK first (:app:assembleReleaseAndroidTest).")
    run("helper-install.txt", ["install", "-r", str(args.test_apk.resolve())], timeout=60)
    services = run("services-before-instrumentation.txt", ["shell", "dumpsys", "activity", "services", PACKAGE])
    if ACTIVE_SERVICE.search(services):
        raise RuntimeError("A new assistance session started. Collection is deferred to avoid interrupting it.")
    status = run("helper-export.txt", ["shell", "am", "instrument", "-w", "-e", "class", HELPER,
                                       "-e", "authorizedDiagnosticsExport", "true",
                                       PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"], timeout=90)
    if "OK (1 test)" not in status or "diagnostic_export_path=" not in status:
        raise RuntimeError("The private-record helper did not complete; records remain on the phone.")
    remote = "/sdcard/Android/data/" + PACKAGE + "/files/adb-diagnostics"
    run("pull-metadata.txt", ["pull", remote + "/export.json", str(destination / "export.json")])
    metadata = json.loads((destination / "export.json").read_text())
    artifacts = []
    for name in metadata["archives"]:
        if not re.fullmatch(r"diag-\d+-[0-9a-fA-F-]+\.zip", name):
            raise RuntimeError("Unexpected archive filename")
        run("pull-" + name + ".txt", ["pull", remote + "/" + name, str(destination / name)], timeout=90)
        path = destination / name
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise RuntimeError("Corrupt diagnostic archive: " + name)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        actual = run("hash-" + name + ".txt", ["shell", "sha256sum", remote + "/" + name]).split()[0]
        if actual != digest:
            raise RuntimeError("Diagnostic checksum mismatch: " + name)
        artifacts.append({"name": name, "bytes": path.stat().st_size, "sha256": digest})
    receipt = {"serial": args.serial, "target_version": metadata["version_name"],
               "target_version_code": metadata["version_code"], "archives": artifacts,
               "privacy": "local ADB export; original private records are unchanged"}
    (destination / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
    # Only remove this helper's verified temporary copies; never the original private sessions.
    for name in [item["name"] for item in artifacts] + ["export.json"]:
        run("cleanup-" + name + ".txt", ["shell", "rm", "--", remote + "/" + name])
    if getattr(args, "wait_for_session_end", False):
        (destination / "watch-state.json").write_text(json.dumps({
            "phase": "collected", "saw_session": True,
            "at_utc": datetime.now(timezone.utc).isoformat(), "archives": len(artifacts)
        }, indent=2) + "\n")
    print(json.dumps(receipt, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--wait-for-session-end", action="store_true",
                        help="Watch one trial and collect 15 seconds after its assistance service stops.")
    parser.add_argument("--watch-timeout", type=int, default=7200, help="Maximum watch window in seconds (default: 7200).")
    parser.add_argument("--test-apk", type=Path,
                        default=ROOT / "android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk")
    args = parser.parse_args()
    if args.watch_timeout <= 0:
        parser.error("--watch-timeout must be positive")
    try:
        if args.wait_for_session_end:
            wait_for_session_end(args)
        collect(args)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
