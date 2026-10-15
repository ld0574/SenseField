#!/usr/bin/env python3
"""Record Android video, digital output and a Mac microphone, then mix locally."""

from __future__ import annotations

import argparse
from array import array
from datetime import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import select
import signal
import subprocess
import time
import wave


ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "output" / "video"
ACTIVE = BASE / "active-recording.json"
PINNED_SCRCPY = ROOT / "output" / "tools" / "scrcpy-3.3.4" / "scrcpy-macos-aarch64-v3.3.4" / "scrcpy"
MICROPHONE_CAPTURE_LATENCY_SECONDS = .5


def save(record):
    text = json.dumps(record, ensure_ascii=False, indent=2) + "\n"
    (Path(record["directory"]) / "session.json").write_text(text)
    ACTIVE.write_text(text)


def probe(path):
    return json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_format", "-show_streams",
        "-of", "json", str(path)], text=True))


def pcm_peak(path):
    peak = 0
    with wave.open(str(path), "rb") as audio:
        if audio.getsampwidth() != 2:
            raise RuntimeError("音源必须为 16 位 PCM")
        while data := audio.readframes(16384):
            samples = array("h", data)
            peak = max(peak, abs(min(samples)), abs(max(samples)))
    return 20 * math.log10(peak / 32768) if peak else None


def media_duration(info):
    value = info.get("format", {}).get("duration")
    if value is not None:
        return float(value)
    values = [float(stream["duration"]) for stream in info.get("streams", [])
              if stream.get("duration") is not None]
    if values:
        return max(values)
    raise RuntimeError("媒体文件缺少可用时长")


def start_log_monitor(adb):
    return subprocess.Popen(adb + ["logcat", "-v", "threadtime", "-T", "1",
                                   "-s", "MapAssistCapture:I"], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL)


def mix(record):
    directory = Path(record["directory"])
    capture = directory / "phone-capture.mkv"
    microphone = directory / "mac-microphone.wav"
    sources = {"capture": probe(capture), "microphone": probe(microphone)}
    if not any(s.get("codec_type") == "video" for s in sources["capture"]["streams"]):
        raise RuntimeError("手机画面文件没有视频轨")
    if not any(s.get("codec_type") == "audio" for s in sources["capture"]["streams"]):
        raise RuntimeError("手机录制文件没有内部音轨")
    if not any(s.get("codec_type") == "audio" for s in sources["microphone"]["streams"]):
        raise RuntimeError("电脑麦克风文件没有音轨")

    # Keep reviewable source tracks in addition to scrcpy's common-clock container.
    with (directory / "extract.log").open("w") as log:
        subprocess.run(["ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", str(capture),
                        "-map", "0:v:0", "-an", "-c:v", "copy", str(directory / "phone-screen.mp4")],
                       stdout=log, stderr=subprocess.STDOUT, check=True)
        subprocess.run(["ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", str(capture),
                        "-map", "0:a:0", "-vn", "-ar", "48000", "-ac", "2",
                        "-c:a", "pcm_s16le", str(directory / "phone-audio.wav")],
                       stdout=log, stderr=subprocess.STDOUT, check=True)

    extracted = {
        "phone_screen": probe(directory / "phone-screen.mp4"),
        "phone_audio": probe(directory / "phone-audio.wav"),
    }
    durations = {
        "capture": max(media_duration(extracted["phone_screen"]),
                       media_duration(extracted["phone_audio"])),
        "microphone": media_duration(sources["microphone"]),
    }
    # Both recorders are launched from the same monotonic clock. Align their
    # launch edges instead of their stop edges: scrcpy may need several seconds
    # to flush or be killed, so the capture file can outlive the microphone and
    # make common-stop alignment delay the user's voice by 10+ seconds.
    starts = record["launch_monotonic"]
    video_duration = durations["capture"]
    microphone_offset = (starts["microphone"] - starts["capture"]
                         + MICROPHONE_CAPTURE_LATENCY_SECONDS)
    trim = max(0, -microphone_offset)
    delay = round(max(0, microphone_offset) * 1000)
    filters = [
        "[0:a]volume=0.55,aresample=48000,apad[phone_audio]",
        f"[1:a]atrim=start={trim:.6f},asetpts=PTS-STARTPTS,"
        f"highpass=f=100,lowpass=f=10000,"
        f"agate=threshold=0.008:ratio=3:attack=10:release=250,"
        f"loudnorm=I=-13:TP=-1.5:LRA=7,aresample=48000,"
        f"adelay={delay}:all=1,apad[microphone]",
    ]
    filters.append("[phone_audio][microphone]amix=inputs=2:duration=longest:normalize=0,"
                   "alimiter=limit=0.891:level=false:latency=true[mixed]")
    output = directory / "demo.mp4"
    command = ["ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", str(capture),
               "-i", str(microphone), "-filter_complex", ";".join(filters),
               "-map", "0:v:0", "-map", "[mixed]",
               "-c:v", "copy", "-c:a", "aac", "-b:a", "224k", "-ar", "48000", "-ac", "2",
               "-t", str(video_duration), "-movflags", "+faststart", str(output)]
    with (directory / "mix.log").open("w") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    record.update(merged_file=str(output), source_durations_seconds=durations,
                  audio_offsets_seconds={"microphone": microphone_offset},
                  synchronization="phone_common_clock_and_microphone_launch_edge",
                  sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                  output_media=probe(output))
    record["audio_peak_dbfs"] = {key: pcm_peak(directory / file) for key, file in (
        ("phone_audio", "phone-audio.wav"), ("microphone", "mac-microphone.wav"))}
    record["phone_audio_signal_present"] = record["audio_peak_dbfs"]["phone_audio"] is not None
    record["microphone_signal_present"] = record["audio_peak_dbfs"]["microphone"] is not None


