#!/usr/bin/env python3
"""Start the vision assistant directly behind the existing local HTTPS proxy."""
from __future__ import annotations

import argparse
import fcntl
import json
import os
from pathlib import Path
import runpy
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request


DEPLOY_DIR = Path(__file__).resolve().parent
ROOT = DEPLOY_DIR.parents[1]
RUNTIME_DIR = DEPLOY_DIR / "runtime"
PID_FILE = RUNTIME_DIR / "assistant.pid"
LOG_FILE = RUNTIME_DIR / "assistant.log"


def serve(args: argparse.Namespace, parser: argparse.ArgumentParser) -> None:
    # Read source from the unpacked directory, so starting does not depend on
    # the path of a previously installed systemd service or Python package.
    sys.path.insert(0, str(ROOT))
    sys.path.insert(0, str(ROOT / "python"))
    try:
        loader = runpy.run_path(str(DEPLOY_DIR / "serve.py"))
        loader["load_configuration"](args.config)
    except (OSError, ValueError):
        parser.error(f"无法读取有效的私有配置：{args.config}；请检查文件和当前用户的读取权限。")

    # Reuse credentials unchanged. Transport overrides exist only in this
    # process; the original JSON, tokens, and any old TLS files stay intact.
    for key in ("ASSISTANT_GATEWAY_TLS_CERT", "ASSISTANT_GATEWAY_TLS_KEY"):
        os.environ.pop(key, None)
    os.environ.update(ASSISTANT_GATEWAY_LOCAL_TLS_PROXY="1",
                      ASSISTANT_GATEWAY_HOST="127.0.0.1",
                      ASSISTANT_GATEWAY_PORT=str(args.port),
                      ASSISTANT_GATEWAY_ASR_BACKEND="disabled")
    # Official phone builds need no manually distributed connection code. Generic
    # CLI/private deployments remain authenticated unless explicitly opted in.
    os.environ.setdefault("ASSISTANT_GATEWAY_PUBLIC_ACCESS", "1")
    os.environ.setdefault("ASSISTANT_GATEWAY_PUBLIC_QUOTA_DB", str(RUNTIME_DIR / "public-quota.sqlite3"))
    from mapassist.assistant_gateway.cli import main as run_server
    sys.argv = ["听野助手", "--behind-local-proxy", "--host", "127.0.0.1",
                "--port", str(args.port)]
    print(f"正在启动听野助手：http://127.0.0.1:{args.port}；按Ctrl+C停止。", flush=True)
    run_server()


def process_signature(pid: int) -> str:
    """Check the process start time and command to avoid signalling a reused PID."""
    result = subprocess.run(["ps", "-p", str(pid), "-o", "lstart=", "-o", "command="],
                            capture_output=True, text=True, check=False)
    return result.stdout.strip() if result.returncode == 0 else ""


def running_process() -> dict | None:
    try:
        record = json.loads(PID_FILE.read_text())
        pid = record["pid"]
        if type(pid) is not int or pid <= 1:
            return None
        signature = process_signature(pid)
        if (signature and signature == record["signature"]
                and str(DEPLOY_DIR / "start.py") in signature
                and "--foreground" in signature):
            return record
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return None


def stop_process() -> None:
    record = running_process()
    if record is None:
        PID_FILE.unlink(missing_ok=True)
        print("听野助手未运行。")
        return
    try:
        os.kill(record["pid"], signal.SIGTERM)
    except ProcessLookupError:
        pass
    deadline = time.monotonic() + 10
    while running_process() is not None and time.monotonic() < deadline:
        time.sleep(0.1)
    # Recheck the identity before forcing a process that did not finish exiting.
    if running_process() is not None:
        try:
            os.kill(record["pid"], signal.SIGKILL)
        except ProcessLookupError:
            pass
        deadline = time.monotonic() + 2
        while running_process() is not None and time.monotonic() < deadline:
            time.sleep(0.1)
        if running_process() is not None:
            raise ValueError("进程尚未退出，请查看状态。")
    PID_FILE.unlink(missing_ok=True)
    print("听野助手已停止。")


def start_process(args: argparse.Namespace) -> None:
    record = running_process()
    if record is not None:
        print(f"听野助手已在后台运行，PID {record['pid']}；没有重复启动。")
        return
    PID_FILE.unlink(missing_ok=True)
    # Do not mistake an existing service's health endpoint for our new child.
    with socket.socket() as probe:
        probe.settimeout(0.5)
        if probe.connect_ex(("127.0.0.1", args.port)) == 0:
            raise ValueError(f"端口{args.port}已被其他进程占用；请先停止旧服务。")
    command = [sys.executable, "-u", str(DEPLOY_DIR / "start.py"), "--foreground",
               "--config", str(args.config.resolve()), "--port", str(args.port)]
    with LOG_FILE.open("ab") as log:
        os.chmod(LOG_FILE, 0o600)
        log.write(b"\n--- assistant start ---\n")
        log.flush()
        process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise ValueError(f"启动失败；查看日志：{LOG_FILE}")
            try:
                with opener.open(f"http://127.0.0.1:{args.port}/health", timeout=0.5) as response:
                    health = json.loads(response.read(65536))
                    ready = (response.status == 200 and isinstance(health, dict)
                             and health.get("mode") == "production"
                             and health.get("status") == "vision_only")
            except (urllib.error.URLError, TimeoutError, ValueError):
                ready = False
            if ready and process.poll() is None:
                signature = process_signature(process.pid)
                if not signature:
                    raise ValueError("无法确认后台进程状态。")
                record = {"pid": process.pid, "signature": signature}
                PID_FILE.write_text(json.dumps(record) + "\n")
                os.chmod(PID_FILE, 0o600)
                print(f"听野助手已在后台启动，PID {process.pid}；关闭终端可继续运行。")
                print(f"日志：{LOG_FILE}")
                return
            time.sleep(0.1)
        raise ValueError(f"启动超时；查看日志：{LOG_FILE}")
    except BaseException:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        PID_FILE.unlink(missing_ok=True)
        raise


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="听野助手：默认后台启动，支持停止和查看状态。")
    parser.add_argument("action", nargs="?", default="start",
                        choices=("start", "stop", "restart", "status", "logs"))
    parser.add_argument("--foreground", action="store_true", help="前台调试或面板守护")
    parser.add_argument("--config", type=Path,
                        default=Path("/etc/sensefield-assistant/gateway.json"))
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535:
        parser.error("端口必须为1至65535。")
    if args.foreground:
        if args.action != "start":
            parser.error("--foreground仅用于启动。")
        serve(args, parser)
        return
    if args.action == "logs":
        if not LOG_FILE.exists():
            print("暂无日志。")
            return
        subprocess.run(["tail", "-n", "80", str(LOG_FILE)], check=True)
        return
    if args.action == "status":
        record = running_process()
        print(f"听野助手正在后台运行，PID {record['pid']}。" if record else "听野助手未运行。")
        return
    os.umask(0o077)
    RUNTIME_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        with (RUNTIME_DIR / "assistant.lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ValueError("另一次启动或停止正在执行，请稍后再试。") from None
            if args.action in ("stop", "restart"):
                stop_process()
            if args.action in ("start", "restart"):
                start_process(args)
    except (OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
