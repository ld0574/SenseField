# -*- coding: utf-8 -*-
"""C4 候选（可选）：VLM 读结构化棋盘。
书内约定：环境变量有 OPENROUTER_API_KEY 才跑（测 3 帧，记模型名与花费）；
无 key → 跳过并记 BLOCKED.md。本文件提供探测与（有 key 时的）调用骨架。
"""
import os
import time
import json

MODEL = "qwen-vl-max"   # 有 key 时实测的模型名（记录到 results.json）
URL = "https://openrouter.ai/api/v1/chat/completions"
PROMPT = (
    "这是一张三消游戏棋盘截图。请按棋盘行列输出 JSON："
    '{"rows": [[每格类别: bear/bird/cat/chicken/fox/frog/horse/bomb/obstacle/empty], ...]}。'
    "只输出 JSON，不要解释。"
)

def available():
    return bool(os.environ.get("OPENROUTER_API_KEY"))

def run_frame(img_bgr, meta, timeout=60):
    """单帧调用。返回 (parsed_rows, elapsed_ms, usage)。失败抛异常。"""
    import cv2
    import urllib.request
    import base64
    ok, buf = cv2.imencode(".jpg", img_bgr, [cv2.IMWRITE_JPEG_QUALITY, 85])
    b64 = base64.b64encode(buf.tobytes()).decode()
    body = {
        "model": MODEL,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + b64}},
                {"type": "text", "text": PROMPT},
            ],
        }],
        "max_tokens": 2048,
    }
    req = urllib.request.Request(URL, data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"],
                                          "Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode())
    ms = (time.time() - t0) * 1000
    text = data["choices"][0]["message"]["content"]
    start = text.find("{")
    parsed = json.loads(text[start:text.rfind("}") + 1])
    return parsed["rows"], ms, data.get("usage", {})
