from __future__ import annotations

import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from scripts import build_app_update_manifest as manifest_builder


BADGING = """package: name='com.openkhub.sensefield' versionCode='17' versionName='0.4.0' platformBuildVersionName='16'
minSdkVersion:'29'
targetSdkVersion:'35'
native-code: 'arm64-v8a' 'armeabi-v7a'
"""


def test_parse_badging_reads_package_version_min_sdk_and_abis() -> None:
    metadata = manifest_builder.parse_aapt2_badging(BADGING)

    assert metadata.package_name == "com.openkhub.sensefield"
    assert metadata.version_name == "0.4.0"
    assert metadata.version_code == 17
    assert metadata.min_sdk_version == "29"
    assert metadata.native_abis == ("arm64-v8a", "armeabi-v7a")


def test_manifest_hashes_the_apk_and_uses_only_apk_metadata(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    apk = tmp_path / "SenseField-signed.apk"
    apk_bytes = b"a signed-apk-shaped test payload\x00\xff"
    apk.write_bytes(apk_bytes)
    notes = tmp_path / "release-notes.txt"
    notes.write_text("Fixes update checks.\n", encoding="utf-8")
    invocations: list[list[str]] = []

    def fake_run(command: list[str], **_kwargs: object) -> SimpleNamespace:
        invocations.append(command)
        return SimpleNamespace(returncode=0, stdout=BADGING, stderr="")

    monkeypatch.setattr(manifest_builder.subprocess, "run", fake_run)
    result = manifest_builder.create_manifest(
        apk,
        "https://localhost:18766/app.apk",
        "fake-aapt2",
        notes,
    )
    output = tmp_path / "named" / "latest.json"
    manifest_builder.write_manifest(result, output)
    written = json.loads(output.read_text(encoding="utf-8"))

    assert invocations == [["fake-aapt2", "dump", "badging", str(apk.resolve())]]
    assert written == {
        "schema_version": 1,
        "package_name": "com.openkhub.sensefield",
        "version_name": "0.4.0",
        "version_code": 17,
        "apk_url": "https://localhost:18766/app.apk",
        "apk_bytes": len(apk_bytes),
        "apk_sha256": hashlib.sha256(apk_bytes).hexdigest(),
        "release_notes": "Fixes update checks.",
    }
    assert set(written) == {
        "schema_version", "package_name", "version_name", "version_code",
        "apk_url", "apk_bytes", "apk_sha256", "release_notes",
    }


@pytest.mark.parametrize(
    "url",
    [
        "http://updates.example/app.apk",
        "https:///app.apk",
        "https://user:secret@updates.example/app.apk",
        "https://updates.example/app.apk#fragment",
        "https://updates.example/app.apk#",
        "https://updates.example:70000/app.apk",
        "https://updates.example/app apk",
    ],
)
def test_manifest_rejects_non_https_or_unsafe_apk_urls(url: str) -> None:
    with pytest.raises(manifest_builder.ManifestError):
        manifest_builder.validate_apk_url(url)


@pytest.mark.parametrize("notes", ["改" * 101, "第一条\n第二条\n第三条\n第四条", "第一条\n\n第二条\n第三条"])
def test_manifest_rejects_long_update_dialog_notes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, notes: str,
) -> None:
    apk = tmp_path / "app.apk"
    apk.write_bytes(b"apk payload")
    summary = tmp_path / "update-summary.txt"
    summary.write_text(notes, encoding="utf-8")
    monkeypatch.setattr(
        manifest_builder, "read_apk_metadata",
        lambda *_args: manifest_builder.parse_aapt2_badging(BADGING),
    )

    with pytest.raises(manifest_builder.ManifestError, match="Update dialog summary"):
        manifest_builder.create_manifest(apk, "https://updates.example/app.apk", "unused", summary)


def test_manifest_accepts_summary_at_both_limits(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    apk = tmp_path / "app.apk"
    apk.write_bytes(b"apk payload")
    summary = tmp_path / "update-summary.txt"
    notes = "改" * 32 + "\n" + "善" * 32 + "\n" + "提" * 34
    summary.write_text(notes + "\n", encoding="utf-8")
    monkeypatch.setattr(
        manifest_builder, "read_apk_metadata",
        lambda *_args: manifest_builder.parse_aapt2_badging(BADGING),
    )

    result = manifest_builder.create_manifest(apk, "https://updates.example/app.apk", "unused", summary)

    assert result["release_notes"] == notes


def test_aapt_badging_must_include_version_and_minimum_sdk() -> None:
    with pytest.raises(manifest_builder.ManifestError, match="versionName"):
        manifest_builder.parse_aapt2_badging("package: name='com.example.app' versionCode='2'\nminSdkVersion:'23'\n")
    with pytest.raises(manifest_builder.ManifestError, match="minimum SDK"):
        manifest_builder.parse_aapt2_badging(
            "package: name='com.example.app' versionCode='2' versionName='2.0'\n"
        )
