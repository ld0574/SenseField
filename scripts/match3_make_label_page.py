#!/usr/bin/env python3
"""Generate a self-contained HTML labeling page for Match3 cell families.

Three independent controls per element: identity, mechanic archetype, and swap permission.
(what clearing does). Identity scales by data (gallery); the archetype is one of a
small fixed set the engine hand-codes once. Archetype auto-defaults from identity.
Renders crops at full source resolution. Exports {"labels":{...},"archetypes":{...}}.
Requires .venv/bin/python (Pillow+numpy).
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import re
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np
from PIL import Image

PATCH = 16
OPTIONS = [
    ("O", "棕熊 O"), ("R", "狐狸 R"), ("B", "河马 B"), ("Y", "小鸡 Y"), ("G", "青蛙 G"), ("P", "紫猫 P"),
    ("coin", "银币"), ("cookie", "饼干"), ("snow", "雪块"), ("iceflower", "冰花(消完变雪)"),
    ("ice", "裸冰单层背景(不含动物)"), ("honey", "蜜罐"), ("egg", "蛋/鸡窝"), ("empty", "空/背景/天空"),
    ("special", "特殊(彩虹等)"), ("unknown", "不认识/障碍"),
]
# The bounded set of clearing mechanics; each is hand-coded once in the engine.
ARCHETYPES = [
    ("ordinary", "普通可交换"), ("single", "单级·相邻消掉"), ("multistage", "多级·消完变别的"),
    ("cover", "覆盖层·动物压上面"), ("spawner", "生成器·消旁边会生"), ("large", "大物体2×2"),
    ("special", "特殊/彩虹"), ("none", "空/背景/非棋子"), ("unknown", "未确认"),
]
# Today's confirmed identity -> archetype mapping (auto-prefilled, user can override).
DEFAULT_ARCH = {
    "O": "ordinary", "R": "ordinary", "B": "ordinary", "Y": "ordinary", "G": "ordinary", "P": "ordinary",
    "coin": "single", "snow": "single", "honey": "single", "iceflower": "multistage", "ice": "cover",
    "cookie": "large", "egg": "spawner", "special": "special", "empty": "none", "unknown": "unknown",
}


def _png_data_uri(image: Image.Image) -> str:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode()


def _envelope_image(envelope: List[int]) -> Image.Image:
    arr = np.array(envelope, dtype=np.int64).reshape(PATCH, PATCH)
    rgb = np.stack([(arr >> 16) & 255, (arr >> 8) & 255, arr & 255], axis=-1).astype("uint8")
    return Image.fromarray(rgb, "RGB")


class Cropper:
    """Cuts a crisp full-resolution cell crop from the original frame."""
    def __init__(self, frames_dir: Optional[Path], boards: Dict[str, dict]):
        self.frames_dir = frames_dir
        self.boards = boards
        self._cache: Dict[str, Optional[Image.Image]] = {}

    def _frame(self, board: str) -> Optional[Image.Image]:
        if board not in self._cache:
            path = self.frames_dir / board if self.frames_dir else None
            self._cache[board] = Image.open(path).convert("RGB") if path and path.exists() else None
        return self._cache[board]

    def crop(self, cell_id: str, tile: int) -> Optional[Image.Image]:
        board = cell_id.split(":", 1)[0]
        geom = self.boards.get(board)
        frame = self._frame(board)
        match = re.search(r"r(\d+)c(\d+)", cell_id)
        if geom is None or frame is None or match is None:
            return None
        row, col = int(match.group(1)), int(match.group(2))
        x, y = geom["left"] + col * geom["cellW"], geom["top"] + row * geom["cellH"]
        return frame.crop((x, y, x + geom["cellW"], y + geom["cellH"])).resize((tile, tile), Image.LANCZOS)


TEMPLATE = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<title>消消乐元素标注</title><style>
body{font-family:system-ui,sans-serif;margin:16px;background:#f6f6f6}
h1{font-size:17px} .bar{position:sticky;top:0;background:#f6f6f6;padding:8px 0;border-bottom:1px solid #ddd;z-index:9}
button{font-size:15px;padding:8px 14px;margin-right:8px;cursor:pointer}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:10px;margin-top:12px}
.card{background:#fff;border:1px solid #ddd;border-radius:8px;padding:8px;text-align:center}
.card img{width:120px;height:120px;border:1px solid #eee;border-radius:4px}
.meta{font-size:12px;color:#888;margin:4px 0} select{font-size:13px;width:100%;padding:4px;margin-top:3px}
.lab{font-size:11px;color:#666;text-align:left;margin-top:4px} #out{width:100%;height:90px;margin-top:8px;font-family:monospace;display:none}
</style></head><body>
<h1>消消乐元素标注 — 核对长相、交换权限和机制，然后勾选已审阅</h1>
<div class="bar">
<button onclick="exportLabels()">导出标注 JSON（下载 cell-labels.json）</button>
<button onclick="document.getElementById('out').style.display='block'">显示 JSON 以便复制</button>
<span id="count"></span></div>
<div class="grid" id="grid"></div>
<textarea id="out" readonly></textarea>
<script>
const FAMILIES=__FAMILIES__; const OPTIONS=__OPTIONS__; const ARCHETYPES=__ARCHETYPES__; const DEFAULT_ARCH=__DEFAULT_ARCH__;
const grid=document.getElementById('grid');
function opts(list,sel){return list.map(o=>`<option value="${o[0]}" ${o[0]===sel?'selected':''}>${o[1]}</option>`).join('');}
FAMILIES.forEach(f=>{
  const card=document.createElement('div'); card.className='card';
  const arch=f.archetype||DEFAULT_ARCH[f.draft]||'unknown';
  card.innerHTML=`<img src="${f.img}"><div class="meta">${f.family} · ${f.count}格<br>引擎:${f.engine}</div>`+
    `<div class="lab">①长相</div><select class="id" data-family="${f.family}">${opts(OPTIONS,f.draft)}</select>`+
    `<div class="lab">②机制</div><select class="arch" data-family="${f.family}">${opts(ARCHETYPES,arch)}</select>`+
    `<div class="lab">③普通动物交换权限</div><select class="swap" data-family="${f.family}">${opts([['unknown','未确认'],['yes','允许'],['no','禁止']],f.swappable===true?'yes':f.swappable===false?'no':'unknown')}</select>`+
    `<label><input type="checkbox" class="reviewed" data-family="${f.family}" ${f.reviewed?'checked':''}>已逐项审阅</label>`;
  const idSel=card.querySelector('.id'), archSel=card.querySelector('.arch');
  idSel.addEventListener('change',()=>{ if(DEFAULT_ARCH[idSel.value]) archSel.value=DEFAULT_ARCH[idSel.value]; card.querySelector('.swap').value='unknown'; });
  card.querySelectorAll('select').forEach(s=>s.addEventListener('change',()=>card.querySelector('.reviewed').checked=false));
  grid.appendChild(card);
  if(f.members){
    const details=document.createElement('details'),summary=document.createElement('summary');
    summary.textContent='核对全部成员 / 复制拆簇 source 键';details.appendChild(summary);
    f.members.forEach(m=>{const member=document.createElement('div'),img=document.createElement('img'),source=document.createElement('code');
      img.src=m.img;source.textContent=m.source;source.style.cssText='display:block;overflow-wrap:anywhere;font-size:11px';
      member.append(img,source);details.appendChild(member);});card.appendChild(details);
  }
});
function collect(){const labels={},arch={},swap={},reviewed={};
  document.querySelectorAll('select.id').forEach(s=>labels[s.dataset.family]=s.value);
  document.querySelectorAll('select.arch').forEach(s=>arch[s.dataset.family]=s.value);
  document.querySelectorAll('select.swap').forEach(s=>swap[s.dataset.family]=s.value==='unknown'?null:s.value==='yes');
  document.querySelectorAll('input.reviewed').forEach(s=>reviewed[s.dataset.family]=s.checked);
  return {labels:labels,archetypes:arch,swappable:swap,reviewed:reviewed};}
function exportLabels(){
  const data=JSON.stringify(collect(),null,2);
  document.getElementById('out').value=data;
  const a=document.createElement('a');
  a.href=URL.createObjectURL(new Blob([data],{type:'application/json'})); a.download='cell-labels.json'; a.click();
}
document.getElementById('count').textContent=FAMILIES.length+' 种元素';
</script></body></html>"""


