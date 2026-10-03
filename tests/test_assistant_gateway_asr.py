from __future__ import annotations

import asyncio
import sys
import threading
import time
import pytest

from mapassist.assistant_gateway.asr import FunAsrStreamingRecognizer
from mapassist.assistant_gateway.errors import AsrBusy


class _Samples:
    def astype(self, _kind: object) -> "_Samples":
        return self

    def __truediv__(self, _value: int) -> "_Samples":
        return self


class _FakeNumpy:
    float32 = object()

    @staticmethod
    def frombuffer(_data: bytes, *, dtype: str) -> _Samples:
        assert dtype == "<i2"
        return _Samples()


def test_funasr_generate_runs_off_the_event_loop(monkeypatch: pytest.MonkeyPatch) -> None:
    entered = threading.Event()

    class BlockingModel:
        def generate(self, **kwargs: object) -> list[dict[str, str]]:
            entered.set()
            time.sleep(0.15)
            return [{"text": "heard"}]

    monkeypatch.setitem(sys.modules, "numpy", _FakeNumpy())
    recognizer = FunAsrStreamingRecognizer()
    recognizer.model = BlockingModel()

    async def scenario() -> None:
        recognizer_task = asyncio.create_task(recognizer.transcribe(bytes(4), cache={}, is_final=True))
        await asyncio.wait_for(asyncio.to_thread(entered.wait), timeout=0.5)
        event_loop_ran = asyncio.Event()
        asyncio.get_running_loop().call_soon(event_loop_ran.set)
        await asyncio.wait_for(event_loop_ran.wait(), timeout=0.05)
        assert not recognizer_task.done()
        assert await recognizer_task == "heard"

    asyncio.run(scenario())


def test_funasr_admission_is_fail_fast_and_cancelled_thread_keeps_its_slot(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    entered = threading.Event()
    release = threading.Event()
    completed = threading.Event()

    class BlockingModel:
        calls = 0

        def generate(self, **kwargs: object) -> list[dict[str, str]]:
            self.calls += 1
            if self.calls == 1:
                entered.set()
                release.wait()
            completed.set()
            return [{"text": "heard"}]

    monkeypatch.setitem(sys.modules, "numpy", _FakeNumpy())
    recognizer = FunAsrStreamingRecognizer()
    model = BlockingModel()
    recognizer.model = model

    async def scenario() -> None:
        first = asyncio.create_task(recognizer.transcribe(bytes(4), cache={}, is_final=True))
        assert await asyncio.to_thread(entered.wait, 1)
        with pytest.raises(AsrBusy):
            await recognizer.transcribe(bytes(4), cache={}, is_final=True)

        # Cancelling the request does not free admission while its native
        # generate call is still running on the executor thread.
        first.cancel()
        with pytest.raises(asyncio.CancelledError):
            await first
        with pytest.raises(AsrBusy):
            await recognizer.transcribe(bytes(4), cache={}, is_final=True)
        assert model.calls == 1

        release.set()
        assert await asyncio.to_thread(completed.wait, 1)
        await asyncio.sleep(0)
        assert await recognizer.transcribe(bytes(4), cache={}, is_final=True) == "heard"
        assert model.calls == 2

    try:
        asyncio.run(scenario())
    finally:
        release.set()
