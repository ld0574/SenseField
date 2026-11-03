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
import uuid
import zipfile


ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.openkhub.sensefield"
HELPER_PACKAGE = PACKAGE + ".diagnosticshelper"
HELPER_COMPONENT = HELPER_PACKAGE + "/.DiagnosticExportInstrumentation"
HELPER_REGISTRATION = (HELPER_PACKAGE + "/" + HELPER_PACKAGE
                       + ".DiagnosticExportInstrumentation (target=" + PACKAGE + ")")
HELPER_SHORT_REGISTRATION = HELPER_COMPONENT + " (target=" + PACKAGE + ")"
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
    reuse_helper = getattr(args, "reuse_installed_helper", False)
    if not reuse_helper:
        if not args.test_apk.is_file():
            raise RuntimeError("Build the standalone signed diagnostic helper first "
                               "(:diagnostics-helper:assembleRelease).")
        run("helper-install.txt", ["install", "--no-incremental", "-r", "-t",
                                    str(args.test_apk.resolve())], timeout=60)
    registered = run("helper-registration.txt", ["shell", "pm", "list", "instrumentation"])
    registrations = {line.strip() for line in registered.splitlines()}
    if not registrations.intersection({"instrumentation:" + HELPER_REGISTRATION,
                                       "instrumentation:" + HELPER_SHORT_REGISTRATION}):
        raise RuntimeError("No compatible standalone diagnostic helper is registered; "
                           + ("no package was installed. " if reuse_helper else "")
                           + "The legacy app test runner is tied to its app build and is not reused.")
    services = run("services-before-instrumentation.txt", ["shell", "dumpsys", "activity", "services", PACKAGE])
    if ACTIVE_SERVICE.search(services):
        raise RuntimeError("A new assistance session started. Collection is deferred to avoid interrupting it.")
    request_id = uuid.uuid4().hex
    remote = "/sdcard/Android/data/" + PACKAGE + "/files/adb-diagnostics/" + request_id
    status = run("helper-export.txt", ["shell", "am", "instrument", "-w",
                                       "-e", "authorizedDiagnosticsExport", "true",
                                       "-e", "exportRequestId", request_id,
                                       HELPER_COMPONENT], timeout=90)
    if (not re.search(r"^INSTRUMENTATION_CODE: -1\s*$", status, re.MULTILINE)
            or "diagnostic_export_protocol=1" not in status
            or "diagnostic_export_path=" not in status):
        raise RuntimeError("The private-record helper did not complete; records remain on the phone.")
    run("pull-metadata.txt", ["pull", remote + "/export.json", str(destination / "export.json")])
    metadata = json.loads((destination / "export.json").read_text())
    if metadata.get("export_protocol") != 1 or metadata.get("export_request_id") != request_id:
        raise RuntimeError("Diagnostic export protocol or request identity mismatch; no files were removed.")
    if not isinstance(metadata.get("archives"), list) or len(metadata["archives"]) > 3:
        raise RuntimeError("Unexpected diagnostic archive list; no files were removed.")
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
               "helper": HELPER_PACKAGE, "export_protocol": 1,
               "privacy": "local ADB export; original private records are unchanged"}
    (destination / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
    # Only remove this helper's verified temporary copies; never the original private sessions.
    for name in [item["name"] for item in artifacts] + ["export.json"]:
        run("cleanup-" + name + ".txt", ["shell", "rm", "--", remote + "/" + name])
    run("cleanup-export-directory.txt", ["shell", "rmdir", "--", remote])
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
    parser.add_argument("--helper-apk", "--test-apk", dest="test_apk", type=Path,
                        default=ROOT / "android/diagnostics-helper/build/outputs/apk/release/diagnostics-helper-release.apk")
    parser.add_argument("--reuse-installed-helper", action="store_true",
                        help="Reuse an already registered same-signature helper; never install an APK.")
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
