import json
from pathlib import Path

import pytest

from scripts import prepare_android_candidate as candidate


@pytest.fixture
def handoff(tmp_path, monkeypatch):
    apk = tmp_path / "app-release.apk"
    apk.write_bytes(b"frozen signed package fixture")
    docs = tmp_path / "docs"
    docs.mkdir()
    for name in ["UPDATE_SUMMARY.txt", "installation-guide.txt", "RELEASE_NOTES.md", "复测说明.md"]:
        (docs / name).write_text("候选，完整工程验收另见回执。\n")
    scripts = tmp_path / "scripts"
    scripts.mkdir()
    for name in ["build_android_preview.sh", "prepare_android_candidate.py", "verify_android_release.py", "build_app_update_manifest.py"]:
        (scripts / name).write_text("test tool fixture")
    monkeypatch.setattr(candidate, "ROOT", tmp_path)
    monkeypatch.setattr(candidate, "source_digest", lambda: "a" * 64)
    monkeypatch.setattr(candidate.subprocess, "check_output", lambda *a, text=False, **k: "commit\n" if text else b"diff")
    monkeypatch.setattr(candidate, "discover_aapt2", lambda: "unused")

    def verified(path, _allow_fixture):
        return {"sha256": candidate.sha(path), "bytes": path.stat().st_size,
                "certificate_sha256": "cert", "version_name": "0.4.7", "version_code": 24}

    def manifest(path, url, _tool, _notes):
        return {"apk_sha256": candidate.sha(path), "apk_bytes": path.stat().st_size,
                "version_name": "0.4.7", "version_code": 24, "apk_url": url}

    monkeypatch.setattr(candidate, "verify", verified)
    monkeypatch.setattr(candidate, "create_manifest", manifest)
    output = tmp_path / "output/releases/0.4.7"
    candidate.begin(output)
    return apk, docs, output


def prepare(handoff):
    apk, docs, output = handoff
    return candidate.prepare(apk, output, "https://updates.example/sensefieldv0.4.7.apk", docs)


def test_one_handoff_binds_manifest_checksums_and_source_to_verified_copy(handoff):
    result = prepare(handoff)
    apk, docs, output = handoff
    files = list(output.rglob("*.apk"))
    assert files == [output / "sensefieldv0.4.7.apk"]
    assert files[0].read_bytes() == apk.read_bytes()
    manifest = json.loads((output / "latest.json").read_text())
    receipt = json.loads((output / "validation/current-package/release-package.json").read_text())
    assert manifest["apk_sha256"] == receipt["sha256"] == result["sha256"]
    assert manifest["version_code"] == receipt["version_code"] == 24
    for line in (output / "SHA256SUMS.txt").read_text().splitlines():
        digest, name = line.split("  ")
        assert candidate.sha(output / name) == digest
    assert result["state"] == "package_verified_engineering_pending"
    assert not (output / "gitee-upload").exists()
    assert not (output / "cdn-upload").exists()


def test_publishable_notes_keep_links_usable_outside_the_source_checkout(handoff):
    (handoff[1] / "RELEASE_NOTES.md").write_text("[规划](../../plans/0.4.7版本规划.md) [记录](../../../validation/STATUS.md)")
    prepare(handoff)
    body = (handoff[2] / "publishing/RELEASE_NOTES.md").read_text()
    assert "https://github.com/ld0574/SenseField/blob/main/docs/plans/0.4.7版本规划.md" in body
    assert "https://github.com/ld0574/SenseField/blob/main/validation/STATUS.md" in body


def test_failed_validation_cannot_leave_a_ready_apk_or_manifest(handoff, monkeypatch):
    def rejection(*_args):
        raise ValueError("signature mismatch")
    monkeypatch.setattr(candidate, "verify", rejection)
    with pytest.raises(ValueError, match="signature mismatch"):
        prepare(handoff)
    output = handoff[2]
    candidate.failed(output, 1)
    assert not list(output.rglob("*.apk"))
    assert not (output / "latest.json").exists()
    assert json.loads((output / "validation/current-package/build-state.json").read_text())["state"] == "build_failed"


def test_changed_bytes_between_verification_and_manifest_are_rejected(handoff, monkeypatch):
    original = candidate.verify
    def mutate_after_verify(path, allow_fixture):
        result = original(path, allow_fixture)
        path.write_bytes(b"different unverified bytes")
        return result
    monkeypatch.setattr(candidate, "verify", mutate_after_verify)
    with pytest.raises(ValueError, match="变化"):
        prepare(handoff)
    assert not list(handoff[2].rglob("*.apk"))


def test_rebuild_archives_prior_delivery_and_retains_public_older_version(handoff):
    prepare(handoff)
    output = handoff[2]
    previous = {str(p.relative_to(output)): p.read_bytes() for p in output.rglob("*") if p.is_file()}
    public = output.parent / "0.4.6/sensefieldv0.4.6.apk"
    public.parent.mkdir()
    public.write_bytes(b"public older version")
    candidate.begin(output)
    state = json.loads((output / "validation/current-package/build-state.json").read_text())
    archive = Path(state["previous_candidate_archive"])
    assert {str(p.relative_to(archive)): p.read_bytes() for p in archive.rglob("*") if p.is_file()} == previous
    assert not list(output.rglob("*.apk"))
    assert public.read_bytes() == b"public older version"
    prepare(handoff)
    assert list((output / "validation/artifact-history").rglob("release-package.json"))
    assert len(list(output.rglob("*.apk"))) == 1


def test_unexpected_file_added_during_build_is_preserved(handoff):
    other = handoff[2] / "user-note.txt"
    other.write_text("keep this")
    with pytest.raises(ValueError, match="额外文件"):
        prepare(handoff)
    assert other.read_text() == "keep this"
    assert not (handoff[2] / "latest.json").exists()


def test_final_rename_failure_leaves_no_export_and_can_be_marked_failed(handoff, monkeypatch):
    original = candidate.os.replace
    def reject_publish(src, dst):
        if Path(dst) == handoff[2]:
            raise OSError("cannot publish local directory")
        return original(src, dst)
    monkeypatch.setattr(candidate.os, "replace", reject_publish)
    with pytest.raises(OSError, match="cannot publish"):
        prepare(handoff)
    candidate.failed(handoff[2], 1)
    assert not list(handoff[2].rglob("*.apk"))
    assert json.loads((handoff[2] / "validation/current-package/build-state.json").read_text())["exit_code"] == 1


@pytest.mark.parametrize("index,apk", [
    ("https://updates.example/latest.json", "http://updates.example/app.apk"),
    ("https://updates.example/latest.json", "https://other.example/app.apk"),
    ("https://888413.xyz/apk/latest.json", "https://gitee.com/other/SenseField/releases/download/0.4.7/app.apk"),
    ("https://888413.xyz/apk/latest.json", "https://gitee.com/leda/SenseField/releases/download/0.4.7/app.apk?token=x"),
])
def test_cross_origin_and_invalid_download_addresses_fail_before_build(index, apk):
    with pytest.raises(ValueError):
        candidate.validate_urls(index, apk)


def test_download_policy_retains_owned_gitee_and_same_origin():
    candidate.validate_urls("https://888413.xyz/apk/latest.json", "https://gitee.com/leda/SenseField/releases/download/0.4.7/sensefieldv0.4.7.apk")
    candidate.validate_urls("https://updates.example/latest.json", "https://updates.example/sensefieldv0.4.7.apk")
