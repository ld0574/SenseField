# GitHub 0.4.5 发布记录

2026-10-10 14:59:31（北京时间），按用户要求发布[听野 v0.4.5](https://github.com/ld0574/SenseField/releases/tag/v0.4.5)，普通 Release、Latest。发布 ID `408752408`，标签 `v0.4.5` 指向已推送主线 `859e672f9505e380d8af7b2ca216fbaf0477d7a6`，其中 README 下载与发行版链接已改为 GitHub。原有版本、消消乐独立标签和发行版均保留。

## 附件核对

直接使用已验证并在手机回读一致的 0.4.5／code22 APK，没有重新构建。正式 minified Release、非 Debug，Android 10 及以上、arm64，签名沿用证书 SHA-256 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`。

| 附件文件名 | GitHub 显示名 | bytes | SHA-256 |
| --- | --- | ---: | --- |
| `sensefieldv0.4.5.apk` | 听野v0.4.5 安卓安装包.apk | 24,364,276 | `00c3b31434d26d105eb0b96f6cad30506756f3d7d550fa752074a5ac2e86c4e4` |
| `installation-guide.txt` | 听野v0.4.5 使用说明.txt | 2,928 | `250712d23f914e8cdd1b63f2cb2b69551dd4669a859bf53515a8b45599d74e1b` |
| `SHA256SUMS.txt` | 听野v0.4.5 文件校验.txt | 176 | `7753f8d83acffbe8170417dd1450d21a6c82ce3e03ef1218b2d1844f69b8a900` |

先上传草稿并逐项确认 `state=uploaded`、中文显示名、大小与服务器 `digest`，然后公开并设为 Latest。公开后再次核对三个附件、Latest ID 与标签源码提交；APK 公开下载返回 HTTP 200、Content-Length 24,364,276。GitHub、已有 Gitee 附件与本地已测 APK 使用同一摘要。

正文复用 `UPDATE_SUMMARY.txt`，只有三行、55 字符。完整使用方法、权限、动态语音引擎与体验版边界放在 `.txt` 附件和[版本说明](../../docs/releases/0.4.5/RELEASE_NOTES.md)中；`SHA256SUMS.txt` 覆盖 APK 和使用说明，不包含自身。

## 本次检查范围

- 再次校验实际 APK 的版本、现有证书、v2 签名、非 Debug、压缩打包、六个运行库、16KB ELF 加载对齐、两份小地图模型及 856 个音频文件。
- 对即将标记的提交单独导出只读源码快照，公开仓库检查 1,582 个文件通过，README 与文档索引的本地链接通过；没有扫描或发布正在进行的陌生元素／独立准确率工作及其留出材料。
- 与已测 `0fefaef8e031d02e0a3533063e81404de29a011f` 比较，Android、native、profiles 与相关交付脚本没有变化。本次未重跑应用测试或安装；该 APK 原有 495 项 JVM、4 项 Python、73 项 Debug／48 项 minified Release 通过，lint 0 错误／33 警告，范围见[当批记录](../match3/MATCH3_TASK_FEEDBACK_0_4_5_2026-10-10.md)。
- 消消乐仍为体验版。最新真实试用仍不满意；目标识别、交换收益、完整动态语音、复杂机制与受控温升继续待验证或整改。普通 Release 不改变评分、`verified`／`release_ready` 或患者门禁。

## 保存与分发

三个附件在 `output/releases/0.4.5/github-upload/`。源码扫描、包校验、草稿及公开接口、附件摘要与下载核对保存在 `output/releases/0.4.5/validation/github-release-2026-10-10/`。

本次按用户的新指令发布 GitHub，README 下载链接改为 GitHub；应用内更新仍使用既有 Gitee APK 与固定网站清单。本次没有连接生产服务器、修改 Gitee 或上传公网清单；后续步骤见[CDN 发布说明](../../deploy/assistant/CDN发布.md)。
