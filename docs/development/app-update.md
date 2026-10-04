# Android 自动更新开发说明

更新日期：2026-10-04。当前源码与预览版本为 `0.4.1 / versionCode 18`，更新器清单默认值已配置为项目 CDN 的 `https://888413.xyz/apk/latest.json`；APK 地址为 `https://888413.xyz/apk/sensefieldv0.4.1.apk`。APK 对象已可 GET，当前返回的仍是旧构建（21,400,339 bytes，SHA-256 `b2a4dd6a0ea2b699a72500f10af69ef97b3a870ba1c2f760733db4c76b71203a`）；清单端点返回 404，新的同版本修订包尚未上传。外网 CDN 更新链路因此仍未验证。

本轮本地候选为 `0.4.1/code18`，21,400,339 bytes，SHA-256 `88992f7ef9323ad58558fcf5b4ee8ff8ad384f8e7405a567beaeb2389be8539d`。JVM 212 项、Python 自动更新专项 28 项、Android updater instrumentation 7 项、arm64 build/lint 均通过。真实同版本更新 UI 在 Mac localhost TLS fixture 上通过 Android 系统 UPDATE 将已安装包替换为本轮 APK；追加的安装后指纹/no-op 检查确认安装内容 SHA 和缓存失效正确。该流程不是 CDN 实时清单检查；系统安装界面通过 fixture 验证，CDN `latest.json` 仍为 404。详细边界见[本轮 CDN 修订验证记录](../../validation/APP_UPDATE_CDN_0.4.1_2026-10-04.md)。

## 应用内更新流程

应用启动后，在游戏选择页每次冷启动至多自动检查一次更新；默认开启，可在“启动时检查更新”关闭。屏幕旋转或从安装权限设置返回不会重复触发同一次冷启动检查。“检查更新”按钮仍可手动重试。冷启动和旋转行为在此前的 0.4.1 验证中经代码审查确认，没有专门的生命周期 instrumentation。

发现更新后，用户先查看版本、包体大小和更新说明，再选择“下载并安装”。应用不会在后台自行下载或静默安装。下载包暂存在应用私有的 `files/updates/` 中，客户端核对清单中的字节数和 SHA-256，并由 Android 包管理器检查包名、版本名称和相同签名证书。较低的 `versionCode`、同序号但不同版本名称、不同签名或损坏的 APK 都会被拒绝；更高的 `versionCode` 按常规更新处理。新客户端还支持同版本修订：当包名、版本名称和 `versionCode` 与当前安装相同、但 APK SHA-256 不同时，提供更新；SHA-256 相同时不提示更新。随后交给 Android 系统安装器；用户仍需在系统界面确认安装。首次安装更新时，Android 可能要求用户在系统设置中允许听野安装应用。

更新检查和安装不传输屏幕画面、语音、助手网关地址或助手凭据。CDN 会收到普通 HTTPS 请求及网络连接元数据。检查或下载失败时，当前安装仍可使用；本地小地图辅助链路不依赖更新服务。游戏辅助正在运行时不会开始下载或安装，用户可先停止辅助，再继续处理更新。

## 0.4.1 / code18 CDN 构建与发布

本次同版本修订使用以下同源 HTTPS 地址：

- 清单：`https://888413.xyz/apk/latest.json`
- APK：`https://888413.xyz/apk/sensefieldv0.4.1.apk`

Gradle 默认清单地址已配置为上述 `latest.json` 路径；该路径是项目配置选在 APK 同目录的稳定清单名。APK 对象 URL 用于版本包，用户负责手动上传本轮 APK 和工具生成的清单。在新 `latest.json` 上传并可读前，客户端实际不能从 CDN 检查到本轮修订。客户端要求清单和 APK 使用同一个 HTTPS origin（协议、主机和端口相同），且不会跟随到另一个 origin 下载。

运行 `bash scripts/build_android_preview.sh` 时，会在 `android/app/build/outputs/preview/cdn-upload/` 自动生成 APK 和 `latest.json`，默认使用上述 CDN 地址。清单总是从本次签名后的安装包读取元数据并计算哈希。更换下载地址可设置 `SENSEFIELD_UPDATE_APK_URL`，需与清单同源。下面的独立工具命令也适用于其他构建方式。

按下面顺序准备同版本修订包：

1. 从当前源码重建 `versionName=0.4.1`、`versionCode=18` 的 APK，并使用与已安装应用相同的签名证书。不要因同版本修订递增应用版本号；本轮已核实的候选为 21,400,339 bytes、SHA-256 `88992f7ef9323ad58558fcf5b4ee8ff8ad384f8e7405a567beaeb2389be8539d`。
2. 将最终 APK 交给仓库已有工具生成清单。该工具调用 Android SDK `aapt2` 读取 APK 的包名与版本，并从同一文件计算字节数和 SHA-256；不要手写或事后修改 manifest 字段：

   ```sh
   python3 scripts/build_app_update_manifest.py \
     --apk /path/to/sensefield-v0.4.1-arm64-v8a.apk \
     --apk-url https://888413.xyz/apk/sensefieldv0.4.1.apk \
     --output output/app-update/release/latest.json \
     --notes-file /path/to/release-notes.txt
   ```

   `latest.json` 在本地由工具生成。脚本不上传文件，也不替用户操作 CDN；无需另写 manifest 生成器或缓存模板。

