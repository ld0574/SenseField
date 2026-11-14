#!/usr/bin/env python3
"""将已确认的消消乐动作转为九方位口令；不推断游戏合法性。"""

from __future__ import annotations

import argparse
import html
import json
from pathlib import Path
import sys


def _text(value: object, field: str, limit: int) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ValueError(f"{field} 必须是 1～{limit} 字符的文字")
    if any(ord(char) < 32 for char in value):
        raise ValueError(f"{field} 不能含控制字符")
    return value.strip()


def validate(payload: object) -> dict:
    if not isinstance(payload, dict):
        raise ValueError("输入必须是 JSON 对象")
    if payload.get("confirmed_legal") is not True:
        raise ValueError("缺少外部已确认的动作合法性依据，不能生成交换指引")
    result = {}
    for key in ("rows", "cols"):
        value = payload.get(key)
        if type(value) is not int or not 1 <= value <= 16:
            raise ValueError(f"{key} 必须是 1～16 的整数")
        result[key] = value
    for key in ("source", "target"):
        point = payload.get(key)
        if (not isinstance(point, list) or len(point) != 2
                or any(type(v) is not int for v in point)
                or not 0 <= point[0] < result["rows"]
                or not 0 <= point[1] < result["cols"]):
            raise ValueError(f"{key} 必须是棋盘范围内的整数坐标 [行, 列]")
        result[key] = tuple(point)
    result["action"] = payload.get("action")
    a, b = result["source"], result["target"]
    distance = abs(a[0] - b[0]) + abs(a[1] - b[1])
    if result["action"] == "swap":
        if distance != 1:
            raise ValueError("交换必须是上下左右相邻的两个格子")
    elif result["action"] == "activate":
        if distance != 0:
            raise ValueError("单格激活的起止位置必须相同")
    else:
        raise ValueError("action 只支持 swap 或 activate")
    result["source_name"] = _text(payload.get("source_name"), "source_name", 24)
    if result["action"] == "swap":
        result["target_name"] = _text(payload.get("target_name"), "target_name", 24)
    result["evidence"] = _text(payload.get("evidence"), "evidence", 240)
    return result


def third(doubled_center: int, size: int) -> int:
    """与 Android Match3HintGuide.third 使用相同的整型边界。"""
    return -1 if 3 * doubled_center < 2 * size else 1 if 3 * doubled_center >= 4 * size else 0


def format_cue(payload: object) -> dict:
    state = validate(payload)
    a, b = state["source"], state["target"]
    x = third(a[1] + b[1] + 1, state["cols"])
    y = third(a[0] + b[0] + 1, state["rows"])
    direction = {
        (-1, -1): "左上方", (0, -1): "上方", (1, -1): "右上方",
        (-1, 0): "左侧", (0, 0): "中间", (1, 0): "右侧",
        (-1, 1): "左下方", (0, 1): "下方", (1, 1): "右下方",
    }[(x, y)]
    if state["action"] == "activate":
        operation = "点击" + state["source_name"]
    else:
        step = (b[0] - a[0], b[1] - a[1])
        movement = {(-1, 0): "向上", (1, 0): "向下", (0, -1): "向左", (0, 1): "向右"}[step]
        operation = f"{state['source_name']}{movement}与{state['target_name']}交换"
    return {"direction": direction, "speech": f"{direction}，{operation}。",
            "source": list(a), "target": list(b), "action": state["action"]}


