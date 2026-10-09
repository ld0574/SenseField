"""Operator-side collection must never interrupt active assistance or confuse Wi-Fi loss with stop."""

import json
from pathlib import Path
from subprocess import CompletedProcess
from types import SimpleNamespace

import pytest

from scripts import pull_android_diagnostics as diagnostics


ACTIVE = " * ServiceRecord{42 u0 com.openkhub.sensefield/.Match3LiveService}"


def arguments(tmp_path):
    return SimpleNamespace(adb=Path("/adb"), serial="authorized-phone", output=tmp_path,
                           watch_timeout=60, test_apk=tmp_path / "helper.apk")


def clock(monkeypatch):
    seconds = [0]
    monkeypatch.setattr(diagnostics.time, "monotonic", lambda: seconds[0])
    monkeypatch.setattr(diagnostics.time, "sleep", lambda duration: seconds.__setitem__(0, seconds[0] + duration))
    return seconds


def test_watch_waits_for_a_real_session_and_a_confirmed_online_stop(tmp_path, monkeypatch):
    seconds = clock(monkeypatch)
    responses = iter([(0, ""), (0, ""), (0, ACTIVE), (1, ""), (1, ""),
                      (0, ACTIVE), (0, ""), (0, ""), (0, ""), (0, "")])

    def run(command, **kwargs):
        code, output = next(responses)
        return CompletedProcess(command, code, output, "")

    monkeypatch.setattr(diagnostics.subprocess, "run", run)
    diagnostics.wait_for_session_end(arguments(tmp_path))
    assert seconds[0] == 45
    assert json.loads((tmp_path / "watch-state.json").read_text())["saw_session"] is True


def test_disconnect_cannot_trigger_export(tmp_path, monkeypatch):
    clock(monkeypatch)
    first = [True]

    def run(command, **kwargs):
        if first[0]:
            first[0] = False
            return CompletedProcess(command, 0, ACTIVE, "")
        return CompletedProcess(command, 1, "", "device offline")

    monkeypatch.setattr(diagnostics.subprocess, "run", run)
    with pytest.raises(RuntimeError, match="No completed trial"):
        diagnostics.wait_for_session_end(arguments(tmp_path))
    assert json.loads((tmp_path / "watch-state.json").read_text())["phase"] == "device_unavailable"


def test_active_assistance_blocks_even_helper_installation(tmp_path, monkeypatch):
    commands = []

    def run(command, **kwargs):
        commands.append(command)
        return CompletedProcess(command, 0, ACTIVE, "")

    monkeypatch.setattr(diagnostics.subprocess, "run", run)
    with pytest.raises(RuntimeError, match="Assistance is still active"):
        diagnostics.collect(arguments(tmp_path))
    assert len(commands) == 1
    assert "install" not in commands[0]
