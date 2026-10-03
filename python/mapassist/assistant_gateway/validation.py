"""Protocol request validation and conservative vision-result filtering."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from .images import NormalizedImage, normalize_image

_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_TACTICAL_RE = re.compile(
    r"\b(?:enemy|enemies|opponent|opponents|foe|foes|flank|attack|shoot|push|rush|rotate|"
    r"kill|eliminate|move|go|head|turn|retreat|advance|hold|camp|reload|equip|deploy|"
    r"activate|throw|aim|crouch|sprint|use)\b|"
    r"\b(?:north|south|east|west|northeast|northwest|southeast|southwest|"
    r"left|right|behind|ahead|nearby|above|below|upward|downward)\b|"
    r"\b(?:you should|you can|try to|recommend|suggest|consider|I recommend|I suggest)\b|"
    r"\b(?:opposing|opposition|hostile)\s+(?:team|player|squad|unit)\b.{0,24}\b(?:at|near|in|on|to)\b|"
    # The model is prompted in Simplified Chinese, so scope checks must cover
    # Chinese terms too; unlike English, these phrases have no word boundaries.
    r"敌人|敌方|敌军|对手|敌队|敌兵|敌人位置|对手位置|"
    r"左上|右上|左下|右下|左侧|右侧|左边|右边|正前方|前方|后方|前面|后面|背后|身后|附近|"
    r"东北|西北|东南|西南|北边|南边|东边|西边|上方|下方|楼上|楼下|"
    r"(?:向|往|朝)(?:左|右|上|下|前|后|东|西|南|北)|"
    r"建议.{0,16}(?:撤退|前进|推进|移动|转点|绕后|进攻|攻击|开火|射击|瞄准|守住|防守|换弹|冲锋)|"
    r"(?:撤退|撤离|前进|推进|冲锋|绕后|包抄|转点|守住|架枪|埋伏|集火|开火|射击|瞄准|换弹|装弹|投掷|蹲下|趴下|冲刺|部署|释放技能|攻击敌人|进攻敌方)",
    re.IGNORECASE,
)
_SENTENCE_END_RE = re.compile(r"[.!?。！？]+")
UNKNOWN_ANSWER = "无法从清晰可见的界面文字中可靠确认。"


class InvalidRequest(ValueError):
    pass


@dataclass(frozen=True)
class VisualRequest:
    session_id: str
    generation: int
    turn_id: str
    frame_id: str
    question: str
    frame_age_ms: int
    proactive: bool
    image: NormalizedImage


@dataclass(frozen=True)
class VisualAnswer:
    kind: str
    answer: str
    uncertain: bool


def _require_id(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not _ID_RE.fullmatch(value):
        raise InvalidRequest(f"{field_name} must be 1-128 letters, digits, or . _ : - characters")
    return value


def validate_visual_request(payload: Any) -> VisualRequest:
    if not isinstance(payload, dict):
        raise InvalidRequest("request must be a JSON object")
    session_id = _require_id(payload.get("session_id"), "session_id")
    turn_id = _require_id(payload.get("turn_id"), "turn_id")
    frame_id = _require_id(payload.get("frame_id"), "frame_id")
    generation = payload.get("generation")
    if isinstance(generation, bool) or not isinstance(generation, int) or not 0 <= generation <= 2**31 - 1:
        raise InvalidRequest("generation must be a non-negative integer")
    age = payload.get("frame_age_ms")
    if isinstance(age, bool) or not isinstance(age, int) or not 0 <= age <= 60_000:
        raise InvalidRequest("frame_age_ms must be an integer from 0 to 60000")
    proactive = payload.get("proactive")
    if not isinstance(proactive, bool):
        raise InvalidRequest("proactive must be a boolean")
    question = payload.get("question")
    if not isinstance(question, str):
        raise InvalidRequest("question must be a string")
    question = _CONTROL_RE.sub("", question).strip()
    if not question or len(question) > 500:
        raise InvalidRequest("question must contain 1-500 characters")
    try:
        image = normalize_image(payload.get("image_base64"))
    except ValueError as exc:
        raise InvalidRequest(str(exc)) from exc
    return VisualRequest(session_id, generation, turn_id, frame_id, question, age, proactive, image)


def parse_visual_answer(raw: str) -> VisualAnswer:
    if not isinstance(raw, str):
        raise InvalidRequest("model response must be text")
    content = raw.strip()
    if content.startswith("```") and content.endswith("```"):
        content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content, flags=re.IGNORECASE).strip()
    try:
        value = json.loads(content)
    except json.JSONDecodeError as exc:
        raise InvalidRequest("model response was not valid JSON") from exc
    if not isinstance(value, dict):
        raise InvalidRequest("model response must be a JSON object")
    kind = value.get("kind")
    answer = value.get("answer")
    uncertain = value.get("uncertain")
    if kind not in {"hud", "ui_text", "unknown"} or not isinstance(answer, str) or not isinstance(uncertain, bool):
        raise InvalidRequest("model response did not match the required schema")
    answer = _CONTROL_RE.sub("", answer).strip()
    if len(answer) > 320 or len(_SENTENCE_END_RE.findall(answer)) > 2:
        return VisualAnswer("unknown", UNKNOWN_ANSWER, True)
    if kind == "unknown":
        return VisualAnswer("unknown", "", True)
    if not answer or _TACTICAL_RE.search(answer):
        return VisualAnswer("unknown", UNKNOWN_ANSWER, True)
    return VisualAnswer(kind, answer, uncertain)
