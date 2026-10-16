#!/usr/bin/env python3
"""Capture independent Android video/audio and Mac speech; produce a 30 fps demo.

The phone's CV ImageReader and microphone are not used by this recorder. Raw
sources stay local. An encode at 30 fps alone is never a source quality pass.
"""
from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import signal
import shutil
import subprocess
import sys
import time
import uuid
import fcntl

import os
from zoneinfo import ZoneInfo
ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "deps/demo-recorder"
ADB = shutil.which("adb") or str(Path.home() / "Library/Android/sdk/platform-tools/adb")

def probe(path):
    return json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)
    ]))

def pcm_peak(path):
    import array
    import wave
    peak = 0
    with wave.open(str(path), "rb") as sound:
        if sound.getsampwidth() != 2:
            raise RuntimeError("Expected 16-bit PCM audio")
        while True:
            block = sound.readframes(48000)
            if not block:
                break
            samples = array.array("h", block)
            peak = max(peak, max((abs(v) for v in samples), default=0))
    return 20 * math.log10(peak / 32768) if peak else None


BASE = ROOT / "output/video"
ACTIVE = RUNTIME / "active-session.json"
SCRCPY = RUNTIME / "scrcpy-3.3.4/scrcpy"
MIC_MODEL = RUNTIME / "bd.rnnn"
MIC_MODEL_SHA256 = "ae3f7411e1e6a884f839a4a145c394408398f09854dbc1216ee02faafc98a17b"
MIC_FILTER = (
    f"highpass=f=100,arnndn=m='{MIC_MODEL}':mix=1,lowpass=f=12000"
)
# RNNoise adds one 480-sample frame at 48 kHz. Pad its input to flush the
# tail, then remove that processing delay without moving the user's speech.
MIC_DENOISE_DELAY = .010


def denoise_microphone(source, directory):
    if not MIC_MODEL.is_file() or hashlib.sha256(MIC_MODEL.read_bytes()).hexdigest() != MIC_MODEL_SHA256:
        raise RuntimeError("缺少本地人声降噪模型，保留原始音轨，请先补齐 bd.rnnn")
    duration = float(probe(source)["format"]["duration"])
    output = directory / "mac-microphone-denoised.wav"
    temporary = directory / "mac-microphone-denoised.partial.wav"
    filters = (f"apad=pad_dur={MIC_DENOISE_DELAY},{MIC_FILTER},"
               f"atrim=start={MIC_DENOISE_DELAY}:duration={duration},asetpts=PTS-STARTPTS")
    with (directory / "denoise.log").open("w") as log:
        subprocess.run(["ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", str(source),
                        "-af", filters, "-ar", "48000", "-ac", "1", "-c:a", "pcm_s24le",
                        str(temporary)], stdout=log, stderr=subprocess.STDOUT, check=True)
    temporary.replace(output)
    return output, {"method": "RNNoise", "model": str(MIC_MODEL),
                    "model_sha256": hashlib.sha256(MIC_MODEL.read_bytes()).hexdigest(),
                    "filter": MIC_FILTER, "processing_delay_compensated_seconds": MIC_DENOISE_DELAY,
                    "entire_microphone_track_preserved": True, "speech_segment_cutting": False,
                    "phone_audio_denoised": False, "file": str(output)}


def atomic_json(path, record):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)


def save(record):
    directory = Path(record["directory"])
    # Keep control state outside disposable output, including when its session
    # directory was removed. Never silently recreate a deleted recording.
    if directory.is_dir():
        atomic_json(directory / "session.json", record)
    atomic_json(ACTIVE, record)


def choose_device(device_output, requested=None, allow_wireless=False):
    devices = []
    for line in device_output.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            wireless = ":" in fields[0] or "_adb-tls-" in fields[0]
            devices.append({"serial": fields[0], "wireless": wireless})
    candidates = [d for d in devices if (not requested or d["serial"] == requested)
                  and (allow_wireless or not d["wireless"])]
    if len(candidates) != 1:
        raise RuntimeError("需要一台已授权的 USB 手机；插线并开启 USB 调试，多台时指定 --serial。")
    return candidates[0]


