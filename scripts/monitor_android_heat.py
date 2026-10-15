#!/usr/bin/env python3
"""Read-only ADB heat investigation, with operator-marked comparison phases.

Never starts/stops apps, changes game settings, records screenshots, or installs
an APK. Raw logs stay in the chosen local output directory. A phase marker is
an operator annotation, not proof that the phone entered that state.
"""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shutil
import subprocess
import time

from sample_android_thermal import sample_thermal_zones


PID_QUERY = r"""
printf 'sensefield='; pidof com.openkhub.sensefield
printf '\nsgame='; pidof com.tencent.tmgp.sgame
printf '\nmatch3='; pidof com.happyelements.AndroidAnimal
printf '\ncompositor='; pidof surfaceflinger
printf '\n'
"""
PHASES = ("unspecified", "game-only", "capture-paused", "normal", "normal-no-images")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def save_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)


def read_phase(directory):
    try:
        return json.loads((directory / "phase.json").read_text())
    except (OSError, ValueError):
        return {"name": "unspecified", "operator_marked": True}


def mark_phase(directory, name):
    phase = {"name": name, "operator_marked": True, "marked_utc": utc_now()}
    with (directory / "phase-events.jsonl").open("a") as events:
        events.write(json.dumps(phase, ensure_ascii=False) + "\n")
    save_json(directory / "phase.json", phase)


def parse_work(line):
    if "CaptureWork " not in line:
        return None
    work = {}
    for name, value in re.findall(r"(\w+)=([^\s\[\]]+)", line):
        if re.fullmatch(r"-?\d+", value):
            work[name] = int(value)
        elif value in ("true", "false"):
            work[name] = value == "true"
        elif name == "loadMode":
            work[name] = value
    for name in ("detectorWork", "imageWork"):
        match = re.search(name + r"=\[([^\]]*)\]", line)
        if match:
            try:
                work[name] = [int(value.strip()) for value in match.group(1).split(",")]
            except ValueError:
                work[name] = None
    # Preserve raw schema arrays. Legacy APKs lack paused/image-cost fields;
    # those remain absent rather than being fabricated as false or zero.
    return work


def run_shell(adb, serial, script, timeout=12):
    try:
        result = subprocess.run([str(adb), "-s", serial, "shell", script],
                                capture_output=True, text=True, timeout=timeout)
        return {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"returncode": -1, "stdout": "", "stderr": type(error).__name__}


