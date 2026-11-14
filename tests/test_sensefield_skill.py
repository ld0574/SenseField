"""Behavioral checks for the packaged cue tool and public artifact boundary."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

import pytest


ROOT = Path(__file__).resolve().parents[1]
SKILL = ROOT / "skills/sensefield-game-buddy"


def load(path):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


cue = load(SKILL / "scripts/format_cue.py")
packager = load(ROOT / "scripts/package_sensefield_skill.py")


def example(**changes):
    value = json.loads((SKILL / "assets/demo-match3-cue.json").read_text())
    value.update(changes)
    return value


def test_direction_is_action_location_not_goal_location():
    actual = cue.format_cue(example())
    assert actual["direction"] == "右下方"
    assert actual["speech"] == "右下方，魔力鸟向右与河马交换。"
    # Mirror an interior location; exact third boundaries follow the App's tie rule.
    interior = cue.format_cue(example(source=[4, 4], target=[4, 5]))
    assert interior["direction"] == "右下方"
    mirrored = example(source=[4, 1], target=[4, 0])
    actual = cue.format_cue(mirrored)
    assert actual["direction"] == "左下方"
    assert "向左" in actual["speech"]


def test_vertical_and_single_activation():
    actual = cue.format_cue(example(source=[0, 3], target=[1, 3]))
    assert actual["direction"] == "上方" and "向下" in actual["speech"]
    actual = cue.format_cue(example(action="activate", source=[2, 2], target=[2, 2]))
    assert actual["speech"] == "中间，点击魔力鸟。"


@pytest.mark.parametrize("changes", [
    {"confirmed_legal": False}, {"confirmed_legal": "true"}, {"evidence": ""},
    {"rows": True}, {"cols": 17}, {"source": [True, 3]}, {"source": [-1, 3]},
    {"target": [6, 4]}, {"target": [5, 4]}, {"target": [4, 3]},
    {"target": [4, 5]}, {"action": "activate"}, {"action": "touch"},
    {"source_name": ""}, {"source_name": "\n河马"},
])
def test_unconfirmed_or_malformed_actions_are_rejected(changes):
    with pytest.raises(ValueError):
        cue.format_cue(example(**changes))


def test_svg_escapes_names_and_has_readable_description():
    source = cue.render_svg(example(source_name='<动物 & "名称">'))
    root = ET.fromstring(source)
    ns = {"s": "http://www.w3.org/2000/svg"}
    assert root.find("s:title", ns).text.startswith("右下方，<动物")
    assert root.find("s:desc", ns).text
    assert len(root.findall(".//s:circle", ns)) == 36
    assert "<动物" not in source


def test_invalid_cli_keeps_existing_guide(tmp_path):
    value = tmp_path / "input.json"
    value.write_text(json.dumps(example(confirmed_legal=False)))
    output = tmp_path / "guide.svg"
    output.write_text("preserve the existing guide")
    result = subprocess.run([sys.executable, str(SKILL / "scripts/format_cue.py"),
                             "--input", str(value), "--svg", str(output)], capture_output=True, text=True)
    assert result.returncode == 2
    assert result.stdout == ""
    assert "error" in json.loads(result.stderr)
    assert output.read_text() == "preserve the existing guide"


def test_package_is_installable_self_contained_and_reproducible(tmp_path):
    first, second = tmp_path / "first.zip", tmp_path / "second.zip"
    report = packager.package(SKILL, first)
    report2 = packager.package(SKILL, second)
    assert report["sha256"] == report2["sha256"]
    assert report["file_count"] <= 30 and report["uncompressed_bytes"] <= 40_000_000
    with zipfile.ZipFile(first) as archive:
        names = archive.namelist()
        assert "SKILL.md" in names
        assert "LICENSE" in names
        assert all(not name.startswith("/") and ".." not in name for name in names)
        assert sorted(names) == sorted(packager.FILES)
        installed = tmp_path / "installed/sensefield-game-buddy"
        archive.extractall(installed)
    result = subprocess.run([sys.executable, str(installed / "scripts/format_cue.py"),
                             "--input", str(installed / "assets/demo-match3-cue.json"),
                             "--svg", str(tmp_path / "card.svg")], cwd=tmp_path,
                            capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)["direction"] == "右下方"
    ET.parse(tmp_path / "card.svg")


def test_package_refuses_unreviewed_file_and_links(tmp_path):
    import shutil
    source = tmp_path / "sensefield-game-buddy"
    shutil.copytree(SKILL, source, ignore=shutil.ignore_patterns("__pycache__"))
    private = source / "credentials.json"
    private.write_text('{"private":true}')
    with pytest.raises(ValueError, match="Unreviewed"):
        packager.package(source, tmp_path / "unsafe.zip")
    private.unlink()
    target = source / "assets/demo-match3-cue.json"
    target.unlink()
    target.symlink_to(SKILL / "assets/demo-match3-cue.json")
    with pytest.raises(ValueError, match="link outside"):
        packager.package(source, tmp_path / "unsafe.zip")