def microphone_devices(listing):
    if "AVFoundation audio devices:" not in listing:
        raise RuntimeError("无法读取电脑麦克风列表")
    audio = listing.split("AVFoundation audio devices:", 1)[1]
    return [(int(index), name.strip()) for index, name in
            re.findall(r"\]\s+\[(\d+)\]\s+([^\r\n]+)", audio)]


def select_microphone(devices, requested):
    exact = [(i, n) for i, n in devices if n == requested]
    matches = exact or [(i, n) for i, n in devices if requested.casefold() in n.casefold()]
    if len(matches) != 1:
        raise RuntimeError("麦克风名称未唯一匹配，请用 --microphone-name 指定设备，避免录到虚拟麦克风。")
    return matches[0]


def default_audio_output(audio_info):
    outputs = [d for group in audio_info.get("SPAudioDataType", [])
               for d in group.get("_items", [])
               if d.get("coreaudio_default_audio_output_device") == "spaudio_yes"]
    if len(outputs) != 1:
        raise RuntimeError("无法确认电脑声音输出；请把游戏和播报切到电脑耳机。")
    output = outputs[0]
    name = output.get("_name", "")
    route = name + " " + output.get("coreaudio_output_source", "")
    speaker = bool(re.search(r"扬声器|speaker|HDMI|DisplayPort|显示器", route, re.I))
    return {"name": name, "speaker_detected": speaker}


