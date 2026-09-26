"""Convert a YOLOX TorchScript model to ncnn and repair its Focus prefix.

pnnx currently lowers YOLOX's stride-two Focus slices to ncnn Crop layers even
though that slice form is unsupported.  This tool verifies the exact graph
prefix emitted by pnnx and replaces it with the ncnn ``YoloV5Focus`` custom
layer used by the runtime and parity checker.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


NCNN_MAGIC = "7767517"
FOCUS_POSITIONS = ((0, 0), (1, 0), (0, 1), (1, 1))


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _artifact(path: Path) -> dict[str, object]:
    return {
        "path": str(path),
        "size_bytes": path.stat().st_size,
        "sha256": _sha256(path),
    }


def _published_artifact(source: Path, destination: Path) -> dict[str, object]:
    value = _artifact(source)
    value["path"] = str(destination)
    return value


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


@dataclass(frozen=True)
class Layer:
    layer_type: str
    name: str
    bottoms: tuple[str, ...]
    tops: tuple[str, ...]
    parameters: tuple[str, ...]
    source: str


def _parse_layer(line: str, line_number: int) -> Layer:
    fields = line.split()
    if len(fields) < 4:
        raise ValueError(f"invalid ncnn layer on line {line_number}: {line!r}")
    try:
        bottom_count = int(fields[2])
        top_count = int(fields[3])
    except ValueError as error:
        raise ValueError(
            f"invalid ncnn input/output counts on line {line_number}: {line!r}"
        ) from error
    names_end = 4 + bottom_count + top_count
    if bottom_count < 0 or top_count < 0 or len(fields) < names_end:
        raise ValueError(f"truncated ncnn layer on line {line_number}: {line!r}")
    return Layer(
        layer_type=fields[0],
        name=fields[1],
        bottoms=tuple(fields[4 : 4 + bottom_count]),
        tops=tuple(fields[4 + bottom_count : names_end]),
        parameters=tuple(fields[names_end:]),
        source=line,
    )


def _parse_param(text: str) -> tuple[int, int, list[Layer]]:
    lines = [line.rstrip() for line in text.splitlines() if line.strip()]
    if len(lines) < 3 or lines[0].strip() != NCNN_MAGIC:
        raise ValueError("input is not an ncnn text param file (missing magic 7767517)")
    counts = lines[1].split()
    if len(counts) != 2:
        raise ValueError("invalid ncnn layer/blob count header")
    try:
        layer_count, blob_count = (int(value) for value in counts)
    except ValueError as error:
        raise ValueError("invalid ncnn layer/blob count header") from error
    layers = [_parse_layer(line, index) for index, line in enumerate(lines[2:], 3)]
    if layer_count != len(layers):
        raise ValueError(
            f"ncnn header declares {layer_count} layers but file contains {len(layers)}"
        )
    actual_blobs = {blob for layer in layers for blob in (*layer.bottoms, *layer.tops)}
    if blob_count != len(actual_blobs):
        raise ValueError(
            f"ncnn header declares {blob_count} blobs but graph contains {len(actual_blobs)}"
        )
    return layer_count, blob_count, layers


def _array_parameter(layer: Layer, key: str) -> tuple[int, ...]:
    prefix = f"{key}="
    matches = [item[len(prefix) :] for item in layer.parameters if item.startswith(prefix)]
    if len(matches) != 1:
        raise ValueError(f"{layer.name} must contain exactly one {key} array parameter")
    try:
        values = tuple(int(value) for value in matches[0].split(","))
    except ValueError as error:
        raise ValueError(f"{layer.name} has an invalid {key} array parameter") from error
    if not values or values[0] != len(values) - 1:
        raise ValueError(f"{layer.name} has an invalid {key} array length")
    return values[1:]


def _parameter(layer: Layer, key: str) -> str:
    prefix = f"{key}="
    matches = [item[len(prefix) :] for item in layer.parameters if item.startswith(prefix)]
    if len(matches) != 1:
        raise ValueError(f"{layer.name} must contain exactly one {key} parameter")
    return matches[0]


def _tuple_parameter(layer: Layer, key: str) -> tuple[int, ...]:
    value = _parameter(layer, key)
    if not value.startswith("(") or not value.endswith(")"):
        raise ValueError(f"{layer.name} has an invalid {key} tuple parameter")
    try:
        return tuple(int(item) for item in value[1:-1].split(","))
    except ValueError as error:
        raise ValueError(f"{layer.name} has an invalid {key} tuple parameter") from error


def _validate_pnnx_focus(text: str) -> dict[str, object]:
    """Verify the stride information that is lost during pnnx's ncnn lowering."""
    _, _, layers = _parse_param(text)
    expected_types = ["pnnx.Input"] + ["Tensor.slice"] * 4 + ["torch.cat"]
    actual_types = [layer.layer_type for layer in layers[: len(expected_types)]]
    if actual_types != expected_types:
        raise ValueError(
            "pnnx graph does not begin with the expected Input+4 slice+cat "
            f"Focus pattern: got {actual_types}"
        )
    input_layer = layers[0]
    slices = layers[1:5]
    concat = layers[5]
    if input_layer.bottoms or len(input_layer.tops) != 1:
        raise ValueError("the leading pnnx Input layer must have one output")
    input_blob = input_layer.tops[0]
    if any(item.bottoms != (input_blob,) or len(item.tops) != 1 for item in slices):
        raise ValueError("the four pnnx Focus slices must consume the model input")

    slices_by_position: dict[tuple[int, int], Layer] = {}
    for item in slices:
        if _tuple_parameter(item, "dims") != (2, 3):
            raise ValueError(f"{item.name} must slice the height and width dimensions")
        if _tuple_parameter(item, "ends") != (2147483647, 2147483647):
            raise ValueError(f"{item.name} must slice through both spatial dimensions")
        if _tuple_parameter(item, "selects") != (2147483647, 2147483647):
            raise ValueError(f"{item.name} has unexpected select indices")
        if _tuple_parameter(item, "steps") != (2, 2):
            raise ValueError(f"{item.name} is not a stride-two Focus slice")
        position = _tuple_parameter(item, "starts")
        if position not in {(0, 0), (0, 1), (1, 0), (1, 1)}:
            raise ValueError(f"{item.name} has unexpected Focus offsets {position}")
        if position in slices_by_position:
            raise ValueError(f"duplicate pnnx Focus offsets {position}")
        slices_by_position[position] = item
    expected_inputs = tuple(slices_by_position[position].tops[0]
                            for position in FOCUS_POSITIONS)
    if concat.bottoms != expected_inputs or len(concat.tops) != 1:
        raise ValueError(
            "pnnx Focus cat inputs are not ordered as top-left, bottom-left, "
            "top-right, bottom-right"
        )
    if _parameter(concat, "dim") != "1":
        raise ValueError("pnnx Focus cat must concatenate along NCHW channel dim 1")
    return {
        "matched_prefix": "pnnx.Input+4 Tensor.slice+torch.cat",
        "slice_dims": [2, 3],
        "slice_steps": [2, 2],
        "slice_starts": [list(position) for position in FOCUS_POSITIONS],
        "concat_dim": 1,
        "input_blob": input_blob,
        "output_blob": concat.tops[0],
    }


