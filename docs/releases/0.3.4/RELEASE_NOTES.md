# SenseField 0.3.4 内部降负载候选

2026-10-02 交付；以下保留交付时的说明与验证边界。

APK：`sensefield-0.3.4-arm64-v8a-debug-candidate.apk`。版本 0.3.4 / code 12，Android 10（API 29）及以上 arm64-v8a，沿用既有 Debug 签名，可在原候选上升级。

SHA-256：`53b5f66ea59caff0f5f51d739a50b6da158be43311d13f82c9d734c2b7a9ddec`；大小 19,429,121 bytes。

本轮处理完成后按耗时和热档留出间歇，定期诊断截图从 2 秒改为 10 秒。模型、500 ms 观测预算和提示规则保持 0.3.3 配置，暂不调整提示频率。

单元测试 142 项、构建、lint、签名和内置模型配置核验通过。尚无实体手机降温或提醒时效验证，不能宣称发热已解决。

先用原手机、相近起始温度和固定亮度/游戏画质/音量/充电条件试约 15 分钟，同时观察发热和双手操控预警是否漏报或迟报；明显烫手或不适时提前结束。停止辅助后，从「测试记录与反馈」导出 ZIP。保持诊断截图开关一致，新版本会自动采用 10 秒定期截图。

完整日志分析与复测步骤见 [0.3.4 发热记录](../../../validation/performance/HEAT_LOAD_0.3.4_2026-10-02.md)。本包为内部候选，未发布 GitHub Release。

## 交付文件与来源

APK、ZIP 和校验文件保存在本地 `output/releases/0.3.4/`，不纳入文档目录。

| 项目 | 记录 |
| --- | --- |
| APK | `sensefield-0.3.4-arm64-v8a-debug-candidate.apk` |
| Android versionCode | `12` |
| APK 大小 | 19,429,121 bytes |
| APK SHA-256 | `53b5f66ea59caff0f5f51d739a50b6da158be43311d13f82c9d734c2b7a9ddec` |

说明来源：`output/releases/0.3.4/README.md`。

后续验证与证据边界见[0.3.4 发热与复测记录](../../../validation/performance/HEAT_RETEST_0.3.4_2026-10-02.md)。