def run(args):
    BASE.mkdir(parents=True, exist_ok=True)
    if ACTIVE.exists():
        previous = json.loads(ACTIVE.read_text())
        if previous.get("status") in {"starting", "recording", "finalizing"}:
            raise RuntimeError("已有录制状态，请先停止或检查原录制进程")
    adb = ["adb", "-s", args.serial]
    subprocess.run(adb + ["get-state"], check=True, capture_output=True, timeout=10)
    if subprocess.run(adb + ["shell", "pidof", "screenrecord"], capture_output=True,
                      text=True, timeout=10).stdout.strip():
        raise RuntimeError("手机已有 screenrecord，保留该进程，未启动新录制")
    name = "sensefield-demo-" + datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    directory = BASE / name
    directory.mkdir()
    remote = f"/sdcard/Movies/{name}.mp4"
    record = dict(status="starting", directory=str(directory), device=args.serial,
                  controller_pid=os.getpid(), started_at=datetime.now().astimezone().isoformat(),
                  phone_audio_digital_capture=True, phone_audio_source="output",
                  phone_playback_preserved=False, computer_audio_playback=not args.no_monitor,
                  microphone_device_index=args.microphone, capture_method="scrcpy_common_clock_and_mac_microphone")
    record["scrcpy_program"] = args.scrcpy
    record["scrcpy_version"] = subprocess.check_output([args.scrcpy, "--version"], text=True).splitlines()[0]
    commands = {
        "microphone": ["ffmpeg", "-hide_banner", "-nostdin", "-nostats", "-f", "avfoundation",
                       "-i", f":{args.microphone}", "-vn", "-af", "aresample=async=1:first_pts=0",
                       "-ar", "48000", "-ac", "1", "-c:a", "pcm_s16le",
                       "-n", str(directory / "mac-microphone.wav")],
        "capture": [args.scrcpy, "-s", args.serial, "--no-video-playback", "--no-control",
                    "--no-clipboard-autosync", "--audio-source=output", "--audio-codec=opus",
                    "--video-codec=h264", "--video-bit-rate=6M", "--max-size=1920",
                    "--require-audio", f"--time-limit={args.duration + 30}",
                    "--record", str(directory / "phone-capture.mkv")],
    }
    if args.no_monitor:
        commands["capture"].append("--no-audio-playback")
    processes, handles = {}, []
    record["launch_monotonic"] = {}
    save(record)
    monitor = None
    try:
        for key in ("microphone", "capture"):
            log = (directory / f"{key}.log").open("wb", buffering=0)
            handles.append(log)
            record["launch_monotonic"][key] = time.monotonic()
            processes[key] = subprocess.Popen(commands[key], stdin=subprocess.DEVNULL,
                                              stdout=log, stderr=subprocess.STDOUT,
                                              start_new_session=True)
        time.sleep(2)
        record["pids"] = {key: process.pid for key, process in processes.items()}
        # Require both sources to write real data; container headers alone are insufficient.
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            exited = {key: process.poll() for key, process in processes.items()
                      if process.poll() is not None}
            if exited:
                raise RuntimeError(f"录制进程在准备阶段退出：{exited}")
            if all((directory / file).exists() and (directory / file).stat().st_size > 4096
                   for file in ("phone-capture.mkv", "mac-microphone.wav")):
                break
            time.sleep(.1)
        else:
            raise RuntimeError("内部音轨或麦克风没有写入 PCM 数据")
        monitor = start_log_monitor(adb)
        record["log_monitor_reconnects"] = 0
        record["status"] = "recording"
        save(record)
        print(f"录制就绪：{directory}\n手机声音已转到电脑；使用电脑耳机可避免麦克风再次收进播报。", flush=True)
        stop = directory / "stop-requested"
        deadline = time.monotonic() + args.duration
        record["reason"] = "duration_limit"
        with (directory / "capture-monitor.log").open("ab", buffering=0) as log:
            while time.monotonic() < deadline:
                if stop.exists():
                    record["reason"] = "user_requested_stop"
                    break
                if any(process.poll() is not None for process in processes.values()):
                    record["reason"] = "recording_process_exited"
                    break
                ready, _, _ = select.select([monitor.stdout], [], [], .5)
                if ready:
                    chunk = os.read(monitor.stdout.fileno(), 65536)
                    if not chunk:
                        monitor.wait()
                        record["log_monitor_reconnects"] += 1
                        log.write((f"\n# logcat disconnected; reconnect "
                                   f"{record['log_monitor_reconnects']}\n").encode())
                        save(record)
                        # ADB logcat is diagnostic only. Reconnect it without
                        # sacrificing the phone and microphone recording.
                        time.sleep(1)
                        monitor = start_log_monitor(adb)
                        continue
                    log.write(chunk)
                    # A recoverable frame starvation is logged and does not stop recording.
                    if re.search(rb"CaptureHealth[^\n]*state=(?:FAILED|REVOKED)\b", chunk):
                        record["reason"] = "assistant_capture_failed"
                        break
    except KeyboardInterrupt:
        record["reason"] = "user_requested_stop"
    except Exception as error:
        record["error"] = str(error)
        record["reason"] = "recording_error"
    finally:
        record["status"] = "finalizing"
        record["stop_monotonic"] = {}
        save(record)
        for key, process in processes.items():
            record["stop_monotonic"][key] = time.monotonic()
            if process.poll() is None:
                process.send_signal(signal.SIGINT)
        for process in processes.values():
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        record["exit_codes"] = {key: process.returncode for key, process in processes.items()}
        if monitor:
            monitor.terminate()
            try:
                monitor.wait(timeout=3)
            except subprocess.TimeoutExpired:
                monitor.kill()
                monitor.wait()
        for handle in handles:
            handle.close()
        try:
            mix(record)
            record["status"] = "stopped" if "error" not in record else "stopped_with_error"
            if not record["phone_audio_signal_present"]:
                record["status"] = "stopped_phone_audio_silent"
                record["error"] = "手机内部音轨为静音，混音视频不作为完整 Demo 交付；原始音源已保留。"
            elif not record["microphone_signal_present"]:
                record["status"] = "stopped_microphone_silent"
                record["error"] = "电脑麦克风音轨为静音，混音视频不作为完整 Demo 交付；原始音源已保留。"
        except Exception as error:
            record["finalization_error"] = str(error)
            record["status"] = "stopped_sources_preserved"
        save(record)
    print(json.dumps({key: record.get(key) for key in (
        "status", "directory", "merged_file", "reason", "error", "finalization_error")},
        ensure_ascii=False, indent=2), flush=True)
    return 0 if record["status"] == "stopped" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stop", action="store_true", help="停止已登记的录制并自动混音")
    parser.add_argument("--serial", help="无线 ADB 地址或设备序列号")
    parser.add_argument("--microphone", type=int, default=1, help="Mac AVFoundation 麦克风索引")
    parser.add_argument("--scrcpy", default=str(PINNED_SCRCPY) if PINNED_SCRCPY.exists() else "scrcpy",
                        help="scrcpy 可执行文件；本机优先使用独立保存的 3.3.4")
    parser.add_argument("--duration", type=int, default=1800, help="自动停止上限，秒")
    parser.add_argument("--size", default="1920x864", help="原生录屏尺寸；游戏横屏默认 1920x864")
    parser.add_argument("--no-monitor", action="store_true", help="关闭电脑回放；手机声音仍转入录音")
    args = parser.parse_args()
    if args.stop:
        record = json.loads(ACTIVE.read_text())
        if record.get("status") not in {"starting", "recording"}:
            parser.error("当前没有进行中的录制")
        (Path(record["directory"]) / "stop-requested").touch()
        print("停止请求已提交；等待原进程完成拉取和混音。")
        return 0
    if not args.serial or not 1 <= args.duration <= 3600:
        parser.error("需要 --serial，且 --duration 必须为 1～3600 秒")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
