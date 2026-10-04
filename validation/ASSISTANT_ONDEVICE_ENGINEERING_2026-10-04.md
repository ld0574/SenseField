# 0.4.1 手机端语音工程复核

日期：2026-10-04。本记录对应用户带手机离开后的源码续改；用户重新提供无线ADB后，已将最终干净候选覆盖安装。此前手机包 `4218bf94…` 与中间候选 `0c9991a4…` 分别保留为历史。版本仍为 0.4.1/code18；不将模拟器、合成输入或固定视觉回复替身当作真人验收。

## 目标与需求证据

| 当前工程目标 | 实现与复核入口 | 证据边界 |
| --- | --- | --- |
| 手机端中文 ASR，不占服务器 ASR | `OnDeviceAsr` → JNI sherpa-onnx 1.13.8 / ONNX Runtime 1.28.2，SenseVoiceSmall int8、单 CPU 推理线程；单槽、取消 epoch、16 秒有界 PCM。Controller 不连接音频 WSS | 既有小米 Android14 合成 PCM 三例为170/294/352ms；真实麦克风、蓝牙路由、VAD句末及实声P95仍待测 |
| 暂停停止录音、恢复清理旧轮缓冲 | `AssistantVoiceInput` + `PauseableRecorderLoop`：HOT/手动暂停调用 `AudioRecord.stop()`，恢复复用同一实例；整帧消费与暂停/路由切换串行化，清除 partial/pre-roll、VAD、AEC和声学统计。退出先注销自有回调，再由唯一读线程释放录音实例 | 7项fake-recorder JVM测试覆盖阻塞读、partial丢弃、预期stop错误、真实读故障、关闭、reset竞态和整帧切换；fake恢复时flush旧块并证明仅新样本进入整帧。AOSP Android10/14/16源码flush契约另见下节；厂商AudioFlinger/HAL、真实麦克风、系统指示器、功耗与温升仍未测，暂停并不销毁实例 |
| 保留本地预警抢占 | 复用现有声音协调器；助手取消后不续播；本轮不改检测权重、ROI、near-zone规则和提醒文案 | 原有播放器/仲裁回归继续保留；队友此前“提醒到位、无感知误报”是单局反馈，未要求重测已满意的提醒效果 |
| 游戏建议结合近期帧 | 手动问题原文、最多两张640px历史图与一张1280px当前图；6秒历史保留，NORMAL/WARM为2/4秒采样；动态建议强制 `hud`、5秒帧龄，稳定文字15秒；HOT清空并停视觉 | 出装、选人、对战各类的正式Controller/HTTPS/网关结构测试与真实Qwen质量分开。真实Qwen装备合成单点成功、选人合成超时均保留，不能称三个真实产品场景均已通过 |
| 运行状态准确 | 本地模型的ready回调不覆盖其他录音、用户暂停和HOT状态；旧排队状态不覆盖新状态 | `AssistantControlsInstrumentedTest.localModelReadyCannotClearBlockedInputOrPause`；真实游戏开麦与恢复仍待设备记录 |
| TTS临时文件可回收 | `AssistantTtsCache`限定cache根目录/严格助手WAV命名；启动与60秒后回收旧孤儿，跳过当前进程active路径、新文件、符号链接和其他cache。正常/失败/取消统一注销与删除 | 6项JVM测试含两个消费者、失败后迟到文件与外部符号链接；删除是best-effort，进程终止/外部TTS迟到写入可暂留至下一次清理，不承诺全路径零留存 |
| 符合度口径一致 | 更新《符合度改造方案》、35项对照和助手Goal；区分0.4.0服务器ASR/GLM历史与当前本地ASR/Qwen | 历史52.96%、未实现的HRTF/独立左右马达/预测等、独立玩家/声学/热负载门禁均不改写 |

## 干净服务器环境验证

实际新建Python3.12环境并运行 `scripts/assistant_gateway_install_deps.sh --vision-only`，仅安装 `assistant-vision-gateway` extra。确认 `torch`、`funasr`、`huggingface_hub`、`sherpa_onnx` 均不存在，正式app的lifespan与health在禁用ASR的条件下可运行。此验证不是新Linux主机部署，也没有卸载既有服务器比较环境。

新增 `scripts/assistant_android_local_vision_fixture.py` 在同一干净环境运行正式 `create_app`，注入固定视觉回复并设置 `ASR_BACKEND=disabled`。独立临时CA、服务端叶证书、设备令牌只用于回环测试；不连接付费上游。真实HTTPS检查确认CA校验成功、未知CA被拒、缺令牌401、认证后的禁用音频WSS握手403。fixture生成器也补齐SKI/AKI，独立生成后通过OpenSSL strict CA/主机名/用途检查，临时目录随即清理；这是证书兼容检查，不增加Android测试计数。当前运行的测试网关重启后，经已有CA验证health为 `vision_only` / `asr_backend=disabled` / `asr_ready=false` / `vision_ready=true`，`vision_model=qwen/qwen3.8-27b`。一次Python3.14严格校验发现旧叶证书缺少AKI；已保留同一CA及密钥重签叶证书，补SKI/AKI，OpenSSL strict主机名/用途校验和Python3.14 HTTPS health均通过。新叶证书为一天测试证书；此为临时联调环境，不是公开生产部署。

