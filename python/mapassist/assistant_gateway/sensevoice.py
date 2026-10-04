"""Bounded VAD-utterance recognition with pinned SenseVoiceSmall int8 on CPU."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .asr import AsrUnavailable, FunAsrStreamingRecognizer

_MAX_UTTERANCE_BYTES = 16 * 16_000 * 2
_BUFFER_KEY = "sensevoice_pcm"


class SenseVoiceRecognizer(FunAsrStreamingRecognizer):
    """Continuous capture, final-only inference; no partial text or model fallback."""

    def __init__(self, *, model_dir: Path | None):
        super().__init__()
        self.model_dir = model_dir

    def _load_model(self) -> None:
        if self.model_dir is None:
            raise RuntimeError("Set ASSISTANT_GATEWAY_SENSEVOICE_MODEL_DIR")
        manifest = json.loads(Path(__file__).with_name("sensevoice_manifest.json").read_text())
        self._verify_snapshot(self.model_dir, manifest)
        import sherpa_onnx

        self.model = sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=str(self.model_dir / "model.int8.onnx"),
            tokens=str(self.model_dir / "tokens.txt"),
            num_threads=2,
            provider="cpu",
            language="zh",
            use_itn=True,
        )
        self.model_path = self.model_dir

    async def transcribe(self, pcm16le: bytes, *, cache: dict[str, Any], is_final: bool) -> str:
        if self.model is None:
            raise AsrUnavailable()
        if len(pcm16le) % 2:
            cache.pop(_BUFFER_KEY, None)
            raise ValueError("Expected signed 16-bit PCM")
        buffered = cache.setdefault(_BUFFER_KEY, bytearray())
        if not isinstance(buffered, bytearray):
            raise ValueError("Invalid utterance cache")
        if len(buffered) + len(pcm16le) > _MAX_UTTERANCE_BYTES:
            cache.pop(_BUFFER_KEY, None)
            raise ValueError("Utterance exceeds the bounded 16-second audio budget")
        buffered.extend(pcm16le)
        if not is_final:
            return ""
        audio = bytes(cache.pop(_BUFFER_KEY))
        # Reuse the admission/cancellation barrier: a cancelled native call
        # retains its slot until the real thread finishes, suppressing overlap.
        return await super().transcribe(audio, cache=cache, is_final=True)

    def _generate(self, pcm16le: bytes, _cache: dict[str, Any], _is_final: bool) -> str:
        import numpy as np

        samples = np.frombuffer(pcm16le, dtype="<i2").astype(np.float32) / 32768.0
        with self._generate_lock:
            stream = self.model.create_stream()
            stream.accept_waveform(16_000, samples)
            self.model.decode_stream(stream)
            return stream.result.text.strip()
