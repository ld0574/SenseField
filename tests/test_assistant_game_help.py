from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
from types import SimpleNamespace

import pytest

from mapassist.assistant_gateway.config import GatewaySettings
from mapassist.assistant_gateway.glm import SYSTEM_PROMPT
from mapassist.assistant_gateway.test_text_trace import TestTextTraceRecorder
from mapassist.assistant_gateway.validation import parse_visual_answer
from mapassist.assistant_gateway.app import _vision_call
from mapassist.assistant_gateway.request_trace import visual_request_context


@pytest.mark.parametrize("answer", ["不可阻挡", "屏幕中央显示【不可阻挡】，是连杀提示。", "画面出现超神字样。"])
def test_game_help_does_not_accept_banner_transcription_as_advice(answer):
    result = parse_visual_answer(json.dumps({"kind": "hud", "answer": answer, "uncertain": False}),
                                 question="请分析当前对战打法")
    assert result.uncertain
    assert result.kind == "unknown"


def test_banner_meaning_can_be_answered_when_explicitly_asked():
    result = parse_visual_answer('{"kind":"ui_text","answer":"不可阻挡是连杀提示。","uncertain":false}',
                                 question="不可阻挡是什么意思")
    assert result.answer == "不可阻挡是连杀提示。"
    assert not result.uncertain


@pytest.mark.parametrize("question", ["请把屏幕上方横幅念出来", "读一下横幅内容", "解释不可阻挡"])
def test_explicit_banner_transcription_intent_is_not_filtered(question):
    result = parse_visual_answer('{"kind":"ui_text","answer":"不可阻挡","uncertain":false}',
                                 question=question)
    assert result.answer == "不可阻挡" and not result.uncertain


def test_banner_mentioned_in_strategy_question_is_not_a_transcription_request():
    result = parse_visual_answer('{"kind":"hud","answer":"不可阻挡","uncertain":false}',
                                 question="不可阻挡时我下一步怎么做")
    assert result.uncertain and result.kind == "unknown"


def test_banner_mention_does_not_remove_useful_advice():
    result = parse_visual_answer('{"kind":"hud","answer":"先跟前排推进。不可阻挡是连杀提示。","uncertain":false}',
                                 question="请分析当前对战打法")
    assert result.answer.startswith("先跟前排推进")
    assert not result.uncertain


def test_game_prompt_prioritizes_short_advice_and_excludes_decorative_banners():
    assert "FIRST sentence" in SYSTEM_PROMPT
    assert "不可阻挡" in SYSTEM_PROMPT
    assert "60 Chinese characters" in SYSTEM_PROMPT
    assert "transcribe particular visible text take precedence" in SYSTEM_PROMPT


def _request():
    return SimpleNamespace(session_id="test-session", generation=2, turn_id="g2-t3",
                           frame_id="4", proactive=False, question="应该买哪件装备？",
                           image_base64="private-image-not-for-logging", token="private-token-not-for-logging")


def test_private_text_trace_keeps_question_raw_reply_final_reply_and_terminal(tmp_path):
    recorder = TestTextTraceRecorder(tmp_path)
    request = _request()
    assert recorder.record("request", request, image_base64=request.image_base64, token=request.token)
    assert recorder.record("model_response", request, raw_answer='{"answer":"建议先买鞋"}')
    assert recorder.record("validated_response", request, answer="建议先买鞋", kind="hud", uncertain=False)
    assert recorder.record("terminal", request, code="cancelled", stage="provider", elapsed_ms=32)
    rows = [json.loads(line) for line in recorder.path.read_text().splitlines()]
    assert [row["event"] for row in rows] == ["request", "model_response", "validated_response", "terminal"]
    assert rows[0]["question"] == request.question
    assert rows[1]["raw_answer"] == '{"answer":"建议先买鞋"}'
    assert rows[2]["answer"] == "建议先买鞋"
    raw = recorder.path.read_text()
    assert request.token not in raw and request.image_base64 not in raw
    assert os.stat(recorder.path).st_mode & 0o777 == 0o600