3. 用户手动先上传本轮 APK 到 `https://888413.xyz/apk/sensefieldv0.4.1.apk`，再上传工具生成的 `latest.json` 到 `https://888413.xyz/apk/latest.json`。不要先发布清单，以免客户端拿到尚不可读的 APK 地址。目前检查到 APK 路径仍返回旧构建，清单路径返回 404。
4. 为 `latest.json` 设置 `Cache-Control: no-cache`，并在 CDN 规则中关闭其长时间缓存。当前 APK URL 允许同路径覆盖，不应按不可变对象处理；覆盖后对 APK 和 `latest.json` 两个路径都执行 CDN 缓存刷新/失效，避免客户端下载旧 APK 后因 SHA-256 不匹配而拒绝安装。未来普通新版本可以使用带版本号的新文件名，减少与旧缓存混淆。
5. 从 CDN 实际读取清单和 APK，核对生成的 `apk_bytes`、`apk_sha256` 与本轮 APK 字节一致，并确认响应 URL 没有跳转到其他 origin。只有上传、缓存刷新和外网读取核验完成后，才记录为 CDN 链路已验证。本轮 APK 尚未上传，不能用当前旧 APK 的成功 GET 代替此核验。

构建使用已配置的 CDN 清单地址；命令仅重建客户端，不会写入 CDN 或上传文件：

```sh
cd android
./gradlew -PsensefieldUpdateManifestUrl=https://888413.xyz/apk/latest.json assembleDebug
```

清单端点不能包含用户名、密码、查询参数或片段。清单中的 APK 地址也必须是无凭据的 HTTPS 地址，并与清单端点严格同源。

## 清单格式

清单使用 schema version 1，`release_notes` 可省略。工具从 APK 读取 `package_name`、`version_name` 和 `version_code`，将用户指定的 APK URL 写入 `apk_url`，并生成整数 `apk_bytes` 和 64 位十六进制 `apk_sha256`。不要手工创建或修改 `latest.json`；上传由最终 APK 自动生成的文件，确保摘要始终对应这份 APK 的实际字节。

## 隔离 HTTPS 复现

在 CDN 对象准备好之前，仍可用本地 loopback HTTPS fixture 验证清单获取、APK 下载和 Android 安装链路；fixture 结果不能当作 CDN 已部署或连通的证据。Linux 测试机可接收构建机生成的 APK 与清单：

```sh
python3 scripts/app_update_https_fixture.py \
  --apk /path/to/sensefieldv0.4.1-arm64-v8a.apk \
  --manifest output/app-update/release/latest.json \
  --port 18766
```

`--manifest` 模式会校验 schema、HTTPS 地址、APK 实际大小和 SHA-256，不需要 Linux 测试机安装 Android SDK 或 `aapt2`。这只证明预生成清单与所复制 APK 的字节/hash 相符，不证明清单中的版本字段或 APK 签名可信；下载到目标设备后还要由 Android 包管理器核对实际包名、版本和签名。fixture 只绑定 `127.0.0.1`，仅响应 `/latest.json` 与 `/app.apk`；给 Android 模拟器使用的清单和配置地址为 `https://10.0.2.2:<port>/...`。临时测试 CA 的 SAN 包含 `10.0.2.2`，运行配置和证书路径会在终端输出，文件放在 Git 忽略的 `output/app-update/test/` 下。跨主机测试应使用只转发到远端 loopback 的临时 SSH 本地端口转发，不要把 fixture 绑定到公网接口或开放防火墙端口。

fixture 仅用于本地 debug 复现。只把临时 CA 加入隔离的 debug 构建信任配置；不要把它带入正式构建。按 Ctrl+C 或发送 SIGTERM 后，fixture 会删除自己创建的密钥、证书、运行配置和 APK 副本，不会删除输入 APK。需要保留请求计数时，可增加 `--stats-file output/app-update/test/stats.json`；结果只含请求数与传输字节数。

## 此前 0.4.1 / code18 构建的历史验证边界

此前的 0.4.1 GitHub stable Release 解析器使用合成 metadata 验证；工作站直接请求 GitHub Latest API 遇到匿名限流，应用显示可重试状态且游戏入口仍可用，没有证明真实 GitHub 检查或下载成功。Linux loopback fixture 和真实系统 UPDATE UI 流程使用隔离测试 APK 完成，不代表 GitHub 或当前 CDN 已联通。该轮冷启动检查频率及旋转/权限设置返回行为经代码审查确认，没有专门的生命周期 instrumentation。旧 APK 的文件信息和验证证据继续保留在[原 0.4.1 自动更新验证记录](../../validation/APP_UPDATE_0.4.1_2026-10-04.md)，本次同版本修订的产物和 CDN 读取结果应另行记录，不覆盖旧 hash 或旧构建结果。
