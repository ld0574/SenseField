# Android 应用更新

听野使用固定 HTTPS 清单发现版本，通过 Gitee Release 分发 APK。当前应用版本为 `0.4.7 / versionCode 24`。

- 版本清单：`https://888413.xyz/apk/latest.json`
- 当前 APK：`https://gitee.com/leda/SenseField/releases/download/0.4.7/sensefieldv0.4.7.apk`
- 发布流程：[下载分发与 CDN 发布](../../deploy/assistant/CDN发布.md)

## 客户端流程

默认每次冷启动至多自动检查一次，也可关闭启动检查或手动点击“检查更新”。发现版本后显示版本、包体大小和简短说明，用户选择下载，再由 Android 系统确认安装。辅助运行期间先停止辅助，再进行更新。

下载保存于应用私有目录。客户端核对字节数、SHA-256、包名、版本与签名；不接受降级包或不同签名。当前更新器支持同版本修订：版本名称与 `versionCode` 相同而 APK 摘要不同可提示更新，相同摘要不重复提示。旧客户端也可从发布页手动覆盖安装同证书 APK。

清单与 APK 均使用无凭据的 HTTPS 地址。固定自有清单允许指向本项目 Gitee Release，并仅跟随受支持的附件跳转。更新请求不携带画面、语音或助手凭据。

## 构建清单

优先使用签名构建脚本，由同一份最终 APK 自动生成清单：

```sh
bash scripts/build_android_preview.sh
```

单独生成时使用现有工具，不手写版本和摘要字段：

```sh
python3 scripts/build_app_update_manifest.py \
  --apk output/releases/0.4.7/sensefieldv0.4.7.apk \
  --apk-url https://gitee.com/leda/SenseField/releases/download/0.4.7/sensefieldv0.4.7.apk \
  --output output/releases/0.4.7/latest.json \
  --notes-file docs/releases/0.4.7/UPDATE_SUMMARY.txt
```

工具通过 Android SDK 读取包身份，计算实际大小及 SHA-256。`release_notes` 最多三行、合计 100 字符，完整说明放在版本文档中。上传顺序为 APK、公开下载核对、固定清单，最后刷新缓存。

## 本地更新链路验证

可用隔离的 HTTPS fixture 验证清单获取与下载：

```sh
python3 scripts/app_update_https_fixture.py \
  --apk /path/to/sensefield.apk \
  --manifest /path/to/latest.json \
  --port 18766
```

fixture 仅用于开发测试，绑定回环地址；临时 CA 不进入正式构建。它证明本地链路，公网分发与系统安装仍需分别核对。
