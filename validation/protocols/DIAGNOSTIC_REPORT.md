# 离线诊断 ZIP 报告

`mapassist.diagnostic_report` 读取 Android `DiagnosticRecorder` 导出的 ZIP，在本机生成 `diagnostic-report.json` 与 `diagnostic-report.html`。HTML 不依赖网络或外部脚本，引用到的 JPEG 以内嵌数据 URI 提供，复制单个 HTML 文件即可离线查看；点击图片可保存对应原始 JPEG。

报告包含原始游戏截图和原样保留的 Cue ID，画面可能含昵称等信息，因此不是自动匿名材料。分享前仍需核对内容与用途授权，原附件和生成报告应由负责人在本地保管。

```sh
PYTHONPATH=python .venv/bin/python -m mapassist.diagnostic_report \
  /path/to/sensefield-diag-session.zip \
  --output-dir /path/to/diagnostic-report
```

如有来自外部连续录音的人工时间标注，可以附上 `LATENCY.example.csv` 格式的 CSV：

```sh
PYTHONPATH=python .venv/bin/python -m mapassist.diagnostic_report \
  /path/to/sensefield-diag-session.zip \
  --output-dir /path/to/diagnostic-report \
  --latency-csv /path/to/latency.csv
```

CSV 的 Cue ID 必须存在于该 ZIP 的 `CueDispatch` 记录中，Cue 类型必须与派发类型匹配；未知、重复或类型不符的 ID 会报错。未出现在 CSV 中的派发 ID 会保留为覆盖缺口，空 `audio_ms` 仍表示没有人工记录到听见时间。CSV 的 `source_note` 不会写入报告。

报告默认只复制诊断需要的应用版本、profile事件的version字符串、Android API 级别、温度 / 热状态、负载与帧时间、上下文画面复制总量 / 均值 / 最大耗时、限频数与图像队列峰值、cue 派发和回调、图像上下文，以及 ZIP 原始 SHA-256。缺失的热优化计数保留为 `null`，不会补成零。不会单独输出设备型号、昵称、制造商、session UUID 字段或 profile 名称。Cue ID 为了和人工 CSV 配对会原样保留，因此其中可能含有 session 前缀。HTML 对动态文字做转义；归档成员名先检查绝对路径、反斜杠、路径越界、重复成员和符号链接。报告直接读取允许的归档成员，不调用 `extractall`。

`requested`、`dispatch` 和应用层播放回调都不等于实际听见或感到震动。只有独立录音与人工标注的 `evidence_ms` / `audio_ms` 可用于报告外部可感知延迟。诊断 ZIP 不包含完整的 Android `CueEvent` logcat 字段、玩家实际听觉记录或连续录像时，报告会保留该证据缺口；状态固定为“未评估”，不会生成验收通过结论。

近区事件先按审计时间与唯一派发匹配，再以派发创建时间和上下文请求的完成时间关联触发帧；没有精确依据时保留缺口。合并窗口（COALESCED）的每条提醒仍使用自身触发帧，后续截图从该帧开始筛选；限流窗口不会借用其他事件的前后图片。

当前实现复用 `diagnostic_replay.py` 的归档帧校验和年龄 / native 处理时间分位数，以及 `android_session_log.py` 的通道与回调结果枚举。帧年龄回放不可用时，工具会输出其它可读证据并注明缺口。

本地测试命令：

```sh
PYTHONPATH=python .venv/bin/python -m pytest tests/test_diagnostic_report.py tests/test_android_session_log.py
```
