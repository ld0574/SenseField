import json

import pytest

pytest.importorskip("httpx")

from mapassist.assistant_gateway.compatible import CompatibleVisionClient
from mapassist.assistant_gateway.config import GatewaySettings
from mapassist.assistant_gateway.prompts import load_vision_prompt
from mapassist.assistant_gateway.validation import InvalidRequest, parse_visual_answer, is_immediate_combat_question


def test_configured_chinese_prompt_is_sent_without_provider_specific_rewriting(tmp_path, monkeypatch):
    prompt = tmp_path / "搭子提示词.txt"
    prompt.write_text("你是为低视力用户服务的游戏搭子。请根据实际画面回答。", encoding="utf-8")
    monkeypatch.setenv("ASSISTANT_GATEWAY_VISION_PROMPT_FILE", str(prompt))
    settings = GatewaySettings.from_env()
    client = CompatibleVisionClient("test-only", base_url="https://example.test/v1", model="Kimi-K3",
                                    system_prompt=load_vision_prompt(settings.vision_prompt_file))
    body = client._request_body(question="我擅长辅助，这个阵容选哪个？", image_base64="image")
    assert body["messages"][0]["content"] == prompt.read_text()
    assert body["messages"][1]["content"][0]["text"] == "我擅长辅助，这个阵容选哪个？"


@pytest.mark.parametrize("contents", ["", "\x00", "x" * 32769])
def test_empty_invalid_or_oversize_prompt_fails_explicitly(tmp_path, contents):
    path = tmp_path / "prompt.txt"
    path.write_text(contents)
    with pytest.raises(ValueError):
        load_vision_prompt(path)


@pytest.mark.parametrize("scene", ["equipment", "hero_selection"])
def test_decision_metadata_keeps_legacy_hud_kind(scene):
    answer = parse_visual_answer(json.dumps({"kind": "ui_text", "answer": "先补法术防御，当前主要威胁是法术伤害。",
        "uncertain": False, "decision_scene": scene}), question="接下来怎么选？")
    assert answer.kind == "hud"
    assert answer.decision_scene == scene


@pytest.mark.parametrize("scene", [None, [], {}, "combat", "draft", 1, True])
def test_invalid_scene_never_grants_a_longer_answer_lifetime(scene):
    with pytest.raises(InvalidRequest):
        parse_visual_answer(json.dumps({"kind": "hud", "answer": "先撤退。", "uncertain": False,
                                       "decision_scene": scene}))


@pytest.mark.parametrize("kind,uncertain", [("unknown", True), ("hud", True)])
def test_uncertain_answers_never_keep_decision_scene(kind, uncertain):
    answer = parse_visual_answer(json.dumps({"kind": kind, "answer": "看不清。", "uncertain": uncertain,
                                             "decision_scene": "hero_selection"}))
    assert answer.decision_scene == ""


def test_legacy_hud_transcription_does_not_gain_a_decision_lifetime():
    assert parse_visual_answer('{"kind":"hud","answer":"生命值20。","uncertain":false}').decision_scene == ""


def test_thinking_extension_requires_explicit_compatible_opt_in(monkeypatch):
    monkeypatch.setenv("ASSISTANT_GATEWAY_VISION_DISABLE_THINKING", "1")
    settings = GatewaySettings.from_env()
    client = CompatibleVisionClient("test-only", base_url="https://example.test/v1", model="Kimi-K3",
                                    disable_thinking=settings.vision_disable_thinking)
    assert client._request_body(question="选哪个？", image_base64="image")["thinking"] == {"type": "disabled"}
    monkeypatch.setenv("ASSISTANT_GATEWAY_VISION_DISABLE_THINKING", "automatic")
    with pytest.raises(ValueError):
        GatewaySettings.from_env()


def test_longer_term_build_question_is_not_mistaken_for_instant_combat_action():
    assert not is_immediate_combat_question("现在团战容易死，接下来怎么出装？")
    assert not is_immediate_combat_question("选人时应该选哪个英雄？")
    assert is_immediate_combat_question("现在团战怎么操作？")


@pytest.mark.parametrize("value", ["nan", "inf", "0", "31", "bad"])
def test_invalid_cloud_wait_budget_fails_before_requests(monkeypatch, value):
    monkeypatch.setenv("ASSISTANT_GATEWAY_VISION_TIMEOUT_SECONDS", value)
    with pytest.raises(ValueError):
        GatewaySettings.from_env()
