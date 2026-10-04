"""Protocol request validation and bounded vision-result filtering."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from .images import NormalizedImage, normalize_image

_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_SENTENCE_END_RE = re.compile(r"[.!?。！？]+")
_BUILD_QUESTION_RE = re.compile(
    r"\b(?:build|item|items|equipment|loadout|gear|buy|purchase)\b|出装|配装|装备|"
    r"买什么|买啥|买哪些|买哪件|购买什么|购买哪件|先买|出什么|推荐(?:什么)?装备|怎么出|怎么配装",
    re.IGNORECASE,
)
_BUILD_HUD_ONLY_RE = re.compile(
    r"你|您|目前|当前|现在|仅|只有|还|剩|剩余|还有|拥有|有|为|是|和|与|及|"
    r"不够|不足|充足|很多|不多|较少|还差|差|可用|"
    r"对局时间|游戏时间|倒计时|计时器|金币|金钱|经济|钱|时间|分钟|分|秒|金|"
    r"\b(?:i|my|your|you|have|currently|current|remaining|left|only|match|game|timer|time|the|is|it|at|"
    r"shows?|reads?|amount|indicates?|there|are|of|now|gold|coins?|money|minutes?|seconds?)\b",
    re.IGNORECASE,
)
_BUILD_ADVICE_RE = re.compile(
    r"装备|道具|出装|配装|购买|买|合成|推荐|建议|核心|先出|可以出|适合|build|item|gear|buy|purchase|"
    r"sword|shield|armor|boots|potion|upgrade",
    re.IGNORECASE,
)
_DYNAMIC_ADVICE_QUESTION_RE = re.compile(
    r"出装|配装|装备建议|装备推荐|装备搭配|装备选择|购买装备|购买什么装备|买什么装备|买啥装备|买什么道具|"
    r"(?:推荐|建议|买|购买|出|换|带|选择).{0,6}(?:装备|道具)|(?:装备|道具).{0,6}(?:推荐|建议|购买|买|出|换|带|选择)|"
    r"怎么出装|如何出装|怎么配装|推荐出什么|推荐买什么|建议买什么|"
    r"这套出装|当前出装|现在出装|本局装备|这局装备|这把装备|这套装备|"
    r"选人|选英雄|选择英雄|选角色|选择角色|选.{0,4}英雄|选择.{0,4}英雄|挑选英雄|英雄推荐|英雄建议|选人建议|选人推荐|"
    r"选哪个|选哪一个|选哪位|选谁|选择哪个|选择哪一个|选择哪位|怎么选|如何选|"
    r"应该选|该选|要选|阵容|队伍搭配|团队搭配|"
    r"对战策略|对战打法|打法|战术|策略|怎么打|如何打|怎么应对|如何应对|怎么反制|如何反制|"
    r"该如何处理|下一步|走哪步|怎么走|"
    r"\b(?:build|loadout|buy|purchase|draft|pick|choose|strategy|tactics?|counter|"
    r"team composition|team comp|lineup|roster|item recommendation|gear recommendation|"
    r"what should i (?:buy|build|choose|pick|do)|how should i (?:play|choose)|how to play|next move|"
    r"which .{0,30} should i (?:use|buy|build|choose|pick)|who should i (?:pick|choose))\b",
    re.IGNORECASE,
)
_NON_WORD_RE = re.compile(r"[\s\d０-９.,，:：;；!！?？。+\-/]+")
UNKNOWN_ANSWER = "无法从清晰可见的界面文字中可靠确认。"


class InvalidRequest(ValueError):
    pass


@dataclass(frozen=True)
class ContextFrame:
    frame_id: str
    frame_age_ms: int
    image: NormalizedImage


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
    context_frames: tuple[ContextFrame, ...] = ()


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

    raw_context_frames = payload.get("context_frames", [])
    if not isinstance(raw_context_frames, list) or len(raw_context_frames) > 2:
        raise InvalidRequest("context_frames must be an array containing at most two frames")
    context_frames: list[ContextFrame] = []
    seen_frame_ids = {frame_id}
    for index, raw_frame in enumerate(raw_context_frames):
        if not isinstance(raw_frame, dict):
            raise InvalidRequest(f"context_frames[{index}] must be an object")
        context_id = _require_id(raw_frame.get("frame_id"), f"context_frames[{index}].frame_id")
        if context_id in seen_frame_ids:
            raise InvalidRequest("context frame IDs must be distinct from each other and the primary frame")
        seen_frame_ids.add(context_id)
        context_age = raw_frame.get("frame_age_ms")
        if (
            isinstance(context_age, bool)
            or not isinstance(context_age, int)
            or not 0 <= context_age <= 6_000
        ):
            raise InvalidRequest(f"context_frames[{index}].frame_age_ms must be an integer from 0 to 6000")
        if context_age <= age:
            raise InvalidRequest("context frames must be older than the primary frame")
        try:
            context_image = normalize_image(raw_frame.get("image_base64"))
        except ValueError as exc:
            raise InvalidRequest(f"context_frames[{index}].{exc}") from exc
        context_frames.append(ContextFrame(context_id, context_age, context_image))

    # Ages are relative to the primary frame, so larger ages are earlier in
    # chronological order. The last image sent by the client remains primary.
    context_frames.sort(key=lambda context: context.frame_age_ms, reverse=True)
    return VisualRequest(
        session_id,
        generation,
        turn_id,
        frame_id,
        question,
        age,
        proactive,
        image,
        tuple(context_frames),
    )


def _is_progress_only_build_answer(answer: str, question: str | None) -> bool:
    if not question or not _BUILD_QUESTION_RE.search(question):
        return False
    if _BUILD_ADVICE_RE.search(answer):
        return False
    remainder = _BUILD_HUD_ONLY_RE.sub("", answer)
    remainder = _NON_WORD_RE.sub("", remainder)
    return not remainder


def _requires_live_hud(question: str | None) -> bool:
    return bool(question and _DYNAMIC_ADVICE_QUESTION_RE.search(question))


def parse_visual_answer(raw: str, *, question: str | None = None) -> VisualAnswer:
    if not isinstance(raw, str):
        raise InvalidRequest("model response must be text")
    content = raw.strip()
    if content.startswith("```") and content.endswith("```"):
        content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content, flags=re.IGNORECASE).strip()
    try:
        value = json.loads(content)
    except json.JSONDecodeError as exc:
        raise InvalidRequest("model response was not valid JSON") from exc
    if not isinstance(value, dict) or set(value) != {"kind", "answer", "uncertain"}:
        raise InvalidRequest("model response must be a JSON object with exactly the required keys")
    kind = value.get("kind")
    answer = value.get("answer")
    uncertain = value.get("uncertain")
    if (
        not isinstance(kind, str)
        or kind not in {"hud", "ui_text", "unknown"}
        or not isinstance(answer, str)
        or not isinstance(uncertain, bool)
    ):
        raise InvalidRequest("model response did not match the required schema")
    answer = _CONTROL_RE.sub("", answer).strip()
    if len(answer) > 100 or len(_SENTENCE_END_RE.findall(answer)) > 2:
        return VisualAnswer("unknown", UNKNOWN_ANSWER, True)
    if kind == "unknown":
        return VisualAnswer("unknown", "", True)
    if not answer or _is_progress_only_build_answer(answer, question):
        return VisualAnswer("unknown", UNKNOWN_ANSWER, True)
    if _requires_live_hud(question):
        kind = "hud"
    return VisualAnswer(kind, answer, uncertain)
