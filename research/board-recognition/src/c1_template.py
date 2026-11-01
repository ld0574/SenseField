# -*- coding: utf-8 -*-
"""C1 候选：切格 + 多尺度模板匹配（纯 OpenCV，不训练）。
与 C0 的区别：
  - 不做 HSV 色桶（去掉 C0 的兜底，纯模板路线）
  - 模板比对用 alpha 掩膜（只比棋子像素，排除棋盘背景干扰——
    针对 sources.md 来源3 记录的「带背景格模板匹配不可靠」坑）
  - 多尺度 0.85/1.0/1.15（覆盖合成抖动与真机标定偏差）
模板来源与 C0 相同：素材原图（玩家标注物 = 棋子图，alpha 天然可用）。
"""
import cv2
import numpy as np
from .common import cell_center

PATCH = 48
SCALES = [0.85, 1.0, 1.15]
THRESH = 0.42   # 掩膜归一化 MAE（0-1 域）阈值，高于判未识别

def make_templates(assets):
    """{class: (48×48 BGR, alpha 掩膜)}。"""
    lib = {}
    for name, img in assets.items():
        if img is None:
            continue
        base = cv2.resize(img[:, :, :3], (PATCH, PATCH), interpolation=cv2.INTER_AREA)
        alpha = cv2.resize(img[:, :, 3:4], (PATCH, PATCH), interpolation=cv2.INTER_AREA)
        lib[name] = (base, (alpha > 128).astype(np.uint8))
    return lib

def _crop(img, cx, cy, side):
    h, w = img.shape[:2]
    l, t = max(0, cx - side // 2), max(0, cy - side // 2)
    r, b = min(w, l + side), min(h, t + side)
    if r - l < 8 or b - t < 8:
        return None
    return img[t:b, l:r]

def classify_cell(img, cx, cy, lib):
    patch = _crop(img, cx, cy, PATCH)
    if patch is None:
        return "X"
    p = patch.astype(np.int16)
    best, best_name = float("inf"), None
    for name, (timg, tmask) in lib.items():
        t = timg.astype(np.int16)
        for s in SCALES:
            sz = max(16, int(PATCH * s))
            ps = cv2.resize(p, (sz, sz), interpolation=cv2.INTER_AREA)
            ts = cv2.resize(t, (sz, sz), interpolation=cv2.INTER_AREA)
            mm = cv2.resize(tmask * 255, (sz, sz), interpolation=cv2.INTER_NEAREST) > 128
            if mm.sum() < 64:
                continue
            d = float(np.abs(ps - ts)[mm].mean()) / 255.0
            if d < best:
                best, best_name = d, name
    if best_name is None or best > THRESH:
        return "X"
    return best_name

def run(img, meta, lib):
    n = meta["n"]
    out = {}
    for r in range(n):
        for c in range(n):
            cx, cy = cell_center(meta, r, c)
            out[(r, c)] = classify_cell(img, cx, cy, lib)
    return out
