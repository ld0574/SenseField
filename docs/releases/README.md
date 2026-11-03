# 发布与交付

当前版本为 [听野 0.4.7](0.4.7/RELEASE_NOTES.md)，Android `versionCode 24`，支持 Android 10 及以上的 arm64 设备。

当前公开测试版为 [0.4.8 / versionCode 28](0.4.8/RELEASE_NOTES.md)，[GitHub Release](https://github.com/ld0574/SenseField/releases/tag/v0.4.8)。现有 `/data/wwwroot/sf/` 服务按 [本版更新说明](0.4.8/SERVER_UPDATE.md) 原地更新，确认模型首次下载后再切换网站清单；0.4.7 继续作为 Latest。

此前已有 0.4.9 / code26 的预发布记录，本次保留该记录。0.4.9 用户需手动覆盖安装 0.4.8 / code28；内部升级序号递增，版本名称按本次交付要求回归 0.4.8。

| 材料 | 用途 |
| --- | --- |
| [版本说明](0.4.7/RELEASE_NOTES.md) | 当前功能与使用方式 |
| [安装指引](0.4.7/installation-guide.txt) | 安装、授权及基本操作 |
| [更新摘要](0.4.7/UPDATE_SUMMARY.txt) | 应用内更新弹窗文案 |
| [版本命名规范](版本命名规范.md) | 版本、构建身份与制品管理 |
| [GitHub 发布检查](GITHUB发布检查清单.md) | 源码、素材与附件核对 |
| [下载分发与 CDN 发布](../../deploy/assistant/CDN发布.md) | APK、模型与版本清单发布流程 |

公开下载和历史版本见 [GitHub Releases](https://github.com/ld0574/SenseField/releases)。APK、校验文件和本机构建记录保存在 `output/releases/<版本>/`，不提交到源码仓库。
