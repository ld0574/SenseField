# Android 自动更新开发说明

2026-10-06当前策略：固定版本清单保留`https://888413.xyz/apk/latest.json`，APK改为听野Gitee Release，例如`https://gitee.com/leda/SenseField/releases/download/0.4.1/sensefieldv0.4.1.apk`。语音模型拆成两个小于100MB的固定资源附件，由APP拼接校验解压，后续Release不重复上传模型。迁移APK必须先手动覆盖安装一次，旧客户端同源规则不能自动接收Gitee地址。迁移版允许固定自有清单指向本项目Gitee Release，并适配实际附件/foruda签名跳转；其他跨域仍拒绝。APK大小、SHA、包名、版本与签名检查均保留。队友发布操作见[Gitee下载分发与后续发布](../../deploy/assistant/CDN发布.md)，工程证据见[Gitee迁移记录](../../validation/GITEE_DISTRIBUTION_2026-10-06.md)。

当前源码与预览版本为 `0.4.1 / versionCode 18`。历史测试包大小与当时线上状态单独保留在文末，当前交付以2026-10-06迁移记录为准。

## 应用内更新流程

应用启动后，在游戏选择页每次冷启动至多自动检查一次更新；默认开启，可在“启动时检查更新”关闭。屏幕旋转或从安装权限设置返回不会重复触发同一次冷启动检查。“检查更新”按钮仍可手动重试。冷启动和旋转行为在此前的 0.4.1 验证中经代码审查确认，没有专门的生命周期 instrumentation。

发现更新后，用户先查看版本、包体大小和更新说明，再选择“下载并安装”。应用不会在后台自行下载或静默安装。下载包暂存在应用私有的 `files/updates/` 中，客户端核对清单中的字节数和 SHA-256，并由 Android 包管理器检查包名、版本名称和相同签名证书。较低的 `versionCode`、同序号但不同版本名称、不同签名或损坏的 APK 都会被拒绝；更高的 `versionCode` 按常规更新处理。新客户端还支持同版本修订：当包名、版本名称和 `versionCode` 与当前安装相同、但 APK SHA-256 不同时，提供更新；SHA-256 相同时不提示更新。随后交给 Android 系统安装器；用户仍需在系统界面确认安装。首次安装更新时，Android 可能要求用户在系统设置中允许听野安装应用。

更新检查和安装不传输屏幕画面、语音、助手网关地址或助手凭据。CDN 会收到普通 HTTPS 请求及网络连接元数据。检查或下载失败时，当前安装仍可使用；本地小地图辅助链路不依赖更新服务。游戏辅助正在运行时不会开始下载或安装，用户可先停止辅助，再继续处理更新。

## 0.4.1 / code18 CDN 构建与发布

本次同版本修订使用固定清单与本项目 Gitee Release 两个 HTTPS 地址：

- 清单：`https://888413.xyz/apk/latest.json`
- APK：`https://gitee.com/leda/SenseField/releases/download/0.4.1/sensefieldv0.4.1.apk`

Gradle 默认清单地址已配置为上述 `latest.json` 路径；它是独立于版本 tag 的稳定入口。APK 对象 URL 用于版本包，用户负责手动上传最终 APK 和工具生成的清单。在新 `latest.json` 上传并可读前，客户端实际不能检查到本轮修订。客户端默认保持同源HTTPS；固定清单允许本项目Gitee Release这个明确例外，仅跟随本仓库附件及foruda的同文件名签名URL，不放开任意外站。

运行 `bash scripts/build_android_preview.sh` 时，会在 `android/app/build/outputs/preview/cdn-upload/` 自动生成 APK 和 `latest.json`，默认使用上述 CDN 地址。清单总是从本次签名后的安装包读取元数据并计算哈希。更换下载地址可设置 `SENSEFIELD_UPDATE_APK_URL`，需同源，或由固定清单指向听野Gitee Release。下面的独立工具命令也适用于其他构建方式。

按下面顺序准备同版本修订包：

