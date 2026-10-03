# 0.4.1 自动更新验证记录（专项完成；整体候选未发布）

记录日期：2026-10-04。当前源码配置 `0.4.1 / versionCode 18`；最新公开体验版仍为 [0.3.8](https://github.com/ld0574/SenseField/releases/tag/v0.3.8)。本记录只用于跟踪候选验证，不是发布授权。

## 候选和边界

- 自动更新默认读取 GitHub `/repos/ld0574/SenseField/releases/latest`，要求普通、非 draft、非 prerelease Release 和一个可验证 SHA-256 的 APK 附件。
- 每次冷启动最多自动检查一次；关闭自动检查后仍可手动检查。用户确认后才下载，安装由 Android 系统安装器完成。
- 更新下载在应用私有目录，检查 APK 的字节数、SHA-256、包名、版本和与当前应用相同的签名。第一次安装更新可能需要用户在系统设置中授权听野安装应用。运行游戏辅助时不下载或安装。
- 更新检查不发送游戏画面、语音或助手网关凭据。更新服务失败不能代替或阻断本地辅助路径。
- 仓库支持为受控构建配置同源 HTTPS 清单，但团队没有公开自托管更新服务。Linux 复现使用 loopback-only TLS fixture，并通过受限的 SSH 本地转发访问，不开放公网 listener；测试证书和临时配置不进入 Release。

## 当前验证进度

| 范围 | 已知记录 | 最终结果 |
| --- | --- | --- |
| JVM 全套与更新客户端 | 新增更新用例覆盖 GitHub stable Release、旧附件缺少 digest、metadata timeout 和下载取消等路径。 | **全套 217 项通过，其中新增更新用例 13 项** |
| GitHub Latest 直接访问 | 正式 Release parser 使用合成 stable metadata 验证；工作站直接请求 GitHub Latest API。 | 合成 stable metadata 解析测试通过；工作站请求遇匿名 API 限流，应用显示可重试且游戏入口可用。未验证工作站上的实时 GitHub 检查或下载 |
| Python HTTPS fixture | 预生成清单 schema、HTTPS URL、大小/hash 拒绝路径和免 aapt2 路径；fixture 只绑定 loopback。 | **13 项通过** |
| Linux HTTPS 传输 | 预生成清单由 Android SDK 主机构建后提供给 Linux；HTTPS fixture 经受限 SSH 本地转发访问，只在 loopback 监听。 | 收到 **3 次清单请求、2 次 APK 下载**，共传输 **42,685,346 bytes**，**0 次 404**；实际字节数和 SHA-256 对照通过，没有公网 listener |
| Android 下载与包验证 | updater instrumentation 覆盖真实 HTTPS 下载、APK platform metadata/signature 验证、FileProvider 可读 URI 与系统安装 intent；篡改包、同版本包、不同签名和其他应用私有文件访问均拒绝。 | **4 项 updater instrumentation 通过** |
| 安装权限与系统安装器 | UI 从“下载并安装”开始，首次来源权限在系统设置中授予，返回应用后继续交给系统 UPDATE 安装器。 | 隔离未来测试包实际变为 `0.4.2 / versionCode 19`；使用应用 UI 完成，不是 `adb install`。随后 Android 14 arm64 模拟器恢复干净的 0.4.1/code18 候选并成功启动。该测试 APK 不是公开版本 |
| 构建 | 0.4.1 arm64 候选构建和静态检查。 | arm64 build 与 lint 通过 |
| 冷启动检查 | 每次冷启动一次；屏幕旋转、权限设置返回不重复；自动检查可关闭，手动检查可重试。 | 控制流代码审查确认上述行为；没有专门的生命周期 instrumentation，不将其记作 JVM 或 Android instrumentation 实测 |

## 候选 APK 与整体状态

- APK 文件：`output/releases/0.4.1/听野v0.4.1 安卓测试安装包.apk`
- 实际 `versionName / versionCode`：`0.4.1 / 18`
- APK 字节数 / SHA-256：`21,400,339` / `b2a4dd6a0ea2b699a72500f10af69ef97b3a870ba1c2f760733db4c76b71203a`
- Android 包：minSdk 29、targetSdk 35、arm64-v8a；本机 Android Debug 签名，证书 SHA-256 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`
- 最终测试结果：JVM 全套 217 通过（含 updater 新增 13 项）；Python 全套 543 通过、1 跳过（fixture 子集 13 通过）；Android updater instrumentation 4 通过；arm64 build/lint 通过；Linux loopback TLS fixture 两次下载总计 42,685,346 bytes，字节数和 SHA-256 校验通过
- 包内扫描：默认 GitHub 更新源存在；未发现 fixture 测试 CA、`18766` 测试端口 URL 或提取到的测试凭据值
- 当前状态：0.4.1 尚未发布；整体 `verified=false`，`release_ready=false`

自动更新专项验证和本地 Debug 测试候选包已完成，仍未发布。隔离的 0.4.2/code19 APK 只用于验证真实升级，不是 0.4.1 候选交付或公开版本。GitHub stable Release 解析已用合成 metadata 覆盖；工作站实时 API 检查遇匿名限流，没有声称正式 GitHub 源联网检查成功。0.4.1 专项验证不能替代整体验收：目标玩家实际体验、实体机持续运行、热负载、小地图独立留出等门禁仍未完成。保留[0.4.0 历史工程记录](../docs/releases/0.4.0/RELEASE_NOTES.md)中的结果与限制，不回写旧版本证据。
