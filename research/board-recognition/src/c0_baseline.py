# -*- coding: utf-8 -*-
"""C0 基线移植：Match3Sampler.java 的 Python 等价实现。
逐行对应（Java 行号）：
  classifyCell   L116-148  模板优先 → HSV 桶（S<0.18 或 V<0.15 回退模板）
  matchTemplate  L151-169  裁 half*4 正方形 → 16×16 → MAE，best>30 → UNKNOWN
  meanAbsDiff    L197-210
  autoDetectBoard L261-277 + detectBoundsFromMask L219-255（见 common.auto_detect）
  sample 采样几何 L75-92   half = max(3, min(cellW,cellH)//8)，格中心采样
模板来源说明（POC 约定）：真机里模板是玩家对真实棋子截图学习（alpha.7）；
这里用合成帧已知格子的裁剪当「学习结果」（frames 0-9 每类取 1 格，36px 方 → 16×16），
等价于玩家对每类标注了一张模板。
"""
import cv2
import numpy as np
from .common import auto_detect, cell_center

UNKNOWN = "X"
HSV_S_MIN = 0.18   # Java L138
HSV_V_MIN = 0.15   # Java L138
MAE_MAX = 30.0     # Java L166

def _rgb_to_hsv_java(r, g, b):
    """Java Color.colorToHSV 等价：返回 (h 0-360, s 0-1, v 0-1)。"""
    mx, mn = max(r, g, b), min(r, g, b)
    v = mx / 255.0
    s = 0.0 if mx == 0 else (mx - mn) / mx
    if s == 0:
        h = 0.0
    elif mx == r:
        h = 60.0 * (((g - b) / (mx - mn)) % 6.0)
    elif mx == g:
        h = 60.0 * (((b - r) / (mx - mn)) + 2.0)
    else:
        h = 60.0 * (((r - g) / (mx - mn)) + 4.0)
    if h < 0:
        h += 360.0
    return h, s, v

def _bucket(h):
    """Java L141-147 的色桶。"""
    if h >= 345 or h < 14:
        return "R"
    if h < 38:
        return "O"
    if h < 68:
        return "Y"
    if h < 165:
        return "G"
    if h < 262:
        return "B"
    return "P"

def learn_templates(synth_frames, metas, labels, n_frames=10):
    """从合成前 n_frames 帧每类已知格子学一张 16×16 模板（模拟玩家标注）。
    返回 {class: thumb16}。"""
    from .common import imread_unicode, SYNTH
    t = {}
    for fi in sorted(metas)[:n_frames]:
        img = imread_unicode("%s/synth_%s.png" % (SYNTH, fi))
        meta = metas[fi]
        half = max(3, int(meta["cs"]) // 8)   # Java L84
        for (r, c), cls in labels[fi].items():
            if cls in t or cls == "empty":
                continue
            cx, cy = cell_center(meta, r, c)
            side = half * 4                    # Java L154 cropSquare(cell, half*4)
            l, tp = max(0, cx - side), max(0, cy - side)
            patch = img[tp:tp + side * 2, l:l + side * 2]
            if patch.size == 0:
                continue
            t[cls] = cv2.resize(patch, (16, 16), interpolation=cv2.INTER_AREA)
        if len(t) == 9:  # 9 个非 empty 类
            break
    return t

def _match_template(img, cx, cy, half, templates):
    """Java L151-169：裁 half*4 → 16×16 → MAE<30 取最像。"""
    if not templates:
        return UNKNOWN
    side = half * 4
    h, w = img.shape[:2]
    l, tp = max(0, cx - side), max(0, cy - side)
    r, b = min(w, cx + side), min(h, cy + side)
    if r - l < 8 or b - tp < 8:
        return UNKNOWN
    small = cv2.resize(img[tp:b, l:r], (16, 16), interpolation=cv2.INTER_AREA)
    best, best_name = float("inf"), None
    for name, thumb in templates.items():
        diff = float(np.abs(small.astype(np.int16) - thumb.astype(np.int16)).mean())
        if diff < best:
            best, best_name = diff, name
    if best > MAE_MAX:
        return UNKNOWN
    return best_name

def classify_cell(img, cx, cy, half, templates, letter_of, letter2class):
    """Java L116-148 移植。返回类名（或 'X' 未识别）。"""
    # 模板优先（L120-123）
    by_t = _match_template(img, cx, cy, half, templates)
    if by_t != UNKNOWN:
        return by_t
    # HSV 平均色（L124-137）
    l, tp = max(0, cy - half), max(0, cx - half)
    r, b = min(img.shape[0], cy + half + 1), min(img.shape[1], cx + half + 1)
    patch = img[tp:b, l:r]
    if patch.size == 0:
        return UNKNOWN
    mean = patch.reshape(-1, 3).mean(axis=0)
    h, s, v = _rgb_to_hsv_java(*mean.tolist())
    if s < HSV_S_MIN or v < HSV_V_MIN:          # L138-140
        return _match_template(img, cx, cy, half, templates)
    letter = _bucket(h)                          # L141-147
    # 字母 → 类：唯一映射才算对；多类同字母时基线只报字母（无法区分）→ 记 ambiguous
    cand = letter2class.get(letter, [])
    if len(cand) == 1:
        return cand[0]
    return "A"   # ambiguous（基线只能报字母，区分不了同字母的类）

def run(img, meta, templates, letter_of, letter2class):
    """对一帧出完整格子分类。返回 { (r,c): 预测类 }。"""
    n = meta["n"]
    half = max(3, int(meta["cs"]) // 8)   # Java L84
    out = {}
    for r in range(n):
        for c in range(n):
            cx, cy = cell_center(meta, r, c)
            out[(r, c)] = classify_cell(img, cx, cy, half, templates, letter_of, letter2class)
    return out
