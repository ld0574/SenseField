from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import stat

import pytest


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("native_state", ROOT / "deploy/assistant/native-state.py")
state = importlib.util.module_from_spec(spec)
spec.loader.exec_module(state)
TEMPLATE = ROOT / "deploy/assistant/sensefield-assistant.service"
LEGACY = ROOT / "tests/fixtures/sensefield-native-v1.service"


def test_previous_opt_service_is_recognized_without_marker_in_the_new_directory(tmp_path):
    unit = tmp_path / "service"
    unit.write_bytes(LEGACY.read_bytes())
    assert not (tmp_path / "new-install/.sensefield-native-install-v1").exists()
    assert state.recognized_service(unit, TEMPLATE)


def test_stale_unit_is_recognized_even_after_its_old_application_was_deleted(tmp_path):
    removed = tmp_path / "removed-old-install"
    removed.mkdir()
    removed.rmdir()
    unit = tmp_path / "service"
    unit.write_text(LEGACY.read_text().replace("/opt/sensefield-assistant", str(removed)))
    assert not removed.exists()
    assert state.recognized_service(unit, TEMPLATE)


def test_current_custom_directory_proxy_service_is_recognized(tmp_path):
    unit = tmp_path / "service"
    unit.write_text(TEMPLATE.read_text().replace("@APP_DIR@", "/data/wwwroot/sf")
                    .replace("@LOCAL_PROXY_ENV@", "Environment=ASSISTANT_GATEWAY_LOCAL_TLS_PROXY=1"))
    assert state.recognized_service(unit, TEMPLATE)


@pytest.mark.parametrize("old,new", [
    ("User=sensefield-gateway", "User=root"),
    ("/opt/sensefield-assistant/serve.py", "/opt/unrelated/serve.py"),
    ("ExecStart=", "ExecStartPre="),
    ("ASSISTANT_GATEWAY_ENV_FILE=/etc/sensefield-assistant/gateway.json", "ASSISTANT_GATEWAY_ENV_FILE=/elsewhere/config"),
])
def test_unrelated_or_changed_service_is_not_overwritten(tmp_path, old, new):
    unit = tmp_path / "service"
    original = LEGACY.read_text().replace(old, new)
    unit.write_text(original)
    assert not state.recognized_service(unit, TEMPLATE)
    assert unit.read_text() == original


def test_symlinked_service_is_rejected(tmp_path):
    unit = tmp_path / "service"
    unit.symlink_to(LEGACY)
    assert not state.recognized_service(unit, TEMPLATE)


def private_config(tmp_path):
    config = {
        "ASSISTANT_GATEWAY_HOST": "127.0.0.1",
        "ASSISTANT_GATEWAY_PORT": "18765",
        "ASSISTANT_GATEWAY_ASR_BACKEND": "disabled",
        "ASSISTANT_GATEWAY_TLS_CERT": "/etc/sensefield-assistant/tls/backend.crt",
        "ASSISTANT_GATEWAY_TLS_KEY": "/etc/sensefield-assistant/tls/backend.key",
        "ASSISTANT_GATEWAY_DEVICE_TOKENS": "test-original-code-a,test-original-code-b",
        "ASSISTANT_GATEWAY_VISION_API_KEY": "test-original-provider-key",
        "ASSISTANT_GATEWAY_VISION_MODEL": "qwen/qwen3.8-27b",
        "ASSISTANT_GATEWAY_ACCOUNT_CONCURRENCY": "1",
    }
    path = tmp_path / "gateway.json"
    path.write_text(json.dumps(config, indent=3) + "\n")
    path.chmod(0o400)
    return path, config


def test_tls_migration_preserves_credentials_and_an_exact_private_backup(tmp_path, capsys):
    path, original = private_config(tmp_path)
    before = path.read_bytes()
    owner = (path.stat().st_uid, path.stat().st_gid)
    assert state.migrate_configuration(path, "local-proxy")
    migrated = json.loads(path.read_text())
    assert migrated["ASSISTANT_GATEWAY_LOCAL_TLS_PROXY"] == "1"
    assert "ASSISTANT_GATEWAY_TLS_CERT" not in migrated and "ASSISTANT_GATEWAY_TLS_KEY" not in migrated
    for key in original:
        if key not in {"ASSISTANT_GATEWAY_TLS_CERT", "ASSISTANT_GATEWAY_TLS_KEY"}:
            assert migrated[key] == original[key]
    backups = list(tmp_path.glob("gateway.json.before-migration-*"))
    assert len(backups) == 1 and backups[0].read_bytes() == before
    assert stat.S_IMODE(backups[0].stat().st_mode) == 0o600
    assert stat.S_IMODE(path.stat().st_mode) == 0o400
    assert (path.stat().st_uid, path.stat().st_gid) == owner
    assert capsys.readouterr() == ("", "")
    assert not list(tmp_path.glob(".gateway-migration-*"))
    after = path.read_bytes()
    assert not state.migrate_configuration(path, "local-proxy")
    assert path.read_bytes() == after and len(list(tmp_path.glob("*.before-migration-*"))) == 1


def test_migrating_back_to_backend_tls_keeps_same_codes(tmp_path):
    path, original = private_config(tmp_path)
    state.migrate_configuration(path, "local-proxy")
    assert state.migrate_configuration(path, "backend-tls")
    assert json.loads(path.read_text()) == original


@pytest.mark.parametrize("text", ["{", "[]", '{"PYTHONPATH":"/unexpected"}', '{"ASSISTANT_GATEWAY_PORT":18765}'])
def test_invalid_configuration_stays_byte_identical_without_backups(tmp_path, text):
    path = tmp_path / "gateway.json"
    path.write_text(text)
    with pytest.raises(ValueError):
        state.migrate_configuration(path, "local-proxy")
    assert path.read_text() == text
    assert not list(tmp_path.glob("*.before-migration-*"))


def test_configuration_symlink_cannot_replace_its_target(tmp_path):
    original, _ = private_config(tmp_path)
    before = original.read_bytes()
    link = tmp_path / "linked.json"
    link.symlink_to(original)
    with pytest.raises(ValueError):
        state.migrate_configuration(link, "local-proxy")
    assert original.read_bytes() == before
