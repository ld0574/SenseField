from __future__ import annotations

from scripts.check_public_repo import scan


def test_validation_evidence_directory_is_never_public(tmp_path) -> None:
    (tmp_path / "README.md").write_text("# test\n", encoding="utf-8")
    (tmp_path / "LICENSE").write_text("test\n", encoding="utf-8")
    evidence = tmp_path / "validation/private/final-evidence.json"
    evidence.parent.mkdir(parents=True)
    evidence.write_text("{}\n", encoding="utf-8")

    errors, _ = scan(tmp_path, ["validation/private/final-evidence.json"])

    assert errors == [
        "private/generated path is public: validation/private/final-evidence.json"
    ]
