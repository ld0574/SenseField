"""WebSocket audio protocol and bounded streaming-ASR queue."""

from __future__ import annotations

import asyncio
import json
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Any, Protocol

from .errors import AsrBusy

_SAMPLE_RATE = 16_000
_BYTES_PER_SAMPLE = 2
_CHUNK_BYTES = int(_SAMPLE_RATE * 0.6) * _BYTES_PER_SAMPLE
_MAX_AUDIO_MESSAGE_BYTES = 96 * 1024
_QUEUE_CAPACITY = 3
_BACKLOG_LIMIT_SECONDS = 2.0
_MAX_REMEMBERED_AUDIO_SESSIONS = 1024


class StreamingRecognizer(Protocol):
    def new_cache(self) -> dict[str, Any]: ...

    async def transcribe(self, pcm16le: bytes, *, cache: dict[str, Any], is_final: bool) -> str: ...


@dataclass
class Utterance:
    turn_id: str
    generation: int
    started_at: float
    cache: dict[str, Any]
    speech_ended_at: float | None = None
    inference_ms: int = 0
    buffered: bytearray = field(default_factory=bytearray)
    pending_full_chunk: bytes | None = None
    latest_text: str = ""
    cancelled: bool = False


@dataclass(frozen=True)
class AsrJob:
    utterance: Utterance
    pcm16le: bytes
    is_final: bool


class AudioProtocolError(ValueError):
    pass


def _merge_recognized_text(previous: str, update: str) -> str:
    """Merge incremental ASR pieces and cumulative hypotheses without duplication."""
    previous = previous.strip()
    update = update.strip()
    if not previous:
        return update
    if not update:
        return previous
    if update.startswith(previous):
        return update
    if previous.startswith(update):
        return previous
    overlap = min(len(previous), len(update))
    while overlap and previous[-overlap:] != update[:overlap]:
        overlap -= 1
    return previous + update[overlap:]


class GenerationHighWater:
    """Bounded monotonic generation memory for reconnecting audio sessions."""

    def __init__(self, capacity: int = _MAX_REMEMBERED_AUDIO_SESSIONS):
        if capacity < 1:
            raise ValueError("capacity must be positive")
        self.capacity = capacity
        self._generations: OrderedDict[str, int] = OrderedDict()

    def accept(self, session_id: str, generation: int) -> bool:
        previous = self._generations.get(session_id)
        if previous is not None and generation < previous:
            self._generations.move_to_end(session_id)
            return False
        self._generations[session_id] = max(generation, previous if previous is not None else generation)
        self._generations.move_to_end(session_id)
        while len(self._generations) > self.capacity:
            self._generations.popitem(last=False)
        return True


