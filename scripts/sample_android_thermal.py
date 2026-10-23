#!/usr/bin/env python3
"""Read temperature zones through ADB without starting or changing phone apps.

For development telemetry only: Android apps generally cannot read these nodes.
The vendor's sensor names do not establish exterior surface temperatures.
"""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import subprocess


READ_ZONES = r"""
for node in /sys/class/thermal/thermal_zone*/type; do
    if [ -r "$node" ]; then
        zone=${node%/type}
        IFS= read -r thermal_name < "$node"
        thermal_value=unavailable
        if [ -r "$zone/temp" ]; then
            IFS= read -r thermal_value < "$zone/temp"
        fi
        printf '%s\t%s\t%s\n' "$zone" "$thermal_name" "$thermal_value"
    fi
done
"""


def selected_temperature(name):
    # Some vendor zones hold voltage/current or invalid sentinel values instead
    # of temperatures. Do not label every thermal_zone as a CPU temperature.
    return name in {"battery", "cpu_therm", "quiet_therm"} or name.startswith(
        ("cpuss-", "cpu-", "gpuss-", "gpu-", "skin", "case")
    )


def sample_thermal_zones(adb_bin, serial, timeout=12):
    sample = {
        "started_utc": datetime.now(timezone.utc).isoformat(),
        "serial": serial,
        "source": "adb_shell_linux_thermal_sysfs",
        "note": "Internal sensors; not an exterior surface measurement or an in-match peak. "
        "Unavailable sensors are omitted, never reported as zero or NORMAL.",
    }
    try:
        result = subprocess.run(
            [str(adb_bin), "-s", serial, "shell", READ_ZONES],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        sample.update({"state": "unavailable", "reason": type(error).__name__, "sensors": []})
    else:
        sensors = []
        for line in result.stdout.splitlines()[:512]:
            fields = line.split("\t")
            if len(fields) != 3 or not selected_temperature(fields[1]):
                continue
            try:
                raw_value = int(fields[2])
            except ValueError:
                continue
            if not -10000 < raw_value < 150000:
                continue
            sensors.append(
                {"path": fields[0], "name": fields[1], "raw_millidegrees_c": raw_value,
                 "temperature_c": raw_value / 1000}
            )
        sample.update(
            {"state": "available" if result.returncode == 0 and sensors else "unavailable",
             "returncode": result.returncode, "sensors": sensors}
        )
        if result.returncode != 0:
            sample["reason"] = "adb_command_failed"
        elif not sensors:
            sample["reason"] = "no_readable_named_temperature_zones"
    sample["completed_utc"] = datetime.now(timezone.utc).isoformat()
    return sample


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit ADB device serial.")
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--output", type=Path, help="Save one JSON sample instead of printing it.")
    args = parser.parse_args()
    adb_bin = args.adb or shutil.which("adb") or Path.home() / "Library/Android/sdk/platform-tools/adb"
    sample = sample_thermal_zones(adb_bin, args.serial)
    content = json.dumps(sample, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)
        print(f"{sample['state']}: {len(sample['sensors'])} temperature zones saved to {args.output}")
    else:
        print(content, end="")
    return 0 if sample["state"] == "available" else 1


if __name__ == "__main__":
    raise SystemExit(main())
