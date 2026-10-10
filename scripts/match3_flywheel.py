#!/usr/bin/env python3
"""Persistent, local-only Match3 harvest -> review -> gallery pipeline.

Engine outputs are suggestions. Only explicit three-axis human reviews enter a
gallery. Split whole source sessions before evaluation, never adjacent frames.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(canonical(value) + "\n", encoding="utf-8")


def sibling(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def pixels(value):
    if not isinstance(value, list) or len(value) != 256 or any(type(v) is not int for v in value):
        raise ValueError("a descriptor needs exactly 256 integer pixels")
    return [v & 0xffffff for v in value]


def anchor_pixels(role, patch):
    return patch[:128] + [0x1e2a58] * 128 if role == "goal" else patch


def backdrop(pixel):
    r, g, b = pixel >> 16 & 255, pixel >> 8 & 255, pixel & 255
    high, low = max(r, g, b), min(r, g, b)
    if high >= 128 or high == low:
        return False
    delta = high - low
    hue = (60 * (g - b) / delta if high == r else
           60 * ((b - r) / delta + 2) if high == g else 60 * ((r - g) / delta + 4)) % 360
    return 170 <= hue <= 300


def body_distance(a, b):
    """Same rigid registration/background/quadrant constraints as Body.difference.

This clusters appearances only; it never classifies a piece or grants rules.
"""
    a, b = pixels(a), pixels(b)
    def aligned(dx, dy):
        if any(not backdrop(a[y * 16 + x]) for y in range(16) for x in range(16)
               if not (0 <= x - dx < 16 and 0 <= y - dy < 16)):
            return math.inf
        sums, counts = [0] * 4, [0] * 4
        for y in range(16):
            for x in range(16):
                xx, yy = x + dx, y + dy
                if 0 <= xx < 16 and 0 <= yy < 16:
                    first, second = a[yy * 16 + xx], b[y * 16 + x]
                    error = sum(abs((first >> k & 255) - (second >> k & 255)) for k in (16, 8, 0))
                    quarter = y // 8 * 2 + x // 8
                    sums[quarter] += error
                    counts[quarter] += 1
        if any(s / (n * 765) > .14500002 for s, n in zip(sums, counts)):
            return math.inf
        value = sum(sums) / (sum(counts) * 765)
        return value if value <= .14500002 else math.inf
    direct = aligned(0, 0)
    if direct <= .12:
        return direct
    return min(aligned(dx, dy) for dy in range(-2, 3) for dx in range(-2, 3))


def harvest(predictions, manifest, registry):
    group = manifest.get("session_id")
    if not isinstance(group, str) or not group or ":" in group:
        raise ValueError("manifest needs a stable session_id for whole-session splits")
    frames = {frame["file"] for frame in manifest["frames"]}
    families = registry.setdefault("families", [])
    registry["format"] = "match3-family-registry-v1"
    seen = {member["source"] for family in families for member in family["samples"]}
    samples = []
    for sample in predictions.get("samples", []):
        frame = sample["id"].split(":", 1)[0]
        if frame not in frames:
            raise ValueError("prediction outside harvest manifest")
        if "envelope" in sample:
            samples.append({**sample, "frame": frame, "role": "cell", "pixels": sample["envelope"]})
    for frame, cards in predictions.get("goal_cards", {}).items():
        if frame not in frames:
            raise ValueError("goal outside harvest manifest")
        for slot, card in enumerate(cards):
            samples.append({**card, "id": card.get("id", f"{frame}:goal{slot}"), "frame": frame, "role": "goal"})
    for sample in predictions.get("large_objects", []):
        if sample.get("frame") not in frames:
            raise ValueError("object outside harvest manifest")
        samples.append({**sample, "role": "object"})
    for sample in samples:
        source = f"{group}:{sample['role']}:{sample['id']}"
        if source in seen:
            prior = next(member for family in families for member in family["samples"] if member["source"] == source)
            if prior["pixels"] != pixels(sample["pixels"]):
                raise ValueError("source id reused for different pixels; preserve stable recording ids")
            continue
        patch = pixels(sample["pixels"])
        descriptor = anchor_pixels(sample["role"], patch)
        scores = sorted((body_distance(descriptor, family["anchor"]), family["family"])
                        for family in families if family["role"] == sample["role"])
        by_id = {family["family"]: family for family in families}
        if scores and scores[0][0] <= .12 and (len(scores) < 2 or scores[1][0] - scores[0][0] >= .025):
            family = by_id[scores[0][1]]
        else:
            family_id = "g-" + digest({"role": sample["role"], "anchor": descriptor})[:20]
            if family_id in by_id:
                family = by_id[family_id]
            else:
                family = {"family": family_id, "role": sample["role"], "anchor": descriptor, "samples": [],
                          "ambiguous": bool(scores and scores[0][0] <= .12)}
                families.append(family)
        family["samples"].append({**sample, "pixels": patch, "group": group, "source": source})
        seen.add(source)
    families.sort(key=lambda family: family["family"])
    return registry


def event_predictions(path):
    predictions = {"samples": [], "goal_cards": {}, "large_objects": []}
    frames = set()
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        event = json.loads(line)
        if event.get("type") != "Match3FlywheelSample":
            continue
        sample = event["data"]
        frames.add(sample["frame"])
        if sample["role"] == "cell":
            predictions["samples"].append(sample)
        elif sample["role"] == "goal":
            predictions["goal_cards"].setdefault(sample["frame"], []).append(sample)
        elif sample["role"] == "object":
            predictions["large_objects"].append(sample)
    return predictions, sorted(frames)


def split_family(registry, family_id, selected):
    family = next(f for f in registry["families"] if f["family"] == family_id)
    members = [sample for sample in family["samples"] if sample["source"] in selected]
    if not members or len(members) == len(family["samples"]) or len(members) != len(selected):
        raise ValueError("split must select a nonempty proper subset of existing members")
    new_id = "g-" + digest({"parent": family_id, "sources": sorted(selected)})[:20]
    family["samples"] = [sample for sample in family["samples"] if sample["source"] not in selected]
    family["anchor"] = anchor_pixels(family["role"], family["samples"][0]["pixels"])
    family.pop("review", None)
    registry["families"].append({"family": new_id, "role": family["role"], "anchor": anchor_pixels(family["role"], members[0]["pixels"]),
                                 "samples": members, "split_from": family_id})
    registry["families"].sort(key=lambda f: f["family"])
    return registry


def export_labels(registry, splits, partition):
    groups = splits["groups"]
    samples, goals = {}, {}
    if any(member["group"] not in groups for family in registry["families"] for member in family["samples"]):
        raise ValueError("every source session needs an explicit split")
    for family in registry["families"]:
        if not any(groups[sample["group"]] == partition for sample in family["samples"]):
            continue
        review = family.get("review", {})
        if review.get("reviewed") is not True:
            raise ValueError("every family in the evaluation registry needs review")
        truth = {key: review[key] for key in ("family", "kind", "color", "swappable", "rule", "archetype")}
        for sample in family["samples"]:
            if groups.get(sample["group"]) != partition:
                continue
            item = {**truth, "id": sample["id"], "source": sample["source"], "family_id": family["family"], "reviewed": True}
            if family["role"] == "cell":
                if review["identity"] == "ice":
                    item["ice"] = 1
                if sample["id"] in samples and samples[sample["id"]] != item:
                    raise ValueError("colliding cell ids across sessions; use unique frame names")
                samples[sample["id"]] = item
            elif family["role"] == "goal":
                kind = {"coin": "COIN", "snow": "SNOW", "honey": "HONEY", "iceflower": "ICEFLOWER",
                        "cookie": "COOKIE", "ice": "ICE", "egg": "EGG", "R": "RED", "O": "BEAR",
                        "Y": "CHICK", "G": "FROG", "B": "HIPPO", "P": "CAT"}.get(review["identity"], "UNKNOWN")
                item = {"id": sample["id"], "kind": kind, "source": sample["source"], "reviewed": True}
                if sample["id"] in goals and goals[sample["id"]] != item:
                    raise ValueError("colliding goal ids across sessions; use unique frame names")
                goals[sample["id"]] = item
    # Whole-object reviews override the four per-cell appearance fragments.
    for family in registry["families"]:
        review = family.get("review", {})
        if family["role"] != "object" or review.get("identity") != "cookie":
            continue
        for sample in family["samples"]:
            if groups.get(sample["group"]) != partition:
                continue
            for row in range(sample["row"], sample["row"] + 2):
                for col in range(sample["col"], sample["col"] + 2):
                    sample_id = f"{sample['frame']}:r{row}c{col}"
                    prior = samples.get(sample_id)
                    if prior and prior["kind"] not in ("UNKNOWN", "SURFACE", "COOKIE"):
                        raise ValueError("whole-object review conflicts with a cell's reviewed identity")
                    samples[sample_id] = {"id": sample_id, "family": "COOKIE", "kind": "COOKIE", "color": "",
                                          "swappable": False, "rule": "identity_only_2x2", "source": sample["source"], "reviewed": True}
    return {"samples": list(samples.values()), "goal_samples": list(goals.values()),
            "registry_sha256": digest(registry), "split_sha256": digest(splits), "partition": partition}


def apply_review(registry, review):
    if review.get("registry_sha256") != digest(registry):
        raise ValueError("review refers to another registry revision; regenerate the page")
    labels = sibling("match3_apply_cell_labels")
    for family in registry["families"]:
        family_id = family["family"]
        if family_id in review.get("reviewed", {}) and review["reviewed"][family_id] is not True:
            family.pop("review", None)
            continue
        if review.get("reviewed", {}).get(family_id) is not True:
            continue
        truth = labels.record(review["labels"][family_id], review["archetypes"][family_id],
                              review["swappable"][family_id])
        family["review"] = {**truth, "identity": review["labels"][family_id], "reviewed": True,
                            "registry_sha256": review["registry_sha256"]}
    return registry


def build_gallery(registry, splits, max_per_family=24, max_families=64, max_bytes=1048576):
    groups = splits.get("groups", {})
    if not groups or "train" not in groups.values() or "test" not in groups.values():
        raise ValueError("split manifest needs disjoint train and test source sessions")
    if any(value not in ("train", "dev", "test") for value in groups.values()):
        raise ValueError("partition must be train, dev or test")
    if any(sample["group"] not in groups for family in registry["families"] for sample in family["samples"]):
        raise ValueError("every harvested source session needs an explicit split")
    catalog = {"format": "match3-gallery-v1", "ordinary_envelopes": [], "cells": [], "goals": [],
               "large_objects": [], "ice_surfaces": [], "metadata": {"registry_sha256": digest(registry),
               "split_sha256": digest(splits), "max_per_family": max_per_family, "max_families": max_families,
               "max_bytes": max_bytes, "evaluation": "pending", "training_groups": sorted(k for k, v in groups.items() if v == "train")}}
    admitted = 0
    for family in registry["families"]:
        truth = family.get("review", {})
        if not truth.get("reviewed") or truth.get("archetype") == "unknown":
            continue
        train = [sample for sample in family["samples"] if groups[sample["group"]] == "train"]
        if not train:
            continue
        identity, role = truth["identity"], family["role"]
        if identity in ("unknown", "special", "empty"):
            continue
        admitted += 1
        if admitted > max_families:
            raise ValueError("family budget exceeded; curate the training gallery")
        kept = []
        for sample in sorted(train, key=lambda item: item["source"]):
            if any(body_distance(sample["pixels"], previous["pixels"]) <= .06 for previous in kept):
                continue
            if len(kept) >= max_per_family:
                break
            kept.append(sample)
            entry = {"family_id": family["family"], "source": sample["source"], "reviewed": True,
                     "archetype": truth["archetype"]}
            def pattern(values, kind, rule, mask=None):
                return {**entry, "kind": kind, "rule": rule,
                        "pixels_rgb": "".join(f"{pixel:06x}" for pixel in pixels(values)),
                        "mask_bits": mask or "1" * 256}
            if role == "goal":
                kind = {"coin": "COIN", "snow": "SNOW", "iceflower": "ICEFLOWER", "honey": "HONEY",
                        "cookie": "COOKIE", "ice": "ICE", "egg": "EGG"}.get(identity)
                if identity in "ROYGBP" and len(identity) == 1:
                    kind = {"R": "RED", "O": "BEAR", "Y": "CHICK", "G": "FROG", "B": "HIPPO", "P": "CAT"}[identity]
                if kind:
                    catalog["goals"].append({**pattern(sample["pixels"], kind, "reviewed_goal_icon", "1" * 128 + "0" * 128), "aspect": sample.get("aspect", 1.0)})
            elif role == "object" and identity == "cookie":
                catalog["large_objects"].append(pattern(sample["pixels"], "cookie", "identity_only_2x2"))
            elif role == "cell" and truth["kind"] == "ANIMAL" and truth["swappable"] is True:
                catalog["ordinary_envelopes"].append({**entry, "color": truth["color"], "rule": "ordinary_uncovered_animal",
                    "pixels_rgb": "".join(f"{pixel:06x}" for pixel in pixels(sample["pixels"]))})
                if "patch" in sample:
                    catalog["cells"].append(pattern(sample["patch"], "animal_" + truth["color"], "ordinary_uncovered_animal"))
            elif role == "cell" and identity == "ice":
                # Only explicitly reviewed bare stationary ice may supply a background reference.
                if truth.get("bare_ice") is True:
                    catalog["ice_surfaces"].append({**pattern(sample["pixels"], "ice1", "stationary_single_layer_ice"), "neutral_background": True})
            elif role == "cell" and identity in ("coin", "snow", "iceflower", "honey", "egg"):
                if "patch" not in sample:
                    raise ValueError("obstacle sample is missing the real engine inset patch")
                mask = "".join("1" if (i % 16 - 7.5) ** 2 + (i // 16 - 7.5) ** 2 <= 49 else "0" for i in range(256))
                catalog["cells"].append(pattern(sample["patch"], "snow1" if identity == "snow" else identity,
                                                 truth["rule"], mask))
    catalog["id"] = "gallery-" + digest(catalog)[:20]
    if not any(catalog[key] for key in ("ordinary_envelopes", "cells", "goals", "large_objects", "ice_surfaces")):
        raise ValueError("no reviewed training exemplars were admitted")
    if len((canonical(catalog) + "\n").encode()) > max_bytes:
        raise ValueError("gallery byte budget exceeded")
    return catalog


def review_page(registry, out):
    page = sibling("match3_make_label_page")
    from PIL import Image
    cards = []
    for family in registry["families"]:
        truth = family.get("review", {})
        representatives = family["samples"][:4]
        montage = Image.new("RGB", (240, 240), "white")
        for i, sample in enumerate(representatives):
            image = page._envelope_image(sample["pixels"]).resize((120, 120), Image.Resampling.NEAREST)
            montage.paste(image, ((i % 2) * 120, (i // 2) * 120))
        cards.append({"family": family["family"], "count": len(family["samples"]),
                      "engine": family["role"] + (" · 聚类有歧义，请核对" if family.get("ambiguous") else ""),
                      "img": page._png_data_uri(montage), "draft": truth.get("identity", "unknown"),
                      "archetype": truth.get("archetype", "unknown"), "swappable": truth.get("swappable"),
                      "reviewed": truth.get("reviewed", False),
                      "members": [{"source": sample["source"], "img": page._png_data_uri(page._envelope_image(sample["pixels"]))}
                                  for sample in family["samples"]]})
    html = page.render(cards).replace("return {labels:labels,archetypes:arch,swappable:swap,reviewed:reviewed};",
        "return {labels:labels,archetypes:arch,swappable:swap,reviewed:reviewed,registry_sha256:'" + digest(registry) + "'};")
    Path(out).write_text(html, encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("harvest", "review", "apply-review", "split", "export-labels", "compile"))
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--predictions", type=Path)
    parser.add_argument("--events", type=Path, help="pulled diagnostics/current/events.jsonl")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--labels", type=Path)
    parser.add_argument("--splits", type=Path)
    parser.add_argument("--family")
    parser.add_argument("--members", help="comma-separated source keys for a mixed-family split")
    parser.add_argument("--partition", choices=("train", "dev", "test"), default="test")
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args(argv)
    registry = read(args.registry) if args.registry.exists() else {"families": []}
    if args.action == "harvest":
        manifest = read(args.manifest)
        if args.events:
            predictions, frames = event_predictions(args.events)
            manifest = {**manifest, "frames": [{"file": frame, "virtual": True} for frame in frames]}
        else:
            predictions = read(args.predictions)
        write(args.out, harvest(predictions, manifest, registry))
    elif args.action == "review":
        review_page(registry, args.out)
    elif args.action == "apply-review":
        write(args.out, apply_review(registry, read(args.labels)))
    elif args.action == "split":
        write(args.out, split_family(registry, args.family, set(args.members.split(","))))
    elif args.action == "export-labels":
        write(args.out, export_labels(registry, read(args.splits), args.partition))
    else:
        write(args.out, build_gallery(registry, read(args.splits)))
    print(f"{args.action}: {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
