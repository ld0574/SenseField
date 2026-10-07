#!/usr/bin/env python3
"""Run the actual Android UI width/font matrix on an explicitly selected test device.

Install the app and AndroidTest APK first. This runner never connects a device, installs
an app, publishes a release, reads credentials, or starts microphone/game services.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--adb-port', type=int, default=5037)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--only', help='One cell, e.g. 320-font200 or landscape-font200')
    parser.add_argument('--dialogs-only', action='store_true',
                        help='Rerun only the affected dialog layouts over every width/font cell')
    parser.add_argument('--pages-only', action='store_true',
                        help='Capture every page with explicit, verified top/bottom scroll positions')
    parser.add_argument('--audio-only', action='store_true',
                        help='Check audio tuning, group help, sound choices and picker over every cell')
    parser.add_argument('--guide-only', action='store_true',
                        help='Check the first-start guide and its fixed start action over every cell')
    args = parser.parse_args()
    adb = [args.adb, '-P', str(args.adb_port), '-s', args.serial]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)

    def run(*command, timeout=30):
        return subprocess.run(adb + list(command), text=True, capture_output=True,
                              check=True, timeout=timeout).stdout.strip()

    run('get-state')
    original_font = run('shell', 'settings', 'get', 'system', 'font_scale')
    animation_keys = ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')
    original_motion = {key: run('shell', 'settings', 'get', 'global', key) for key in animation_keys}
    original_size = re.search(r'Override size: (\S+)', run('shell', 'wm', 'size'))
    original_density = re.search(r'Override density: (\S+)', run('shell', 'wm', 'density'))
    cells = [(f'{width}-font{font}', width, 800, font / 100)
             for width in (320, 360, 432) for font in (100, 150, 200)]
    cells += [(f'landscape-font{font}', 800, 360, font / 100) for font in (100, 200)]
    if args.only:
        cells = [cell for cell in cells if cell[0] == args.only]
        if not cells:
            parser.error('Unknown cell')
    base = 'com.openkhub.sensefield.'
    classes = ','.join([
        base + 'UiQualityInstrumentedTest#everyPageHasUnclippedTextAndFitsTheActualWindow',
        base + 'UiQualityInstrumentedTest#dialogReflowKeepsOriginalCallbacksAndDismissal',
        base + 'UiQualityInstrumentedTest#realUpdateOfferAndUploadConsentRemainReadableAndCancelable',
        base + 'AssistantOverlayInstrumentedTest#largeTextControlsStayScrollableWithoutMovingTheDock',
        base + 'TouchTargetSpacingInstrumentedTest',
    ])
    if args.guide_only:
        classes = base + 'UiQualityInstrumentedTest#firstStartGuideKeepsSkipReachableAtTopAndBottom'
    elif args.audio_only:
        classes = ','.join([
            base + 'UiQualityInstrumentedTest#audioSettingsAndSoundPickerFitTheActualWindow',
            base + 'TouchTargetSpacingInstrumentedTest#cueSoundChoicesDoNotTouch',
            base + 'TouchTargetSpacingInstrumentedTest#tuningTestAndHapticButtonsNoLongerTouch',
        ])
    elif args.dialogs_only:
        classes = ','.join([
            base + 'UiQualityInstrumentedTest#dialogReflowKeepsOriginalCallbacksAndDismissal',
            base + 'UiQualityInstrumentedTest#realUpdateOfferAndUploadConsentRemainReadableAndCancelable',
        ])
    elif args.pages_only:
        classes = base + 'UiQualityInstrumentedTest#everyPageHasUnclippedTextAndFitsTheActualWindow'
    # A targeted repair rerun replaces only its cell; completed cells keep their
    # logs and actual screenshots. A full run always starts a fresh record.
    results = []
    record_file = out / 'matrix.json'
    if args.only and record_file.exists():
        results = [item for item in json.loads(record_file.read_text())
                   if item.get('cell') != args.only]
    try:
        for key in animation_keys:
            run('shell', 'settings', 'put', 'global', key, '0')
        run('shell', 'appops', 'set', 'com.openkhub.sensefield', 'SYSTEM_ALERT_WINDOW', 'allow')
        for name, width, height, font in cells:
            print(f'Running {name}: {width}x{height}dp, font {font:g}', flush=True)
            run('shell', 'wm', 'density', '480')
            run('shell', 'wm', 'size', f'{width * 3}x{height * 3}')
            run('shell', 'settings', 'put', 'system', 'font_scale', str(font))
            started = time.monotonic()
            result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r',
                '-e', 'class', classes, '-e', 'preview_phase', name,
                'com.openkhub.sensefield.test/androidx.test.runner.AndroidJUnitRunner'],
                capture_output=True, text=True, timeout=360)
            log = result.stdout + result.stderr
            (out / f'{name}.txt').write_text(log)
            match = re.search(r'OK \((\d+) tests?\)', log)
            passed = bool(match) and 'FAILURES!!!' not in log and result.returncode == 0
            record = {'cell': name, 'width_dp': width, 'height_dp': height, 'font_scale': font,
                      'passed': passed, 'tests': int(match.group(1)) if match else None,
                      'seconds': round(time.monotonic() - started, 2), 'returncode': result.returncode}
            results.append(record)
            (out / 'matrix.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
            print(json.dumps(record, ensure_ascii=False), flush=True)
            snapshots = out / 'screenshots'
            snapshots.mkdir(exist_ok=True)
            remote = f'/sdcard/Android/data/com.openkhub.sensefield/files/ui-0.4.3/{name}'
            if args.guide_only:
                # Other runs reuse these phase names. Pull only screenshots
                # produced by this targeted check, not older page captures.
                cell_snapshots = snapshots / name
                cell_snapshots.mkdir(exist_ok=True)
                for filename in ('first-start-guide.png', 'first-start-guide-bottom.png'):
                    run('pull', f'{remote}/{filename}',
                        str(cell_snapshots / filename), timeout=90)
            else:
                run('pull', remote, str(snapshots), timeout=90)
            if not passed:
                failures = re.findall(r'INSTRUMENTATION_STATUS: stack=([^\n]+)', log)
                print('\n'.join(failures), flush=True)
                return 1
        return 0
    finally:
        run('shell', 'wm', 'size', original_size.group(1) if original_size else 'reset')
        run('shell', 'wm', 'density', original_density.group(1) if original_density else 'reset')
        if original_font == 'null':
            run('shell', 'settings', 'delete', 'system', 'font_scale')
        else:
            run('shell', 'settings', 'put', 'system', 'font_scale', original_font)
        for key, value in original_motion.items():
            if value == 'null':
                run('shell', 'settings', 'delete', 'global', key)
            else:
                run('shell', 'settings', 'put', 'global', key, value)


if __name__ == '__main__':
    raise SystemExit(main())