def readiness(args):
    report = {"ready": True, "checks": [], "checked_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat()}
    def check(name, work):
        try:
            value = work()
            report["checks"].append({"name": name, "passed": True, "details": value})
            return value
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            report["ready"] = False
            report["checks"].append({"name": name, "passed": False, "details": str(error)})
            return None
    def dependencies():
        for name in ("ffmpeg", "ffprobe"):
            if not shutil.which(name):
                raise RuntimeError(f"缺少 {name}")
        if not Path(ADB).is_file() or not SCRCPY.is_file():
            raise RuntimeError("缺少 adb 或固定版本 scrcpy；见录制说明中的依赖准备。")
        version = subprocess.check_output([str(SCRCPY), "--version"], text=True, timeout=10).splitlines()[0]
        if not version.startswith("scrcpy 3.3.4"):
            raise RuntimeError("录制只使用已固定的 scrcpy 3.3.4")
        if not MIC_MODEL.is_file() or hashlib.sha256(MIC_MODEL.read_bytes()).hexdigest() != MIC_MODEL_SHA256:
            raise RuntimeError("本地降噪模型缺失或校验失败")
        if shutil.disk_usage(ROOT).free < 10 * 1024**3:
            raise RuntimeError("剩余磁盘不足 10GB，请先腾出空间")
        return {"scrcpy": version, "denoiser_sha256": MIC_MODEL_SHA256}
    check("dependencies", dependencies)
    device = check("usb_phone", lambda: choose_device(subprocess.check_output(
        [ADB, "devices", "-l"], text=True, timeout=10), args.serial, args.allow_wireless))
    listing = check("microphone_list", lambda: subprocess.run([
        "ffmpeg", "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""
    ], capture_output=True, text=True, timeout=15).stderr)
    if listing is not None:
        # The device-list command exits nonzero by design; only its audio section
        # is interpreted. Do not expose camera names or require camera access.
        report["checks"][-1]["details"] = "仅枚举设备，没有录制麦克风"
        mic = check("microphone", lambda: select_microphone(microphone_devices(listing), args.microphone_name))
        if mic is not None:
            report["microphone_index"], report["microphone_name"] = mic
    def output_route():
        route = default_audio_output(json.loads(subprocess.check_output(
            ["system_profiler", "SPAudioDataType", "-json"], text=True, timeout=20)))
        if route["speaker_detected"]:
            raise RuntimeError(f"当前输出为 {route['name']}；请接电脑耳机，避免游戏声再次进入麦克风。")
        return route
    if not args.no_monitor:
        check("headphone_route", output_route)
    if device is not None:
        report["device"] = device
        def landscape():
            data = subprocess.check_output([ADB, "-s", device["serial"], "shell", "dumpsys", "display"], text=True, timeout=10)
            line = next((s for s in data.splitlines() if "mOverrideDisplayInfo=" in s and "displayId 0" in s), "")
            dimensions = re.search(r"\breal (\d+) x (\d+)", line)
            if not dimensions or int(dimensions[1]) <= int(dimensions[2]):
                raise RuntimeError("手机尚未横屏；进入游戏横屏页面后即可，不用开始对局。")
            return [int(dimensions[1]), int(dimensions[2])]
        report["landscape_size"] = check("landscape", landscape)
    atomic_json(RUNTIME / "readiness.json", report)
    return report


def disconnect_owned_server(process, serial):
    """Close only this capture's shell server, letting scrcpy finalize its muxer.

    On this macOS no-window configuration SIGINT alone leaves the recorder
    waiting. Its own remote socket closing is a normal scrcpy shutdown path.
    Never terminate the Android assistant or another capture's server.
    """
    host = subprocess.check_output(["ps", "-A", "-o", "pgid=,command="], text=True, timeout=3)
    sessions = set()
    for line in host.splitlines():
        group, _, command = line.strip().partition(" ")
        if group == str(process.pid) and "com.genymobile.scrcpy.Server" in command:
            match = re.search(r"\bscid=([0-9a-f]+)\b", command)
            if match: sessions.add(match.group(1))
    if len(sessions) != 1:
        raise RuntimeError("无法唯一确认本次录制的服务，拒绝结束其他会话")
    scid = sessions.pop()
    remote = subprocess.check_output([ADB, "-s", serial, "shell", "ps", "-A", "-o", "PID,ARGS"],
                                     text=True, timeout=3)
    candidates = []
    for line in remote.splitlines():
        pid, _, command = line.strip().partition(" ")
        # Match the Java server, not its `sh -c ...` launcher containing the
        # same arguments, or scrcpy's separate CleanUp process.
        if pid.isdecimal() and re.match(r"app_process(?:32|64)?\s+/\s+com\.genymobile\.scrcpy\.Server\b",
                                       command.lstrip()) \
                and re.search(r"\bscid=" + re.escape(scid) + r"\b", command):
            candidates.append(pid)
    if len(candidates) != 1:
        raise RuntimeError("无法唯一确认手机录制进程，拒绝结束其他会话")
    subprocess.run([ADB, "-s", serial, "shell", "kill", "-TERM", candidates[0]], check=True, timeout=3)
    return {"method": "owned_server_disconnect", "scid": scid, "server_pid": int(candidates[0])}


def quality(path):
    raw = subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_packets",
        "-show_entries", "packet=pts_time", "-of", "json", str(path)])
    pts = [float(p["pts_time"]) for p in json.loads(raw)["packets"]]
    gaps = [b - a for a, b in zip(pts, pts[1:])]
    if not gaps or any(g <= 0 for g in gaps):
        raise RuntimeError("源视频时间戳缺失或不递增")
    duration = pts[-1] - pts[0]
    ordered = sorted(gaps)
    # This whole-file check deliberately rejects static/paused captures as a
    # smoothness proof. Motion fixture or gameplay must be assessed separately.
    return {
        "packets": len(pts), "span_seconds": duration,
        "average_source_fps": (len(pts) - 1) / duration,
        "p95_gap_ms": ordered[min(len(ordered) - 1, math.ceil(len(ordered) * .95) - 1)] * 1000,
        "max_gap_ms": max(gaps) * 1000,
        "gaps_over_50ms": sum(g > .0505 for g in gaps),
        "gaps_over_100ms": sum(g > .1005 for g in gaps),
        "continuous_motion_cadence_passed": (len(pts) - 1) / duration >= 29
            and max(gaps) <= .1005,
        "unique_frames_verified": False,
    }