## 录音恢复的平台契约与边界

独立核对AOSP `AudioRecord::start()`，下列三个正式tag均含 `// discard data in buffer` 与 `mProxy->flush()`：

- [Android10 / android-10.0.0_r1](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r1/media/libaudioclient/AudioRecord.cpp)
- [Android14 / android-14.0.0_r1](https://android.googlesource.com/platform/frameworks/av/+/android-14.0.0_r1/media/libaudioclient/AudioRecord.cpp)
- [Android16 / android-16.0.0_r1](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r1/media/libaudioclient/AudioRecord.cpp)

据此，正式adapter用同一实例stop/start，fake recorder在start时清除待读样本，测试暂停期间的旧块不进入恢复帧。Java loop另以epoch和锁丢弃跨轮partial，重置pre-roll/VAD/AEC。上述为源码和fake软件证据，不证明每家厂商的HAL、物理麦克风恢复新鲜度、系统隐私指示灯或实际降热；本轮模拟器fixture没有启动AudioRecord。

## 工程检查

- 239项Android JVM通过，失败/错误/跳过均为0；含7项录音生命周期与6项TTS缓存回收。
- 122项相关Python回归通过；保留1条既有Starlette弃用告警。新增“请推荐一件当前适合的装备”的动态HUD归类回归，避免误用静态文字15秒时效；已重启服务加载修复。
- Android14模拟器14项专项测试通过，零跳过：12项助手控制，2项正式本地ASR→Controller→HTTPS客户端→正式网关测试。网络测试只用合成PCM、合成RGBA画面与固定视觉回复，不启动麦克风、MediaProjection、overlay或真实TTS，也不请求真实Qwen。它覆盖原问题、近期图、session/generation/turn/frame归属、取消/迟到回复丢弃和voice-only无网络请求。
- fixture清理修复后2项网络测试再次通过；`@AfterClass`删除目标app配置已实查，临时主机配置/密钥/CA及debug manifest/XML均已删除。不把重复执行计为新测试数。
- 模型资产 `--check-only` 的大小与SHA校验通过，无下载或更换模型。
- 移除临时信任资源后重新构建Debug/Test APK与lint成功：0错误、22条既有警告；公开仓库检查和diff空白检查通过。中间fixture重复debug-overrides的lint失败已通过独立debug测试XML解决，最终包不含fixture证书/资源。
- 最终APK解压扫描已知私有提供商/设备凭据，匹配数0；权重作为ignored asset，不提交Git。

本机工程日志保存在ignored `output/assistant/0.4.1/local-asr-vision/`：`build-fixture-final.log`、`instrumentation-pass.log`、`instrumentation-cleanup-final.log`、`build-clean-final.log`。公开文档只记录测试范围与标量，不含私有配置或玩家画面。

## 最终候选与装机

最终干净APK：`output/releases/0.4.1/ondevice-asr-final-2026-10-04/听野v0.4.1 手机端语音工程候选.apk`。

- 0.4.1 / code18，Android10+/arm64，214,750,924 bytes。
- SHA-256：`ce8dd75befd042444199867b8deea09619a69c6bce9bbb123e65eecb6d3eae1f`。
- v2签名验证通过，沿用Android Debug证书，证书SHA-256 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`。
- 用户重新提供无线ADB后，无活动辅助服务时覆盖安装成功；手机APK读回SHA与候选一致，服务器地址和设备令牌仍有配置。未自动开始录音/录屏。
- 上一手机包为214,121,822bytes / `4218bf94…`；中间未装候选为214,738,312bytes / `0c9991a4…`，不混用结果。
- 安装包附使用说明、SHA文件和制品JSON，首次复制模型另需约239MB。旧100MiB更新器首次进入此大包需手动覆盖；新候选256MiB上限已包含在本次安装中。没有push、CDN上传或Release。

本轮工程目标交付完毕；下列物理、玩家和严格性能门禁继续保留待测，不能据候选装机改变其状态。

## 仍需独立取得的证据

真实麦克风/普通蓝牙输入路线、游戏开麦让路与恢复、插话后实际停声、回声效果、中文离线TTS可用性、语音结束到识别与有用回答实声P95、受控热负载，以及真实游戏建议的准确性/有帮助性仍待测。热对照沿用至少3组配对15分钟、匹配起始温度和热点/画质/声音/充电条件，温升增量≤1°C、native processing P95回归≤10%的原协议。

当前视觉提供商账户、主机/代理日志和数据条款尚未独立审核，不承诺上游零留存。手机端开关、MediaProjection停止及前台服务的真实设备生命周期另待记录。既有严格小地图门槛、`verified`、`release_ready`均保持原状态；不发布、不上传患者素材。
