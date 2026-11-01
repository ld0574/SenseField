# -*- coding: utf-8 -*-
"""C2 候选：格子级 MobileNetV3-Small（torchvision 预训练 + 微调，CPU）。
策略（控时）：
  - 图像 224；格子裁剪（格子尺寸 ×1.0 方，不足补边）
  - 微调 = 解冻最后 2 个 block + 分类头（约 15% 参数），AdamW lr=1e-3，
    200 帧 × ~70 格/帧 ≈ 1.4 万样本，20 epochs（书内上限）
  - 单图推理 ~19ms（实测 batch4），200 帧 ≈ 5 分钟，可接受
空格外框补棋盘底色；未识别类不单独设（empty 是真类，直接学）。
"""
import os
import torch
import torch.nn as nn
import torchvision.models as M
import torchvision.transforms as T
import cv2
import numpy as np
from .common import CLASSES, CLIDX, cell_center, imwrite_unicode, MODELS

INPUT = 224

def crop_cell(img, meta, r, c, pad=1.0):
    """裁格子并统一缩放到 96×96（不同帧格子边长不同，必须定尺）。"""
    cx, cy = cell_center(meta, r, c)
    side = int(meta["cs"] * pad)
    h, w = img.shape[:2]
    l, t = max(0, cx - side // 2), max(0, cy - side // 2)
    out = np.zeros((side, side, 3), np.uint8)
    if side > 0:
        src = img[t:t + side, l:l + side]
        sh = min(side, src.shape[0], src.shape[1])
        out[:sh, :sh] = src[:sh, :sh]
    return cv2.resize(out, (96, 96), interpolation=cv2.INTER_AREA)

def load_data(metas, labels):
    from .common import imread_unicode, SYNTH
    X, y, meta_idx = [], [], []
    for fi in sorted(metas):
        img = imread_unicode("%s/synth_%s.png" % (SYNTH, fi))
        m = metas[fi]
        for (r, c), cls in labels[fi].items():
            X.append(crop_cell(img, m, r, c))
            y.append(CLIDX[cls])
            meta_idx.append(fi)
    return np.stack(X), np.array(y), meta_idx

def split_frame_ids(meta_idx, test_frac=0.3):
    """按帧切分训练/测试（同一帧的格子不跨集，防泄漏）。"""
    ids = sorted(set(meta_idx))
    test = set(ids[int(len(ids) * (1 - test_frac)):])
    return test

def build_model():
    m = M.mobilenet_v3_small(weights=None)
    # torchvision 0.28: classifier 末层可能不是 Linear（有 Hardswish/AdaptivePool），找最后一个 Linear
    lines = [l for l in m.classifier if isinstance(l, nn.Linear)]
    fc = lines[-1]
    prefix = [l for l in m.classifier if l is not fc]
    m.classifier = nn.Sequential(*prefix, nn.Dropout(0.3),
                                 nn.Linear(fc.in_features, len(CLASSES)))
    return m

def freeze_all_but_last_blocks(model, n_blocks=2):
    for p in model.parameters():
        p.requires_grad = False
    feats = model.features
    for blk in feats[-n_blocks:]:
        for p in blk.parameters():
            p.requires_grad = True
    for p in model.classifier.parameters():
        p.requires_grad = True

def train(epochs=20, bs=32, resume=True):
    from .common import load_synth
    metas, labels = load_synth()
    X, y, meta_idx = load_data(metas, labels)
    test_ids = split_frame_ids(meta_idx)
    tf = np.array([m not in test_ids for m in meta_idx])
    Xtr, ytr, Xte, yte = X[tf], y[tf], X[~tf], y[~tf]
    Xtr = torch.from_numpy(Xtr.astype(np.float32) / 255.0).permute(0, 3, 1, 2).contiguous()
    Xte = torch.from_numpy(Xte.astype(np.float32) / 255.0).permute(0, 3, 1, 2).contiguous()
    ytr, yte = torch.from_numpy(ytr), torch.from_numpy(yte)
    dev = "cpu"
    model = build_model().to(dev)
    freeze_all_but_last_blocks(model)
    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=1e-3, weight_decay=1e-4)
    ckpt_path = os.path.join(MODELS, "c2_ckpt.pth")
    start_ep, hist = 0, []
    if resume and os.path.exists(ckpt_path):
        sd = torch.load(ckpt_path, map_location="cpu", weights_only=False)
        model.load_state_dict(sd["state"])
        opt.load_state_dict(sd["opt"])
        start_ep, hist = sd["ep"], sd["hist"]
        print("C2-RESUME from epoch %d" % start_ep)
    crit = nn.CrossEntropyLoss()
    tfm = T.Compose([T.Normalize((0.485, 0.456, 0.406), (0.229, 0.224, 0.225))])
    tr = torch.utils.data.TensorDataset(Xtr, ytr)
    dl = torch.utils.data.DataLoader(tr, batch_size=bs, shuffle=True, num_workers=0)
    os.makedirs(MODELS, exist_ok=True)
    for ep in range(start_ep, epochs):
        model.train()
        tot, n = 0.0, 0
        for xb, yb in dl:
            opt.zero_grad()
            loss = crit(model(tfm(xb)), yb)
            loss.backward()
            opt.step()
            tot += loss.item() * len(xb); n += len(xb)
        model.eval()
        with torch.no_grad():
            yhat = model(tfm(Xte)).argmax(1)
            acc = float((yhat == yte).float().mean())
        hist.append({"epoch": ep + 1, "loss": tot / n, "val_acc": acc})
        torch.save({"state": model.state_dict(), "opt": opt.state_dict(),
                    "ep": ep + 1, "hist": hist}, ckpt_path)
        print("C2-EPOCH %d/%d loss=%.4f val_acc=%.4f" % (ep + 1, epochs, tot / n, acc), flush=True)
    torch.save({"state": model.state_dict(), "hist": hist, "classes": CLASSES},
               os.path.join(MODELS, "c2_mobilenet.pth"))
    if os.path.exists(ckpt_path):
        os.remove(ckpt_path)
    print("C2-TRAIN-DONE last_epoch=%d val_acc=%.4f" % (epochs, hist[-1]["val_acc"]))

def load_model():
    m = build_model()
    sd = torch.load(os.path.join(MODELS, "c2_mobilenet.pth"), map_location="cpu", weights_only=False)
    m.load_state_dict(sd["state"])
    m.eval()
    return m

_norm = T.Normalize((0.485, 0.456, 0.406), (0.229, 0.224, 0.225))

def run(img, meta, model):
    n = meta["n"]
    out = {}
    with torch.no_grad():
        for r in range(n):
            for c in range(n):
                cell = crop_cell(img, meta, r, c).astype(np.float32) / 255.0
                x = torch.from_numpy(cell).permute(2, 0, 1).unsqueeze(0)
                pred = int(model(_norm(x)).argmax(1))
                out[(r, c)] = CLASSES[pred]
    return out