def finalize(record):
    directory = Path(record["directory"])
    capture, mic = directory / "phone-capture.mkv", directory / "mac-microphone.wav"
    sources = {"phone": probe(capture), "microphone": probe(mic)}
    if not any(s["codec_type"] == "video" for s in sources["phone"]["streams"]):
        raise RuntimeError("手机录像没有视频轨")
    if not any(s["codec_type"] == "audio" for s in sources["phone"]["streams"]):
        raise RuntimeError("手机录像没有设备音轨")
    record["source_quality"] = quality(capture)
    with (directory / "extract.log").open("w") as log:
        subprocess.run(["ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", str(capture),
                        "-vn", "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "2",
                        str(directory / "phone-audio.wav")], stdout=log, stderr=subprocess.STDOUT, check=True)
    peaks = {"phone": pcm_peak(directory / "phone-audio.wav"), "microphone": pcm_peak(mic)}
    if any(value is None for value in peaks.values()):
        raise RuntimeError("设备声音或电脑麦克风全静音，保留原始音源")
    mic, record["microphone_processing"] = denoise_microphone(mic, directory)
    # File creation follows input initialization, so it provides a closer
    # startup estimate than process launch + a fixed arbitrary half-second.
    # It still includes muxer/buffering delay and is explicitly uncalibrated.
    observed = record.get("first_file_monotonic", {})
    clocks = observed if {"microphone", "capture"} <= observed.keys() else record["launch_monotonic"]
    offset = clocks["microphone"] - clocks["capture"]
    if record.get("microphone_offset_override_ms") is not None:
        offset = record["microphone_offset_override_ms"] / 1000
    record["microphone_offset_seconds_estimate"] = offset
    record["microphone_alignment_method"] = "explicit_offset" if record.get("microphone_offset_override_ms") is not None else "first_file_creation_estimate"
    record["quantitative_av_sync_verified"] = False
    duration = float(sources["phone"]["format"]["duration"])
    # Preserve stereo digital game cues. Only the Mac microphone is denoised.
    # Fixed moderate gain avoids boosting silence through whole-track loudnorm.
    video_filter = "hflip,vflip,fps=30" if record.get("rotate_video_clockwise_degrees") == 180 else "fps=30"
    filters = (
        f"[0:v]{video_filter}[v];[0:a]asplit=2[phone_raw][phone_input];"
        "[phone_raw]apad[phone_track];[phone_input]volume=0.65,apad[phone];"
        f"[1:a]atrim=start={max(0, -offset):.6f},asetpts=PTS-STARTPTS,"
        f"adelay={round(max(0, offset) * 1000)}:all=1,apad,asplit=2[mic_track][mic_input];"
        "[mic_input]volume=1.5[mic];"
        "[phone][mic]amix=inputs=2:duration=longest:normalize=0,"
        "alimiter=limit=0.891:level=false:latency=true[mixed]"
    )
    output = directory / record.get("output_filename", "demo-landscape-30fps.mp4")
    temporary = output.with_name(output.stem + ".partial.mp4")
    with (directory / "encode.log").open("w") as log:
        subprocess.run([
            "ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", str(capture), "-i", str(mic),
            "-filter_complex", filters, "-map", "[v]", "-map", "[mixed]",
            "-map", "[phone_track]", "-map", "[mic_track]",
            "-c:v", "h264_videotoolbox", "-b:v", "12M", "-pix_fmt", "yuv420p",
            "-fps_mode:v", "cfr", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
            "-metadata:s:a:0", "title=混合声音", "-metadata:s:a:1", "title=手机原声",
            "-metadata:s:a:2", "title=电脑人声", "-disposition:a:0", "default",
            "-metadata:s:a:0", "handler_name=混合声音", "-metadata:s:a:1", "handler_name=手机原声",
            "-metadata:s:a:2", "handler_name=电脑人声",
            "-disposition:a:1", "0", "-disposition:a:2", "0",
            "-t", str(duration), "-movflags", "+faststart", str(temporary),
        ], stdout=log, stderr=subprocess.STDOUT, check=True)
    with (directory / "decode.log").open("w") as log:
        subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-xerror", "-i", str(temporary),
                        "-map", "0:v", "-map", "0:a", "-f", "null", "-"], stdout=log, stderr=subprocess.STDOUT, check=True)
    media = probe(temporary)
    video = next(s for s in media["streams"] if s["codec_type"] == "video")
    source_video = next(s for s in sources["phone"]["streams"] if s["codec_type"] == "video")
    if (video["width"], video["height"]) != (source_video["width"], source_video["height"]) or video["avg_frame_rate"] != "30/1":
        raise RuntimeError("成片尺寸或30帧检查失败，保留原轨与临时成片")
    if len([s for s in media["streams"] if s["codec_type"] == "audio"]) != 3:
        raise RuntimeError("成片缺少混音、手机原声或独立人声轨")
    temporary.replace(output)
    record.update(merged_file=str(output), source_media=sources, output_media=probe(output),
                  audio_peak_dbfs=peaks, full_decode_passed=True,
                  sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                  recording_quality_passed=False,
                  quality_note="已核验原尺寸、30fps、三条音轨与完整解码；源运动连续性、听感及音画同步仍须短录验收。")


