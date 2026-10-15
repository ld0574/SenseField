# 离线诊断对照工具

`mapassist.diagnostic_compare` 把两个 `mapassist.diagnostic_report` v1 JSON 报告并列成离线 JSON 和 HTML，适用于同机基线与候选版本（如0.3.5/0.3.7）的受控发热复测。输入可为报告 JSON，也可为原始 DiagnosticRecorder ZIP；ZIP 会复用 `analyze_diagnostic_zip`。HTML 与 JSON 只含聚合指标、哈希、版本、时长、手工条件和缺口，不带截图、逐条 cue 或 cue ID。

```sh
PYTHONPATH=python python -m mapassist.diagnostic_compare \
  /path/to/before-diagnostic-report.json \
  /path/to/after-diagnostic-report.json \
  --conditions-json /path/to/compare-conditions.json \
  --output-dir /path/to/diagnostic-compare
```

也可以把一个或两个报告 JSON 参数换成诊断 ZIP。`--conditions-json` 可省略；省略时输出会明确记录同机和条件未知。每个输入保留输入文件 SHA-256 和原诊断 ZIP SHA-256。ZIP 输入的两个哈希相同；报告 JSON 输入分别记录 JSON 文件哈希和其 `source.archive_sha256`。

条件文件需要显式标明 schema，并为前后两局填写同一组字段。设备代号应是测试者自定的匿名短代号，不要写设备型号、昵称或 UUID。

```json
{
  "schema": "mapassist.diagnostic_compare.conditions",
  "schema_version": 1,
  "before": {
    "device_code": "lab-device-a",
    "charging": "unplugged",
    "quality": "high",
    "audio": "speaker 60%",
    "starting_conditions": "cool idle"
  },
  "after": {
    "device_code": "lab-device-a",
    "charging": "unplugged",
    "quality": "high",
    "audio": "speaker 60%",
    "starting_conditions": "cool idle"
  }
}
```

工具会报告 frame 数和横屏处理帧数、native 处理时长、frame age、completion gap、诊断画面复制耗时、图像与 cue 队列计数、图窗引用及其可用图片覆盖、温度分布及系统热状态样本，并计算后测减前测。图窗覆盖只计入原报告 `images` 中存在且 `available=true` 的图片引用；缺失或未嵌入的图片不会被算作覆盖。工具不输出原图片 ID。

手工条件有任何差异时，双方证据缺口都会写明“条件差异，差值不构成同条件对照”。如果原诊断 ZIP 哈希相同，JSON 会标记 `same_source=true`，HTML 会醒目标明这是“重复输入/管线演示，不构成独立前后对照”；数值差值仍会显示。人工条件与同机代号不构成自动证明。

时长少于 15 分钟、上游报告标记中断、没有观察到结束、处理帧或温度样本缺失、统计字段缺失、图像引用不可用，都会保留证据缺口。原诊断报告的缺口只汇总为数量并提示在本地查看原报告，不复制其自由文本。即使记录总时长达到 15 分钟，它也不证明连续有效 15 分钟采样；工具不估算 FPS，也不生成温控或验收结论。

输入报告必须符合 `mapassist.diagnostic_report` schema v1，版本字段须是整数 `1`（布尔值不接受），所有 JSON 数值都须为有限数值。毫秒与温度分布分别要求 `milliseconds` 和 `degrees_celsius`；零样本分布的统计值必须全为 null。比较器只读取指定的聚合字段和会话完整性状态，不复制设备身份、session UUID或cue明细。手工条件自由文字会在 HTML 中转义；条件 schema 不接受 UUID 字符串。人工填写的内容仍需负责人核对和保管。报告与输出都由本机处理，不访问网络。

模块命令可直接运行；安装或更新本项目Python包后，也可使用 `mapassist-diagnostic-compare` CLI。对照工具不依赖APK升级。运行定向测试：

```sh
PYTHONPATH=python python -m pytest tests/test_diagnostic_compare.py
```