def _rewrite_focus(text: str) -> tuple[str, dict[str, object]]:
    original_layer_count, original_blob_count, layers = _parse_param(text)
    expected_types = ["Input", "Split", "Crop", "Crop", "Crop", "Crop", "Concat"]
    actual_types = [layer.layer_type for layer in layers[: len(expected_types)]]
    if actual_types != expected_types:
        raise ValueError(
            "pnnx graph does not begin with the expected "
            f"Input+Split+4 Crop+Concat Focus pattern: got {actual_types}"
        )

    input_layer, split, *tail = layers[:7]
    crops = tail[:4]
    concat = tail[4]
    if len(input_layer.bottoms) != 0 or len(input_layer.tops) != 1:
        raise ValueError("the leading Input layer must have one output")
    input_blob = input_layer.tops[0]
    if split.bottoms != (input_blob,) or len(split.tops) != 4:
        raise ValueError("the leading Split layer is not connected to the model input")
    if {crop.bottoms[0] for crop in crops if len(crop.bottoms) == 1} != set(split.tops):
        raise ValueError("the four Focus Crop layers do not consume all Split outputs")
    if any(len(crop.bottoms) != 1 or len(crop.tops) != 1 for crop in crops):
        raise ValueError("each Focus Crop layer must have one input and one output")

    crops_by_position: dict[tuple[int, int], Layer] = {}
    for crop in crops:
        starts = _array_parameter(crop, "-23309")
        ends = _array_parameter(crop, "-23310")
        axes = _array_parameter(crop, "-23311")
        if len(starts) != 2 or ends != (-233, -233) or axes != (1, 2):
            raise ValueError(f"{crop.name} is not a two-axis stride-two Focus crop")
        position = (starts[0], starts[1])
        if position not in {(0, 0), (0, 1), (1, 0), (1, 1)}:
            raise ValueError(f"{crop.name} has unexpected Focus offsets {position}")
        if position in crops_by_position:
            raise ValueError(f"duplicate Focus crop offsets {position}")
        crops_by_position[position] = crop
    expected_concat_inputs = tuple(crops_by_position[position].tops[0]
                                   for position in FOCUS_POSITIONS)
    if concat.bottoms != expected_concat_inputs or len(concat.tops) != 1:
        raise ValueError(
            "Focus Concat inputs are not ordered as top-left, bottom-left, "
            "top-right, bottom-right"
        )
    if concat.parameters != ("0=0",):
        raise ValueError("Focus Concat must concatenate along the channel axis")

    existing_names = {layer.name for layer in layers[7:]}
    focus_name = "focus" if "focus" not in existing_names else "focus_patched"
    focus_output = concat.tops[0]
    focus_line = (
        f"{'YoloV5Focus':<24} {focus_name:<24} 1 1 {input_blob} {focus_output}"
    )
    rewritten_layers = [input_layer.source, focus_line]
    rewritten_layers.extend(layer.source for layer in layers[7:])
    new_layer_count = len(rewritten_layers)
    parsed_rewritten = [
        _parse_layer(line, index) for index, line in enumerate(rewritten_layers, 3)
    ]
    new_blobs = {
        blob for layer in parsed_rewritten for blob in (*layer.bottoms, *layer.tops)
    }
    rewritten = "\n".join(
        [NCNN_MAGIC, f"{new_layer_count} {len(new_blobs)}", *rewritten_layers, ""]
    )
    # Parse our output again so a malformed count can never be published.
    _parse_param(rewritten)
    details = {
        "matched_prefix": "Input+Split+4 Crop+Concat",
        "replacement_layer": "YoloV5Focus",
        "focus_order": ["top_left", "bottom_left", "top_right", "bottom_right"],
        "original_layer_count": original_layer_count,
        "output_layer_count": new_layer_count,
        "layers_removed": original_layer_count - new_layer_count,
        "original_blob_count": original_blob_count,
        "output_blob_count": len(new_blobs),
        "intermediate_blobs_removed": original_blob_count - len(new_blobs),
        "input_blob": input_blob,
        "output_blob": focus_output,
    }
    return rewritten, details