def run(args):
    if not MIC_MODEL.is_file():
        raise RuntimeError("缺少本地人声降噪模型 bd.rnnn，尚未开始录制")
    BASE.mkdir(parents=True, exist_ok=True)
    if ACTIVE.exists():
        previous = recording_status()
        if previous.get("supervisor_alive") or previous.get("owned_capture_processes_alive"):
            raise RuntimeError("上一录制仍在运行，请先 stop")
    subprocess.run([ADB, "-s", args.serial, "get-state"], capture_output=True, check=True, timeout=10)
    directory = BASE / ("sensefield-demo-" + datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6])
    directory.mkdir()
    record = dict(directory=str(directory), device=args.serial, status="starting", microphone="Mac", supervisor_pid=os.getpid(), started_at=datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
                  capture_fps_limit=30, output_fps=30, capture_resolution="native",
                  allow_resolution_downsize=False, video_bit_rate="12M", phone_audio_source="output",
                  capture_orientation="landscape_locked" if args.landscape else "device",
                  phone_playback_preserved=False, computer_audio_playback=not args.no_monitor,
                  scrcpy_version=subprocess.check_output([str(SCRCPY), "--version"], text=True).splitlines()[0],
                  launch_monotonic={}, first_file_monotonic={}, log_monitor=True,
                  microphone_name=args.microphone_name,
                  microphone_offset_override_ms=args.mic_offset_ms,
                  readiness=args.readiness)
    commands = {
        "microphone": ["ffmpeg", "-hide_banner", "-nostdin", "-nostats", "-f", "avfoundation",
                       "-i", f":{args.microphone}", "-vn", "-af", "aresample=async=1:first_pts=0",
                       "-ar", "48000", "-ac", "1", "-c:a", "pcm_s16le",
                       "-n", str(directory / "mac-microphone.wav")],
        "capture": [str(SCRCPY), "-s", args.serial, "--no-video-playback", "--no-control",
                    "--no-clipboard-autosync", "--audio-source=output", "--audio-codec=opus",
                    "--video-bit-rate=12M", "--no-downsize-on-error", "--require-audio",
                    "--max-fps=30",
                    f"--time-limit={args.duration}", "--record", str(directory / "phone-capture.mkv")],
    }
    if args.landscape:
        # Lock the current, upright landscape orientation. A fixed rotation
        # relative to the phone's natural orientation may invert the game.
        display_info = subprocess.check_output(
            [ADB, "-s", args.serial, "shell", "dumpsys", "display"],
            text=True, timeout=10)
        default_display = next((line for line in display_info.splitlines()
                                if "mOverrideDisplayInfo=" in line and "displayId 0" in line), "")
        dimensions = re.search(r"\breal (\d+) x (\d+)", default_display)
        if not dimensions or int(dimensions.group(1)) <= int(dimensions.group(2)):
            raise RuntimeError("请先让手机游戏处于横屏，再开始录制，以便锁定正确方向")
        record["capture_orientation"] = "initial_landscape_locked"
        record["initial_display_size"] = [int(dimensions.group(1)), int(dimensions.group(2))]
        commands["capture"].append("--capture-orientation=@")
    if args.no_monitor:
        commands["capture"].append("--no-audio-playback")
    # Noninteractive launchers may inherit SIGINT=SIG_IGN. Reset before exec so
    # scrcpy can drain/mux on user stop instead of running until a forced kill.
    signal.signal(signal.SIGINT, signal.default_int_handler)
    signal.signal(signal.SIGTERM, signal.default_int_handler)
    processes, logs, monitor = {}, [], None
    save(record)
    try:
        for name, command in commands.items():
            handle = (directory / f"{name}.log").open("wb", buffering=0); logs.append(handle)
            record["launch_monotonic"][name] = time.monotonic()
            processes[name] = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=handle,
                                               stderr=subprocess.STDOUT, start_new_session=True)
        handle = (directory / "assistant-logcat.txt").open("wb", buffering=0); logs.append(handle)
        monitor = subprocess.Popen([ADB, "-s", args.serial, "logcat", "-v", "threadtime", "-T", "1",
                                    "-s", "MapAssistCapture:I", "MapAssistAudio:I", "AndroidRuntime:E"], stdout=handle, stderr=subprocess.DEVNULL)
        record.update(pids={name: p.pid for name, p in processes.items()}); save(record)
        print(f"正在启动，等待画面与麦克风写入：{directory}", flush=True)
        deadline = time.monotonic() + args.duration + 10
        startup_deadline = time.monotonic() + 20
        paths = {"capture": directory / "phone-capture.mkv", "microphone": directory / "mac-microphone.wav"}
        heartbeat_at = 0
        while processes["capture"].poll() is None:
            if (directory / "stop-requested").exists():
                record["reason"] = "user_stop"; break
            if processes["microphone"].poll() is not None:
                raise RuntimeError("电脑麦克风录制提前退出")
            if not directory.is_dir():
                raise RuntimeError("录制目录被移走或删除，停止采集；不会标记为成功")
            sizes = {}
            for name, path in paths.items():
                if path.exists():
                    sizes[name] = path.stat().st_size
                    record["first_file_monotonic"].setdefault(name, time.monotonic())
            if record["status"] == "starting" and all(sizes.get(name, 0) > 128 for name in paths):
                record["status"] = "recording"
                print("两路原始素材已写入，正在录制。结束后运行 stop。", flush=True)
                save(record)
            if record["status"] == "starting" and time.monotonic() >= startup_deadline:
                raise RuntimeError("启动20秒仍缺少手机画面/内部音频或麦克风文件")
            if time.monotonic() >= heartbeat_at:
                record.update(last_heartbeat=datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(), source_file_bytes=sizes)
                save(record)
                heartbeat_at = time.monotonic() + 5
            if time.monotonic() >= deadline:
                raise RuntimeError("录制未按上限自动停止")
            time.sleep(.2)
        record.setdefault("reason", "duration_limit")
    except KeyboardInterrupt:
        record["reason"] = "user_stop"
    except Exception as error:
        record.update(reason="recording_error", error=str(error))
    finally:
        record["status"] = "finalizing"; save(record)
        capture = processes.get("capture")
        if capture is not None and capture.poll() is None:
            try:
                record["capture_stop"] = disconnect_owned_server(capture, args.serial)
            except Exception as error:
                record["capture_stop_error"] = str(error)
                capture.send_signal(signal.SIGINT)
        microphone = processes.get("microphone")
        if microphone is not None and microphone.poll() is None:
            microphone.send_signal(signal.SIGINT)
        for name, process in processes.items():
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                # Only groups created by this supervisor; never kill the app,
                # another scrcpy session, or every ffmpeg process on the host.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(); record["error"] = f"{name}_forced_stop"
        if monitor is not None:
            monitor.terminate()
            try: monitor.wait(timeout=3)
            except subprocess.TimeoutExpired: monitor.kill(); monitor.wait()
        for log in logs: log.close()
        record["exit_codes"] = {name: p.returncode for name, p in processes.items()}
        try:
            if record.get("error"): raise RuntimeError(record["error"])
            expected_capture_codes = {0, 2} if record.get("capture_stop") else {0}
            if processes["capture"].returncode not in expected_capture_codes:
                raise RuntimeError("手机录制进程异常退出")
            finalize(record); record["status"] = "stopped_pending_quality_review"
        except Exception as error:
            record.update(status="failed_sources_preserved", finalization_error=str(error))
        save(record)
    print(json.dumps({k: record.get(k) for k in ("status", "merged_file", "exit_codes", "error", "finalization_error")}, ensure_ascii=False), flush=True)
    return 1 if record["status"] == "failed_sources_preserved" else 0


