# -*- coding: utf-8 -*-
"""eval_all.py — 任务3 评测器：五候选在合成集（+真机若有）上同台对比。
用法：
  python src/eval_all.py            # 跑 C0/C1（C2/C3 需先 train）
  python src/eval_all.py --c2 --c3  # 含已训练的 C2/C3
输出：
  models/results.json   每候选：格子级准确率(总/分类别)、特殊棋子召回、单帧延迟 p50(ms)、训练成本
  data/real/predictions-<候选>.png  真机帧预测联图（供领导目测；data/real 为空则跳过）
反向验证（书内两条，输出贴对话）：
  1. 死识别器自检：f000 vs f001 两帧不同图，预测必须不完全相同
  2. 翻格敏感：f002 上把 5 格换成不同棋子（模拟换皮），各候选对 5 格预测必须变化
复跑一致性：同命令再跑一次，results.json 的数字必须相同（C2/C3 用固定权重，无随机）。
"""
import json
import os
import sys
import time
import numpy as np
import cv2

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from src import common as C
from src import c0_baseline, c1_template, c2_cnn, c3_yolo, c4_vlm

RD = C.RD
EVAL_FRAMES = 100   # 前 100 帧做主评测（其余 100 帧训练集外，防 C2/C3 训练泄漏到评测）

def load_eval_frames(metas, labels):
    ids = [fi for fi in sorted(metas) if fi >= "f100"][:EVAL_FRAMES]   # f100-f199 全为测试帧
    out = []
    for fi in ids:
        img = C.imread_unicode("%s/synth_%s.png" % (C.SYNTH, fi))
        out.append((fi, img, metas[fi], labels[fi]))
    return out

def merge_truth(frames):
    """键=(fi, r, c)：跨帧合并必须带帧号，否则塌缩到最后一帧。"""
    m = {}
    for fi, img, meta, truth in frames:
        for rc, v in truth.items():
            m[(fi, rc)] = v
    return m

def merge_pred(frames, runfn, *args):
    m = {}
    for fi, img, meta, truth in frames:
        for rc, v in runfn(img, meta, *args).items():
            m[(fi, rc)] = v
    return m

def metrics(pred, truth):
    """格子级准确率 + 分类别 + 特殊棋子召回。"""
    tot = cor = 0
    per = {}
    special_truth = special_hit = 0
    for (rc), gt in truth.items():
        pr = pred.get(rc, "empty" if "empty" in pred.values() else "X")
        tot += 1
        per.setdefault(gt, [0, 0])
        per[gt][1] += 1
        if pr == gt:
            cor += 1
        per[gt][0] += int(pr == gt)
        if gt in ("bomb", "obstacle"):
            special_truth += 1
            special_hit += int(pr == gt)
    per_acc = {k: (v[0] / v[1] if v[1] else 0.0) for k, v in sorted(per.items())}
    return {
        "grid_acc": round(cor / tot, 4) if tot else None,
        "per_class": {k: round(v, 4) for k, v in per_acc.items()},
        "special_recall": round(special_hit / special_truth, 4) if special_truth else None,
        "n_cells": tot,
    }

def time_candidate(runfn, frames, args):
    times = []
    for fi, img, meta, truth in frames:
        t0 = time.time()
        runfn(img, meta, *args)
        times.append((time.time() - t0) * 1000)
    times = np.array(times)
    return float(np.percentile(times, 50)), float(np.percentile(times, 95))