def test_private_trace_stops_at_time_and_byte_limits(tmp_path):
    clock = [10.0]
    recorder = TestTextTraceRecorder(tmp_path, seconds=5, clock=lambda: clock[0], max_bytes=600)
    assert recorder.record("request", _request())
    clock[0] = 15.0
    assert not recorder.record("terminal", _request(), code="expired")
    recorder2 = TestTextTraceRecorder(tmp_path, max_bytes=10)
    assert not recorder2.record("request", _request())
    assert recorder2.path.stat().st_size == 0


def test_private_trace_does_not_follow_replaced_file_symlink(tmp_path):
    recorder = TestTextTraceRecorder(tmp_path)
    unrelated = tmp_path / "unrelated.txt"
    unrelated.write_text("keep")
    recorder.path.unlink()
    recorder.path.symlink_to(unrelated)
    assert not recorder.record("request", _request())
    assert unrelated.read_text() == "keep"


def test_private_trace_does_not_block_on_replaced_fifo(tmp_path):
    recorder = TestTextTraceRecorder(tmp_path)
    recorder.path.unlink()
    os.mkfifo(recorder.path)
    assert not recorder.record("request", _request())


def test_private_trace_rejects_widened_file_permissions(tmp_path):
    recorder = TestTextTraceRecorder(tmp_path)
    recorder.path.chmod(0o644)
    assert not recorder.record("request", _request())
    assert recorder.path.stat().st_size == 0


def test_private_trace_disabled_by_default_and_explicit_env_can_enable(monkeypatch, tmp_path):
    monkeypatch.delenv("ASSISTANT_GATEWAY_TEST_TEXT_LOG_DIR", raising=False)
    assert GatewaySettings.from_env().test_text_log_dir is None
    monkeypatch.setenv("ASSISTANT_GATEWAY_TEST_TEXT_LOG_DIR", str(tmp_path))
    monkeypatch.setenv("ASSISTANT_GATEWAY_TEST_TEXT_LOG_SECONDS", "1800")
    settings = GatewaySettings.from_env()
    assert settings.test_text_log_dir == tmp_path and settings.test_text_log_seconds == 1800
    monkeypatch.setenv("ASSISTANT_GATEWAY_TEST_TEXT_LOG_SECONDS", "7200")
    with pytest.raises(ValueError):
        GatewaySettings.from_env()


def test_provider_context_isolated_between_tasks_and_reset_on_cancel():
    async def scenario():
        entered = asyncio.Event()
        seen = []

        class Vision:
            async def complete(self, **kwargs):
                before = visual_request_context.get()
                if kwargs["question"] == "cancel-me":
                    entered.set()
                    await asyncio.Event().wait()
                await asyncio.sleep(0)
                seen.append((before, visual_request_context.get()))
                return "ok"

        def request(session, question):
            return SimpleNamespace(session_id=session, generation=2, turn_id="t2",
                                   frame_id="f3", question=question, frame_age_ms=0,
                                   context_frames=(), image=SimpleNamespace(jpeg_base64="PRIVATE"))

        outer = ("outer", 0, "outer-turn", "outer-frame")
        token = visual_request_context.set(outer)
        try:
            cancelled = asyncio.create_task(_vision_call(Vision(), request("cancel", "cancel-me")))
            await entered.wait()
            assert await asyncio.gather(
                _vision_call(Vision(), request("session-a", "a")),
                _vision_call(Vision(), request("session-b", "b")),
            ) == ["ok", "ok"]
            cancelled.cancel()
            with pytest.raises(asyncio.CancelledError):
                await cancelled
            assert visual_request_context.get() == outer
            assert sorted(seen) == [
                (("session-a", 2, "t2", "f3"), ("session-a", 2, "t2", "f3")),
                (("session-b", 2, "t2", "f3"), ("session-b", 2, "t2", "f3")),
            ]
        finally:
            visual_request_context.reset(token)
        assert visual_request_context.get() is None

    asyncio.run(scenario())