1. 从当前源码重建 `versionName=0.4.1`、`versionCode=18` 的 APK，并使用与已安装应用相同的签名证书。同版本修订不递增应用版本号，具体大小和SHA-256以最终APK自动生成的清单为准。
2. 将最终 APK 交给仓库已有工具生成清单。该工具调用 Android SDK `aapt2` 读取 APK 的包名与版本，并从同一文件计算字节数和 SHA-256；不要手写或事后修改 manifest 字段：

   ```sh
   python3 scripts/build_app_update_manifest.py \
     --apk /path/to/sensefield-v0.4.1-arm64-v8a.apk \
     --apk-url https://gitee.com/leda/SenseField/releases/download/0.4.1/sensefieldv0.4.1.apk \
     --output output/app-update/release/latest.json \
     --notes-file /path/to/release-notes.txt
   ```

   `latest.json` 在本地由工具生成。脚本不上传文件，也不替用户操作 CDN；无需另写 manifest 生成器或缓存模板。

3. 用户手动先上传最终 APK 到 `https://gitee.com/leda/SenseField/releases/download/0.4.1/sensefieldv0.4.1.apk`，下载公开附件核对大小与SHA-256，再上传配套的 `latest.json` 到 `https://888413.xyz/apk/latest.json`。首次迁移还需先准备0.4.1的两个模型分片，后续无需重复上传模型。不要先发布清单，以免客户端拿到尚不可读的 APK 地址。
4. 为 `latest.json` 设置 `Cache-Control: no-cache`，并在自有站点/CDN规则中关闭其长时间缓存，覆盖后刷新清单缓存。同版本APK替换后确认Gitee永久地址返回新内容，附件缓存按Gitee后台能力处理。普通新版本使用新tag与文件名，模型仍引用原0.4.1资源地址。
5. 从公网实际读取清单和 APK，核对 `apk_bytes`、`apk_sha256` 与最终 APK 字节一致；APK跳转只能经过当前允许的本项目附件/foruda同名文件路径，清单仍要求同源。公开读取核验完成才记录下载分发通过，另需安卓手机实际验证更新安装和模型准备。

构建使用已配置的 CDN 清单地址；命令仅重建客户端，不会写入 CDN 或上传文件：

```sh
cd android
./gradlew -PsensefieldUpdateManifestUrl=https://888413.xyz/apk/latest.json assembleDebug
```

清单端点不能包含用户名、密码、查询参数或片段。清单中的 APK 地址也必须是无凭据、无查询参数或片段的 HTTPS 地址；默认与清单同源，唯一跨域例外是上述固定清单指向听野 Gitee Release。

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

2026-10-04时，更新器清单已配置为 `https://888413.xyz/apk/latest.json`，APK地址仍为同源的 `https://888413.xyz/apk/sensefieldv0.4.1.apk`。当时公开APK为21,400,339 bytes，SHA-256 `b2a4dd6a0ea2b699a72500f10af69ef97b3a870ba1c2f760733db4c76b71203a`；清单返回404，新的同版本包未上传。这是当时的状态，不是2026-10-06 Gitee附件的核对结果。

该轮本地候选为 `0.4.1/code18`，21,400,339 bytes，SHA-256 `88992f7ef9323ad58558fcf5b4ee8ff8ad384f8e7405a567beaeb2389be8539d`。JVM 212项、Python自动更新专项28项、Android updater instrumentation 7项、arm64 build/lint通过。本地TLS fixture通过Android系统UPDATE安装该包，追加安装后指纹/no-op检查；这些结果不代表公网CDN清单或当前Gitee手机链路通过，见[历史CDN修订记录](../../validation/APP_UPDATE_CDN_0.4.1_2026-10-04.md)。

此前的 0.4.1 GitHub stable Release 解析器使用合成 metadata 验证；工作站直接请求 GitHub Latest API 遇到匿名限流，应用显示可重试状态且游戏入口仍可用，没有证明真实 GitHub 检查或下载成功。Linux loopback fixture 和真实系统 UPDATE UI 流程使用隔离测试 APK 完成，不代表 GitHub 或当前 CDN 已联通。该轮冷启动检查频率及旋转/权限设置返回行为经代码审查确认，没有专门的生命周期 instrumentation。旧 APK 的文件信息和验证证据继续保留在[原 0.4.1 自动更新验证记录](../../validation/APP_UPDATE_0.4.1_2026-10-04.md)，本次同版本修订的产物和 CDN 读取结果应另行记录，不覆盖旧 hash 或旧构建结果。
