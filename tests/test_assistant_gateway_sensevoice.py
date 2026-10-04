from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from mapassist.assistant_gateway.sensevoice import SenseVoiceRecognizer


class RecordingRecognizer(SenseVoiceRecognizer):
    def __init__(self):
        super().__init__(model_dir=None)
        self.model = object()
        self.calls: list[bytes] = []

    def _generate(self, pcm: bytes, _cache: dict, _final: bool) -> str:
        self.calls.append(pcm)
        return "test result"


def test_final_only_backend_keeps_utterances_isolated_and_flushes_last_audio():
    async def run():
        recognizer = RecordingRecognizer()
        first, second = recognizer.new_cache(), recognizer.new_cache()
        assert await recognizer.transcribe(b"\x01\x00", cache=first, is_final=False) == ""
        assert await recognizer.transcribe(b"\x02\x00", cache=second, is_final=False) == ""
        assert recognizer.calls == []
        assert await recognizer.transcribe(b"\x03\x00", cache=first, is_final=True) == "test result"
        assert await recognizer.transcribe(b"", cache=second, is_final=True) == "test result"
        assert recognizer.calls == [b"\x01\x00\x03\x00", b"\x02\x00"]
        assert first == second == {}
    asyncio.run(run())


def test_oversize_or_odd_pcm_clears_buffer_without_native_work():
    async def run():
        recognizer = RecordingRecognizer()
        cache = recognizer.new_cache()
        await recognizer.transcribe(bytes(16 * 16000 * 2), cache=cache, is_final=False)
        with pytest.raises(ValueError, match="16-second"):
            await recognizer.transcribe(bytes(2), cache=cache, is_final=True)
        assert cache == {} and recognizer.calls == []
        await recognizer.transcribe(bytes(2), cache=cache, is_final=False)
        with pytest.raises(ValueError, match="16-bit"):
            await recognizer.transcribe(bytes(1), cache=cache, is_final=True)
        assert cache == {} and recognizer.calls == []
    asyncio.run(run())


def test_unpinned_or_missing_model_fails_before_native_initialization(tmp_path: Path):
    recognizer = SenseVoiceRecognizer(model_dir=tmp_path)
    with pytest.raises(RuntimeError, match="snapshot"):
        recognizer._load_model()
