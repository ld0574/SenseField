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


def test_private_output_paths_are_rejected_without_forbidding_public_pdf_by_suffix(tmp_path) -> None:
    (tmp_path / "README.md").write_text("# test\n", encoding="utf-8")
    (tmp_path / "LICENSE").write_text("test\n", encoding="utf-8")
    private = tmp_path / "output/research/source.pdf"
    private.parent.mkdir(parents=True)
    private.write_bytes(b"local source")
    public = tmp_path / "docs/releases/example.pdf"
    public.parent.mkdir(parents=True)
    public.write_bytes(b"approved document")

    errors, _ = scan(tmp_path, [
        "README.md", "LICENSE", "output/research/source.pdf", "docs/releases/example.pdf",
    ])

    assert any("private/generated path is public: output/research/source.pdf" in error
               for error in errors)
    assert not any("example.pdf" in error for error in errors)


def _repo(tmp_path):
    (tmp_path / "README.md").write_text("# public gateway docs\n", encoding="utf-8")
    (tmp_path / "LICENSE").write_text("test\n", encoding="utf-8")


def test_model_service_api_key_is_blocked_without_echoing_secret(tmp_path) -> None:
    _repo(tmp_path)
    key = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4"
    source = tmp_path / "config.py"
    source.write_text(f'ZHIPU_API_KEY = "{key}"\n', encoding="utf-8")

    errors, _ = scan(tmp_path, ["README.md", "LICENSE", "config.py"])

    assert any("model-service API key: config.py" in error for error in errors)
    assert key not in "\n".join(errors)


def test_device_auth_secret_and_bearer_token_are_blocked(tmp_path) -> None:
    _repo(tmp_path)
    token = "devtoken0123456789abcdefghijklmnop"
    source = tmp_path / "gateway.properties"
    source.write_text(
        f"SENSEFIELD_DEVICE_TOKEN={token}\nAuthorization: Bearer {token}\n",
        encoding="utf-8",
    )

    errors, _ = scan(tmp_path, ["README.md", "LICENSE", "gateway.properties"])

    assert any("device or gateway auth secret" in error for error in errors)
    assert any("Bearer token" in error for error in errors)
    assert token not in "\n".join(errors)


def test_public_check_finds_nested_auth_secret_assignments(tmp_path) -> None:
    _repo(tmp_path)
    token = "s3cur3devicecredential0123456789"
    source = tmp_path / "gateway.json"
    source.write_text(
        f'{{"device_token": "{token}", "headers": '
        f'{{"Authorization": "Bearer {token}"}}}}\n',
        encoding="utf-8",
    )

    errors, _ = scan(tmp_path, ["README.md", "LICENSE", "gateway.json"])

    assert any("device or gateway auth secret" in error for error in errors)
    assert any("Bearer token" in error for error in errors)
    assert token not in "\n".join(errors)


def test_public_gateway_urls_and_safe_placeholders_do_not_false_positive(tmp_path) -> None:
    _repo(tmp_path)
    docs = tmp_path / "README.md"
    docs.write_text(
        "GET /health; WSS wss://gateway.example.test/v1/audio; "
        "ZHIPU_API_KEY=<server-only>; DEVICE_AUTH_TOKEN=${DEVICE_TOKEN}\n",
        encoding="utf-8",
    )

    errors, _ = scan(tmp_path, ["README.md", "LICENSE"])

    assert errors == []


def test_environment_files_are_blocked_but_template_is_allowed(tmp_path) -> None:
    _repo(tmp_path)
    (tmp_path / ".env.local").write_text("TOKEN=private\n", encoding="utf-8")
    (tmp_path / ".env.example").write_text(
        "ZHIPU_API_KEY=<server-only>\nDEVICE_AUTH_TOKEN=<device-token>\n",
        encoding="utf-8",
    )

    errors, _ = scan(tmp_path, ["README.md", "LICENSE", ".env.local", ".env.example"])

    assert any("secret or environment file is public: .env.local" in error for error in errors)
    assert not any(".env.example" in error for error in errors)
