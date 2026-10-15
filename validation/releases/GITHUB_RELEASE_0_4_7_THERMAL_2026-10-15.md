# 0.4.7 高温问答同版本发布与旧包清理

2026-10-15 09:47（Asia/Shanghai），按用户要求保留 0.4.7／code24，更新现有 [GitHub v0.4.7](https://github.com/ld0574/SenseField/releases/tag/v0.4.7)。仍为 Pre-release，普通 Release、Latest 仍为 v0.4.6。Gitee 和固定网站清单由用户手动同步，本次未操作它们。

## 修订与源码

高温只暂停后台自动观察和历史图片采样，主动语音提问、读画面、在途回答及复述继续可用；本地降载、用户暂停、权限、限流与截图过期保护保留。见[问题证据与工程范围](../assistant/ASSISTANT_THERMAL_MANUAL_0_4_7_2026-10-15.md)。本次仅需更新 Android，服务器无需再次部署。

源码 `9281a178cfb1972f08d782c77c4a2e1bb0835cc8` 已推送 main，标签 v0.4.7 按同版本覆盖要求更新至该提交。构建源码摘要 `db2e962493db993acb8ce785a88b336ea7da1890cd4a7982583487f5a9c7a026` 在构建后至发布前一致，源码与 APK 绑定另有机器回执。旧标签对象和[首版发布记录](GITHUB_RELEASE_0_4_7_2026-10-15.md)保留原绑定，未改写主分支历史。

APK：24,592,049 bytes，SHA-256 `2b7f7c9f21e481669b1c23b4953321179dd9802c3b74db751b700a345fbc95e9`；混淆、不可调试的 Release，Android 10 及以上、arm64-v8a，沿用此前证书。已在辅助停止时同版本覆盖安装测试手机，未卸载或清数据，安装后 base.apk 摘要与交付包一致。旧 0.4.7 用户需手动下载安装，同 code 不会触发应用内更新。

## 附件与公开下载

替换时暂将现有 Release 转为草稿，上传并逐项校验后重新公开，避免混合新旧附件。六个附件均 uploaded，GitHub digest 与本地一致，旧同名附件由 clobber 替换。APK 和 Gitee ZIP 分别用不含认证头的公开下载地址读取，均 HTTP 200，大小及 SHA-256 与本地一致。

| 附件 | bytes | SHA-256 |
| --- | ---: | --- |
| `sensefieldv0.4.7.apk` | 24,592,049 | `2b7f7c9f21e481669b1c23b4953321179dd9802c3b74db751b700a345fbc95e9` |
| `installation-guide.txt` | 2,962 | `eb11a00132e68a60ea5a1b4b71a1daacf9b74c36da9ae83441921a0aff27661f` |
| `latest.json` | 596 | `1af69b776999ef62366be1ac7cd1c59c4ab37c4bcdec83d1cf26b00e66557d74` |
| `SHA256SUMS.txt` | 254 | `9bbe64e4a86e7f670ee84d818cd689c3edcfa50ea9b32c33da2b95208755e8a3` |
| `sensefield-release-0.4.7-gitee.zip` | 24,270,071 | `0aab9253f0d0b894b5e9377e74ab513b983ac4dacbc1876f37fe9e704c5652f4` |
| `sensefield-release-0.4.7-gitee.zip.sha256` | 101 | `f79282a90fe2d7145f9d4f1d848b8e9cb4e52fdd2735e1ce5e0d02722815d6a7` |

ZIP 通过完整性及逐文件核对，只包含 APK、安装说明、清单、校验单、发布／复测说明、验证摘要和先前仅含中文提示词的服务端更新材料。温控修复无需再次部署该提示词；ZIP 不含 Key、私有配置、诊断、截图或录音。latest.json 已同步新 APK 的大小、SHA 与更新说明；用户上传 Gitee 后还需同步固定网站清单并刷新缓存。

## 验证与清理

本次 Debug／Release JVM 各 634 项、Release lint、模拟器 Debug 32 项助手控制器检查及相关 Python 30 项通过；包身份、签名、856 份语音资产及手机安装摘要通过。模拟软件高温检查不等于受控温升、外部实声或自然实局验收；最终包整体成本稳定性、独立准确率、策略收益与完整实局仍待验。上版全量 Python 结果保留原记录，本次没有再次运行全量套件。

按用户要求删除 5 个旧 0.4.7 APK，以及旧校验单和 Finder 缓存，共 7 个文件、释放 122,963,578 bytes（约 117.27 MiB）。当前唯一独立 0.4.7 APK 为 output/releases/0.4.7/sensefieldv0.4.7.apk，另有同一字节装入 Gitee ZIP。历史源码、摘要、测试失败和诊断记录保留，其他版本及用户视频目录未修改。

公开 Git 索引的路径、文件大小和常见密钥扫描通过；仅提交本次源码和公开说明。机器回执、旧附件元数据、公开下载与删除前逐文件摘要在本地 output/validation/assistant-thermal-0.4.7-20261015/，未上传原始用户内容。
