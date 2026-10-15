# 0.4.7 移除问法白名单：同版本分发核对

2026-10-15 10:26:59（Asia/Shanghai），按用户要求保留 0.4.7／code24，更新现有 [GitHub v0.4.7](https://github.com/ld0574/SenseField/releases/tag/v0.4.7)。保留发布前已有的普通 Release、Latest 状态，以及 APK、installation-guide.txt 两个附件；没有恢复旧 Pre-release 状态或额外加入本地 ZIP、清单附件。Gitee 与公网清单由用户手动同步，本次未操作它们。

## 修订与工程范围

移除语音问法白名单，完整非空识别文字直接作为对话消息，原话交给游戏搭子理解。暂停、复述等本地指令与授权、输入安全、时效、限流保留；新消息替换等待中的上一轮，空结果不会打断。此前温控主动问答修复继续保留，本次无需再次更新 Python 服务。见[工程说明](../assistant/ASSISTANT_OPEN_CONVERSATION_0_4_7_2026-10-15.md)。

源码 `f2da698196f1d787169906b7b49c6788c42dfdff` 已推送 main，标签 v0.4.7 的 peeled commit 相同；仅同版本标签按用户要求更新，主分支历史没有改写。构建源码摘要与发布前一致，旧标签对象与源码回执留在本地。

Debug／Release JVM 各 630 项、Release lint（0 错误／31 警告）、模拟器 37 项助手控制器检查通过。撤销 6 项过时的问法过滤单测，并用实际控制器回调验证完整消息、空结果和生命周期；不把前一版 636／33 的计数迁移到本包。受控回调不证明真实语音准确率、策略正确性、外部实际发声或受控温升。

APK：24,591,309 bytes，SHA-256 `5f65b2bb849c772f03739f01943ce6ad8545bf7c3bf7c89fb0740f561bcaf78a`，混淆且不可调试；身份、现有证书、856 份语音资产与 native 对齐通过。辅助停止时同签名覆盖安装手机，没有卸载或清数据，安装后 base.apk 摘要一致。同 code 用户需手动下载安装，应用内版本检查不会提示本次更新。

## 文件

下表列出本地交付；GitHub 只替换前两个文件。用户随后明确不需要 Gitee ZIP，已删除本轮生成的归档与配套校验，不再生成；直接提供 APK、说明、清单及校验文件。没有上传用户画面、语音、诊断、Key 或私有配置。

| 文件 | bytes | SHA-256 |
| --- | ---: | --- |
| `sensefieldv0.4.7.apk` | 24,591,309 | `5f65b2bb849c772f03739f01943ce6ad8545bf7c3bf7c89fb0740f561bcaf78a` |
| `installation-guide.txt` | 3,346 | `8cb1c8f2320f286f9029b01b935f42675ea758f5d69172b8d6e60d6d8de3d77a` |
| `latest.json` | 466 | `272e944041965374a2bd0354299d6176b8b2b8edd22b01e2e9893ca60c51363c` |
| `SHA256SUMS.txt` | 254 | `1871142be2bd7707f46d1b9b8426309071c213f8f6cd1c53ea4af20a341236c0` |

GitHub 两个附件的 digest 与 bytes 均和上表一致。上传前后均回读发布状态与 Latest，标签按 force-with-lease 更新以避免覆盖并发改动；发布正文使用本次三行简要说明。完整本地材料仍在 output/releases/0.4.7/，公网清单由用户同步，生成本地 latest.json 不会自动更新网站。

历史 [高温包](GITHUB_RELEASE_0_4_7_THERMAL_2026-10-15.md)与[首次发布](GITHUB_RELEASE_0_4_7_2026-10-15.md)保持当时摘要；中间问法补丁 `530819d8…` 也保留各自回执。当前版本的独立准确率、实际收益、受控温升和整体成本仍待完整验收，公开分发不改变评分、verified 或 release_ready。

机器回执在本地 output/validation/assistant-open-conversation-0.4.7-20261015/。用户 demo-video/、私有素材和其他版本未改动。

匿名下载两个正式附件均为 HTTP 200，大小与 SHA-256 和交付包一致，未使用缓存绕过参数。公开下载回执与 GitHub digest 回执分别保存。

本次删除两个被替代修订的独立 APK、Gitee ZIP 和 ZIP 校验文件，共 6 个旧二进制／配套文件、释放 97,725,178 bytes（约 93.20 MiB）。当前唯一独立 0.4.7 APK 保持原摘要，历史源码、摘要、诊断与失败证据保留。

最终核对发现本地 latest.json 的发布摘要随后被同步改动，APK 版本、大小和摘要未变；保留该摘要并对齐本地校验文件；此前归档按用户随后“不需要 Gitee ZIP”的要求删除。GitHub 的 APK 和使用说明保持上表字节，未再次构建或替换手机包；前后清单与归档摘要留在 manifest-summary-alignment.json 回执。
