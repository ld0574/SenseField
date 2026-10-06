# 0.4.1 线上服务配套 APK（2026-10-06）

用户自行部署服务后授权生成对应 APK。本轮保持 `0.4.1 / versionCode 18`，构建属性仅预置画面服务地址 `https://sf.888413.xyz`；Android业务源码和用户已有连接偏好未改写。旧测试配置可在助手设置点击“使用听野线上服务”，填生产体验连接码、保存后重新开始辅助。新安装仍需授权并开启画面理解，不向APK写入模型密钥或设备连接码。

## 制品

- 安装包：`output/releases/0.4.1/apk-production-2026-10-06/听野v0.4.1 安卓测试安装包.apk`，214,861,660 bytes，SHA-256 `9b47b8f872cf00475506b8fe67ed00248a2c39e741063a0320376dd98f3e8b0c`。
- 包名 `com.openkhub.sensefield`，Android 10/API29及以上，target35，仅arm64-v8a；包含手机侧SenseVoiceSmall int8模型，包约205MiB。
- 本机Android Debug测试签名，证书SHA-256 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`，与此前手机安装候选一致。未提供发布签名变量，因此按现有构建脚本生成Debug candidate，不能称为正式Release签名。
- CDN文件：`output/releases/0.4.1/cdn-upload/sensefieldv0.4.1.apk` 与自动生成的 `latest.json`；保持原URL和版本，按APK先、清单后手动覆盖并刷新缓存。旧规范输出已保存在 `archive-before-production-2026-10-06/`，dated目录保留本轮独立制品。

## 验证与边界

构建脚本与 `lintDebug` 通过。APK内BuildConfig反汇编确认生产地址和CDN清单地址，aapt2核对版本/SDK/ABI，apksigner核对签名，ZIP CRC、ASR模型及词表的固定SHA-256核对通过；未包含传输测试CA或测试服务器URL。自动生成的CDN清单大小与SHA对应实际安装包。

公网HTTPS通过系统信任链验证：`/health` 返回200、production/vision_only、asr_ready=false、vision_ready=true；无认证空JSON提交 `/v1/visual` 返回401。只验证连通与认证拒绝，没有读取服务器配置、传递生产令牌或调用上游模型。

证据位于 `output/releases/0.4.1/apk-production-2026-10-06/`：build.log、lint.log、apk-buildconfig.txt、apk-badging.txt、endpoint-health.json、handoff.json及使用说明。没有重新运行JVM/UI场景，不复用旧批次计数作为本轮结果。尚未安装新包到手机，未完成生产有效令牌的视觉/实声端到端验收；没有连接测试主机或生产SSH、上传CDN、push、创建Release或改变 `verified` / `release_ready`。

复现构建时设置 `SENSEFIELD_ASSISTANT_ENDPOINT=https://sf.888413.xyz`，运行 `bash scripts/build_android_preview.sh`；需要本机Android SDK、JDK及已有固定模型资产。
