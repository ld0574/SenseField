# 听野 0.3.3 内部候选

2026-10-02，版本 `0.3.3 / versionCode 11`。本地 Debug 候选，未发布 GitHub Release。

## 本版变化

- 针对操控期间漏报，将双类近区 profile 的观测年龄预算由 250 ms 调整为 500 ms；真实采集时间保留，超过预算的观测仍被拒绝。
- 同步 Android 内置、导入与桌面 profile；模型、置信度、ROI、确认帧数和占用去重规则沿用此前配置。
- 增加同日志事件层回放，帮助检查年龄过滤造成的事件缺口；回放事件数不代表实际提醒数或准确率。

## 安装与复测

支持 Android 10（API 29）及以上、arm64-v8a，同签名旧候选可覆盖安装。开始前先试听声音与震动，完成对局后停止辅助并导出诊断 ZIP，记录双手操控时的漏报、迟报和发热体验。

`output/releases/0.3.3/` 没有单独的使用说明；本文的变化与复测信息依据[漏报排查记录](../../../validation/honor/TOUCH_ALERT_DIAG_2026-10-02.md)整理。后续队友反馈预警改善，日志仍显示明显升温，见[0.3.4 发热分析](../../../validation/performance/HEAT_LOAD_0.3.4_2026-10-02.md)。

500 ms 是单条观测的年龄上限，不能作为实际发声延迟或稳定性验收结论。

## 交付文件与来源

APK、ZIP 和校验文件保存在本地 `output/releases/0.3.3/`，不纳入文档目录。

| 项目 | 记录 |
| --- | --- |
| APK | `sensefield-0.3.3-arm64-v8a-debug-candidate.apk` |
| Android versionCode | `11` |
| APK 大小 | 19,430,612 bytes |
| APK SHA-256 | `8341c47a7344e4a49279179fc3e92484cc888026d9f055fd705fce2e101f9ae1` |


后续验证与证据边界见[操控期间漏报排查](../../../validation/honor/TOUCH_ALERT_DIAG_2026-10-02.md)。