def render(cards: List[dict]) -> str:
    return (TEMPLATE.replace("__FAMILIES__", json.dumps(cards, ensure_ascii=False))
            .replace("__OPTIONS__", json.dumps(OPTIONS, ensure_ascii=False))
            .replace("__ARCHETYPES__", json.dumps(ARCHETYPES, ensure_ascii=False))
            .replace("__DEFAULT_ARCH__", json.dumps(DEFAULT_ARCH, ensure_ascii=False)))


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Generate an HTML labeling page for cell families")
    parser.add_argument("--families", required=True, type=Path)
    parser.add_argument("--predictions", required=True, type=Path)
    parser.add_argument("--frames-dir", type=Path, help="dir of original frames named like the board ids")
    parser.add_argument("--draft", type=Path, help="optional {\"labels\":{family:code}} to pre-fill")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--tile", type=int, default=120)
    args = parser.parse_args(argv)

    data = json.loads(args.predictions.read_text(encoding="utf-8"))
    envelopes = {s["id"]: s["envelope"] for s in data["samples"] if "envelope" in s}
    cropper = Cropper(args.frames_dir, data.get("boards", {}))
    families = json.loads(args.families.read_text(encoding="utf-8"))["families"]
    review = json.loads(args.draft.read_text(encoding="utf-8")) if args.draft else {}
    draft = review.get("labels", {})
    cards = []
    for family in families:
        image = None
        for cell in family["cells"]:
            image = cropper.crop(cell, args.tile)
            if image is not None:
                break
        if image is None:
            rep = next((c for c in family["cells"] if c in envelopes), None)
            if rep is None:
                continue
            image = _envelope_image(envelopes[rep]).resize((args.tile, args.tile), Image.NEAREST)
        engine = f"{family['engine_kind']}{family['engine_color']}/{family['engine_swap_permission']}"
        cards.append({"family": family["family"], "count": family["members"], "engine": engine,
                      "img": _png_data_uri(image), "draft": draft.get(family["family"], "unknown"),
                      "archetype": review.get("archetypes", {}).get(family["family"]),
                      "swappable": review.get("swappable", {}).get(family["family"]),
                      "reviewed": review.get("reviewed", {}).get(family["family"], False)})
    args.out.write_text(render(cards), encoding="utf-8")
    print(f"wrote {args.out} ({len(cards)} families, full-res crops: {args.frames_dir is not None})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
