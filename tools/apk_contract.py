#!/usr/bin/env python3
"""Bind a private R8 map to actual DEX definitions, never to inline metadata alone."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

PREFIX = "com.openkhub.sensefield."
CLASSES = (
    "BoardGeometry", "Match3Sampler", "Match3HudReader", "Match3Position",
    "Match3Position$Cell", "Match3Goals", "Match3Goals$Target", "Match3MoveRanker",
    "Match3RuleDiagnostics", "Match3Action", "GameProfile", "GameProfile$TemplateData",
    "GameProfile$PlayerLifeData", "GameProfile$RelationData", "NativeBridge",
    "DiagnosticSnapshot", "NativeFrameResult", "NativeFrameResult$Relation",
)
PRIMITIVES = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
              "int": "I", "long": "J", "float": "F", "double": "D"}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def descriptor(name: str, classes: dict[str, str]) -> str:
    if name.endswith("[]"):
        return "[" + descriptor(name[:-2], classes)
    return PRIMITIVES.get(name, "L" + classes.get(name, name).replace(".", "/") + ";")


def uleb(data: bytes, at: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 35, 7):
        byte = data[at]
        at += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, at
    raise ValueError("Invalid DEX ULEB128")


def dex_definitions(data: bytes) -> dict:
    if not data.startswith(b"dex\n"):
        raise ValueError("Require a DEX file")
    def words(at, count):
        return struct.unpack_from("<" + "I" * count, data, at)
    ns, os, nt, ot, np, op, nf, of, nm, om, nc, oc = words(56, 12)
    strings = []
    for at in words(os, ns):
        _, start = uleb(data, at)
        end = data.index(0, start)
        # DEX uses modified UTF-8 (encoded NUL and UTF-16 surrogates).
        strings.append(data[start:end].replace(b"\xc0\x80", b"\0").decode("utf-8", errors="surrogatepass"))
    types = [strings[i] for i in words(ot, nt)]
    protos = []
    for i in range(np):
        _, result, params = words(op + 12 * i, 3)
        args = [] if not params else [types[x] for x in struct.unpack_from(
            "<" + "H" * words(params, 1)[0], data, params + 4)]
        protos.append("(" + "".join(args) + ")" + types[result])
    fields = []
    for i in range(nf):
        owner, typ, name = struct.unpack_from("<HHI", data, of + 8 * i)
        fields.append((types[owner], strings[name], types[typ]))
    methods = []
    for i in range(nm):
        owner, proto, name = struct.unpack_from("<HHI", data, om + 8 * i)
        methods.append((types[owner], strings[name], protos[proto]))
    declared_fields, declared_methods = set(), set()
    for i in range(nc):
        class_info = words(oc + 32 * i, 8)
        at = class_info[6]
        if not at:
            continue
        counts = []
        for _ in range(4):
            n, at = uleb(data, at)
            counts.append(n)
        for n in counts[:2]:
            index = 0
            for _ in range(n):
                delta, at = uleb(data, at)
                _, at = uleb(data, at)
                index += delta
                declared_fields.add(fields[index])
        for n in counts[2:]:
            index = 0
            for _ in range(n):
                delta, at = uleb(data, at)
                _, at = uleb(data, at)
                _, at = uleb(data, at)
                index += delta
                declared_methods.add(methods[index])
    return {"fields": declared_fields, "methods": declared_methods}


def parse_map(text: str) -> tuple[dict, dict]:
    classes, members = {}, {}
    owner = None
    for line in text.splitlines():
        if not line or line.lstrip().startswith("#"):
            continue
        if not line[0].isspace():
            match = re.fullmatch(r"(.+) -> (.+):", line)
            if not match:
                raise ValueError("Invalid R8 class mapping")
            owner, runtime = match.groups()
            classes[owner] = runtime
            members.setdefault(owner, [])
        else:
            original, runtime = line.strip().split(" -> ")
            original = re.sub(r"^\d+:\d+:", "", original)
            original = re.sub(r":\d+(?::\d+)?$", "", original)
            members[owner].append((original, runtime))
    return classes, members


def descriptor_types(desc: str, inverse: dict) -> list[str]:
    out = []
    primitive_names = {v: k for k, v in PRIMITIVES.items()}
    i = 0
    while i < len(desc):
        rank = 0
        while desc[i] == "[":
            rank += 1
            i += 1
        if desc[i] == "L":
            end = desc.index(";", i)
            name = desc[i+1:end].replace("/", ".")
            name = inverse.get(name, name)
            i = end + 1
        else:
            name = primitive_names[desc[i]]
            i += 1
        out.append(name + "[]" * rank)
    return out


def bind_members(classes: dict, members: dict, definitions: dict, selected=CLASSES) -> dict:
    out = {}
    # R8 omits unchanged kept members from mapping.txt. DEX still proves them.
    counts = Counter(classes.values())
    inverse = {runtime: source for source, runtime in classes.items() if counts[runtime] == 1}
    for short in selected:
        owner = PREFIX + short
        if owner not in classes:
            continue
        runtime = classes[owner]
        runtime_owner = "L" + runtime.replace(".", "/") + ";"
        result = {"name": runtime, "fields": {}, "methods": {}}
        for original, alias in members[owner]:
            if "(" in original:
                match = re.fullmatch(r"(\S+) (\S+)\(([^)]*)\)", original)
                if not match:
                    continue
                returns, name, args = match.groups()
                if "." in name:  # Inlined code belonging to a different source class.
                    continue
                params = args.split(",") if args else []
                desc = "(" + "".join(descriptor(p, classes) for p in params) + ")" + descriptor(returns, classes)
                if (runtime_owner, alias, desc) not in definitions["methods"]:
                    continue
                key = name + "(" + args + ")"
                value = {"name": alias, "parameters": descriptor_types(desc[1:desc.index(")")], {}), "descriptor": desc}
                if key in result["methods"] and result["methods"][key] != value:
                    raise ValueError("Ambiguous retained method: " + owner + "." + key)
                result["methods"][key] = value
            else:
                typ, name = original.split(" ", 1)
                if "." in name:
                    continue
                desc = descriptor(typ, classes)
                if (runtime_owner, alias, desc) in definitions["fields"]:
                    result["fields"][name] = {"name": alias, "descriptor": desc}
        mapped_fields = {alias for original, alias in members[owner] if "(" not in original}
        mapped_methods = {alias for original, alias in members[owner] if "(" in original}
        for decl_owner, name, desc in definitions["fields"]:
            if decl_owner == runtime_owner and name not in mapped_fields:
                result["fields"][name] = {"name": name, "descriptor": desc}
        for decl_owner, name, desc in definitions["methods"]:
            if decl_owner == runtime_owner and name not in mapped_methods:
                params_desc = desc[1:desc.index(")")]
                params = descriptor_types(params_desc, inverse)
                key = name + "(" + ",".join(params) + ")"
                result["methods"][key] = {"name": name, "parameters": descriptor_types(params_desc, {}), "descriptor": desc}
        out[short] = result
    return out


def contract(apk: Path, mapping: Path) -> dict:
    apk_bytes, map_bytes = apk.read_bytes(), mapping.read_bytes()
    text = map_bytes.decode("utf-8")
    map_id = re.search(r'^# pg_map_id: (\S+)$', text, re.M)
    if not map_id:
        raise ValueError("Missing R8 map ID")
    definitions = {"fields": set(), "methods": set()}
    ids = set()
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            data = z.read(name)
            for marker in re.findall(rb'~~R8(\{[^\x00]*?\})', data):
                ids.add(json.loads(marker)["pg-map-id"])
            d = dex_definitions(data)
            for key in definitions:
                definitions[key].update(d[key])
    if ids != {map_id[1]}:
        raise ValueError("Mapping fingerprint differs from actual APK")
    classes, members = parse_map(text)
    return {"schema": "sensefield.original-apk-contract/1", "apk_sha256": sha(apk_bytes),
            "mapping_sha256": sha(map_bytes), "r8_map_id": map_id[1],
            "classes": bind_members(classes, members, definitions)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    a = parser.parse_args()
    a.output.parent.mkdir(parents=True, exist_ok=True)
    with a.output.open("x") as f:
        json.dump(contract(a.apk, a.mapping), f, indent=2, sort_keys=True)
        f.write("\n")
