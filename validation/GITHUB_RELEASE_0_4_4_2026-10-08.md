# GitHub 0.4.4 发布记录

2026-10-08 22:52:37（北京时间），按用户要求发布[听野 v0.4.4](https://github.com/ld0574/SenseField/releases/tag/v0.4.4)，普通 Release、Latest。发布 ID `406938636`，Git tag `v0.4.4` 指向已推送主线 `0bcff04a9b952ec8dd92dc0a9401329ac257b3bc`；消消乐最新 `5484401` 已合入该主线，原有独立标签和发布均保留。

## 制品与说明

使用已安装在用户手机并回读一致的最终 0.4.4／code21 APK，没有重建。正式 Release、非 Debug，Android 10 及以上、arm64，沿用 0.4.3 签名证书 SHA-256 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`。

| 附件文件名 | GitHub 显示名 | bytes | SHA-256 |
| --- | --- | ---: | --- |
| `sensefieldv0.4.4.apk` | 听野v0.4.4 安卓安装包.apk | 24,222,060 | `a98f826b80cd5c80d1be37fe77ef1474731efc471b2d5a213d7815e50c04b2ee` |
| `installation-guide.txt` | 听野v0.4.4 使用说明.txt | 3,296 | `c73a886ec3956bbe6a6f52fc0d5dd3e85237834671472112d282c39a408cccee` |
| `SHA256SUMS.txt` | 听野v0.4.4 文件校验.txt | 176 | `fda3183435715ab592b30b2b6ebe4a903f855acaa3ed7c81bb5462505aead2ae` |

三个附件的 `state=uploaded`、显示名、字节数及服务器 `digest` 均逐项与本地比较一致。`SHA256SUMS.txt` 覆盖同目录 APK 与使用说明，不包含自身。GitHub 正文复用 `UPDATE_SUMMARY.txt` 的三行、56 字符摘要；不追加工程过程、系统提示词或发热已解决的声明。使用说明同步内置语音、动态文本引擎、音色选择和正常开始／停止流程，不要求用户暂停辅助。

先在草稿上传并核对全部附件，再公开并设为 Latest。公开后再次读取发布信息、Latest 接口及 tag 引用，确认 `draft=false`、`prerelease=false`、Latest ID 一致、源提交一致。上传过程较慢但完成，无残缺附件公开。

## 本轮检查与边界

- 最终 APK 校验通过：版本、证书、v2 签名、非 Debug、ZIP 压缩、六个运行库、16KB ELF 加载对齐、两份模型摘要及 856 个音频文件。
- 公开仓库扫描 1,484 个候选文件，通过；没有上传患者日志、截图、签名私钥、语音源下载目录或开发密钥。
- 项目虚拟环境中的 Python 全套 788 项通过、1 项跳过、1 项警告，约 51 秒。最初系统 Python 因缺少 NumPy 在收集阶段失败，改用已有 `.venv` 后通过；没有为此改业务代码或安装新依赖。
- 本轮没有重跑 Android 工程或设备测试。已发布的同一 APK 对应此前 376 项 JVM、最终 Release 9 项运行检查及 lint 0 错误／38 警告，范围见[素材与包记录](FISH_GAME_GUIDE_0_4_4_2026-10-08.md)。
- 本机女声反馈“语音还可以，略微发烫”，不等于受控温升或患者效果通过；见[真机对局](GAME_GUIDE_LIVE_0_4_4_2026-10-08.md)。Android 10／16KB 实际系统、TalkBack、外部实声和 3×15 分钟热门禁仍待测，历史评分及 `verified`／`release_ready` 不变。

## 保存位置与分发

GitHub 附件在 `output/releases/0.4.4/github-upload/`，APK 与 `gitee-upload/` 的最终包字节一致；更新清单仍在 `cdn-upload/latest.json`。本轮原始接口记录、包校验、Python XML／文本及附件核对 JSON 保存在 `output/releases/0.4.4/validation/github-release-2026-10-08/`。

本次只发布 GitHub。Gitee APK 和固定网站更新清单仍由负责人按[CDN 发布步骤](../deploy/assistant/CDN发布.md)同步最终包；没有连接或修改生产服务。本地记录已同步，未自动提交或推送后续文档修改。
