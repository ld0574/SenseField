# GitHub 0.4.7 体验版发布核对

发布时间：2026-10-15 09:08:56（Asia/Shanghai）。按用户要求公开 [v0.4.7](https://github.com/ld0574/SenseField/releases/tag/v0.4.7)，标记为 Pre-release。普通 Release、Latest 仍为 v0.4.6；README 下载按钮已改为本次 0.4.7 APK。

## 源码与安装包

源码修订已提交并推送 `main`，标签 `v0.4.7` 指向 `286ed34cfa8deb2df87a8fe39640a70bdc0d6e8f`。构建时未提交的 Android 源码摘要与标签提交中的内容一致；本次沿用已验证 APK，不因发布重新构建。源码绑定与构建回执保存在本地交付目录 `validation/current-package/`。

APK：0.4.7／code24，Android 10 及以上、arm64-v8a，24,592,205 bytes，混淆、不可调试。SHA-256 为 `617c4c6c40d67028f905d7a2cc06ae4df76594e631abd251a6404c433eedcb52`；沿用原公开包 Android debug 签名证书，可兼容覆盖安装同证书 0.4.6。测试手机覆盖安装后回读摘要一致，见[本轮工程记录](../assistant/ASSISTANT_DEMO_FEEDBACK_2026_10_15.md)。

## 附件与公开下载

六个附件均为 `uploaded`，GitHub `digest` 与本地逐项一致。未登录、未携带认证头的 APK 公开下载返回 HTTP 200，下载大小与 SHA-256 再次核对一致。

| 附件 | bytes | SHA-256 |
| --- | ---: | --- |
| `SHA256SUMS.txt` | 254 | `a487ce2287b4e75b9fc913a8616bc82829e872212863c53bc7e9254f07c3d89f` |
| `installation-guide.txt` | 2,494 | `c85fb3cb9aa442c7772a78814b9c2272d079095881e65c5d8887c20f83df874e` |
| `latest.json` | 645 | `6700c9754675a0ae706b6694a8019d652fb5bf988c8507e7b12bc8170119bf0a` |
| `sensefield-release-0.4.7-gitee.zip` | 24,268,887 | `6930a94c78d2529f9899a43bbe584edcedea5d6e68b4450e09b18e8c0c228e52` |
| `sensefield-release-0.4.7-gitee.zip.sha256` | 101 | `59e1778ff04abe0665ace5a7edb3c30e3ee2ced4ab9f645b199308f2ad0b2c79` |
| `sensefieldv0.4.7.apk` | 24,592,205 | `617c4c6c40d67028f905d7a2cc06ae4df76594e631abd251a6404c433eedcb52` |

交付 ZIP 与其中生成时回执保持原字节，包含使用说明、复测要点、Gitee 上传步骤和仅含中文提示词的服务端更新包；没有 Key、私有配置或用户录音、截图。

## 工程检查与剩余范围

- Android Debug 与 Release 各 633 项 JVM 单测、Release lint、APK 身份／签名／语音资产检查通过。
- 仓库 `tests/` 全量 Python：1085 passed、1 skipped、29 subtests passed。首次在根目录直接运行 pytest 额外收集了 `output/` 下第三方 Shapely 自带测试，出现 1 项失败；该原始日志保留，随后明确指定仓库测试目录完成复核。
- 暂存区公开文件与常见密钥扫描、API Key 模式扫描、`git diff --check` 通过；未跟踪的 `demo-video/` 保留本地，未纳入提交或附件。
- 完整发布门禁、最终包整体成本稳定性、受控温升、独立准确率、策略收益及完整实局录制仍待验。公开体验分发不改变模型 `verified`、`release_ready` 或历史评分。

GitHub 发布未更新 Gitee、固定网站 `latest.json` 或 Python 生产服务。Gitee 与公网清单由用户同步；配套事实优先提示词需部署者按交付包说明单独更新后复核。

机器可读附件元数据、公开下载结果和原始测试日志保存于本地 `output/validation/github-release-0.4.7-20261015/`。
