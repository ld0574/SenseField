# 听野 0.4.1 工程候选（未发布）

记录日期：2026-10-04。当前源码配置为 `0.4.1 / versionCode 18`。GitHub 最新公开体验版仍是 [0.3.8](https://github.com/ld0574/SenseField/releases/tag/v0.3.8)；0.4.1 是未发布的本地工程候选，不是可下载的 Release，也没有本版公开附件。

## 0.4.1 CDN 同版本修订（本轮）

本轮保持 `0.4.1 / versionCode 18`，构建同版本修订 APK：`output/releases/0.4.1/听野v0.4.1 安卓测试安装包.apk`，21,400,339 bytes，SHA-256 `88992f7ef9323ad58558fcf5b4ee8ff8ad384f8e7405a567beaeb2389be8539d`。客户端从项目 CDN 的 `https://888413.xyz/apk/latest.json` 读取清单，并下载同源 `https://888413.xyz/apk/sensefieldv0.4.1.apk`。APK URL 由用户提供；清单路径为构建中选定的相邻固定路径，已配置为客户端默认值。

JVM 212 项、Python 自动更新专项 28 项、Android updater instrumentation 7 项、arm64 build/lint 均通过。Android 专项覆盖本地 TLS 下载、同字节拒绝、同签名同版本不同 SHA 修订接受、异签名拒绝及 provider 访问范围。另有 1 项安装后指纹/no-op 复核确认实际安装内容 SHA 与新 APK 相符，后续检查不会对同一字节重复提示。

真实 UI 流程已在 Mac localhost TLS fixture 上由已安装的旧同版本包进入“同版本修订安装包”下载，并通过 Android 系统 UPDATE 安装为本轮 APK；没有用 `adb install` 安装新 APK。该本地 TLS 结果不代表 CDN 在线更新成功。CDN APK URL 的真实 GET 目前返回旧 APK（21,400,339 bytes，SHA-256 `b2a4dd6a0ea2b699a72500f10af69ef97b3a870ba1c2f760733db4c76b71203a`），而清单 URL 返回 HTTP 404；新 APK 和工具生成的 `latest.json` 仍待用户按 APK 先、清单后的顺序手动上传，并刷新两条 CDN 缓存。清单 404 时没有进行 CDN manifest 在线检查。详见[本轮 CDN 修订验证记录](../../../validation/APP_UPDATE_CDN_0.4.1_2026-10-04.md)。整体 `verified=false`、`release_ready=false`，UI 安装成功也不替代玩家、热负载或实声验收。

## 0.4.1 首次 GitHub 更新器构建（历史证据）

- 此历史构建默认从 GitHub `Latest` Release API 检查已发布的普通稳定 Release。用户可保留或关闭每次冷启动时的自动检查，也可手动重试。
- 检测到更新后由用户决定是否下载。下载到应用私有目录后，客户端核对清单中的 APK 字节数和 SHA-256，再检查包名、版本名称、`versionCode` 高于已安装版本且签名证书相同。
- 安装始终交给 Android 系统安装器并由用户确认。首次安装更新时，系统可能要求先允许听野安装应用。游戏辅助运行中不会开始下载或安装。
- 更新服务不可用或数据校验失败不会阻断已安装版本的本地辅助链路。更新客户端不上传画面、语音或助手服务凭据。
- 该历史构建曾通过 Gradle 属性支持同源 HTTPS 自托管清单；schema、APK 清单生成和隔离 Linux HTTPS fixture 见[自动更新开发说明](../../development/app-update.md)。本轮当前更新源已迁移到项目 CDN，前一轮 GitHub 结果保留为历史记录。

## 候选验证状态

自动更新专项的 JVM 全套 217 项（含 updater 新增 13 项）、Python fixture 子集 13 项、全 Python 套件 543 项通过/1 项跳过、Android updater instrumentation 4 项、arm64 build/lint 均已通过。GitHub stable Release 解析器使用合成 stable metadata 验证；工作站对 GitHub Latest 的直接 API 请求遇到匿名 API 限流，应用显示可重试状态且游戏入口仍可用，因此未验证该工作站上的实时 GitHub 检查或下载。Linux loopback TLS fixture 收到 3 次清单请求和 2 次 APK 下载，共传输 42,685,346 bytes，0 次 404，下载内容的字节数和 SHA-256 均核对通过。真实 UI 流程完成了隔离未来版本 `0.4.2 / versionCode 19` 的来源安装授权与系统 UPDATE 安装；更新后系统实际安装为 0.4.2/code19，不是以 `adb install` 替代升级。该 APK 只作升级验证，不是公开版本。随后 Android 14 arm64 模拟器恢复干净的 0.4.1/code18 候选包并成功完成冷启动。冷启动单次检查和旋转/权限设置返回不重复触发的行为经代码审查确认；没有专门的生命周期 instrumentation。

候选安装包：`output/releases/0.4.1/听野v0.4.1 安卓测试安装包.apk`；大小 21,400,339 bytes，SHA-256 `b2a4dd6a0ea2b699a72500f10af69ef97b3a870ba1c2f760733db4c76b71203a`。实际包版本 `0.4.1 / versionCode 18`、minSdk 29、targetSdk 35、arm64-v8a，使用本机 Android Debug 签名，证书 SHA-256 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`。包内扫描确认默认 GitHub 更新源存在，未发现 fixture 测试 CA、`18766` 测试端口 URL 或提取到的测试凭据值。此为本地 Debug 测试候选，仍未发布，也不改变 `verified=false` 或 `release_ready=false`。详细证据见[0.4.1 验证记录](../../../validation/APP_UPDATE_0.4.1_2026-10-04.md)。

0.4.0 的语音与画面助手、消消乐体验入口及其历史验证边界保留在[0.4.0 工程候选记录](../0.4.0/RELEASE_NOTES.md)，不因版本递增而改写。目标玩家实际体验、实体机持续运行、热负载和小地图独立留出验收仍未通过；0.4.1 的自动更新验证也不能替代这些门禁。
