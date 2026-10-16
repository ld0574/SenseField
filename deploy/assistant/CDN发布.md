# 下载分发与 CDN 发布

APK 由 Gitee Release 分发，应用通过固定网站清单发现更新。源码与公开版本说明见 [发布索引](../../docs/releases/README.md)。

## 资源入口

| 资源 | 地址 |
| --- | --- |
| 固定版本清单 | `https://888413.xyz/apk/latest.json` |
| 当前 APK | `https://gitee.com/leda/SenseField/releases/download/0.4.7/sensefieldv0.4.7.apk` |
| 语音模型分片一 | `https://gitee.com/leda/SenseField/releases/download/0.4.1/sensevoice-int8-v1.zip.part01` |
| 语音模型分片二 | `https://gitee.com/leda/SenseField/releases/download/0.4.1/sensevoice-int8-v1.zip.part02` |

模型是独立固定资源，后续 APK 继续引用原地址。保留 0.4.1 的两个模型附件，不随旧 APK 清理，也不重命名或重新压缩。

## 发布顺序

1. 准备完整模型、内置语音和本机签名配置，执行 `bash scripts/build_android_preview.sh`。
2. 核对构建输出的 APK、配套 `latest.json` 和 SHA-256。当前版本为 `0.4.7 / versionCode 24`；新版本同步源码、脚本与说明。
3. 将最终 APK 上传至对应 Gitee Release。公开下载后核对字节数和 SHA-256，与本地最终文件一致。
4. 再将工具生成的清单发布到固定 `latest.json` 地址，设置 `Cache-Control: no-cache` 并刷新缓存。
5. 从公开入口读取清单，核对版本、APK URL、大小与摘要，并在手机完成更新安装验证。

构建工具只准备本地产物，不自动上传。不要先发布指向尚未就绪 APK 的清单。

## 独立生成版本清单

```sh
python3 scripts/build_app_update_manifest.py \
  --apk output/releases/0.4.7/sensefieldv0.4.7.apk \
  --apk-url https://gitee.com/leda/SenseField/releases/download/0.4.7/sensefieldv0.4.7.apk \
  --output output/releases/0.4.7/latest.json \
  --notes-file docs/releases/0.4.7/UPDATE_SUMMARY.txt
```

清单由最终 APK 自动读取包身份、计算字节数和摘要。更新摘要最多三行、100 字符；完整说明放在 Release 页面和使用指引中。

## 同版本修订与模型

同版本覆盖保持签名一致，以 SHA-256 区分制品。替换附件后重新核对公开下载字节，再更新清单并刷新缓存。客户端更新规则见 [应用更新说明](../../docs/development/app-update.md)。

手机自动拼接、校验并解压两个模型分片；分片不能单独解压。模型配置见 [sensevoice.metadata.json](../../android/app/src/main/assets/sensevoice.metadata.json)，重打包工具见 [package-asr-model.py](package-asr-model.py)。发布者应核对模型来源和分发许可，保留配套许可证。