def process_command(pid):
    if not isinstance(pid, int) or pid <= 1:
        return ""
    result = subprocess.run(["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True, timeout=3)
    return result.stdout.strip()


def recording_status():
    if not ACTIVE.exists():
        return {"status": "idle"}
    record = json.loads(ACTIVE.read_text())
    directory = Path(record["directory"])
    record["sources_directory_exists"] = directory.is_dir()
    record["supervisor_alive"] = str(Path(__file__).name) in process_command(record.get("supervisor_pid"))
    record["owned_capture_processes_alive"] = {
        name: pid for name, pid in record.get("pids", {}).items()
        if str(directory) in process_command(pid)
    }
    if not directory.is_dir():
        record["status"] = "sources_missing"
    elif record.get("status") in {"starting", "recording", "finalizing"} and not record["supervisor_alive"]:
        record["status"] = "interrupted_sources_preserved"
    return record


def main():
    parser = argparse.ArgumentParser(description="USB手机画面/原声＋电脑麦克风；横屏原分辨率30fps。默认不启动录制。")
    parser.add_argument("command", choices=["check", "start", "stop", "status", "recover"])
    parser.add_argument("--serial")
    parser.add_argument("--microphone-name", default="MacBook Pro麦克风")
    parser.add_argument("--duration", type=int, default=1800)
    parser.add_argument("--mic-offset-ms", type=float, help="仅用于已校准的麦克风相对手机音轨偏移；正值延后麦克风")
    parser.add_argument("--allow-wireless", action="store_true", help="明确启用无线备用模式；常规录制使用USB")
    parser.add_argument("--no-monitor", action="store_true", help="不在电脑播放手机声音；仍保存手机内部音轨")
    parser.add_argument("--directory", type=Path, help="recover 时的已有原始素材目录")
    args = parser.parse_args()
    if args.command == "status":
        print(json.dumps(recording_status(), ensure_ascii=False, indent=2)); return 0
    if args.command == "stop":
        record = recording_status()
        if not record.get("supervisor_alive") or record.get("status") not in {"starting", "recording"}:
            raise RuntimeError("当前没有可停止的录制；status 可检查中断状态，recover 可处理保留原轨。")
        (Path(record["directory"]) / "stop-requested").touch()
        print("已请求停止；等待 status 变为 stopped_pending_quality_review，期间保留原轨。")
        return 0
    if args.command == "check":
        report = readiness(args)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if report["ready"] else 2
    if not 1 <= args.duration <= 3600:
        parser.error("录制上限为1至3600秒")
    RUNTIME.mkdir(parents=True, exist_ok=True)
    # One supervisor, including during finalization. A dead process releases
    # this OS lock, so stale JSON cannot permanently block the next session.
    with (RUNTIME / "session.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("已有录制或合成进程，请用 status 查看")
        if args.command == "recover":
            if args.directory:
                directory = args.directory.resolve()
            elif ACTIVE.exists():
                directory = Path(json.loads(ACTIVE.read_text())["directory"])
            else:
                raise RuntimeError("请指定已有素材 --directory")
            if not directory.is_dir():
                raise RuntimeError("原始素材目录已不存在；元数据和脚本不能重建删除的录音录像。")
            record = json.loads((directory / "session.json").read_text())
            if any(str(directory) in process_command(pid) for pid in record.get("pids", {}).values()):
                raise RuntimeError("该目录还有采集进程，请先停止采集")
            record["output_filename"] = "demo-recovered-30fps.mp4"
            if args.mic_offset_ms is not None:
                record["microphone_offset_override_ms"] = args.mic_offset_ms
            finalize(record)
            record["status"] = "stopped_pending_quality_review"
            save(record)
            print(json.dumps({"status": record["status"], "file": record["merged_file"]}, ensure_ascii=False))
            return 0
        args.readiness = readiness(args)
        if not args.readiness["ready"]:
            print(json.dumps(args.readiness, ensure_ascii=False, indent=2))
            return 2
        args.serial = args.readiness["device"]["serial"]
        args.microphone = args.readiness["microphone_index"]
        args.microphone_name = args.readiness["microphone_name"]
        args.landscape = True
        return run(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"错误：{error}", file=sys.stderr)
        raise SystemExit(2)