def record(args):
    directory = args.output.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    if (directory / "samples.jsonl").exists():
        raise SystemExit("Use a new output directory; existing samples will not be overwritten.")
    adb = args.adb or shutil.which("adb") or Path.home() / "Library/Android/sdk/platform-tools/adb"
    clock = run_shell(adb, args.serial, "date +%s")
    device_epoch = clock["stdout"].strip()
    if clock["returncode"] != 0 or not device_epoch.isdigit():
        raise SystemExit("ADB device is not available; no monitor was started.")
    mark_phase(directory, args.phase)
    status = {"state": "recording", "serial": args.serial, "started_utc": utc_now(),
              "interval_sec": args.interval, "maximum_duration_sec": args.duration,
              "note": "Temperatures are internal; marked phase is not independently confirmed. "
              "CPU percentages are short samples, not an energy measurement or CPU P95."}
    save_json(directory / "status.json", status)
    for name, command in (("services-at-start.txt", "dumpsys activity services com.openkhub.sensefield; dumpsys media_projection"),
                          ("display-at-start.txt", "wm size; wm density; settings get system screen_brightness")):
        result = run_shell(adb, args.serial, command)
        (directory / name).write_text(result["stdout"] + result["stderr"])
    log_output = (directory / "capture-logcat.log").open("a")
    log_error = (directory / "logcat-errors.log").open("a")
    log = subprocess.Popen([str(adb), "-s", args.serial, "logcat", "-T", device_epoch + ".000",
                            "-v", "epoch", "MapAssistCapture:I", "SenseFieldDiagnostics:W", "*:S"],
                           stdout=log_output, stderr=log_error)
    reason = "duration_reached"
    started = time.monotonic()
    index = 0
    latest_work = None
    log_offset = 0
    log_tail = ""
    next_sample = started
    try:
        with (directory / "samples.jsonl").open("a", buffering=1) as output:
            while time.monotonic() - started < args.duration:
                if (directory / "STOP").exists():
                    reason = "operator_stopped"
                    break
                if time.monotonic() < next_sample:
                    time.sleep(min(1, next_sample - time.monotonic()))
                    continue
                sample = {"recorded_utc": utc_now(), "elapsed_sec": round(time.monotonic() - started, 3),
                          "index": index, "phase": read_phase(directory), "logcat_alive": log.poll() is None}
                battery = run_shell(adb, args.serial, "dumpsys battery")
                (directory / f"battery-{index:03d}.txt").write_text(battery["stdout"] + battery["stderr"])
                sample["battery_returncode"] = battery["returncode"]
                for name in ("temperature", "level", "status"):
                    match = re.search(r"^\s*" + name + r":\s*(-?\d+)\s*$", battery["stdout"], re.M)
                    if match:
                        sample["battery_" + name] = int(match.group(1))
                sample["charging_inputs"] = dict(re.findall(r"(AC powered|USB powered|Wireless powered): (true|false)", battery["stdout"]))
                sample["thermal_zones"] = sample_thermal_zones(adb, args.serial)
                pids = run_shell(adb, args.serial, PID_QUERY)
                sample["pids"] = {name: [int(pid) for pid in values.split() if pid.isdigit()]
                                  for name, values in re.findall(r"^(\w+)=([^\n]*)", pids["stdout"], re.M)}
                selected_pids = sorted({pid for values in sample["pids"].values() for pid in values})
                if selected_pids:
                    top = run_shell(adb, args.serial, "top -b -n 2 -d 1 -o PID,%CPU,TIME+,NAME -s 2 -p "
                                    + ",".join(map(str, selected_pids)))
                    (directory / f"top-{index:03d}.txt").write_text(top["stdout"] + top["stderr"])
                    sample["top_returncode"] = top["returncode"]
                    sample["fresh_top"] = top["stdout"].split("Tasks:")[-1].strip()
                # Incremental log reads avoid repeatedly loading a growing log.
                with (directory / "capture-logcat.log").open() as captured:
                    captured.seek(log_offset)
                    log_tail += captured.read()
                    log_offset = captured.tell()
                lines = log_tail.split("\n")
                log_tail = lines.pop()
                for line in lines:
                    work = parse_work(line)
                    if work is not None:
                        latest_work = {"observed_utc": utc_now(), "raw_line": line, "counters": work}
                sample["latest_capture_work"] = latest_work
                if index % 3 == 0:
                    services = run_shell(adb, args.serial, "dumpsys activity services com.openkhub.sensefield; dumpsys media_projection")
                    (directory / f"services-{index:03d}.txt").write_text(services["stdout"] + services["stderr"])
                sample["completed_utc"] = utc_now()
                output.write(json.dumps(sample, ensure_ascii=False) + "\n")
                status.update({"samples": index + 1, "last_sample_utc": sample["recorded_utc"]})
                save_json(directory / "status.json", status)
                index += 1
                next_sample = max(next_sample + args.interval, time.monotonic())
    except KeyboardInterrupt:
        reason = "interrupted"
    finally:
        if log.poll() is None:
            log.terminate()
            try:
                log.wait(timeout=5)
            except subprocess.TimeoutExpired:
                log.kill()
                log.wait(timeout=5)
        log_output.close()
        log_error.close()
        status.update({"state": "stopped", "stop_reason": reason, "ended_utc": utc_now(), "samples": index})
        save_json(directory / "status.json", status)
    print(f"Stopped: {index} samples saved to {directory}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    recording = commands.add_parser("record")
    recording.add_argument("--serial", required=True)
    recording.add_argument("--output", type=Path, required=True)
    recording.add_argument("--adb", type=Path)
    recording.add_argument("--phase", choices=PHASES, default="unspecified")
    recording.add_argument("--duration", type=int, default=900)
    recording.add_argument("--interval", type=int, default=10)
    marking = commands.add_parser("mark")
    marking.add_argument("--output", type=Path, required=True)
    marking.add_argument("--phase", choices=PHASES, required=True)
    stopping = commands.add_parser("stop")
    stopping.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "record":
        if not 1 <= args.duration <= 5400 or not 10 <= args.interval <= 60:
            parser.error("Duration must be 1..5400 sec; sampling interval must be 10..60 sec.")
        record(args)
    else:
        if not (args.output / "status.json").is_file():
            parser.error("Output must identify an existing monitor run.")
        status = json.loads((args.output / "status.json").read_text())
        if args.command == "mark":
            if status.get("state") != "recording":
                parser.error("Cannot mark a phase after the monitor stopped.")
            mark_phase(args.output, args.phase)
        else:
            if status.get("state") == "stopped":
                print("Monitor already stopped.")
                return
            (args.output / "STOP").write_text("operator_stopped\n")


if __name__ == "__main__":
    main()
