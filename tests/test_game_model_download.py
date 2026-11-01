from dataclasses import replace
import hashlib
import importlib.util
import json
from pathlib import Path
import uuid
import pytest
from fastapi.testclient import TestClient
from mapassist.assistant_gateway.app import create_app
from mapassist.assistant_gateway.config import GatewaySettings
from mapassist.assistant_gateway.game_models import GameModelRepository
from mapassist.assistant_gateway.errors import GatewayError

@pytest.fixture
def resources(tmp_path):
    data = b"synthetic private game detector bytes"
    target = tmp_path / "game/v1/model.bin"
    target.parent.mkdir(parents=True)
    target.write_bytes(data)
    index = dict(schema=1, packs={"game": dict(version="v1", files=[dict(name="model.bin", bytes=len(data), sha256=hashlib.sha256(data).hexdigest())])})
    (tmp_path / "game-models.json").write_text(json.dumps(index))
    return tmp_path, data

def settings(root, **kwargs):
    return GatewaySettings(game_models_dir=root, device_tokens=("test-token-device",),
        asr_backend="disabled", mode="development_mock", require_tls=True, **kwargs)

def test_scoped_ticket_and_real_range_transfer(resources):
    root, data = resources
    with TestClient(create_app(settings(root)), base_url="https://testserver") as client:
        assert client.post("/v1/game-models/ticket", json=dict(game="game", version="v1")).status_code == 401
        result = client.post("/v1/game-models/ticket", json=dict(game="game", version="v1"), headers={"Authorization": "Bearer test-token-device"})
        assert result.status_code == 200
        token = result.json()["ticket"]
        url = "/v1/game-models/game/v1/model.bin"
        assert client.get(url).status_code == 401
        headers = {"Authorization": "Bearer " + token}
        response = client.get(url, headers=headers)
        assert response.status_code == 200 and response.content == data
        resumed = client.get(url, headers={**headers, "Range": "bytes=8-"})
        assert resumed.status_code == 206 and resumed.content == data[8:]
        assert resumed.headers["content-range"] == f"bytes 8-{len(data)-1}/{len(data)}"
        assert client.get(url, headers={**headers, "Range": "bytes=999-"}).status_code == 416
        assert client.get(url, headers={**headers, "Range": "bytes=0-4,8-"}).status_code == 416
        assert client.get("/v1/game-models/game/v2/model.bin", headers=headers).status_code == 401
        assert client.get(url, headers={"Authorization": "Bearer " + token[:-1] + "z"}).status_code == 401
        assert client.get("/v1/game-models/game/v1/not-listed.bin", headers=headers).status_code == 404
        (root / "game/v1/model.bin").write_bytes(b"changed")
        assert client.get(url, headers=headers).status_code == 503

def test_expiry_key_survives_restart_and_cross_game_denial(resources):
    root, _ = resources
    repository = GameModelRepository(root)
    ticket = repository.ticket("game", "v1", now=100)["ticket"]
    GameModelRepository(root).authorize(ticket, "game", "v1", now=101)
    for game, version, now in [("game", "v1", 1900), ("other", "v1", 101), ("game", "v2", 101)]:
        with pytest.raises(GatewayError): repository.authorize(ticket, game, version, now=now)
    assert (root / ".download-key").stat().st_mode & 0o777 == 0o600

def test_unconfigured_and_https_only(resources):
    with TestClient(create_app(replace(settings(None), require_tls=False))) as client:
        assert client.post("/v1/game-models/ticket", json={}).status_code == 503
    root, _ = resources
    with TestClient(create_app(settings(root))) as client:
        assert client.post("/v1/game-models/ticket", json={}).status_code == 426

def test_public_download_is_separate_opt_in_and_rate_limited(resources):
    root, _ = resources
    conf = settings(root, public_access=True, public_quota_db=root / "quota.db", public_installation_minute=1)
    headers = {"X-SenseField-Installation": str(uuid.uuid4())}
    body = dict(game="game", version="v1")
    with TestClient(create_app(conf), base_url="https://testserver") as client:
        assert client.post("/v1/game-models/ticket", json=body, headers=headers).status_code == 401
    with TestClient(create_app(replace(conf, game_models_public=True)), base_url="https://testserver") as client:
        assert client.post("/v1/game-models/ticket", json=body).status_code == 401
        assert client.post("/v1/game-models/ticket", json=body, headers=headers).status_code == 200
        assert client.post("/v1/game-models/ticket", json=body, headers=headers).status_code == 429

@pytest.mark.parametrize("name", ["../private.bin", "/private.bin", "model..bin", "folder/model.bin"])
def test_index_rejects_paths(resources, name):
    root, _ = resources
    path = root / "game-models.json"
    index = json.loads(path.read_text()); index["packs"]["game"]["files"][0]["name"] = name
    path.write_text(json.dumps(index))
    with pytest.raises(ValueError): GameModelRepository(root)

def test_packager_reproducible_and_preserves_bytes(tmp_path):
    spec = importlib.util.spec_from_file_location("package_game_models", Path(__file__).parents[1] / "scripts/package_game_models.py")
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    source = tmp_path / "source"; source.mkdir()
    for names in module.PACKS.values():
        for name in names: (source / name).write_bytes(("synthetic " + name).encode())
    output = tmp_path / "private"
    manifest = module.package(source, output, tmp_path / "public.json")
    assert module.package(source, output, tmp_path / "public.json") == manifest
    for game, pack in manifest["packs"].items():
        for entry in pack["files"]:
            assert (output / game / pack["version"] / entry["name"]).read_bytes() == (source / entry["name"]).read_bytes()
    name = module.PACKS["match3"][0]; (source / name).write_bytes(b"new synthetic data")
    updated = module.package(source, output, tmp_path / "public.json")
    assert updated["packs"]["hok"] == manifest["packs"]["hok"]
    assert updated["packs"]["match3"]["version"] != manifest["packs"]["match3"]["version"]
    repository = GameModelRepository(output)
    for game in ("hok", "match3"):
        repository.ticket(game, manifest["packs"][game]["version"])
        repository.ticket(game, updated["packs"][game]["version"])