def _find_pnnx(explicit: Path | None) -> Path:
    if explicit is not None:
        candidate = explicit.expanduser()
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate.resolve()
        raise FileNotFoundError(f"--pnnx is not an executable file: {candidate}")
    candidates = [Path(sys.executable).with_name("pnnx")]
    from_path = shutil.which("pnnx")
    if from_path:
        candidates.append(Path(from_path))
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate.resolve()
    raise FileNotFoundError(
        "pnnx executable was not found. Install it into the active environment "
        "(`uv pip install --python .venv/bin/python pnnx`) or pass --pnnx."
    )


def _pnnx_install(executable: Path) -> dict[str, object]:
    result: dict[str, object] = {"launcher": _artifact(executable)}
    active_launcher = Path(sys.executable).with_name("pnnx")
    if not active_launcher.is_file() or executable != active_launcher.resolve():
        result["package_version"] = None
        result["backend_binary"] = None
        return result
    try:
        result["package_version"] = importlib.metadata.version("pnnx")
    except importlib.metadata.PackageNotFoundError:
        result["package_version"] = None
    specification = importlib.util.find_spec("pnnx")
    backend = (
        Path(specification.origin).resolve().parent / "pnnx"
        if specification is not None and specification.origin is not None
        else None
    )
    result["backend_binary"] = (
        _artifact(backend) if backend is not None and backend.is_file() else None
    )
    return result


