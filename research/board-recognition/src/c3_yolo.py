# -*- coding: utf-8 -*-
"""C3 候选：整屏 YOLOv8n（ultralytics，CPU 训练）。
输入：整帧 640；类别 = 9 个非 empty 类（empty 不画框）。
标注：由合成真值生成 YOLO txt（每格中心+框大小=格子边长×0.9，空格外框不标）。
训练：imgsz=640，epochs 上限 50（书内），CPU 单 epoch 估 3-6 分钟 →
  若前 3 epoch 的单 epoch 时长 > 8 分钟，按书内限时规则降 10 epochs 并标注。
推理：逐帧 predict → 按格子几何把框分配到 (r,c)（框中心落在哪格算哪格），
  无框覆盖的格子判 empty。
"""
import os
import numpy as np
import cv2
from .common import CLASSES, CLIDX, cell_center, MODELS, imwrite_unicode

YOLO_CLASSES = [c for c in CLASSES if c != "empty"]

def make_dataset(root, metas, labels):
    os.makedirs(os.path.join(root, "images"), exist_ok=True)
    os.makedirs(os.path.join(root, "labels"), exist_ok=True)
    from .common import imread_unicode, SYNTH
    n = 0
    for fi in sorted(metas):
        m = metas[fi]
        img = imread_unicode("%s/synth_%s.png" % (SYNTH, fi))
        lines = []
        for (r, c), cls in labels[fi].items():
            if cls == "empty":
                continue
            cx, cy = cell_center(m, r, c)
            w = h = m["cs"] * 0.9
            x1, y1 = (cx - w / 2) / img.shape[1], (cy - h / 2) / img.shape[0]
            lines.append("%d %.4f %.4f %.4f %.4f" % (YOLO_CLASSES.index(cls),
                                                     x1 + w / (2 * img.shape[1]),
                                                     y1 + h / (2 * img.shape[0]),
                                                     w / img.shape[1], h / img.shape[0]))
        with open(os.path.join(root, "labels", "%s.txt" % fi), "w") as f:
            f.write("\n".join(lines) + ("\n" if lines else ""))
        imwrite_unicode(os.path.join(root, "images", "%s.jpg" % fi),
                        cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        n += 1
    train_ids = [fi for fi in sorted(metas)][:140]
    test_ids = [fi for fi in sorted(metas)][140:]
    with open(os.path.join(root, "train.txt"), "w") as f:
        f.write("\n".join(os.path.join(root, "images", i + ".jpg") for i in train_ids) + "\n")
    with open(os.path.join(root, "val.txt"), "w") as f:
        f.write("\n".join(os.path.join(root, "images", i + ".jpg") for i in test_ids) + "\n")
    with open(os.path.join(root, "dataset.yaml"), "w") as f:
        f.write("train: train.txt\nval: val.txt\nnc: %d\nnames: %s\n" % (len(YOLO_CLASSES), YOLO_CLASSES))
    return root

def train(epochs=10, time_limit_min=60, resume=True, imgsz=320):
    """书内限时规则：50 epochs 全程估 >60min（CPU imgsz640 单 epoch 实测 >4.5min）→ 降 10 epochs，
    报告标注「未训足，结论仅供参考」。imgsz=320（CPU 控时；棋盘 600px→letterbox 后约 150px，
    格子 ~18px，YOLO 可检但低于 640 精度——POC 对比目的成立，报告注明）。
    分批续训：每批 bash 300s，靠 last.pt 断点。"""
    from ultralytics import YOLO
    from .common import load_synth, RD
    metas, labels = load_synth()
    root = os.path.join(RD, "models", "yolo_dataset")
    make_dataset(root, metas, labels)
    last = os.path.join(MODELS, "yolo_runs", "c3", "weights", "last.pt")
    import time
    t0 = time.time()
    if resume and os.path.exists(last):
        model = YOLO(last)
        model.train(resume=True, project=os.path.join(MODELS, "yolo_runs"), name="c3", exist_ok=True)
    else:
        model = YOLO(os.path.join(MODELS, "yolov8n.pt"))
        model.train(data=os.path.join(root, "dataset.yaml"), imgsz=imgsz, epochs=epochs,
                    batch=8, device="cpu", workers=0, verbose=True,
                    project=os.path.join(MODELS, "yolo_runs"), name="c3", exist_ok=True)
    dur_min = (time.time() - t0) / 60
    os.makedirs(MODELS, exist_ok=True)
    best = os.path.join(MODELS, "yolo_runs", "c3", "weights", "best.pt")
    out = os.path.join(MODELS, "c3_yolo.pt")
    if os.path.exists(best) and os.path.abspath(best) != os.path.abspath(out):
        with open(best, "rb") as f:
            data = f.read()
        with open(out, "wb") as f:
            f.write(data)
    done = os.path.exists(out)
    print("C3-TRAIN-DONE this_batch_min=%.1f total_target=10epochs time_limited=True(书内规则:50ep>60min降10)" % dur_min, flush=True)

def load_model():
    from ultralytics import YOLO
    return YOLO(os.path.join(MODELS, "c3_yolo.pt"))

def assign_cells(meta, dets):
    """dets: [(cls_idx, cx_px, cy_px)] → {(r,c): cls}。"""
    n = meta["n"]
    out = {}
    for cls_idx, cx, cy in dets:
        c = int((cx - meta["x0"]) / meta["cs"])
        r = int((cy - meta["y0"]) / meta["cs"])
        if 0 <= r < n and 0 <= c < n:
            out[(r, c)] = YOLO_CLASSES[cls_idx]
    return out

def run(img, meta, model, conf=0.35):
    res = model.predict(img, imgsz=640, conf=conf, verbose=False)[0]
    dets = []
    if res.boxes is not None:
        for b in res.boxes:
            x1, y1, x2, y2 = [float(v) for v in b.xyxy[0]]
            dets.append((int(b.cls[0]), (x1 + x2) / 2, (y1 + y2) / 2))
    return assign_cells(meta, dets)   # 未覆盖格子 = empty（eval 层按 labels 补空）
