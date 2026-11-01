"""Private on-device game resources, served with expiring scoped bearer tickets."""
from __future__ import annotations
import base64
import asyncio
import hashlib
import hmac
import json
from pathlib import Path
import re
import secrets
import os
import tempfile
import time
from fastapi import Request
from fastapi.responses import JSONResponse, StreamingResponse
from .errors import GatewayError
from .public_access import installation_key, PublicQuotaExceeded

NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,159}\Z")
ID = re.compile(r"[a-z][a-z0-9-]{0,63}\Z")
VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}\Z")
TICKET_SECONDS = 1800

class GameModelRepository:
    def __init__(self, root: Path):
        self.root = root.resolve()
        manifest = json.loads((self.root / "game-models.json").read_text())
        if manifest.get("schema") != 1 or not isinstance(manifest.get("packs"), dict):
            raise ValueError("Invalid game resource index")
        self.packs = manifest["packs"]
        self.versions = {}
        for game, versions in manifest.get("versions", {}).items():
            for version, pack in versions.items():
                if pack.get("version") != version:
                    raise ValueError("Invalid retained resource version")
                self.versions[(game, version)] = pack
        for game, pack in self.packs.items():
            self.versions[(game, pack["version"])] = pack
        self.files = {}
        for (game, _), pack in self.versions.items():
            version, files = pack["version"], pack["files"]
            if not ID.fullmatch(game) or not VERSION.fullmatch(version) or not 1 <= len(files) <= 64:
                raise ValueError("Invalid game resource pack")
            names = set()
            for item in files:
                name, size, checksum = item["name"], item["bytes"], item["sha256"]
                if (not NAME.fullmatch(name) or ".." in name or name in names or type(size) is not int
                        or not 1 <= size <= 512 * 1024 * 1024 or not re.fullmatch("[0-9a-f]{64}", checksum)):
                    raise ValueError("Invalid game resource entry")
                names.add(name)
                path = self.root / game / version / name
                if (path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(self.root)
                        or path.stat().st_size != size):
                    raise ValueError("Missing game resource file")
                with path.open("rb") as source:
                    digest = hashlib.sha256()
                    for block in iter(lambda: source.read(65536), b""):
                        digest.update(block)
                    if digest.hexdigest() != checksum:
                        raise ValueError("Game resource checksum mismatch")
                self.files[(game, version, name)] = (path, size, checksum, path.stat().st_mtime_ns)
        # Server-only key is persisted across worker restarts, outside any static root.
        key = self.root / ".download-key"
        if not key.exists():
            with tempfile.NamedTemporaryFile(dir=self.root, prefix=".key-", delete=False) as output:
                pending = Path(output.name)
                output.write(secrets.token_bytes(32)); output.flush(); os.fsync(output.fileno())
            try:
                try: os.link(pending, key)
                except FileExistsError: pass
            finally:
                pending.unlink()
        if key.is_symlink():
            raise ValueError("Invalid game download signing key path")
        self.key = key.read_bytes()
        if len(self.key) != 32:
            raise ValueError("Invalid game download signing key")

    def ticket(self, game: str, version: str, *, now: int | None = None) -> dict:
        pack = self.versions.get((game, version))
        if pack is None:
            raise GatewayError("model_version_unavailable", "此版本游戏资源尚未部署。", http_status=404)
        expires = int(time.time() if now is None else now) + TICKET_SECONDS
        payload = base64.urlsafe_b64encode(json.dumps([game, version, expires], separators=(",", ":")).encode()).decode().rstrip("=")
        signature = hmac.new(self.key, payload.encode(), hashlib.sha256).hexdigest()
        return {"ticket": payload + "." + signature, "expires": expires}

    def authorize(self, token: str, game: str, version: str, *, now: int | None = None):
        try:
            if len(token) > 512:
                raise ValueError
            payload, signature = token.split(".")
            expected = hmac.new(self.key, payload.encode(), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(signature, expected):
                raise ValueError
            issued_game, issued_version, expires = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
            current = int(time.time() if now is None else now)
            if (issued_game != game or issued_version != version or type(expires) is not int
                    or expires <= current or expires > current + TICKET_SECONDS):
                raise ValueError
        except (ValueError, TypeError, UnicodeError):
            raise GatewayError("model_ticket_invalid", "资源下载凭证已失效，请重新开始下载。", http_status=401) from None

def attach_game_models(app, settings, public_quota, secure_scheme, authorization_matches):
    repository = GameModelRepository(settings.game_models_dir) if settings.game_models_dir else None

    def require_service(request):
        if settings.require_tls and not secure_scheme(request.scope):
            raise GatewayError("https_required", "Use HTTPS for this service.", http_status=426)
        if repository is None:
            raise GatewayError("models_unconfigured", "游戏识别资源尚未部署。", http_status=503)

    @app.post("/v1/game-models/ticket")
    async def ticket(request: Request):
        require_service(request)
        if not authorization_matches(request.headers.get("authorization"), settings.device_tokens):
            if not settings.game_models_public or public_quota is None:
                raise GatewayError("unauthorized", "此设备尚未获得模型下载权限。", http_status=401)
            identity = installation_key(request.headers.get("x-sensefield-installation"))
            try:
                # Shares the persisted installation/IP-independent quota policy.
                await asyncio.to_thread(public_quota.charge, "game-models:" + identity)
            except PublicQuotaExceeded:
                raise GatewayError("model_quota_exceeded", "下载请求过于频繁，请稍后重试。", http_status=429) from None
        try:
            data = await request.json()
            game, version = data["game"], data["version"]
            if not isinstance(game, str) or not ID.fullmatch(game) or not isinstance(version, str) or not VERSION.fullmatch(version):
                raise ValueError
        except (ValueError, TypeError, KeyError):
            raise GatewayError("invalid_request", "资源请求格式无效。", http_status=400) from None
        return JSONResponse(repository.ticket(game, version), headers={"Cache-Control": "no-store"})

    @app.get("/v1/game-models/{game}/{version}/{name}")
    async def download(request: Request, game: str, version: str, name: str):
        require_service(request)
        auth = request.headers.get("authorization", "")
        repository.authorize(auth.removeprefix("Bearer "), game, version)
        item = repository.files.get((game, version, name))
        if item is None:
            raise GatewayError("model_file_unavailable", "资源文件不存在。", http_status=404)
        path, size, checksum, modified = item
        if not path.is_file() or path.stat().st_size != size or path.stat().st_mtime_ns != modified:
            raise GatewayError("model_file_changed", "资源文件已变化，请重新部署资源。", http_status=503)
        start = 0
        range_header = request.headers.get("range")
        if range_header:
            match = re.fullmatch(r"bytes=(\d+)-", range_header)
            if match is None or int(match[1]) >= size:
                return JSONResponse({"error": {"code": "invalid_range"}}, status_code=416, headers={"Content-Range": f"bytes */{size}"})
            start = int(match[1])
        def chunks():
            with path.open("rb") as source:
                source.seek(start)
                remaining = size - start
                while remaining:
                    block = source.read(min(65536, remaining))
                    if not block:
                        raise IOError("Game resource truncated during transfer")
                    remaining -= len(block)
                    yield block
        headers = {"Content-Length": str(size - start), "Accept-Ranges": "bytes", "ETag": f'"{checksum}"', "Cache-Control": "private, no-store"}
        if range_header:
            headers["Content-Range"] = f"bytes {start}-{size - 1}/{size}"
        return StreamingResponse(chunks(), media_type="application/octet-stream", headers=headers, status_code=206 if range_header else 200)
