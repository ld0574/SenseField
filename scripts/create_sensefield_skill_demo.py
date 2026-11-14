#!/usr/bin/env python3
"""Generate original, synthetic game decision examples, with no player data."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "skills/sensefield-game-buddy/assets"
FONT = Path("/System/Library/Fonts/STHeiti Medium.ttc")


def text(draw, xy, value, size=28, color="#f5f8ff", anchor=None):
    draw.text(xy, value, font=ImageFont.truetype(str(FONT), size), fill=color, anchor=anchor)


def base(title, subtitle):
    image = Image.new("RGB", (1080, 1200), "#0b1221")
    draw = ImageDraw.Draw(image)
    text(draw, (64, 48), "听野 · 看清这一步", 40, "#8fe4ef")
    text(draw, (64, 117), title, 50)
    text(draw, (64, 195), subtitle, 28)
    text(draw, (64, 1140), "原创合成示意 · 非真实对局截图 · 不代表实测准确率", 25, "#b7c4d9")
    return image, draw


def match3():
    image, draw = base("开心消消乐 · 最后一个目标", "目标：清除右下静止单层冰面 ×1    剩余步数：3")
    left, top, cell = 150, 320, 130
    rows = ["RYGBPO", "RBGPOY", "YRPBOG", "GOPYBR", "YGP*BB", "OPYRGB"]
    palette = {
        "R": ("#862c3c", "狐狸"), "Y": ("#8b660b", "小鸡"),
        "G": ("#206452", "青蛙"), "B": ("#264f8c", "河马"),
        "P": ("#6b3c90", "猫头鹰"), "O": ("#725345", "棕动物"),
        "*": ("#422f73", "魔力鸟"),
    }
    for r, row in enumerate(rows):
        for c, code in enumerate(row):
            x, y = left + c * cell, top + r * cell
            color, label = palette[code]
            draw.rounded_rectangle((x + 4, y + 4, x + cell - 4, y + cell - 4), 18, fill=color)
            if code == "*":
                for i, band in enumerate(("#ff747b", "#ffcf66", "#84e5be", "#7fd4ff")):
                    draw.line((x + 22, y + 17 + i * 8, x + cell - 22, y + 17 + i * 8), fill=band, width=6)
            if (r, c) == (5, 5):
                draw.rounded_rectangle((x + 7, y + 7, x + cell - 7, y + cell - 7), 15,
                                       outline="#d6f7ff", width=8)
                text(draw, (x + cell / 2, y + 103), "单层冰底", 20, "#d6f7ff", "mm")
            text(draw, (x + cell / 2, y + 69), label, 28, anchor="mm")
    text(draw, (64, 255), "蓝河马可移动；右下白框是冰面，不是包裹动物的冰。", 27)
    image.save(ASSETS / "demo-match3.png", optimize=True)


def draft():
    image, draw = base("王者荣耀 · 选人判断", "用户：我常打辅助，这把选谁？")
    text(draw, (64, 282), "队友已锁定", 37)
    positions = [("对抗路", "已锁定"), ("打野", "已锁定"),
                 ("中路", "已锁定"), ("发育路", "已锁定")]
    for i, (role, status) in enumerate(positions):
        x, y = 64 + (i % 2) * 490, 350 + (i // 2) * 115
        draw.rounded_rectangle((x, y, x + 452, y + 92), 16, fill="#1c3046")
        text(draw, (x + 25, y + 24), f"{role}    {status}", 30)
    text(draw, (64, 628), "当前候选英雄", 37)
    for i, (name, role, status, color) in enumerate([
        ("张飞", "游走", "可选", "#1b534e"),
        ("蒙犽", "发育路", "可选", "#293a67"),
        ("夏侯惇", "对抗路", "已禁用", "#613849"),
    ]):
        y = 700 + i * 117
        draw.rounded_rectangle((64, y, 1016, y + 93), 16, fill=color)
        text(draw, (92, y + 25), name, 34)
        text(draw, (390, y + 29), role, 29)
        text(draw, (800, y + 29), status, 29)
    image.save(ASSETS / "demo-draft.png", optimize=True)


def main():
    if not FONT.is_file():
        raise SystemExit("Synthetic demo generation needs the system STHeiti font on macOS.")
    ASSETS.mkdir(parents=True, exist_ok=True)
    match3()
    draft()
    for name in ("demo-match3.png", "demo-draft.png"):
        print(ASSETS / name)


if __name__ == "__main__":
    main()