def render_svg(payload: object) -> str:
    state = validate(payload)
    cue = format_cue(payload)
    cell = min(500 / state["cols"], 500 / state["rows"])
    left = (720 - cell * state["cols"]) / 2
    top = 165 + (500 - cell * state["rows"]) / 2
    a, b = state["source"], state["target"]
    ax, ay = left + (a[1] + .5) * cell, top + (a[0] + .5) * cell
    bx, by = left + (b[1] + .5) * cell, top + (b[0] + .5) * cell
    cx, cy = 360., 415.
    mx, my = (ax + bx) / 2, (ay + by) / 2
    fx, fy = min(ax, bx) - cell * .45, min(ay, by) - cell * .45
    fw, fh = abs(ax - bx) + cell * .9, abs(ay - by) + cell * .9
    dots = "\n".join(
        f'<circle cx="{left + (col + .5) * cell:.2f}" cy="{top + (row + .5) * cell:.2f}" r="{cell * .08:.2f}" fill="#8995ac"/>'
        for row in range(state["rows"]) for col in range(state["cols"])
    )
    # 中央动作只保留亮框，避免箭头与动作区域重叠。
    dx, dy = mx - cx, my - cy
    limits = [fw / 2 / abs(dx) if dx else float("inf"),
              fh / 2 / abs(dy) if dy else float("inf")]
    ratio = max(0., 1. - min(limits))
    arrow = ""
    if (dx * dx + dy * dy) ** .5 * ratio > cell * .5:
        ex, ey = cx + dx * ratio, cy + dy * ratio
        arrow = (f'<path d="M {cx} {cy} L {ex:.2f} {ey:.2f}" stroke="#030712" stroke-width="18"/>'
                 f'<path d="M {cx} {cy} L {ex:.2f} {ey:.2f}" stroke="#ffdf00" stroke-width="9" marker-end="url(#arrow)"/>')
    safe_speech = html.escape(cue["speech"], quote=True)
    safe_name = html.escape(state["source_name"], quote=True)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="720" height="790" viewBox="0 0 720 790" role="img" aria-labelledby="title desc">
<title id="title">{safe_speech}</title><desc id="desc">听野静态棋盘示意，亮框指向已确认的动作，不是游戏截图。</desc>
<defs><marker id="arrow" markerWidth="5" markerHeight="5" refX="4" refY="2.5" orient="auto"><path d="M0 0 L5 2.5 L0 5 Z" fill="#ffdf00"/></marker></defs>
<rect width="720" height="790" rx="24" fill="#0b1221"/>
<g font-family="sans-serif" fill="#ffffff" text-anchor="middle">
<text x="360" y="57" font-size="35" font-weight="bold">{cue['direction']} · 静态操作指引</text>
<text x="360" y="108" font-size="24">找到亮框中的{safe_name}</text>
<rect x="{left:.2f}" y="{top:.2f}" width="{cell * state['cols']:.2f}" height="{cell * state['rows']:.2f}" rx="12" fill="#18243a" stroke="#8995ac" stroke-width="3"/>
{dots}
{arrow}
<rect x="{fx:.2f}" y="{fy:.2f}" width="{fw:.2f}" height="{fh:.2f}" rx="12" fill="none" stroke="#030712" stroke-width="20"/>
<rect x="{fx:.2f}" y="{fy:.2f}" width="{fw:.2f}" height="{fh:.2f}" rx="12" fill="none" stroke="#ffffff" stroke-width="12"/>
<rect x="{fx:.2f}" y="{fy:.2f}" width="{fw:.2f}" height="{fh:.2f}" rx="12" fill="none" stroke="#ffdf00" stroke-width="6"/>
<text x="360" y="724" font-size="22" textLength="650" lengthAdjust="spacingAndGlyphs">{safe_speech}</text>
<text x="360" y="764" font-size="19" fill="#c5cfe0">示意不会自动消失 · 局面变化后需重新判断</text>
</g></svg>'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="已有合法性依据的动作 JSON")
    parser.add_argument("--svg", type=Path, help="可选：生成静态高对比棋盘示意")
    args = parser.parse_args()
    try:
        if args.input.stat().st_size > 16 * 1024:
            raise ValueError("动作 JSON 超过 16 KiB")
        payload = json.loads(args.input.read_text(encoding="utf-8"))
        result = format_cue(payload)
        if args.svg:
            if args.svg.resolve() == args.input.resolve():
                raise ValueError("输出不能覆盖输入 JSON")
            args.svg.write_text(render_svg(payload), encoding="utf-8")
            result["guide_svg"] = str(args.svg)
    except (ValueError, OSError, RecursionError) as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
