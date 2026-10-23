# -*- coding: utf-8 -*-
"""共享工具：路径/素材/几何/字母映射/IO。所有候选共用。"""
import cv2, json, os
import numpy as np

RD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = r"C:/Users/22812/tmp-kkxk/assets"
SYNTH = os.path.join(RD, "data", "synth")
REAL = os.path.join(RD, "data", "real")
MODELS = os.path.join(RD, "models")
SAMPLER_JAVA = r"C:/Users/22812/SenseField/android/app/src/main/java/com/openkhub/sensefield/Match3Sampler.java"

CLASSES = ["bear", "bird", "bomb", "cat", "chicken", "empty", "fox", "frog", "horse", "obstacle"]
CLIDX = {c: i for i, c in enumerate(CLASSES)}
ANIMALS = ["bear", "bird", "cat", "chicken", "fox", "frog", "horse"]

def imwrite_unicode(path, img):
    ok, buf = cv2.imencode(".png", img)
    assert ok, "imencode failed: %s" % path
    with open(path, "wb") as f:
        f.write(buf.tobytes())

def imread_unicode(path, flags=cv2.IMREAD_COLOR):
    data = np.fromfile(path, dtype=np.uint8)
    return cv2.imdecode(data, flags)

def load_asset(name):
    if name == "bomb":
        p = os.path.join(ASSETS, "Texture/231/kai_231_bomb_yellow.png")
        return _square_crop(imread_unicode(p, cv2.IMREAD_UNCHANGED))
    if name == "obstacle":
        p = os.path.join(ASSETS, "resources/obstacles/ice.png")
        return _square_crop(imread_unicode(p, cv2.IMREAD_UNCHANGED))
    p = os.path.join(ASSETS, "Texture/Cells/animals/%s.png" % name)
    return _square_crop(imread_unicode(p, cv2.IMREAD_UNCHANGED))

def _square_crop(img):
    rgb, a = (img[:, :, :3], img[:, :, 3]) if img.shape[2] == 4 else (img, np.ones(img.shape[:2], np.uint8) * 255)
    m = a > 128
    if not m.any():
        return None
    ys, xs = np.where(m)
    return cv2.cvtColor(rgb[ys.min():ys.max()+1, xs.min():xs.max()+1], cv2.COLOR_BGR2BGRA)

def cell_center(meta, r, c):
    return int(meta["x0"] + (c + 0.5) * meta["cs"]), int(meta["y0"] + (r + 0.5) * meta["cs"])

def letter_map(assets):
    """素材→基线字母表：Java classifyCell L141-147 的 HSV 桶（h 域 0-360）作用于素材 alpha 内均值色。"""
    def bucket(bgr):
        b, g, r = [v / 255.0 for v in bgr]
        mx, mn = max(r, g, b), min(r, g, b)
        v = mx
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
        if s < 0.18 or v < 0.15:
            return None
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
    m = {}
    for name in CLASSES:
        if name == "empty":
            continue
        img = assets[name]
        a = img[:, :, 3] > 128
        bgr = img[:, :, :3][a].mean(axis=0)
        m[name] = bucket(tuple(int(x) for x in bgr))
    return m

def auto_detect(img):
    """C0 移植：Match3Sampler.autoDetectBoard L261-277 + detectBoundsFromMask L219-255。
    返回 (lPct,tPct,rPct,bPct) 或 None。"""
    h, w = img.shape[:2]
    step = max(1, w // 160)
    cols, rows = w // step, h // step
    hsv = cv2.cvtColor(cv2.resize(img, (max(cols, 1), max(rows, 1)), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2HSV)
    # cv2 H 域 0-180；Java 条件 hue 170-300(0-360 域) → cv2 85-150；v<0.45 → <115
    mask = (hsv[:, :, 2] < 115) & (hsv[:, :, 0] >= 85) & (hsv[:, :, 0] <= 150)
    rowcnt = mask.sum(axis=1)
    min_row = cols // 4
    best = (-1, -1, 0)
    top = -1
    for r in range(rows + 1):
        dense = r < rows and rowcnt[r] >= min_row
        if dense and top < 0:
            top = r
        if (not dense or r == rows) and top >= 0:
            if r - top > best[2]:
                best = (top, r - 1, r - top)
            top = -1
    if best[0] < 0 or best[2] < rows // 10:
        return None
    colcnt = mask[best[0]:best[1]+1].sum(axis=0)
    min_col = (best[1] - best[0] + 1) * 3 // 10
    dense_cols = [c for c in range(cols) if colcnt[c] >= min_col]
    if not dense_cols:
        return None
    left, right = dense_cols[0], dense_cols[-1]
    if right - left < cols // 10:
        return None
    return (left * 100 // cols, best[0] * 100 // rows, (right + 1) * 100 // cols, (best[1] + 1) * 100 // rows)

def load_synth():
    metas = json.load(open(os.path.join(SYNTH, "frames_meta.json")))
    labels = {}
    with open(os.path.join(SYNTH, "labels.csv")) as f:
        next(f)
        for line in f:
            fi, r, c, cls = line.strip().split(",")
            # CSV 里 frame_id 是整数（0-199），统一成 fNNN 键与 frames_meta.json 对齐
            labels.setdefault("f%03d" % int(fi), {})[(int(r), int(c))] = cls
    return metas, labels

def paste_piece(img, name, cx, cy, size, assets, angle=0.0):
    p = cv2.resize(assets[name], (size, size), interpolation=cv2.INTER_AREA)
    if angle:
        M = cv2.getRotationMatrix2D((size / 2, size / 2), angle, 1.0)
        p = cv2.warpAffine(p, M, (size, size), borderMode=cv2.BORDER_TRANSPARENT)
    h, w = img.shape[:2]
    x0, y0 = int(cx - size / 2), int(cy - size / 2)
    sx0, sy0 = max(0, -x0), max(0, -y0)
    ex, ey = min(size, w - x0), min(size, h - y0)
    if ex <= sx0 or ey <= sy0:
        return img
    roi = img[y0+sy0:y0+ey, x0+sx0:x0+ex]
    pa = p[sy0:ey, sx0:ex, 3:4].astype(np.float32) / 255.0
    img[y0+sy0:y0+ey, x0+sx0:x0+ex] = (roi.astype(np.float32) * (1 - pa) + p[sy0:ey, sx0:ex, :3].astype(np.float32) * pa).astype(np.uint8)
    return img
