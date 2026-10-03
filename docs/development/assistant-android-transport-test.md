# Android 真实助手传输测试

2026-10-04 在 Android 14 arm64 模拟器通过两项 `AssistantTransportInstrumentedTest`：Android 的正式 `AssistantGatewayClient` 经校验证书的 HTTPS 调用真实 GLM-4.6V-Flash，读取合成图中的 `SYNTHETIC HUD TEST`；经 WSS 上传离线 Tingting 生成的 16 kHz 单声道 PCM，CPU Paraformer 返回“请读出当前比分”，并确认跨 generation 的 reset。没有录制玩家或游戏语音，也没有上传对局截图。

这些测试验证传输、鉴权、序列化、会话/轮次/帧关联及真实推理。没有启用实体手机麦克风、测量物理发声、回声、温度或 P95，不能替代玩家门禁。普通 `connectedDebugAndroidTest` 没有显式配置时会跳过这两项；其余本地设备测试继续运行。

## 复现

1. 使用 Python 3.10–3.13，按 `scripts/assistant_gateway_install_deps.sh` 安装 CPU 网关依赖；另安装 `cryptography` 供测试证书使用。ASR 固定清单与权重校验不变。运行 macOS Android 14 arm64 模拟器，准备 JDK 与 SDK。
2. 在服务器进程环境配置 `ZHIPU_API_KEY`，不要把值写入命令、终端历史或 APK。启动 `.venv/bin/python scripts/assistant_android_transport_fixture.py --adb <SDK路径>/platform-tools/adb`。脚本仅绑定 Mac 回环地址，创建一天有效的临时证书、独立设备 token、合成图测试所需配置和合成语音。
3. 脚本运行期间构建并安装 debug APK 和 androidTest APK。把 ignored `output/assistant/0.4.0/android-transport/assistant-transport-test.json` 与 `asr-smoke-16khz-mono-s16le.pcm` 经 adb 复制到目标应用私有 `files/assistant-transport-test.json` 和 `files/asr-test.pcm`；中间文件也要删除。配置不写进 instrumentation 参数，不读取或打印其内容。
4. 运行 `com.openkhub.sensefield.AssistantTransportInstrumentedTest`，检查两个测试都通过且没有跳过。服务使用真实 ASR 和真实 GLM，不使用 mock，不切换收费模型。
5. 以 Ctrl+C 或 SIGTERM 停止 fixture 服务后，脚本删除生成的 debug CA 覆盖、私钥和设备配置；带 `--adb` 时也尝试清理应用私有配置。若强制终止进程，手工删除这几项，再运行公开仓库扫描。重新构建候选包，确认 APK 不含 `assistant_transport_test_ca` 和任何运行时凭据。

临时 debug 证书配置只用于这次测试。最终候选使用 main 的系统 CA 与 debug 用户 CA 规则；远端部署仍需独立配置 HTTPS/WSS 和设备 token。测试 fixture 存在期间不构建交付包，lint 在 fixture 清理后运行。

报告位于 ignored `output/assistant/0.4.0/android-transport/transport-result.json`。连续语音路由门槛为 Android 10/API 29+：本应用以静音 `USAGE_GAME` `AudioTrack` 的 `getRoutedDevice()` 和 routing callback 检查有线/USB 耳机，BLE 路由类型从 API 31 起可识别。该轨道只证明本应用默认用途的路由，不能确认游戏自己的轨道路由；试用前须由用户确认游戏声已在耳机中。无耳机、未知/失败路由、`isClientSilenced()` 或任何其他录音会话出现时暂停上传，并在恢复前清空旧轮次。此合成传输测试没有验证耳机回声、物理并发、热负载或 P95。

交付构建脚本发现临时测试 CA 时会拒绝生成候选；运行前清理，运行后仍检查 APK 资源清单。
