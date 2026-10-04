from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
INSTALL_SCRIPT = ROOT / "scripts/assistant_gateway_install_deps.sh"


@pytest.fixture
def fake_python(tmp_path: Path) -> tuple[Path, Path]:
    executable = tmp_path / "fake-python"
    calls = tmp_path / "python-calls.jsonl"
    executable.write_text(
        """#!/usr/bin/env python3
import json
import os
import sys

args = sys.argv[1:]
with open(os.environ['FAKE_PYTHON_CALLS'], 'a', encoding='utf-8') as log:
    log.write(json.dumps(args) + '\\n')
if args == ['-']:
    sys.stdin.read()
elif args[:1] == ['-c']:
    print(os.environ.get('FAKE_PLATFORM', 'linux'))
elif args[:3] == ['-m', 'pip', '--version']:
    print('pip 25.0 (mock)')
elif args[:3] == ['-m', 'pip', 'install']:
    pass
else:
    raise SystemExit('unexpected fake Python invocation: ' + repr(args))
""",
        encoding="utf-8",
    )
    executable.chmod(0o755)
    return executable, calls


def _run_installer(
    fake_python: tuple[Path, Path], *arguments: str
) -> tuple[subprocess.CompletedProcess[str], list[list[str]]]:
    executable, calls_path = fake_python
    environment = os.environ.copy()
    environment.update({
        "PYTHON": str(executable),
        "FAKE_PYTHON_CALLS": str(calls_path),
        "FAKE_PLATFORM": "linux",
    })
    result = subprocess.run(
        ["bash", str(INSTALL_SCRIPT), *arguments],
        cwd=ROOT,
        env=environment,
        text=True,
        capture_output=True,
        check=False,
    )
    calls = []
    if calls_path.exists():
        calls = [json.loads(line) for line in calls_path.read_text(encoding="utf-8").splitlines()]
    return result, calls


def test_vision_only_installs_only_the_lightweight_extra(fake_python: tuple[Path, Path]) -> None:
    result, calls = _run_installer(fake_python, "--vision-only")

    assert result.returncode == 0, result.stderr
    install_calls = [call for call in calls if call[:3] == ["-m", "pip", "install"]]
    assert len(install_calls) == 1
    arguments = install_calls[0][3:]
    assert ".[assistant-vision-gateway]" in arguments
    assert ".[assistant-gateway]" not in arguments
    lowered = " ".join(arguments).lower()
    assert "torch" not in lowered
    assert "pytorch" not in lowered
    assert "huggingface" not in lowered
    assert "modelscope" not in lowered
    assert "--index-url" not in arguments
    assert "--extra-index-url" not in arguments


def test_default_linux_install_keeps_the_legacy_asr_path(fake_python: tuple[Path, Path]) -> None:
    result, calls = _run_installer(fake_python)

    assert result.returncode == 0, result.stderr
    install_calls = [call for call in calls if call[:3] == ["-m", "pip", "install"]]
    assert len(install_calls) == 2
    assert "torch==2.8.0+cpu" in install_calls[0]
    assert "--index-url" in install_calls[0]
    assert any("download.pytorch.org/whl/cpu" in value for value in install_calls[0])
    assert ".[assistant-gateway]" in install_calls[1]


@pytest.mark.parametrize(
    "arguments",
    [
        ("--unknown",),
        ("--vision-only", "unexpected"),
        ("--vision-only", "--vision-only"),
    ],
)
def test_invalid_arguments_fail_before_invoking_python(
    fake_python: tuple[Path, Path], arguments: tuple[str, ...]
) -> None:
    result, calls = _run_installer(fake_python, *arguments)

    assert result.returncode == 2
    assert calls == []