def _safe_id(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 128:
        raise AudioProtocolError(f"{field_name} is invalid")
    if not all(char.isalnum() or char in "._:-" for char in value):
        raise AudioProtocolError(f"{field_name} is invalid")
    return value


def validate_start(payload: Any) -> tuple[str, int]:
    if not isinstance(payload, dict) or payload.get("type") != "start":
        raise AudioProtocolError("first message must be a start object")
    session_id = _safe_id(payload.get("session_id"), "session_id")
    generation = payload.get("generation")
    sample_rate = payload.get("sample_rate")
    if isinstance(generation, bool) or not isinstance(generation, int) or not 0 <= generation <= 2**31 - 1:
        raise AudioProtocolError("generation is invalid")
    if sample_rate != _SAMPLE_RATE:
        raise AudioProtocolError("sample_rate must be 16000")
    return session_id, generation


class AudioWebSocketSession:
    """One authenticated WebSocket session, with independent utterance caches."""

    def __init__(
        self,
        session_id: str,
        generation: int,
        recognizer: StreamingRecognizer,
        *,
        generation_high_water: GenerationHighWater | None = None,
        clock=time.monotonic,
    ):
        self.session_id = session_id
        self.generation = generation
        self.recognizer = recognizer
        self.generation_high_water = generation_high_water
        self._clock = clock
        self._current: Utterance | None = None
        self._jobs: asyncio.Queue[AsrJob | None] = asyncio.Queue(maxsize=_QUEUE_CAPACITY)
        self._outgoing: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=64)
        self._processing_started_at: float | None = None
        self._worker_task: asyncio.Task[None] | None = None
        self._processing_job: AsrJob | None = None
        self._closing = False

    async def run(self, websocket: Any) -> None:
        self._worker_task = asyncio.create_task(self._worker())
        receive_task = asyncio.create_task(websocket.receive())
        send_task = asyncio.create_task(self._outgoing.get())
        try:
            while not self._closing:
                done, _pending = await asyncio.wait(
                    {receive_task, send_task}, return_when=asyncio.FIRST_COMPLETED
                )
                if send_task in done:
                    message = send_task.result()
                    await websocket.send_json(message)
                    self._outgoing.task_done()
                    send_task = asyncio.create_task(self._outgoing.get())
                if receive_task in done:
                    packet = receive_task.result()
                    if packet.get("type") == "websocket.disconnect":
                        break
                    receive_task = asyncio.create_task(self._receive_packet(packet))
                    # _receive_packet processes control events directly and
                    # queues PCM jobs without blocking ASR inference.
                    await receive_task
                    receive_task = asyncio.create_task(websocket.receive())
        finally:
            self._closing = True
            if receive_task and not receive_task.done():
                receive_task.cancel()
            if send_task and not send_task.done():
                send_task.cancel()
            self._cancel_all_jobs()
            if self._worker_task is not None:
                self._worker_task.cancel()
                try:
                    await self._worker_task
                except (asyncio.CancelledError, Exception):
                    pass

    async def _receive_packet(self, packet: dict[str, Any]) -> None:
        if packet.get("bytes") is not None:
            await self._handle_audio(packet["bytes"])
            return
        text = packet.get("text")
        if not isinstance(text, str) or len(text) > 4096:
            await self._emit_status(self._current, "invalid_message")
            return
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            await self._emit_status(self._current, "invalid_message")
            return
        if not isinstance(payload, dict):
            await self._emit_status(self._current, "invalid_message")
            return
        event_type = payload.get("type")
        if event_type not in {"speech_start", "speech_end", "reset"}:
            await self._emit_status(self._current, "unsupported_event")
            return
        try:
            turn_id = _safe_id(payload.get("turn_id"), "turn_id")
            generation = payload.get("generation")
            if isinstance(generation, bool) or not isinstance(generation, int) or not 0 <= generation <= 2**31 - 1:
                raise AudioProtocolError("generation is invalid")
        except AudioProtocolError:
            await self._emit_status(self._current, "invalid_event")
            return
        if event_type == "speech_start":
            await self._speech_start(turn_id, generation)
        elif event_type == "speech_end":
            await self._speech_end(turn_id, generation)
        else:
            await self._reset(turn_id, generation)

    async def _speech_start(self, turn_id: str, generation: int) -> None:
        if generation < self.generation or (
            self.generation_high_water is not None
            and not self.generation_high_water.accept(self.session_id, generation)
        ):
            await self._emit_status_for(turn_id, generation, "stale_generation")
            return
        if self._current is not None:
            self._current.cancelled = True
        if generation > self.generation:
            self._cancel_all_jobs()
            self.generation = generation
        self._current = Utterance(turn_id, generation, self._clock(), self.recognizer.new_cache())

    async def _speech_end(self, turn_id: str, generation: int) -> None:
        utterance = self._current
        if utterance is None or generation != self.generation or utterance.turn_id != turn_id:
            await self._emit_status_for(turn_id, generation, "stale_turn")
            return
        utterance.speech_ended_at = self._clock()
        self._current = None
        if utterance.cancelled:
            await self._emit_final(utterance, "", self._elapsed_ms(utterance))
            return
        tail = bytes(utterance.buffered)
        utterance.buffered.clear()
        if len(tail) % _BYTES_PER_SAMPLE:
            utterance.cancelled = True
            await self._emit_status_for(turn_id, generation, "invalid_audio_frame")
            await self._emit_final(utterance, "", 0)
            return
        try:
            if tail:
                if utterance.pending_full_chunk is not None:
                    if not self._enqueue(AsrJob(utterance, utterance.pending_full_chunk, False)):
                        await self._backlog_reset(utterance)
                        return
                    utterance.pending_full_chunk = None
                if not self._enqueue(AsrJob(utterance, tail, True)):
                    await self._backlog_reset(utterance)
                    return
            elif utterance.pending_full_chunk is not None:
                if not self._enqueue(AsrJob(utterance, utterance.pending_full_chunk, True)):
                    await self._backlog_reset(utterance)
                    return
                utterance.pending_full_chunk = None
            else:
                await self._emit_final(utterance, "", self._elapsed_ms(utterance))
        except RuntimeError:
            await self._backlog_reset(utterance)

    async def _reset(self, turn_id: str, generation: int) -> None:
        # Reset is a cancellation barrier: only a strictly newer generation
        # may cross it. Equal-generation resets can arrive late and must not
        # cancel a newer turn that already owns that generation.
        if generation <= self.generation:
            await self._emit_status_for(turn_id, generation, "stale_reset")
            return
        if self.generation_high_water is not None and not self.generation_high_water.accept(self.session_id, generation):
            await self._emit_status_for(turn_id, generation, "stale_generation")
            return
        self.generation = generation
        current = self._current
        if current is not None:
            current.cancelled = True
            self._current = None
        self._cancel_all_jobs()
        await self._emit_status_for(turn_id, generation, "reset")

    async def _handle_audio(self, pcm16le: bytes) -> None:
        utterance = self._current
        if utterance is None or utterance.cancelled:
            return
        if len(pcm16le) > _MAX_AUDIO_MESSAGE_BYTES or len(pcm16le) % _BYTES_PER_SAMPLE:
            utterance.cancelled = True
            self._current = None
            await self._emit_status(utterance, "invalid_audio_frame")
            await self._emit_final(utterance, "", self._elapsed_ms(utterance))
            return
        utterance.buffered.extend(pcm16le)
        while len(utterance.buffered) >= _CHUNK_BYTES:
            block = bytes(utterance.buffered[:_CHUNK_BYTES])
            del utterance.buffered[:_CHUNK_BYTES]
            if utterance.pending_full_chunk is not None:
                if not self._enqueue(AsrJob(utterance, utterance.pending_full_chunk, False)):
                    await self._backlog_reset(utterance)
                    return
            utterance.pending_full_chunk = block

    def _enqueue(self, job: AsrJob) -> bool:
        now = self._clock()
        queued_duration = self._jobs.qsize() * 0.6
        active_duration = now - self._processing_started_at if self._processing_started_at is not None else 0.0
        if queued_duration + active_duration >= _BACKLOG_LIMIT_SECONDS or self._jobs.full():
            return False
        try:
            self._jobs.put_nowait(job)
            return True
        except asyncio.QueueFull:
            return False

    async def _backlog_reset(self, utterance: Utterance) -> None:
        utterance.cancelled = True
        if self._current is utterance:
            self._current = None
        self._cancel_all_jobs()
        await self._emit_status(utterance, "asr_backlog_reset")
        await self._emit_final(utterance, "", self._elapsed_ms(utterance))

    async def _worker(self) -> None:
        while True:
            job = await self._jobs.get()
            try:
                if job is None:
                    return
                if job.utterance.cancelled:
                    continue
                self._processing_job = job
                self._processing_started_at = self._clock()
                inference_started_at = self._processing_started_at
                asr_busy = False
                try:
                    text = await self.recognizer.transcribe(
                        job.pcm16le,
                        cache=job.utterance.cache,
                        is_final=job.is_final,
                    )
                except AsrBusy:
                    text = ""
                    asr_busy = True
                    job.utterance.cancelled = True
                    self._cancel_jobs_for(job.utterance)
                    await self._emit_status(job.utterance, "asr_busy")
                except Exception:
                    text = ""
                    await self._emit_status(job.utterance, "asr_error")
                finally:
                    job.utterance.inference_ms += max(
                        0,
                        int((self._clock() - inference_started_at) * 1000),
                    )
                    self._processing_started_at = None
                    self._processing_job = None
                if job.utterance.cancelled:
                    if asr_busy and job.utterance.speech_ended_at is not None:
                        await self._emit_final(
                            job.utterance,
                            "",
                            self._elapsed_ms(job.utterance),
                        )
                    continue
                if text:
                    job.utterance.latest_text = _merge_recognized_text(job.utterance.latest_text, text)
                if job.is_final:
                    await self._emit_final(
                        job.utterance,
                        job.utterance.latest_text,
                        self._elapsed_ms(job.utterance),
                    )
                elif text:
                    await self._emit(
                        "partial",
                        job.utterance,
                        job.utterance.latest_text,
                        self._elapsed_ms(job.utterance),
                    )
            finally:
                self._jobs.task_done()
                job = None

    def _cancel_all_jobs(self) -> None:
        if self._processing_job is not None:
            self._processing_job.utterance.cancelled = True
        while True:
            try:
                job = self._jobs.get_nowait()
            except asyncio.QueueEmpty:
                break
            if job is not None:
                job.utterance.cancelled = True
            self._jobs.task_done()

    def _cancel_jobs_for(self, utterance: Utterance) -> None:
        retained: list[AsrJob] = []
        while True:
            try:
                job = self._jobs.get_nowait()
            except asyncio.QueueEmpty:
                break
            if job is not None and job.utterance is utterance:
                job.utterance.cancelled = True
            elif job is not None:
                retained.append(job)
            self._jobs.task_done()
        for job in retained:
            self._jobs.put_nowait(job)

    async def _emit_final(self, utterance: Utterance, text: str, asr_ms: int) -> None:
        finalization_ms = None
        if utterance.speech_ended_at is not None:
            finalization_ms = self._elapsed_since_ms(utterance.speech_ended_at)
        await self._emit("final", utterance, text, asr_ms, finalization_ms=finalization_ms)

    async def _emit_status(self, utterance: Utterance | None, reason: str) -> None:
        if utterance is None:
            return
        await self._emit("status", utterance, "", self._elapsed_ms(utterance), reason=reason)

    async def _emit_status_for(self, turn_id: str, generation: int, reason: str) -> None:
        await self._emit_raw({
            "type": "status",
            "session_id": self.session_id,
            "generation": generation,
            "turn_id": turn_id,
            "text": "",
            "asr_ms": 0,
            "inference_ms": 0,
            "status": reason,
            "reason": reason,
        })

    async def _emit(
        self,
        event_type: str,
        utterance: Utterance,
        text: str,
        asr_ms: int,
        *,
        reason: str | None = None,
        finalization_ms: int | None = None,
    ) -> None:
        payload: dict[str, Any] = {
            "type": event_type,
            "session_id": self.session_id,
            "generation": utterance.generation,
            "turn_id": utterance.turn_id,
            "text": text,
            "asr_ms": max(0, asr_ms),
            "inference_ms": max(0, utterance.inference_ms),
        }
        if finalization_ms is not None:
            payload["finalization_ms"] = max(0, finalization_ms)
        if reason:
            payload["status"] = reason
            payload["reason"] = reason
        await self._emit_raw(payload)

    async def _emit_raw(self, payload: dict[str, Any]) -> None:
        if self._outgoing.full():
            try:
                self._outgoing.get_nowait()
                self._outgoing.task_done()
            except asyncio.QueueEmpty:
                pass
        await self._outgoing.put(payload)

    def _elapsed_ms(self, utterance: Utterance) -> int:
        return int(max(0.0, self._clock() - utterance.started_at) * 1000)

    def _elapsed_since_ms(self, started_at: float) -> int:
        return int(max(0.0, self._clock() - started_at) * 1000)
