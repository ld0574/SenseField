# -*- coding: utf-8 -*-
"""任务2：合成棋盘帧渲染器。
素材源：<HOME>/tmp-kkxk（isghost/kaixinxiaoxiaole Cocos 复刻工程，只读）
输出（相对 $RD）：
  data/synth/synth_fNNN.png   200 帧，720x1280，8x8 或 9x9 棋盘
  data/synth/labels.csv       frame_id,row,col,class（0 起）
  data/synth/frames_meta.json 每帧几何（供 eval 用真值标定）
  data/synth/contact_sheet.png 联图（领导目测）
类名：bear,bird,cat,chicken,fox,frog,horse,bomb,obstacle,empty
抖动：棋盘原点 ±3%（标定漂移）；每棋子 ±3° 旋转（摇摆动画）+ ±5% 缩放。
背景：3 种深色棋盘底（均满足 autoDetectBoard 掩膜条件 v<0.45 且 cv2 hue 85-150，
      对应 Java L273 的 v<0.45 且 hue 170-300），外框浅灰模拟游戏 UI 区。
"""
import cv2, json, math, os, random, numpy as np

RD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = r"C:/Users/22812/tmp-kkxk/assets"
OUT = os.path.join(RD, "data", "synth")
random.seed(20261004); np.random.seed(20261004)

ANIMALS = ["bear", "bird", "cat", "chicken", "fox", "frog", "horse"]
BOMB = os.path.join(ASSETS, "Texture/231/kai_231_bomb_yellow.png")
OBSTACLE = os.path.join(ASSETS, "resources/obstacles/ice.png")
BGS = [(90, 45, 30), (80, 60, 50), (85, 70, 45)]   # BGR，深色棋盘底
FRAME_W, FRAME_H = 720, 1280
N_FRAMES = 200
BOARD_SIDE = 600
PIECE_FILL = 0.62   # 棋子占格比例 → 约 38% 深色缝隙供 autoDetect 掩膜

def imwrite_unicode(path, img):
    """cv2.imwrite 在 Windows 中文路径下静默失败，用 imencode+open(wb) 代替。"""
    ok, buf = cv2.imencode(".png", img)
    assert ok, "imencode failed: %s" % path
    with open(path, "wb") as f:
        f.write(buf.tobytes())

def load_piece(path, square=None):
    img = cv2.imread(path, cv2.IMREAD_UNCHANGED)
    assert img is not None, path
    if img.ndim == 3 and img.shape[2] == 4:
        rgb, a = img[:, :, :3], img[:, :, 3]
    else:
        rgb, a = img, np.ones(img.shape[:2], np.uint8) * 255
    if square:
        h, w = rgb.shape[:2]
        s = min(h, w)
        x0, y0 = (w - s) // 2, (h - s) // 2
        rgb, a = rgb[y0:y0+s, x0:x0+s], a[y0:y0+s, x0:x0+s]
    m = a > 128
    if not m.any():
        return None
    ys, xs = np.where(m)
    rgb = rgb[ys.min():ys.max()+1, xs.min():xs.max()+1]
    a = a[ys.min():ys.max()+1, xs.min():xs.max()+1]
    return cv2.cvtColor(rgb, cv2.COLOR_BGR2BGRA)

def paste(canvas, piece, cx, cy, size, angle):
    p = cv2.resize(piece, (size, size), interpolation=cv2.INTER_AREA)
    M = cv2.getRotationMatrix2D((size/2, size/2), angle, 1.0)
    p = cv2.warpAffine(p, M, (size, size), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_TRANSPARENT)
    x0, y0 = int(cx - size/2), int(cy - size/2)
    h, w = canvas.shape[:2]
    sx0, sy0 = max(0, -x0), max(0, -y0)
    ex, ey = min(size, w - x0), min(size, h - y0)
    if ex <= sx0 or ey <= sy0:
        return
    roi = canvas[y0+sy0:y0+ey, x0+sx0:x0+ex]
    pa = p[sy0:ey, sx0:ex, 3:4].astype(np.float32) / 255.0
    canvas[y0+sy0:y0+ey, x0+sx0:x0+ex] = (roi.astype(np.float32) * (1 - pa) + p[sy0:ey, sx0:ex, :3].astype(np.float32) * pa).astype(np.uint8)

def sample_class():
    r = random.random()
    if r < 0.80:
        return random.choice(ANIMALS)
    if r < 0.86:
        return "empty"
    if r < 0.92:
        return "obstacle"
    return "bomb"

def main():
    os.makedirs(OUT, exist_ok=True)
    pieces = {a: load_piece(os.path.join(ASSETS, "Texture/Cells/animals/%s.png" % a)) for a in ANIMALS}
    pieces["bomb"] = load_piece(BOMB, square=True)
    pieces["obstacle"] = load_piece(OBSTACLE)
    assert all(v is not None for v in pieces.values()), "素材加载失败"

    rows, metas, class_count = [], {}, {}
    thumbs = []
    for fi in range(N_FRAMES):
        n = 8 if random.random() < 0.6 else 9
        S = BOARD_SIDE
        x0 = (FRAME_W - S) // 2 + random.randint(-int(0.03*S), int(0.03*S))
        y0 = (FRAME_H - S) // 2 + random.randint(-int(0.03*S), int(0.03*S))
        cs = S / n
        bg = random.choice(BGS)
        canvas = np.full((FRAME_H, FRAME_W, 3), (235, 235, 235), np.uint8)
        canvas[y0:y0+S, x0:x0+S] = bg
        for r in range(n):
            for c in range(n):
                cls = sample_class()
                class_count[cls] = class_count.get(cls, 0) + 1
                rows.append((fi, r, c, cls))
                if cls != "empty":
                    cx, cy = x0 + (c + 0.5) * cs, y0 + (r + 0.5) * cs
                    size = int(cs * PIECE_FILL * random.uniform(0.95, 1.05))
                    paste(canvas, pieces[cls], cx, cy, size, random.uniform(-3, 3))
        fn = os.path.join(OUT, "synth_f%03d.png" % fi)
        imwrite_unicode(fn, canvas)
        metas["f%03d" % fi] = {"n": n, "x0": x0, "y0": y0, "S": S, "cs": cs, "bg": list(bg)}
        if fi < 20:  # 联图前 20 帧
            thumbs.append(cv2.resize(canvas, (144, 256)))
    with open(os.path.join(OUT, "labels.csv"), "w") as f:
        f.write("frame_id,row,col,class\n")
        for r in rows:
            f.write("%d,%d,%d,%s\n" % r)
    with open(os.path.join(OUT, "frames_meta.json"), "w") as f:
        json.dump(metas, f, indent=1)
    cols = 10
    sheet = np.zeros((math.ceil(len(thumbs)/cols)*256, cols*144, 3), np.uint8)
    for i, t in enumerate(thumbs):
        sheet[(i//cols)*256:(i//cols)*256+256, (i%cols)*144:(i%cols)*144+144] = t
    imwrite_unicode(os.path.join(OUT, "contact_sheet.png"), sheet)
    ncells = sum(m["n"]**2 for m in metas.values())
    assert len(rows) == ncells, "labels 行数与总格数不符"
    assert len(metas) == N_FRAMES
    assert set(pieces) <= set(class_count), "有素材类未出现: %s" % (set(pieces) - set(class_count))
    assert class_count.get("empty", 0) > 0, "无空格帧"
    print("FRAMES=%d CELLS=%d CLASSES=%s" % (N_FRAMES, ncells, dict(sorted(class_count.items()))))

if __name__ == "__main__":
    main()
