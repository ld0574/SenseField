"""CPU streaming FunASR adapter pinned to a verified Hugging Face snapshot."""

from __future__ import annotations

import asyncio
import hashlib
import importlib.util
import json
import threading
from pathlib import Path
from typing import Any

from .errors import AsrBusy, GatewayError

_STREAM_CHUNK_SIZE = [0, 10, 5]


def resolve_verified_snapshot(cache_dir: Path | None = None) -> tuple[Path, dict[str, Any]]:
    manifest_path = Path(__file__).with_name("model_manifest.json")
    with manifest_path.open("r", encoding="utf-8") as stream:
        manifest = json.load(stream)
    from huggingface_hub import snapshot_download

    allow_patterns = ["model.pt", "LICENSE", "README.md", "config.yaml", "configuration.json", "tokens.json", "am.mvn", "seg_dict"]
    kwargs: dict[str, Any] = {
        "repo_id": manifest["repo_id"],
        "revision": manifest["revision"],
        "allow_patterns": allow_patterns,
    }
    if cache_dir is not None:
        cache_dir.mkdir(parents=True, exist_ok=True)
        kwargs["cache_dir"] = str(cache_dir)
    snapshot = Path(snapshot_download(**kwargs))
    FunAsrStreamingRecognizer._verify_snapshot(snapshot, manifest)
    return snapshot, manifest


class AsrUnavailable(GatewayError):
    def __init__(self):
        super().__init__("asr_unavailable", "Speech recognition is temporarily unavailable.", http_status=503)


class FunAsrStreamingRecognizer:
    """FunASR Paraformer streaming recognizer, always loaded on CPU."""

    def __init__(self, *, cache_dir: Path | None = None):
        self.cache_dir = cache_dir
        self.model: Any | None = None
        self.model_path: Path | None = None
        self._generate_lock = threading.Lock()
        self._generate_slot = threading.BoundedSemaphore(1)

    async def initialize(self) -> None:
        await asyncio.to_thread(self._load_model)

    def _load_model(self) -> None:
        snapshot, _manifest = resolve_verified_snapshot(self.cache_dir)
        if importlib.util.find_spec("torchaudio") is None and importlib.util.find_spec("kaldi_native_fbank") is None:
            raise RuntimeError("FunASR requires torchaudio or kaldi-native-fbank for feature extraction")
        from funasr import AutoModel

        # A local snapshot path and a full commit SHA prevent alias or hub
        # fallback resolution. CPU is explicit for both M1 and Linux hosts.
        self.model = AutoModel(
            model=str(snapshot),
            device="cpu",
            disable_update=True,
            disable_pbar=True,
            disable_log=True,
        )
        self.model_path = snapshot

    @staticmethod
    def _verify_snapshot(snapshot: Path, manifest: dict[str, Any]) -> None:
        for relative_path, expected in manifest["files"].items():
            path = snapshot / relative_path
            if not path.is_file() or path.stat().st_size != expected["size"]:
                raise RuntimeError(f"Pinned ASR snapshot file is missing or has an unexpected size: {relative_path}")
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(block)
            if digest.hexdigest() != expected["sha256"]:
                raise RuntimeError(f"Pinned ASR snapshot checksum mismatch: {relative_path}")

    def new_cache(self) -> dict[str, Any]:
        return {}

    async def transcribe(self, pcm16le: bytes, *, cache: dict[str, Any], is_final: bool) -> str:
        if self.model is None:
            raise AsrUnavailable()
        if not pcm16le:
            # A zero-length final chunk has no acoustic input. The last held
            # non-empty block is always marked final by AudioWebSocketSession.
            return ""
        # Reject a second session before submitting work to the executor. The
        # shared admission slot stays occupied until the native/threaded call
        # actually finishes, even if its awaiting asyncio task is cancelled.
        if not self._generate_slot.acquire(blocking=False):
            raise AsrBusy()
        try:
            worker = asyncio.create_task(asyncio.to_thread(self._generate, pcm16le, cache, is_final))
        except BaseException:
            self._generate_slot.release()
            raise
        try:
            result = await asyncio.shield(worker)
        except asyncio.CancelledError:
            worker.add_done_callback(self._release_generate_slot)
            raise
        except BaseException:
            self._generate_slot.release()
            raise
        self._generate_slot.release()
        return result

    def _release_generate_slot(self, _worker: asyncio.Task[Any]) -> None:
        self._generate_slot.release()

    def _generate(self, pcm16le: bytes, cache: dict[str, Any], is_final: bool) -> str:
        import numpy as np

        samples = np.frombuffer(pcm16le, dtype="<i2").astype(np.float32) / 32768.0
        with self._generate_lock:
            result = self.model.generate(
                input=samples,
                cache=cache,
                is_final=is_final,
                chunk_size=list(_STREAM_CHUNK_SIZE),
                encoder_chunk_look_back=4,
                decoder_chunk_look_back=1,
            )
        if isinstance(result, dict):
            result = [result]
        if not isinstance(result, list) or not result:
            return ""
        item = result[0]
        if not isinstance(item, dict):
            return ""
        text = item.get("text", "")
        return text.strip() if isinstance(text, str) else ""


class MockStreamingRecognizer:
    """A deliberately local-only adapter for explicit development use."""

    async def initialize(self) -> None:
        return None

    def new_cache(self) -> dict[str, Any]:
        return {}

    async def transcribe(self, pcm16le: bytes, *, cache: dict[str, Any], is_final: bool) -> str:
        del pcm16le, cache, is_final
        return ""