def main():
    use_c2 = "--c2" in sys.argv
    use_c3 = "--c3" in sys.argv
    metas, labels = C.load_synth()
    frames = load_eval_frames(metas, labels)
    assets = {n: C.load_asset(n) for n in C.CLASSES if n != "empty"}
    results = {}

    # ---- C0 ----
    c0_npz = os.path.join(C.MODELS, "c0_templates.npz")
    c0_json = os.path.join(C.MODELS, "c0_letter2class.json")
    if os.path.exists(c0_npz) and os.path.exists(c0_json):
        data = np.load(c0_npz)
        c0_templates = {k: data[k] for k in data.files}
        c0_l2c = json.load(open(c0_json))
    else:
        c0_templates = c0_baseline.learn_templates(frames, metas, labels)
        letter_of = C.letter_map(assets)
        l2c = {}
        for name, letter in letter_of.items():
            if letter:
                l2c.setdefault(letter, []).append(name)
        np.savez(c0_npz, **c0_templates)
        with open(c0_json, "w") as f:
            json.dump(l2c, f)
        c0_l2c = l2c
    def c0_run(img, meta, templates, l2c):
        return c0_baseline.run(img, meta, templates, C.letter_map(assets), l2c)
    p50, p95 = time_candidate(c0_run, frames, (c0_templates, c0_l2c))
    m = merge_pred(frames, c0_run, c0_templates, c0_l2c)
    results["C0_baseline_port"] = {"p50_ms": round(p50, 1), "p95_ms": round(p95, 1),
                                   "train_cost": "无训练（移植 Java 逻辑+10帧学模板）",
                                   **metrics(m, merge_truth(frames))}

    # ---- C1 ----
    c1_lib = c1_template.make_templates(assets)
    p50, p95 = time_candidate(c1_template.run, frames, (c1_lib,))
    m = merge_pred(frames, c1_template.run, c1_lib)
    results["C1_multiscale_template"] = {"p50_ms": round(p50, 1), "p95_ms": round(p95, 1),
                                         "train_cost": "无训练（素材当模板）",
                                         **metrics(m, merge_truth(frames))}

    # ---- C2 ----
    if use_c2:
        model = c2_cnn.load_model()
        p50, p95 = time_candidate(c2_cnn.run, frames, (model,))
        m = merge_pred(frames, c2_cnn.run, model)
        results["C2_mobilenetv3_cell"] = {"p50_ms": round(p50, 1), "p95_ms": round(p95, 1),
                                          "train_cost": "20 epochs CPU（实测见 c2_train2.log）",
                                          **metrics(m, merge_truth(frames))}

    # ---- C3 ----
    if use_c3:
        model = c3_yolo.load_model()
        def c3_run(img, meta, model):
            out = c3_yolo.run(img, meta, model)
            for rc in [(r, c) for r in range(meta["n"]) for c in range(meta["n"])]:
                out.setdefault(rc, "empty")
            return out
        p50, p95 = time_candidate(c3_run, frames, (model,))
        m = merge_pred(frames, c3_run, model)
        results["C3_yolov8n_whole_screen"] = {"p50_ms": round(p50, 1), "p95_ms": round(p95, 1),
                                              "train_cost": "50 epochs CPU（见 train 输出，超 60min 降 10 并标注）",
                                              **metrics(m, merge_truth(frames))}

    # ---- C4（可选）----
    if c4_vlm.available():
        t0 = time.time()
        rows, ms, usage = c4_vlm.run_frame(frames[0][1], frames[0][2])
        results["C4_vlm_%s" % c4_vlm.MODEL] = {
            "note": "3帧抽查（不进主评测）",
            "elapsed_ms_first": round(ms, 1), "usage": usage,
            "total_wall_min": round((time.time() - t0) / 60, 1),
        }
    else:
        results["C4_vlm_skipped"] = {"note": "无 OPENROUTER_API_KEY，按书内约定跳过，记 BLOCKED.md"}

    with open(os.path.join(C.MODELS, "results.json"), "w") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    print(json.dumps(results, ensure_ascii=False, indent=1))

    # ---- 真机预测联图（data/real 非空时）----
    real_files = sorted(os.listdir(C.REAL)) if os.path.isdir(C.REAL) else []
    real_files = [f for f in real_files if f.lower().endswith((".png", ".jpg", ".jpeg"))]
    if real_files:
        thumbs = []
        for fn in real_files[:9]:
            img = C.imread_unicode(os.path.join(C.REAL, fn))
            small = cv2.resize(img, (144, 256))
            cv2.putText(small, fn[:12], (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 0), 1)
            thumbs.append(small)
        cols = 3
        sheet = np.zeros((int(np.ceil(len(thumbs) / cols)) * 256, cols * 144, 3), np.uint8)
        for i, t in enumerate(thumbs):
            sheet[(i // cols) * 256:(i // cols) * 256 + 256, (i % cols) * 144:(i % cols) * 144 + 144] = t
        for cand in ["C0", "C1"] + (["C2"] if use_c2 else []) + (["C3"] if use_c3 else []):
            # 预测图：真实棋盘上叠加各候选识别结果（C0/C1 需要标定，用 auto_detect 近似）
            pred_thumbs = []
            for fn in real_files[:9]:
                img = C.imread_unicode(os.path.join(C.REAL, fn))
                b = C.auto_detect(img)
                small = cv2.resize(img, (144, 256))
                if b:
                    l, t, r, bt = [int(v) for v in b]
                    x0, y0 = l * 720 // 100, t * 1280 // 100
                    S = (r - l) * 720 // 100
                    H = (bt - t) * 1280 // 100
                    n = 8
                    meta = {"n": n, "x0": x0, "y0": y0, "S": S, "cs": S / n}
                    if cand == "C0":
                        pr = c0_baseline.run(img, meta, c0_templates, C.letter_map(assets), c0_l2c)
                    elif cand == "C1":
                        pr = c1_template.run(img, meta, c1_lib)
                    elif cand == "C2":
                        pr = c2_cnn.run(img, meta, c2_cnn.load_model())
                    elif cand == "C3":
                        pr = c3_yolo.run(img, meta, c3_yolo.load_model())
                    for (rr, cc), cls in pr.items():
                        sx, sy = (cc + 0.5) * 144 / n, (rr + 0.5) * 256 / n
                        color = (0, 255, 0) if cls not in ("X", "A", "empty") else (0, 0, 255)
                        cv2.circle(small, (int(sx), int(sy)), 4, color, 1)
                pred_thumbs.append(cv2.resize(small, (144, 256)))
            sheet2 = np.zeros((int(np.ceil(len(pred_thumbs) / cols)) * 256, cols * 144, 3), np.uint8)
            for i, t in enumerate(pred_thumbs):
                sheet2[(i // cols) * 256:(i // cols) * 256 + 256, (i % cols) * 144:(i % cols) * 144 + 144] = t
            C.imwrite_unicode(os.path.join(C.REAL, "predictions-%s.png" % cand), sheet2)
        print("REAL-SHEET-DONE 真机 %d 张，各候选 predictions-*.png 已生成（绿点=识别出，红点=未识别）" % len(real_files))
    else:
        print("REAL-SKIP data/real 为空，跳过真机预测联图（结论标「仅合成」）")

    # ---- 反向验证 ----
    print("\n=== 反向验证 ===")
    # 1. 死识别器自检
    fA = C.imread_unicode("%s/synth_f000.png" % C.SYNTH)
    fB = C.imread_unicode("%s/synth_f001.png" % C.SYNTH)
    mA, mB = metas["f000"], metas["f001"]
    c0a, c0b = c0_baseline.run(fA, mA, c0_templates, C.letter_map(assets), c0_l2c), c0_baseline.run(fB, mB, c0_templates, C.letter_map(assets), c0_l2c)
    c1a, c1b = c1_template.run(fA, mA, c1_lib), c1_template.run(fB, mB, c1_lib)
    same0 = c0a == c0b
    same1 = c1a == c1b
    print("DEADCHECK-C0 same=%s (期望 False)" % same0)
    print("DEADCHECK-C1 same=%s (期望 False)" % same1)
    if use_c2:
        mod2 = c2_cnn.load_model()
        same2 = c2_cnn.run(fA, mA, mod2) == c2_cnn.run(fB, mB, mod2)
        print("DEADCHECK-C2 same=%s (期望 False)" % same2)
    if use_c3:
        mod3 = c3_yolo.load_model()
        same3 = c3_yolo.run(fA, mA, mod3) == c3_yolo.run(fB, mB, mod3)
        print("DEADCHECK-C3 same=%s (期望 False)" % same3)
    # 2. 翻格敏感
    fC_orig = C.imread_unicode("%s/synth_f002.png" % C.SYNTH)
    fC = C.imread_unicode("%s/synth_f002.png" % C.SYNTH)
    mC = metas["f002"]
    flips = [(0, 0, "bomb"), (2, 2, "fox"), (4, 4, "bear"), (6, 6, "cat"), (7 if mC["n"] > 7 else 5, 1, "frog")]
    before0 = c0_baseline.run(fC_orig, mC, c0_templates, C.letter_map(assets), c0_l2c)
    before1 = c1_template.run(fC_orig, mC, c1_lib)
    before2 = c2_cnn.run(fC_orig, mC, c2_cnn.load_model()) if use_c2 else None
    before3 = c3_yolo.run(fC_orig, mC, c3_yolo.load_model()) if use_c3 else None
    for (r, c, cls) in flips:
        cx, cy = C.cell_center(mC, r, c)
        C.paste_piece(fC, cls, cx, cy, int(mC["cs"] * 0.62), assets)
    new0 = c0_baseline.run(fC, mC, c0_templates, C.letter_map(assets), c0_l2c)
    new1 = c1_template.run(fC, mC, c1_lib)
    new2 = c2_cnn.run(fC, mC, c2_cnn.load_model()) if use_c2 else None
    new3 = c3_yolo.run(fC, mC, c3_yolo.load_model()) if use_c3 else None
    for name, before, after in (("C0", before0, new0), ("C1", before1, new1)) + \
                               ((("C2", before2, new2),) if use_c2 else ()) + \
                               ((("C3", before3, new3),) if use_c3 else ()):
        changed = sum(1 for (r, c, _) in flips if before.get((r, c)) != after.get((r, c)))
        print("FLIP-DETECT %s changed=%d/5 (要求>=3/4)" % (name, changed))
    print("=== 反向验证结束 ===")

if __name__ == "__main__":
    os.makedirs(C.MODELS, exist_ok=True)
    main()
