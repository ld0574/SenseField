# 0.4.1 同版本 CDN 修订验证

本轮按用户要求保持 `versionName=0.4.1 / versionCode=18`，由用户手动覆盖 CDN APK，不新增 0.4.2。正式清单地址为 `https://888413.xyz/apk/latest.json`；安装包地址为 `https://888413.xyz/apk/sensefieldv0.4.1.apk`。

## 实现与发布方式

客户端仅读取 CDN HTTPS 清单和其中同源 APK，不再访问 GitHub Release。更高安装序号按正常更新处理；相同序号和版本名称、但安装包 SHA-256 不同，作为修订更新；完全相同的安装包不重复提示。低版本、同序号不同版本名称、不同签名或损坏的安装包均拒绝。

安装包指纹在后台计算，按 Android 安装更新时间、安装路径、文件大小和修改时间缓存。下载和安装期间保留游戏运行保护、取消与迟到结果保护。安装仍由 Android 系统确认。

工具从最终 APK 自动生成 `latest.json`。用户先覆盖 APK，再上传清单，并刷新这两个 CDN 路径的缓存。初次从原 GitHub 更新器迁移，需要先手动安装本次 CDN 修订包；其后按 CDN 清单提示更新。客户端更新检查不上传画面、语音或助手凭据。

## 已有在线对象

本轮只读下载确认 CDN 原始 0.4.1 APK 为 21,400,339 bytes，SHA-256 `b2a4dd6a0ea2b699a72500f10af69ef97b3a870ba1c2f760733db4c76b71203a`，与上一轮 GitHub 更新器构建一致。清单端点当前返回 HTTP 404。这里不把原 APK 下载成功记为新版清单已部署。

本轮修订包、同版本安装测试与指纹缓存复核见下方；整体 `verified=false`、`release_ready=false` 保持不变，用户手动上传仍待完成。

## 当前验证

- JVM 全套 212 项通过；清单工具、HTTPS fixture 和公开文档相关 Python 子集 28 项通过。arm64 Debug 构建与 lint 通过。本轮未重复 native 或完整 Python 套件。
- Android 14/API34 arm64 模拟器 7 项 updater instrumentation 通过，覆盖同版本不同内容接受、相同内容拒绝、不同签名拒绝、真实 TLS 清单与 APK 下载、篡改拒绝和 FileProvider 范围。
- 两个 APK 都是 0.4.1/code18。启动测试构建 B（SHA `a20eb8bd6bd7b3b13e3b335a813f654fd81a2ab5a767bea52cc3f0dcf715bc67`），界面显示同版本修订；用户式点击下载并在系统 UPDATE 确认，实际装入 A（SHA `88992f7ef9323ad58558fcf5b4ee8ff8ad384f8e7405a567beaeb2389be8539d`）。只用 adb 安装启动构建 B，没有用 adb 安装 A 冒充应用内更新。
- 安装 A 后额外 1 项指纹/no-op 复核通过：共享偏好中 B 的安装身份缓存失效，重新计算得到 A 的实际哈希，与当前包比较为无需更新。该项验证缓存和 no-op 判定，没有声称生产 CDN 清单的真实 HTTP no-update 检查已通过。
- 本轮 Mac 回环 TLS fixture 收到 2 次清单请求和 2 次 APK 请求，传输 42,800,678 bytes、404 为 0；与此前 Linux fixture 分开记录。临时服务、证书、私钥和 debug 信任覆盖已清理。
- 清理测试配置后重建通过；新旧 APK 的每个解压条目完全一致。交付固定采用已实测安装及指纹复核的 A，不对该文件重新封装或签名，清单与上传文件保持精确一致。
- 最终 A 为 21,400,339 bytes，0.4.1/code18、minSdk29、target35、arm64；沿用此前 Debug 证书 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`。对应本地 CDN 上传文件为 `output/releases/0.4.1/cdn-upload/sensefieldv0.4.1.apk` 与自动生成的 `latest.json`。
- 生产 CDN 目前仍是旧 APK 和缺失清单。本轮没有上传 CDN 文件，也没有发布 GitHub Release；用户手动上传、清理缓存及实体机复测仍待完成。

上一轮制品和测试证据保留在 [首次 0.4.1 构建记录](APP_UPDATE_0.4.1_2026-10-04.md)，其旧包另存 ignored `output/releases/0.4.1/history/2026-10-04-github-initial/`。本轮不会用新 hash 覆盖历史证据，也不把同版本修订工程测试当作温控、实声、玩家体验或符合度评分验收。
