#!/usr/bin/env python3
"""Prepare one local candidate directory; never publish or change the public manifest."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import urljoin, urlsplit

if __package__:
    from .build_app_update_manifest import create_manifest, discover_aapt2, validate_apk_url, write_manifest
    from .verify_android_release import VERSION_NAME, verify
    from .verify_match3_regression import source_digest
else:
    from build_app_update_manifest import create_manifest, discover_aapt2, validate_apk_url, write_manifest
    from verify_android_release import VERSION_NAME, verify
    from verify_match3_regression import source_digest

ROOT = Path(__file__).resolve().parents[1]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, data: dict) -> None:
    write_manifest(data, path)


def validate_urls(index_url: str, apk_url: str) -> None:
    """清单与 APK 必须严格同源——与客户端 AppUpdateClient 的规则保持一致。

    历史上这里对 ``888413.xyz`` → ``gitee.com/leda/SenseField`` 开过例外，
    但客户端 AppUpdateClient.validateSameOriginApkUrl 没有这个例外，会以
    「更新 APK 下载地址无效。」拒绝该清单；而且 Gitee 附件下载会 302 到
    ``foruda.gitee.com``，重定向之后仍然跨源。例外因此只会产出必然失败的清单，
    已删除。工具放行的每一种组合，客户端都必须能接受。
    """
    validate_apk_url(index_url)
    validate_apk_url(apk_url)
    index, apk = urlsplit(index_url), urlsplit(apk_url)
    if index.query or index.fragment or apk.query or apk.fragment:
        raise ValueError("更新地址不能带查询参数或片段。")
    origin = lambda url: (url.scheme, url.hostname, url.port or 443)
    if origin(index) != origin(apk):
        raise ValueError(
            "APK 必须与版本清单严格同源，客户端不接受跨源下载地址"
            f"（清单 {origin(index)}，APK {origin(apk)}）。"
        )


def begin(output: Path) -> None:
    """Preserve an earlier delivery, then make all ready-to-upload files absent."""
    if output.is_symlink():
        raise ValueError("候选输出不能是符号链接。")
    history = None
    if output.exists():
        history_root = output.parent.parent / "candidate-history" / output.name
        history_root.mkdir(parents=True, exist_ok=True)
        history = Path(tempfile.mkdtemp(prefix=dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ-"), dir=history_root))
        history.rmdir()
        output.rename(history)
    state = {"state": "building", "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
             "previous_candidate_archive": str(history) if history else None}
    write_json(output / "validation/current-package/build-state.json", state)


def failed(output: Path, exit_code: int) -> None:
    state_path = output / "validation/current-package/build-state.json"
    state = json.loads(state_path.read_text()) if state_path.is_file() else {}
    state.update(state="build_failed", exit_code=exit_code,
                 completed_utc=dt.datetime.now(dt.timezone.utc).isoformat())
    write_json(state_path, state)


def prepare(apk: Path, output: Path, apk_url: str, docs: Path) -> dict:
    state_path = output / "validation/current-package/build-state.json"
    state = json.loads(state_path.read_text())
    if state.get("state") != "building":
        raise ValueError("必须先开始新的候选构建。")
    # Refuse unexpected additions rather than silently replace a user's files.
    if list(output.rglob("*.apk")) or any(p.is_file() and p != state_path for p in output.rglob("*")):
        raise ValueError("正在构建的候选目录包含额外文件。")
    with tempfile.TemporaryDirectory(prefix="." + output.name + "-handoff-", dir=output.parent) as temporary:
        staged = Path(temporary)
        target = staged / f"sensefieldv{VERSION_NAME}.apk"
        shutil.copyfile(apk, target)
        package = verify(target, False)
        manifest = create_manifest(target, apk_url, discover_aapt2(), docs / "UPDATE_SUMMARY.txt")
        if (manifest["apk_sha256"] != package["sha256"] or manifest["apk_bytes"] != package["bytes"]
                or manifest["version_code"] != package["version_code"]
                or manifest["version_name"] != package["version_name"]):
            raise ValueError("APK 在核验与清单生成之间变化。")
        write_json(staged / "latest.json", manifest)
        guide = (docs / "installation-guide.txt").read_text(encoding="utf-8")
        guide += (f"\n文件：{target.name}\nbytes：{package['bytes']}\nSHA-256：{package['sha256']}\n"
                  f"证书SHA-256：{package['certificate_sha256']}\n")
        (staged / "installation-guide.txt").write_text(guide, encoding="utf-8")
        publishing = staged / "publishing"
        publishing.mkdir()
        note_names = ["RELEASE_NOTES.md"]
        if (docs / "复测说明.md").is_file():
            note_names.append("复测说明.md")
        for name in note_names:
            base = f"https://github.com/ld0574/SenseField/blob/main/docs/releases/{VERSION_NAME}/{name}"
            body = (docs / name).read_text(encoding="utf-8")
            body = re.sub(r"\]\(([^\s()]+)\)", lambda m: "](" + urljoin(base, m[1]) + ")", body)
            (publishing / name).write_text(body, encoding="utf-8")
        (publishing / "上传说明.md").write_text(
            "这是本地候选，尚未公开发布。先核对 validation/current-package/ 的最终工程报告。\n"
            "只上传版本根目录的 APK、installation-guide.txt、latest.json 和 SHA256SUMS.txt。\n"
            "将 APK 部署到清单指定的同源地址并核对下载字节后，再更新公网 latest.json；生成本地清单不会改变公网版本。\n"
            "独立准确率、真机温升与实际发声按最终报告范围登记。\n", encoding="utf-8")
        receipt = staged / "validation/current-package"
        receipt.mkdir(parents=True)
        package["apk"] = target.name
        write_json(receipt / "release-package.json", package)
        tools = ["build_android_preview.sh", "prepare_android_candidate.py", "verify_android_release.py", "build_app_update_manifest.py"]
        source = {"source_sha256": source_digest(),
                  "base_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                  "working_diff_sha256": hashlib.sha256(subprocess.check_output(["git", "diff", "HEAD"], cwd=ROOT)).hexdigest(),
                  "build_tools_sha256": {name: sha(ROOT / "scripts" / name) for name in tools},
                  "docs_sha256": {p.name: sha(p) for p in sorted(docs.iterdir()) if p.is_file()}}
        write_json(receipt / "source-receipt.json", source)
        state.update(state="package_verified_engineering_pending", apk_sha256=package["sha256"],
                     completed_utc=dt.datetime.now(dt.timezone.utc).isoformat(), published=False)
        write_json(receipt / "build-state.json", state)
        previous = state.get("previous_candidate_archive")
        if previous:
            history = staged / "validation/artifact-history" / Path(previous).name
            history.mkdir(parents=True)
            prior = Path(previous) / "validation/current-package"
            if prior.is_dir():
                shutil.copytree(prior, history / "current-package")
        names = [target.name, "installation-guide.txt", "latest.json"]
        (staged / "SHA256SUMS.txt").write_text("".join(f"{sha(staged / name)}  {name}\n" for name in names), encoding="utf-8")
        # Every check and file write completed before the ready directory appears.
        state_path.unlink()
        for directory in [state_path.parent, state_path.parent.parent, output]:
            directory.rmdir()
        os.replace(staged, output)
    return {"apk": str(output / target.name), "sha256": package["sha256"], "bytes": package["bytes"],
            "manifest": str(output / "latest.json"), "state": state["state"]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check-urls", "begin", "failed", "prepare"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--apk-url")
    parser.add_argument("--index-url")
    parser.add_argument("--docs", type=Path)
    parser.add_argument("--exit-code", type=int)
    args = parser.parse_args()
    if args.action == "check-urls":
        validate_urls(args.index_url, args.apk_url)
    elif args.action == "begin":
        begin(args.output.absolute())
    elif args.action == "failed":
        failed(args.output.absolute(), args.exit_code)
    else:
        print(json.dumps(prepare(args.apk, args.output.absolute(), args.apk_url, args.docs), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
