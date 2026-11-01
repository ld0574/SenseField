#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""「只读不写」对外声明的外部审计器（纯只读，零第三方依赖）。

任何人 clone 本仓库后运行 `python scripts/audit_readonly_claim.py` 即可独立复核
应用对玩家作出的四条可验证承诺，不需要设备、不需要构建、不需要网络。

退出码：0 = 全部通过；1 = 有检查未通过；2 = 审计器自身无法完成（仓库结构对不上）。
每一行输出都以仓库内相对路径报告，不打印任何绝对路径。
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android" / "app" / "src" / "main"
SRC = MAIN / "java"
PKG = SRC / "com" / "openkhub" / "sensefield"

failures = []
notes = []


def report(name, ok, detail):
    print("%s  %s" % ("PASS" if ok else "FAIL", name))
    for line in detail if isinstance(detail, (list, tuple)) else [detail]:
        print("        " + line)
    if not ok:
        failures.append(name)


def rel(p):
    try:
        return p.relative_to(ROOT).as_posix()
    except ValueError:
        return str(p)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace").splitlines()


def java_files(base):
    return sorted(base.rglob("*.java"))


# 1) 清单里没有输入注入类权限
INJECT_PERMS = {"android.permission.INJECT_EVENTS"}
manifest = MAIN / "AndroidManifest.xml"
if not manifest.is_file():
    print("仓库结构对不上，找不到清单文件：%s" % rel(manifest))
    sys.exit(2)
perms = set(re.findall(r'android\.permission\.[A-Z_]+',
                       "\n".join(read(manifest))))
decl_lines = [l for l in read(manifest) if "uses-permission" in l]
bad_perms = sorted(perms & INJECT_PERMS)
report("清单不含输入注入类权限", not bad_perms,
       ["`uses-permission` 行 %d 条；全文出现的权限字样 %d 种（两个分母不同，别混）"
        % (len(decl_lines), len(perms))]
       + (["违规权限：" + ", ".join(bad_perms)] if bad_perms else []))

# 2) 无障碍服务声明没要求手势能力
cfg_dir = MAIN / "res" / "xml"
cfgs = sorted(cfg_dir.glob("*.xml")) if cfg_dir.is_dir() else []
gestures = [p for p in cfgs if "canPerformGestures" in "\n".join(read(p))]
report("无障碍服务未声明手势能力（canPerformGestures）", not gestures,
       ["扫描面：%s 共 %d 个 xml" % (rel(cfg_dir), len(cfgs))]
       + [rel(p) for p in gestures])

# 3) 注入类 API 只许出现在守卫自身的声明区
GUARD_TOKENS = ["dispatchGesture", "GestureDescription", "injectInputEvent",
                "performGlobalAction", "GLOBAL_ACTION_", "enqueueInputEvent",
                "MotionEvent.obtain"]
offenders = []
for f in java_files(SRC):
    if f.name.startswith("ReadOnlyGuard"):
        continue
    for i, line in enumerate(read(f), 1):
        if any(t in line for t in GUARD_TOKENS):
            offenders.append("%s:%d" % (rel(f), i))
report("注入类 API 不出现在守卫之外的任何文件", not offenders,
       ["扫描面：%s 递归 .java" % rel(SRC), "命中 %d 处" % len(offenders)]
       + offenders[:12])

# 4) 对节点施加动作的调用，只许用「只读焦点归还」这一个实参
ACTION_CALL = re.compile(r"performAccessibilityAction\s*\(")
FOCUS_OK = re.compile(r"ACTION_ACCESSIBILITY_FOCUS")
FORBIDDEN_ARG = re.compile(r"\bACTION_(CLICK|LONG_CLICK|SET_TEXT|PASTE)\b")
bad_calls = []
call_sites = 0
for f in java_files(SRC):
    lines = read(f)
    for i, line in enumerate(lines):
        if not ACTION_CALL.search(line):
            continue
        call_sites += 1
        window = "\n".join(lines[i:i + 9])
        if FORBIDDEN_ARG.search(window) or not FOCUS_OK.search(window):
            bad_calls.append("%s:%d" % (rel(f), i + 1))
report("动作类调用仅用于只读焦点归还", not bad_calls,
       ["performAccessibilityAction 调用点共 %d 处" % call_sites,
        "不合用处 %d 处" % len(bad_calls)] + bad_calls[:12])

# 5) 高亮窗对触摸不可达
overlay = PKG / "Match3HintOverlay.java"
has_flag = overlay.is_file() and any(
    "FLAG_NOT_TOUCHABLE" in l for l in read(overlay))
report("高亮窗带 FLAG_NOT_TOUCHABLE（点不到东西）", has_flag,
       rel(overlay) if has_flag else "找不到该 flag：%s" % rel(overlay))

# 6) 报警器自己接着电：守卫有黑名单、且有测试引用它
guard = PKG / "ReadOnlyGuard.java"
guard_txt = "\n".join(read(guard)) if guard.is_file() else ""
listed = all(t in guard_txt for t in ["performAccessibilityAction", "ACTION_CLICK"])
tests = [f for f in java_files(ROOT / "android" / "app" / "src" / "test")
         if "ReadOnlyGuard" in f.name]
wired = bool(tests) and any(
    re.search(r"@Test", l) for f in tests for l in read(f))
report("守卫同时点名动作类 API 且被测试引用（报警器有电）",
       listed and wired,
       ["黑名单含 performAccessibilityAction 与 ACTION_CLICK：%s" % ("是" if listed else "否"),
        "引用它的测试类 %d 个、含 @Test：%s" % (len(tests), "是" if wired else "否")])

# 7) 读屏接入程度只报数，不夸大成「全部支持」
# 7b) 放行名单本身不许被加宽：只许那一个只读动作
allow = re.search(r"READ_ONLY_ACCESSIBILITY_ACTIONS\s*=\s*([^;]+);", guard_txt, re.S)
allow_body = allow.group(1) if allow else ""
allowed_tokens = re.findall(r'"(ACTION_[A-Z_]+)"', allow_body)
widened = [t for t in allowed_tokens if t != "ACTION_ACCESSIBILITY_FOCUS"]
report("只读放行名单未被加宽（只许焦点归还这一个动作）",
       bool(allow) and not widened and allowed_tokens == ["ACTION_ACCESSIBILITY_FOCUS"],
       ["名单内容：%s" % (", ".join(allowed_tokens) if allowed_tokens else "（没解析到）"),
        "多出项：%s" % (", ".join(widened) if widened else "无")])

# 7) 读屏接入程度只报数，不夸大成「全部支持」
live = sum(1 for f in java_files(SRC)
           for l in read(f) if "setAccessibilityLiveRegion" in l)
node_provider = [f for f in java_files(SRC) if "AccessibilityNodeProvider" in "\n".join(read(f))]
notes.append("界面 live region 调用 %d 处；游戏画布的节点树提供器 %d 个文件"
             % (live, len(node_provider)))
notes.append("对外只能写「界面已接入读屏；盘面对象树尚未提供」——两头都不许多说。")

print()
for n in notes:
    print("NOTE  " + n)
print()
if failures:
    print("结论：%d 项未通过 → %s" % (len(failures), ", ".join(failures)))
    sys.exit(1)
print("结论：全部检查通过。这只证明代码与声明层面成立，"
      "不含任何「玩家实际听到／摸到」的效果判断。")
sys.exit(0)
