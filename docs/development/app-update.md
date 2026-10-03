# Android 自动更新开发说明

更新日期：2026-10-04。本文对应源码候选 `0.4.1 / versionCode 18`。最新公开体验版仍为 [0.3.8](../releases/0.3.8/RELEASE_NOTES.md)；0.4.1 自动更新专项验证和本地 Debug 测试候选包已完成，仍未发布。

## 应用内更新流程

应用启动后，在游戏选择页每次冷启动至多自动检查一次更新；默认开启，可在“启动时检查更新”关闭。屏幕旋转或从安装权限设置返回不会重复触发同一次冷启动检查。“检查更新”按钮仍可手动重试。

发现更新后，用户先查看版本、包体大小和更新说明，再选择“下载并安装”。应用不会在后台自行下载或静默安装。下载包暂存在应用私有的 `files/updates/` 中，客户端核对清单中的字节数和 SHA-256，并由 Android 包管理器检查包名、版本名称、较高的 `versionCode` 与当前应用相同的签名证书。随后交给 Android 系统安装器；用户仍需在系统界面确认安装。首次安装更新时，Android 可能要求用户在系统设置中允许听野安装应用。

更新检查和安装不传输屏幕画面、语音、助手网关地址或助手凭据。更新服务器会收到普通 HTTPS 请求及网络连接元数据。检查或下载失败时，当前安装仍可使用；本地小地图辅助链路不依赖更新服务。游戏辅助正在运行时不会开始下载或安装，用户可先停止辅助，再继续处理更新。

## 官方 GitHub 更新源

默认源是 `https://api.github.com/repos/ld0574/SenseField/releases/latest`。客户端只接受已发布的普通稳定 Release：不能是 draft 或 prerelease，标签使用 `v0.4.2` 这样的三段数字格式，Release 中必须恰好有一个 APK 附件，并提供 GitHub API 返回的 SHA-256 摘要。发布前仍需核对实际 APK 与附件、标签和签名；更新器不会发布 Release。

源码构建默认不需要额外属性。如需在受控构建中指定另一个 HTTPS 更新清单地址，可通过 Gradle 属性覆盖：

```sh
cd android
./gradlew -PsensefieldUpdateManifestUrl=https://updates.example.invalid/sensefield/latest.json assembleDebug
```

清单地址必须使用 HTTPS，不能包含用户名、密码、查询参数或片段。自托管清单中的 APK 地址还必须与清单地址具有相同的 HTTPS origin（协议、主机和端口相同）；客户端不会跟随到另一个 origin 下载。

## 自托管清单格式

清单使用 schema version 1。必需字段如下，`release_notes` 可省略：

```json
{
  "schema_version": 1,
  "package_name": "com.openkhub.sensefield",
  "version_name": "0.4.2",
  "version_code": 19,
  "apk_url": "https://updates.example.invalid/sensefield/0.4.2/app.apk",
  "apk_bytes": 12345678,
  "apk_sha256": "<64-character SHA-256 hex digest>",
  "release_notes": "更新内容说明"
}
```

版本信息和包名应从要交付的 APK 读取，不能用手填的版本覆盖另一份 APK。仓库脚本会调用 Android SDK `aapt2` 读取包名、版本和最低 SDK 信息，并散列 APK 的实际字节：

```sh
python3 scripts/build_app_update_manifest.py \
  --apk /path/to/sensefield-0.4.2-arm64-v8a.apk \
  --apk-url https://updates.example.invalid/sensefield/0.4.2/app.apk \
  --output /path/to/latest.json \
  --notes-file /path/to/release-notes.txt
```

把 `latest.json` 和 APK 放在所声明的 HTTPS 地址上。提交或启用该清单前，确认 APK 使用与已安装应用相同的正式签名密钥，且 `versionCode` 高于设备上的版本。

## 隔离 HTTPS 复现

团队目前没有公开自托管更新站点，正式默认源仍是 GitHub 普通 Release。Linux 测试机可以接收在有 Android SDK 的构建机上生成的 APK 和清单，然后以临时 fixture 复现真实下载：

```sh
python3 scripts/app_update_https_fixture.py \
  --apk /path/to/sensefield-0.4.2-arm64-v8a.apk \
  --manifest /path/to/latest.json \
  --port 18766
```

`--manifest` 模式会校验 schema、HTTPS 地址、APK 实际大小和 SHA-256，不需要 Linux 测试机安装 Android SDK 或 `aapt2`。这只证明预生成清单与所复制 APK 的字节/hash 相符，不证明清单中的版本字段或 APK 签名可信；下载到目标设备后还要由 Android 包管理器核对实际包名、版本和签名。fixture 只绑定 `127.0.0.1`，仅响应 `/latest.json` 与 `/app.apk`；给 Android 模拟器使用的清单和配置地址为 `https://10.0.2.2:<port>/...`。临时测试 CA 的 SAN 包含 `10.0.2.2`，运行配置和证书路径会在终端输出，文件放在 Git 忽略的 `output/app-update/test/` 下。跨主机测试应使用只转发到远端 loopback 的临时 SSH 本地端口转发，不要把 fixture 绑定到公网接口或开放防火墙端口。

fixture 仅用于本地 debug 复现。只把临时 CA 加入隔离的 debug 构建信任配置；不要把它带入正式构建。按 Ctrl+C 或发送 SIGTERM 后，fixture 会删除自己创建的密钥、证书、运行配置和 APK 副本，不会删除输入 APK。需要保留请求计数时，可增加 `--stats-file output/app-update/test/stats.json`；结果只含请求数与传输字节数。

自动更新专项的 JVM、Python fixture、Android instrumentation、Linux loopback TLS 和真实系统 UPDATE 流程已通过；真实 UI 测试安装的是隔离用未来版本 `0.4.2 / versionCode 19`，不是 0.4.1 公开包。GitHub stable Release 解析器用合成 stable metadata 验证；工作站对 GitHub Latest API 的直接请求遇到匿名限流，应用显示可重试状态且游戏入口仍可用，故不声称真实 GitHub 检查或下载成功。0.4.1 arm64 Debug 候选包已构建并扫描，未发现 fixture CA、测试端口 URL 或测试凭据。冷启动检查频率及旋转/权限设置返回时的行为经代码审查确认，没有专门的生命周期 instrumentation。0.4.1 仍未发布，也不代表 `verified=true` 或 `release_ready=true`。具体设备、测试计数、APK 哈希和证据边界以[0.4.1 验证记录](../../validation/APP_UPDATE_0.4.1_2026-10-04.md)为准。
