# GitHub 0.4.6 队友测试发布记录

2026-10-13 14:01:12（北京时间），按用户要求发布[听野 v0.4.6（队友测试）](https://github.com/ld0574/SenseField/releases/tag/v0.4.6)，`draft=false`、`prerelease=true`。发布 ID `411332765`，未设为 Latest；0.4.5 保留为稳定版 Latest。

同日整理交付时，GitHub 接口已返回 `prerelease=false`，Latest 为 `v0.4.6`，发布信息的 `updated_at` 为 14:18:20（北京时间）。本次只读取这一状态，附件摘要与初次发布相同；独立验收状态保持原记录。唯一最新交付及删除范围见[制品清理记录](ANDROID_ARTIFACT_CLEANUP_0_4_6_2026-10-13.md)。

## 主线与源码

按用户要求，将 `feature/jev-l2-judgment` 快进合入 `main` 并推送 `origin/main`，由 `d9d0213f56a2236fc1fa3f04b00165e980ead6a8` 前进到 `29cb75b8e1cc154c903570f7d8213e33279c6ba6`。合并没有冲突，合并后的 1,215 个构建输入与候选源码回执逐项一致。GitHub `v0.4.6` 标签实际指向该提交；发布后的文档提交继续进入 `main`，标签保留在 APK 对应源码上。

本次直接分发已验证的 0.4.6／code23 APK。包为混淆 Release、非 debuggable，Android 10 及以上、arm64-v8a，沿用原 Android debug 证书，证书 SHA-256 为 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`。供队友测试，签名范围保持原记录。

## 附件与公开下载

| 附件文件名 | GitHub 显示名 | bytes | SHA-256 |
| --- | --- | ---: | --- |
| `sensefieldv0.4.6.apk` | 听野v0.4.6 安卓安装包.apk | 24,586,457 | `535a653a927f57744922951f63ec9014041e04959823404bb1662a6d80b4f080` |
| `installation-guide.txt` | 听野v0.4.6 使用说明.txt | 2,196 | `e8467b35e100a922f0e6d63518f48c3be0cc58f78ba410becf4d717725e99e37` |
| `latest.json` | 听野v0.4.6 配套更新清单.json | 567 | `457c9277b1cb40cf262270ab3e8df2f628a74a6a14f2ad2d6847b9e51f9b907d` |
| `SHA256SUMS.txt` | 听野v0.4.6 文件校验.txt | 254 | `6bf199aac1342276a428af97e8f66897209ccd36971428d99a0f82aef3d65c7f` |

先创建草稿并上传四个附件，核对 `state=uploaded`、显示名、大小与服务器 `digest` 后公开。公开接口再次核对发布状态、附件摘要和标签源码提交；不携带认证从[APK 下载地址](https://github.com/ld0574/SenseField/releases/download/v0.4.6/sensefieldv0.4.6.apk)获取实际字节，返回 HTTP 200、24,586,457 bytes，SHA-256 与已测 APK 一致。GitHub Latest 接口仍返回 `v0.4.5`。

正文为三行、76 字符，说明典型关卡实测、消消乐修复及王者两字开关修复。完整用法和权限放在 `.txt` 附件及[版本说明](../../docs/releases/0.4.6/RELEASE_NOTES.md)中；`SHA256SUMS.txt` 覆盖其他三个附件，不包含自身。诊断日志、游戏画面和源码回执保留本地。

## 验证范围

- 609 项 Release JVM、Release 构建及 lint（0 错误／31 警告）已经通过。发布前再次核验实际 APK 的版本、签名、非 Debug、模型摘要、856 份语音资产及 16KB ELF 加载对齐。
- 合并后源码绑定与公开仓库扫描通过。此后只补充发布记录及索引，执行公开扫描、本地文档链接和差异检查，不重复构建同一份 APK。
- 当前手机主包仍为三局反馈修订 `ead5e38a…`。此前手机回放、软件成本和持续运行证据保持原制品绑定，本次未安装新主包。
- 独立留出准确率、新包实际发声、受控温升及完整最终门禁继续待验。前 100 关范围内典型关卡的跨版本实测不代表逐关验收全部通过；预发布不提升 `verified` 或 `release_ready`。

工程修订见[王者实局与两字开关更正](../protocols/COMPLIANCE_IMPROVEMENT_2026-10-13.md)与[消消乐三局反馈修复](../../docs/development/消消乐三局反馈修复_2026-10-13.md)，当前汇总见[验证状态](../STATUS.md)。

## 分发与保存

GitHub 四个公开附件已统一位于 `output/releases/0.4.6/`，原 `github-upload-20261013/` 重复目录已删除。草稿、发布接口、标签与主线接口、发布时的 Latest、包核验、资产摘要及公开下载回执保存在 `validation/github-release-2026-10-13/`；下载验证副本已删除，回执和摘要保留。当前普通 Release／Latest 的接口快照与清理结果在 `validation/cleanup-2026-10-13/`。

Gitee 使用相同的版本根目录 APK 和 `latest.json`，发布正文与上传说明在 `publishing/`；原 `honor-speech-fix-20261013/` 和 `cdn-upload/` 重复目录已删除。清单仍指向 `https://gitee.com/leda/SenseField/releases/download/0.4.6/sensefieldv0.4.6.apk`；GitHub 清单附件不更新应用读取的公网清单。本轮 Gitee 同版本附件及公网清单按用户选择由用户同步，步骤见[CDN 发布说明](../../deploy/assistant/CDN发布.md)。
