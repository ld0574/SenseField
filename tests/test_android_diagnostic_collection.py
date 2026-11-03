"""Operator-side collection must never interrupt active assistance or confuse Wi-Fi loss with stop."""

import json
import hashlib
import io
import zipfile
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


def test_reuse_missing_helper_stops_without_installing_a_package(tmp_path, monkeypatch):
    commands = []

    def run(command, **kwargs):
        commands.append(command)
        return CompletedProcess(command, 0, "", "")

    args = arguments(tmp_path)
    args.reuse_installed_helper = True
    monkeypatch.setattr(diagnostics.subprocess, "run", run)
    with pytest.raises(RuntimeError, match="no package was installed"):
        diagnostics.collect(args)
    assert len(commands) == 2
    assert commands[-1][-3:] == ["pm", "list", "instrumentation"]
    assert all("install" not in command for command in commands)


def test_legacy_junit_helper_is_never_run_against_another_release(tmp_path, monkeypatch):
    commands = []

    def run(command, **kwargs):
        commands.append(command)
        output = ("instrumentation:com.openkhub.sensefield.test/"
                  "androidx.test.runner.AndroidJUnitRunner (target=com.openkhub.sensefield)")
        return CompletedProcess(command, 0, output, "")

    args = arguments(tmp_path)
    args.reuse_installed_helper = True
    monkeypatch.setattr(diagnostics.subprocess, "run", run)
    with pytest.raises(RuntimeError, match="legacy app test runner"):
        diagnostics.collect(args)
    assert all("instrument" not in command for command in commands)


def fake_export(tmp_path, monkeypatch, *, protocol=1, request_matches=True,
                checksum_matches=True, instrument_code=-1, short_registration=False):
    request_id = "b" * 32
    monkeypatch.setattr(diagnostics.uuid, "uuid4", lambda: SimpleNamespace(hex=request_id))
    args = arguments(tmp_path)
    args.test_apk.write_bytes(b"standalone helper fixture")
    name = "diag-1793720000000-11111111-2222-3333-4444-555555555555.zip"
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("events.jsonl", '{"type":"playback","state":"FAILED"}\n')
    payload = buffer.getvalue()
    digest = hashlib.sha256(payload).hexdigest()
    remote = ("/sdcard/Android/data/com.openkhub.sensefield/files/adb-diagnostics/"
              + request_id)
    metadata = {"export_protocol": protocol,
                "export_request_id": request_id if request_matches else "c" * 32,
                "version_name": "0.4.8", "version_code": 29, "archives": [name]}
    commands = []

    def run(command, **kwargs):
        commands.append(command)
        tail = command[3:]
        output = ""
        if tail[:3] == ["shell", "dumpsys", "activity"]:
            pass
        elif tail[:1] == ["install"]:
            assert tail[1:4] == ["--no-incremental", "-r", "-t"]
            output = "Success\n"
        elif tail == ["shell", "pm", "list", "instrumentation"]:
            registration = (diagnostics.HELPER_SHORT_REGISTRATION if short_registration
                            else diagnostics.HELPER_REGISTRATION)
            output = "instrumentation:" + registration + "\n"
        elif tail[:3] == ["shell", "am", "instrument"]:
            assert tail[-1] == diagnostics.HELPER_COMPONENT
            assert ["authorizedDiagnosticsExport", "true"] == tail[5:7]
            output = ("INSTRUMENTATION_RESULT: diagnostic_export_protocol=1\n"
                      "INSTRUMENTATION_RESULT: diagnostic_export_path=" + remote + "\n"
                      "INSTRUMENTATION_CODE: " + str(instrument_code) + "\n")
        elif tail[:1] == ["pull"]:
            if tail[1] == remote + "/export.json":
                Path(tail[2]).write_text(json.dumps(metadata))
            else:
                assert tail[1] == remote + "/" + name
                Path(tail[2]).write_bytes(payload)
        elif tail[:2] == ["shell", "sha256sum"]:
            assert tail[2] == remote + "/" + name
            output = (digest if checksum_matches else "0" * 64) + "  " + tail[2]
        elif tail[:2] in [["shell", "rm"], ["shell", "rmdir"]]:
            assert tail[2] == "--"
            assert tail[3] == remote or tail[3].startswith(remote + "/")
        else:
            raise AssertionError(command)
        return CompletedProcess(command, 0, output, "")

    monkeypatch.setattr(diagnostics.subprocess, "run", run)
    return args, commands, name, digest


@pytest.mark.parametrize("short_registration", [False, True])
def test_standalone_export_checks_identity_and_hash_before_cleaning_its_own_copies(
        tmp_path, monkeypatch, short_registration):
    args, commands, name, digest = fake_export(tmp_path, monkeypatch,
                                              short_registration=short_registration)
    diagnostics.collect(args)
    receipt = json.loads((tmp_path / "receipt.json").read_text())
    assert receipt["target_version_code"] == 29
    assert receipt["helper"] == diagnostics.HELPER_PACKAGE
    assert receipt["export_protocol"] == 1
    assert receipt["archives"][0]["sha256"] == digest
    assert receipt["archives"][0]["name"] == name
    hash_index = next(i for i, command in enumerate(commands) if "sha256sum" in command)
    cleanup_indices = [i for i, command in enumerate(commands) if "rm" in command or "rmdir" in command]
    assert cleanup_indices and min(cleanup_indices) > hash_index
    assert all("/files/diagnostics" not in " ".join(command) for command in commands)


@pytest.mark.parametrize("options,error", [
    ({"protocol": 2}, "protocol or request identity mismatch"),
    ({"request_matches": False}, "protocol or request identity mismatch"),
    ({"checksum_matches": False}, "checksum mismatch"),
    ({"instrument_code": 0}, "helper did not complete"),
])
def test_invalid_or_crashed_export_never_deletes_phone_records(tmp_path, monkeypatch, options, error):
    args, commands, _, _ = fake_export(tmp_path, monkeypatch, **options)
    with pytest.raises(RuntimeError, match=error):
        diagnostics.collect(args)
    assert not (tmp_path / "receipt.json").exists()
    assert all("rm" not in command and "rmdir" not in command for command in commands)