def _run_pnnx(
    executable: Path, torchscript: Path, work_dir: Path, input_size: int
) -> tuple[Path, Path, Path, Sequence[str]]:
    pnnx_param = work_dir / "model.pnnx.param"
    raw_param = work_dir / "model.raw.ncnn.param"
    raw_bin = work_dir / "model.raw.ncnn.bin"
    arguments = [
        str(executable),
        str(torchscript),
        f"inputshape=[1,3,{input_size},{input_size}]f32",
        "fp16=0",
        "optlevel=2",
        "device=cpu",
        f"pnnxparam={pnnx_param}",
        f"pnnxbin={work_dir / 'model.pnnx.bin'}",
        f"pnnxpy={work_dir / 'model_pnnx.py'}",
        f"pnnxonnx={work_dir / 'model.pnnx.onnx'}",
        f"ncnnparam={raw_param}",
        f"ncnnbin={raw_bin}",
        f"ncnnpy={work_dir / 'model_ncnn.py'}",
    ]
    result = subprocess.run(arguments, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(
            f"pnnx failed with exit code {result.returncode}"
            + (f":\n{detail}" if detail else "")
        )
    for generated in (pnnx_param, raw_param, raw_bin):
        if not generated.is_file() or generated.stat().st_size == 0:
            raise RuntimeError(f"pnnx succeeded but did not create {generated.name}")
    return pnnx_param, raw_param, raw_bin, arguments[2:6]


def _publish_files(items: Sequence[tuple[Path, Path]]) -> None:
    """Publish related files with rollback on ordinary I/O failures.

    A filesystem cannot atomically rename several independent files.  We stage
    every file beside its destination first, retain old files as backups, and
    restore the complete previous set if any replace fails.  Callers put the
    metadata marker last.
    """
    destinations = [destination for _, destination in items]
    if len(destinations) != len(set(destinations)):
        raise ValueError("publish destinations must be distinct")
    staged: list[tuple[Path, Path]] = []
    backups: dict[Path, Path] = {}
    published: list[Path] = []
    try:
        for source, destination in items:
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists() and not destination.is_file():
                raise RuntimeError(f"output destination is not a file: {destination}")
            descriptor, temporary_name = tempfile.mkstemp(
                dir=destination.parent, prefix=f".{destination.name}.stage-"
            )
            os.close(descriptor)
            temporary = Path(temporary_name)
            shutil.copyfile(source, temporary)
            staged.append((temporary, destination))
        for _, destination in staged:
            if destination.exists():
                descriptor, backup_name = tempfile.mkstemp(
                    dir=destination.parent, prefix=f".{destination.name}.backup-"
                )
                os.close(descriptor)
                backup = Path(backup_name)
                backup.unlink()
                os.replace(destination, backup)
                backups[destination] = backup
        for temporary, destination in staged:
            os.replace(temporary, destination)
            published.append(destination)
    except Exception:
        for destination in reversed(published):
            destination.unlink(missing_ok=True)
        for destination, backup in backups.items():
            if backup.exists():
                os.replace(backup, destination)
        raise
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)
        for backup in backups.values():
            backup.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--torchscript", type=Path, required=True)
    parser.add_argument("--output-param", type=Path, required=True)
    parser.add_argument("--output-bin", type=Path, required=True)
    parser.add_argument(
        "--metadata", type=Path,
        help="JSON output; defaults to <output-param>.conversion.json",
    )
    parser.add_argument("--input-size", type=int, default=320)
    parser.add_argument("--pnnx", type=Path)
    args = parser.parse_args()
    if args.input_size < 32 or args.input_size % 32:
        parser.error("--input-size must be at least 32 and divisible by 32")

    torchscript = args.torchscript.expanduser().resolve()
    output_param = args.output_param.expanduser().resolve()
    output_bin = args.output_bin.expanduser().resolve()
    metadata = (
        args.metadata.expanduser().resolve()
        if args.metadata is not None
        else output_param.with_name(f"{output_param.name}.conversion.json")
    )
    if not torchscript.is_file():
        parser.error(f"TorchScript model does not exist: {torchscript}")
    if (output_param == output_bin or metadata in {output_param, output_bin}
            or torchscript in {output_param, output_bin, metadata}):
        parser.error("TorchScript input and all three outputs must be distinct files")
    executable = _find_pnnx(args.pnnx)
    pnnx_install = _pnnx_install(executable)
    protected_pnnx_paths = {executable}
    backend = pnnx_install.get("backend_binary")
    if isinstance(backend, dict) and isinstance(backend.get("path"), str):
        protected_pnnx_paths.add(Path(backend["path"]).resolve())
    if protected_pnnx_paths & {output_param, output_bin, metadata}:
        parser.error("conversion outputs must not overwrite the pnnx installation")

    with tempfile.TemporaryDirectory(prefix="mapassist-pnnx-") as temporary:
        work_dir = Path(temporary)
        pnnx_param, raw_param, raw_bin, stable_options = _run_pnnx(
            executable, torchscript, work_dir, args.input_size
        )
        pnnx_param_hash = _sha256(pnnx_param)
        raw_param_hash = _sha256(raw_param)
        raw_bin_hash = _sha256(raw_bin)
        pnnx_focus_details = _validate_pnnx_focus(
            pnnx_param.read_text(encoding="utf-8")
        )
        rewritten, focus_details = _rewrite_focus(
            raw_param.read_text(encoding="utf-8")
        )
        focus_details["pnnx_source_validation"] = pnnx_focus_details
        staged_param = work_dir / "model.focus.ncnn.param"
        staged_param.write_text(rewritten, encoding="utf-8")
        report = {
            "schema_version": 1,
            "conversion": "yolox_torchscript_to_ncnn",
            "input_shape": [1, 3, args.input_size, args.input_size],
            "pnnx": {
                "install": pnnx_install,
                "options": list(stable_options),
                "fp16_weights": False,
            },
            "focus_rewrite": focus_details,
            "source": _artifact(torchscript),
            "raw_pnnx_outputs": {
                "pnnx_param_sha256": pnnx_param_hash,
                "ncnn_param_sha256": raw_param_hash,
                "ncnn_bin_sha256": raw_bin_hash,
            },
            "outputs": {
                "ncnn_param": _published_artifact(staged_param, output_param),
                "ncnn_bin": _published_artifact(raw_bin, output_bin),
            },
        }
        staged_metadata = work_dir / "ncnn-conversion.json"
        _write_json(staged_metadata, report)
        _publish_files((
            (staged_param, output_param),
            (raw_bin, output_bin),
            (staged_metadata, metadata),
        ))
    print(json.dumps({**report, "metadata": str(metadata)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (FileNotFoundError, RuntimeError, ValueError) as error:
        raise SystemExit(f"error: {error}") from error
