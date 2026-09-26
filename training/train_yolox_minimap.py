"""Train and validate YOLOX-Nano on a cropped minimap COCO dataset.

The upstream YOLOX trainer assumes CUDA. This small, single-device runner keeps
the model and data format unchanged while supporting CUDA, Apple MPS, and CPU.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from mapassist.detection_evaluate import _match_boxes


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _git_revision(path: Path) -> str | None:
    result = subprocess.run(
        ["git", "-C", str(path), "rev-parse", "HEAD"],
        text=True,
        capture_output=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def _match(predictions: list[list[float]], truths: list[list[float]],
           threshold: float = 0.5) -> int:
    return len(_match_boxes(predictions, truths, threshold))


def _finish(tp: int, fp: int, fn: int) -> dict[str, Any]:
    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    f1 = (2 * precision * recall / (precision + recall)
          if precision is not None and recall is not None and precision + recall else None)
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": round(precision, 6) if precision is not None else None,
        "recall": round(recall, 6) if recall is not None else None,
        "f1": round(f1, 6) if f1 is not None else None,
    }


def _device(torch: Any, value: str) -> Any:
    if value != "auto":
        return torch.device(value)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def _split_predictions(model: Any, device: Any, data_dir: Path,
                       input_size: tuple[int, int], nms_threshold: float,
                       split: str = "val", pre_filter_confidence: float = 0.01
                       ) -> tuple[dict[int, list[tuple[float, list[float]]]],
                                  dict[int, list[list[float]]]]:
    import cv2
    import torch
    from yolox.data import ValTransform
    from yolox.utils import postprocess

    if split not in {"train", "val", "test"}:
        raise ValueError(f"Unsupported COCO split: {split}")
    if not 0 <= pre_filter_confidence <= 1:
        raise ValueError("pre_filter_confidence must be between 0 and 1")
    split_name = f"{split}2017"
    annotation = json.loads(
        (data_dir / "annotations" / f"instances_{split_name}.json").read_text(
            encoding="utf-8"
        )
    )
    truths: dict[int, list[list[float]]] = {image["id"]: [] for image in annotation["images"]}
    for item in annotation["annotations"]:
        truths[item["image_id"]].append([float(value) for value in item["bbox"]])
    transform = ValTransform(legacy=False)
    predictions: dict[int, list[tuple[float, list[float]]]] = {}
    model.eval()
    with torch.inference_mode():
        for image_info in annotation["images"]:
            image = cv2.imread(str(data_dir / split_name / image_info["file_name"]))
            if image is None:
                raise FileNotFoundError(image_info["file_name"])
            height, width = image.shape[:2]
            ratio = min(input_size[0] / height, input_size[1] / width)
            transformed, _ = transform(image, None, input_size)
            tensor = torch.from_numpy(transformed).unsqueeze(0).float().to(device)
            raw = model(tensor).detach().cpu()
            detected = postprocess(
                raw, 1, conf_thre=pre_filter_confidence, nms_thre=nms_threshold,
                class_agnostic=True,
            )[0]
            found = []
            if detected is not None:
                for row in detected.tolist():
                    x0, y0, x1, y1, objectness, class_confidence, _ = row
                    found.append((
                        float(objectness * class_confidence),
                        [x0 / ratio, y0 / ratio,
                         (x1 - x0) / ratio, (y1 - y0) / ratio],
                    ))
            predictions[image_info["id"]] = found
    return predictions, truths


def _validation_predictions(model: Any, device: Any, data_dir: Path,
                            input_size: tuple[int, int], nms_threshold: float
                            ) -> tuple[dict[int, list[tuple[float, list[float]]]],
                                       dict[int, list[list[float]]]]:
    return _split_predictions(model, device, data_dir, input_size, nms_threshold, "val")


def evaluate(model: Any, device: Any, data_dir: Path,
             input_size: tuple[int, int], nms_threshold: float) -> dict[str, Any]:
    predictions, truths = _validation_predictions(
        model, device, data_dir, input_size, nms_threshold
    )
    thresholds = [round(index / 100, 2) for index in range(1, 96, 2)]
    results = []
    for threshold in thresholds:
        tp = fp = fn = 0
        for image_id, ground_truth in truths.items():
            boxes = [box for score, box in predictions[image_id] if score >= threshold]
            matched = _match(boxes, ground_truth)
            tp += matched
            fp += len(boxes) - matched
            fn += len(ground_truth) - matched
        result = {"confidence": threshold, **_finish(tp, fp, fn)}
        results.append(result)
    eligible = [item for item in results if (item["precision"] or 0) >= 0.9]
    if eligible:
        selected = max(eligible, key=lambda item: (item["recall"] or 0,
                                                   item["f1"] or 0))
        policy = "highest_recall_with_precision_at_least_0.9"
    else:
        selected = max(results, key=lambda item: item["f1"] or 0)
        policy = "highest_f1_no_threshold_reached_0.9_precision"
    return {"iou_threshold": 0.5, "selection_policy": policy,
            "selected": selected, "thresholds": results}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yolox-root", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--pretrained", type=Path)
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--input-size", type=int, default=256)
    parser.add_argument("--mosaic-prob", type=float, default=0.5)
    parser.add_argument("--no-aug-epochs", type=int)
    parser.add_argument(
        "--lr-scale", type=float, default=1.0,
        help="Multiply YOLOX's batch-scaled learning rate; use values such as 0.1 for fine-tuning",
    )
    parser.add_argument("--eval-every", type=int, default=5)
    parser.add_argument("--log-every", type=int, default=5)
    parser.add_argument(
        "--early-stop-patience", type=int, default=0,
        help="Stop after this many validation checkpoints without a better selected F1; 0 disables",
    )
    parser.add_argument(
        "--early-stop-min-epoch", type=int, default=0,
        help="Do not early-stop before this epoch",
    )
    parser.add_argument("--no-cache", action="store_true")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--seed", type=int, default=20260926)
    args = parser.parse_args()
    if (args.epochs < 1 or args.batch_size < 1 or args.eval_every < 1 or
            args.log_every < 1 or args.input_size < 64 or args.lr_scale <= 0 or
            args.input_size % 32 != 0 or args.early_stop_patience < 0 or
            args.early_stop_min_epoch < 0):
        parser.error("counts, input size, and lr-scale must be positive")
    if args.early_stop_min_epoch > args.epochs:
        parser.error("--early-stop-min-epoch cannot exceed --epochs")
    if not 0 <= args.mosaic_prob <= 1:
        parser.error("mosaic-prob must be between 0 and 1")
    if args.no_aug_epochs is not None and not 0 <= args.no_aug_epochs < args.epochs:
        parser.error("no-aug-epochs must be between 0 and epochs - 1")

    root = Path(__file__).resolve().parents[1]
    yolox_root = args.yolox_root.resolve()
    data_dir = args.data_dir.resolve()
    output = args.output.resolve()
    sys.path.insert(0, str(yolox_root))
    os.environ["MAPASSIST_COCO_DIR"] = str(data_dir)

    import numpy as np
    import torch
    from yolox.exp import get_exp
    from yolox.utils import ModelEMA, load_ckpt

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    device = _device(torch, args.device)
    exp_path = root / "training/yolox_nano_minimap.py"
    exp = get_exp(str(exp_path), None)
    exp.max_epoch = args.epochs
    exp.input_size = (args.input_size, args.input_size)
    exp.test_size = exp.input_size
    exp.random_size = (args.input_size // 32, args.input_size // 32)
    exp.mosaic_prob = args.mosaic_prob
    exp.basic_lr_per_img *= args.lr_scale
    exp.no_aug_epochs = (args.no_aug_epochs if args.no_aug_epochs is not None else
                         min(exp.no_aug_epochs, max(1, args.epochs // 5)))
    exp.warmup_epochs = min(exp.warmup_epochs, max(0, args.epochs // 10))

    model = exp.get_model()
    if args.pretrained is not None:
        checkpoint = torch.load(args.pretrained, map_location="cpu", weights_only=False)
        model = load_ckpt(model, checkpoint.get("model", checkpoint))
    model.to(device)
    optimizer = exp.get_optimizer(args.batch_size)
    cache_type = None
    if not args.no_cache:
        exp.dataset = exp.get_dataset(cache=True, cache_type="ram")
        cache_type = "ram"
    train_loader = exp.get_data_loader(
        args.batch_size, is_distributed=False, no_aug=False, cache_img=cache_type
    )
    iterations_per_epoch = len(train_loader)
    scheduler = exp.get_lr_scheduler(
        exp.basic_lr_per_img * args.batch_size, iterations_per_epoch
    )
    ema = ModelEMA(model, 0.9998)
    output.mkdir(parents=True, exist_ok=True)

    metadata = {
        "schema_version": 1,
        "design": "development_training",
        "warning": (
            "Development train/validation data were used for model and threshold selection; "
            "metrics are not independent holdout results."
        ),
        "device": str(device),
        "seed": args.seed,
        "epochs": args.epochs,
        "batch_size": args.batch_size,
        "input_size": list(exp.input_size),
        "mosaic_prob": exp.mosaic_prob,
        "lr_scale": args.lr_scale,
        "basic_lr_per_img": exp.basic_lr_per_img,
        "no_aug_epochs": exp.no_aug_epochs,
        "early_stopping": {
            "patience_checkpoints": args.early_stop_patience,
            "minimum_epoch": args.early_stop_min_epoch,
            "selection_metric": "validation selected F1",
        },
        "yolox_revision": _git_revision(yolox_root),
        "experiment": {"path": str(exp_path), "sha256": _sha256(exp_path)},
        "train_annotations_sha256": _sha256(
            data_dir / "annotations/instances_train2017.json"
        ),
        "val_annotations_sha256": _sha256(
            data_dir / "annotations/instances_val2017.json"
        ),
        "pretrained": ({"path": str(args.pretrained.resolve()),
                        "sha256": _sha256(args.pretrained.resolve())}
                       if args.pretrained is not None else None),
        "history": [],
    }
    best_f1 = -1.0
    best_epoch = None
    evaluations_without_improvement = 0
    stopped_early = False
    no_aug = False
    started = time.monotonic()
    for epoch in range(args.epochs):
        if not no_aug and epoch >= args.epochs - exp.no_aug_epochs:
            train_loader.close_mosaic()
            model.head.use_l1 = True
            no_aug = True
        model.train()
        sums: dict[str, float] = {}
        train_iterator = iter(train_loader)
        for iteration in range(iterations_per_epoch):
            inputs, targets, _, _ = next(train_iterator)
            iteration_started = time.monotonic()
            inputs = inputs.float().to(device)
            targets = targets.float().to(device)
            outputs = model(inputs, targets)
            optimizer.zero_grad(set_to_none=True)
            outputs["total_loss"].backward()
            optimizer.step()
            ema.update(model)
            learning_rate = scheduler.update_lr(
                epoch * iterations_per_epoch + iteration + 1
            )
            for group in optimizer.param_groups:
                group["lr"] = learning_rate
            for key, value in outputs.items():
                if torch.is_tensor(value):
                    sums[key] = sums.get(key, 0.0) + float(value.detach().cpu())
            if ((iteration + 1) % args.log_every == 0 or
                    iteration + 1 == iterations_per_epoch):
                print(
                    f"epoch {epoch + 1}/{args.epochs} iteration "
                    f"{iteration + 1}/{iterations_per_epoch} "
                    f"loss={float(outputs['total_loss'].detach().cpu()):.4f} "
                    f"step={time.monotonic() - iteration_started:.2f}s",
                    flush=True,
                )
        record: dict[str, Any] = {
            "epoch": epoch + 1,
            "loss": {key: round(value / iterations_per_epoch, 6)
                     for key, value in sums.items()},
            "learning_rate": learning_rate,
            "elapsed_seconds": round(time.monotonic() - started, 3),
        }
        should_evaluate = ((epoch + 1) % args.eval_every == 0 or
                           epoch + 1 == args.epochs or no_aug and epoch + 1 == args.epochs - exp.no_aug_epochs)
        if should_evaluate:
            validation = evaluate(
                ema.ema, device, data_dir, exp.test_size, exp.nmsthre
            )
            record["validation"] = validation
            selected_f1 = validation["selected"]["f1"] or 0.0
            if selected_f1 > best_f1:
                best_f1 = selected_f1
                best_epoch = epoch + 1
                evaluations_without_improvement = 0
                torch.save({"model": ema.ema.state_dict(), "epoch": epoch + 1,
                            "validation": validation}, output / "best_ckpt.pth")
            else:
                evaluations_without_improvement += 1
        metadata["history"].append(record)
        torch.save({"model": ema.ema.state_dict(), "optimizer": optimizer.state_dict(),
                    "epoch": epoch + 1}, output / "latest_ckpt.pth")
        (output / "metrics.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        validation_text = (f" val={record['validation']['selected']}"
                           if "validation" in record else "")
        print(f"epoch {epoch + 1}/{args.epochs} loss={record['loss']['total_loss']:.4f}"
              f" elapsed={record['elapsed_seconds']:.1f}s{validation_text}", flush=True)
        if ("validation" in record and args.early_stop_patience and
                epoch + 1 >= args.early_stop_min_epoch and
                evaluations_without_improvement >= args.early_stop_patience):
            stopped_early = True
            print(
                f"early stop after epoch {epoch + 1}: no validation improvement for "
                f"{evaluations_without_improvement} checkpoints",
                flush=True,
            )
            break

    metadata["best_validation_f1"] = round(best_f1, 6)
    metadata["best_epoch"] = best_epoch
    metadata["completed_epochs"] = len(metadata["history"])
    metadata["stopped_early"] = stopped_early
    metadata["elapsed_seconds"] = round(time.monotonic() - started, 3)
    (output / "metrics.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"output": str(output), "best_validation_f1": best_f1,
                      "elapsed_seconds": metadata["elapsed_seconds"]}, indent=2))


if __name__ == "__main__":
    main()
