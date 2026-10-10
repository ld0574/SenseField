#!/usr/bin/env python3
"""Label page from the app's deduped unknown-element crops (one card per family).

Cleaner than clustering raw board cells: the app already stabilised each session's
unknown elements into a few `elements/uN-M.png` representatives. This groups them
by family (uN), renders one clear card each, and exports {"labels": {uN: code}}.
Reuses the HTML template/options from match3_make_label_page. .venv/bin/python.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional

from PIL import Image

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("mlp", HERE / "match3_make_label_page.py")
mlp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mlp)


def engine_draft(events: Optional[Path]) -> Dict[str, str]:
    draft: Dict[str, str] = {}
    if events is None or not events.is_file():
        return draft
    for line in events.read_text(encoding="utf-8").splitlines():
        if "Match3Element" not in line:
            continue
        try:
            data = json.loads(line).get("data", {})
        except json.JSONDecodeError:
            continue
        fam, kind = data.get("family_id"), data.get("kind")
        if fam and fam not in draft:
            draft[fam] = {"COIN": "coin", "SNOW": "snow", "COOKIE": "cookie", "EGG": "egg"}.get(kind, "unknown")
    return draft


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Label page from app element crops")
    parser.add_argument("--elements", required=True, type=Path, help="session elements/ dir of uN-M.png")
    parser.add_argument("--events", type=Path, help="events.jsonl for engine-guess prefill")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--tile", type=int, default=120)
    args = parser.parse_args(argv)

    groups: Dict[str, List[Path]] = defaultdict(list)
    for png in sorted(args.elements.glob("*.png")):
        groups[png.stem.split("-", 1)[0]].append(png)
    draft = engine_draft(args.events)
    cards = []
    for family in sorted(groups, key=lambda f: (len(f), f)):
        crops = groups[family]
        image = Image.open(crops[-1]).convert("RGB").resize((args.tile, args.tile), Image.LANCZOS)
        cards.append({"family": family, "count": len(crops), "engine": "待定",
                      "img": mlp._png_data_uri(image), "draft": draft.get(family, "unknown")})
    args.out.write_text(mlp.render(cards), encoding="utf-8")
    print(f"wrote {args.out} ({len(cards)} element families)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
