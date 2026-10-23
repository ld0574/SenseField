# 0.4.4 离线语音与 APK 瘦身验证

此文件记录 10 月 7 日中性音源候选。10 月 8 日用户选定游戏解说女声，默认固定音色已更新；最新候选、摘要及本批通过范围见 [女声替换记录](FISH_VOICE_0_4_4_2026-10-08.md)，不要把本文件的旧 APK 摘要用于上传。

日期：2026-10-07。工程实现和本地交付完成，患者试用验收待测。对应 [Goal](GOAL_0_4_4_2026-10-07.md) 与 [构建说明](../../docs/development/offline-speech.md)。本轮未发布、push、上传 CDN 或操作生产服务；历史评分、`verified` 与 `release_ready` 不变。

## 实现与音频来源

24 条固定提示 × 晓晓／云希 × 80%～240%、每档10%的17档语速，共816个音频；晓晓默认。固定提示在开发阶段用 FFmpeg `atempo` 保音高变速，播放时调整音量。完整说明按实际 Java 目录提取40句，仅保留晓晓，播放时用 Android `PlaybackParams` 保音高变速。共 **856个真实音频，连清单3,377,805 bytes**，低于15MB目标；不另写一份近似说明。JVM覆盖32,768种说明配置的音频文本完整性。

用户指定 `text-to-speech.cn` 生成两批固定提示，额度耗尽后指定 `tts.wangwangit.com` 生成完整说明；使用网页SSML和下载功能，没有绕过额度或调用私有接口。原始WAV、SSML和截图在忽略的 `output/speech-source/`、`output/releases/0.4.4/validation/`。VoiceCraft声明研究／学习／非商业体验用途，本次按非商业黑客松试用记录；不是官方Azure商业授权素材，商业使用需替换相应授权音频。清单的 `source_selected_by_user` 仅表示用户选择来源。

`BundledSpeechAssets` 在声音工作线程校验摘要并解码PCM；固定缓存最多两个音色／语速组合、说明缓存最多三句。已准备的近区语音与音效同一条AudioTrack播放；未准备的内置音频在声音线程读取，不等待网络、初始化TTS或调用ASR。审计区分入队时缓存命中／未命中。消消乐动态文本和助手回答仍用所选手机离线中文引擎，助手默认关闭。

试听按播放头及完成事件结束，移除固定4.5秒等待；连续点击取消旧轮次并替换，旧回调不能结束新播放。音效试听复用播放器和音效资源。设置分开内置音色与「动态文本语音引擎」，选中后自动试听。完整说明保留按段听、暂停／继续、跳过并开始、旋转恢复。

## 最终包

| 项目 | 当前证据 |
| --- | --- |
| 身份 | `0.4.4 / code21`，`com.openkhub.sensefield`，min29／target35／arm64 |
| 构建 | Release，非debuggable，R8及资源裁剪启用，无Debug回退 |
| 唯一APK | `output/releases/0.4.4/gitee-upload/sensefieldv0.4.4.apk` |
| 大小 | **23,280,552 bytes / 23.28MB**，≤40MB |
| 0.4.3对照 | 49,666,353 bytes，下载大小减少 **53.13%** |
| APK SHA-256 | `ca689502d212515f3f8cf4e91ae3258bf24adf39aaf826e8917ce87b8564fd80` |
| v2证书 SHA-256 | `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`，同0.4.3 |
| 清单 | `output/releases/0.4.4/cdn-upload/latest.json`，从最终签名字节生成 |
| APK URL | `https://gitee.com/leda/SenseField/releases/download/0.4.4/sensefieldv0.4.4.apk`，待手动上传 |

ZIP条目压缩大小：原生库12,505,411 bytes、DEX376,675、语音2,997,768、其他7,150,469；另有ZIP目录／签名开销。六个原生库、两款游戏、两份小地图模型摘要保留；没有裁剪ONNX算子或重编其运行时。ASR权重继续按需下载0.4.1的Gitee分片，不塞回APK。

`verify_android_release.py` 核对身份、签名、真实音频、模型、压缩及预算。六个ELF的PT_LOAD对齐均16,384，`zipalign -c -P 16 4`通过；静态检查不替代16KB系统安装。原生库在安装时展开，下载体积不等于安装占用。签名证书仍为既有体验证书（DN为Android Debug），**构建类型是正式Release**。

保留JNI名称、AndroidX／Kotlin跨测试APK契约及有限运行接口，没有保留整个应用。R8重打包包私有 `AsrModelStore.Spec` 曾导致测试 `IllegalAccessError`；通过保留应用包名维护访问边界，类／成员仍可裁剪和混淆，最终ASR检查通过。

## 测试结果与范围

| 条件 | 实际结果 | 范围 |
| --- | --- | --- |
| JVM，54个测试类 | **376通过，0失败／跳过** | 目录、缓存、取消、原有路由和设置回归，含全部说明配置 |
| Python语音／更新清单／模型包 | **31通过** | 缺损、摘要、文本、音色路径、fixture拒绝；导入失败不留下半批素材；清单与ELF校验 |
| 最终minified Release，API34 arm64 | **13通过** | 6项内置音频、1项VAD、4项诊断像素、1项小地图JNI合成输入、1项端侧ASR |
| 同一最终Release，无TTS服务、无默认网络，进程重启 | **2通过** | 固定提醒、说明暂停／旋转／继续；不初始化动态TTS |
| 辅助Debug，API33 arm64 | **36个不同用例完成：首轮35通过，1个旧版本断言修正后增量通过** | 音效、学习、两款游戏入口／合成像素、更新校验／私有文件拒绝 |
| 原0.4.3客户端检查最终0.4.4包 | **1通过，无跳过** | 真实未来包校验、FileProvider URI可读、安装器解析及损坏字节拒绝；没有执行公网下载或安装器UI自动化 |
| 音频UI专项，辅助Debug | **11格、33次执行通过** | 320／360／432dp × 100／150／200%，另横屏100／200%；调参、分组帮助、音效／新音色选择及触区 |
| 构建／lint | 构建成功；**0错误、38警告** | 未称lint零警告 |

真实Release覆盖两音色在80／180／240%的解码、全部40句说明解码、连续20次音效和20次固定语音完成、各10次快速取消及最新播放成功、反复点测试按钮、旋转／说明继续。无引擎时动态TTS仍不可用，不以固定提示成功替动态文字背书。软件播放头和回调不代表外部可听完整性。

首次大字矩阵发现平台单选项固定48dp高度，在200%字号裁切；`UiKit.singleChoiceAdapter` 改为WRAP_CONTENT、至少64dp，应用于音效、内置音色和动态引擎。重新执行完整矩阵后通过，已审视320dp／200%的实际选择框截图。工具设备截图目录仍叫 `ui-0.4.3`，本批实际版本为0.4.4辅助Debug，目录名称不作为版本证据。

最终Release ASR使用事先在模拟器私有缓存中放置并校验的SenseVoice int8权重。纯合成PCM比分／出装／选人三例134／232／269ms，关键词与可处理问题断言通过；只证明R8／压缩后JNI与ASR路径可运行，不是Gitee下载、麦克风、端到端P95或真实助手验收。

未只凭instrumentation的 `OK` 统计成功：额外同版本外部文件fixture因不可读被Assume跳过，不计通过；改用应用私有updates目录的真实未来包后得到无跳过的旧客户端验证。早期R8访问、旧版写死0.4.3断言和布局失败保留日志，最终结果来自各自修复后的记录。

## 覆盖升级与占用

API33实际安装原0.4.3/code20，再 `adb install -r` 最终0.4.4/code21；首次安装时间保留，音量67、语速140%、自动更新false三项不丢失。API33／34从安装目录回读APK，摘要均与交付一致。

同一API33模拟器各三次 `am start -W -S` 进程冷启动，只开游戏选择页，助手／ASR／采集／游戏未运行；没有清空文件系统缓存或控制宿主机负载，不能称手机性能验收：

| 指标 | 0.4.3 | 0.4.4 |
| --- | --- | --- |
| TotalTime，ms | 1,052／902／922 | 734／712／692 |
| 启动后PSS，KiB | 56,359／56,470／56,434 | 36,649／36,698／36,700 |
| 安装代码目录 `du -sk`，KiB | 48,532 | 55,712 |

代码目录增大约7MiB，与原生库展开策略一致；不包含应用数据、已下载的约239MB ASR权重或系统后续优化开销。启动页内存不能替代游戏识别与温升对照。

## 待测门禁

- Android10实际安装／覆盖升级，16KB页系统实际JNI与声音运行。
- 多款手机无TTS／断网冷启动、有线／蓝牙／扬声器路线，音量与保音高体验；耳机可听性、音效和语音间隙需外部录音。
- 快速切换音色／语速／音效后的真实完整性、动态引擎、游戏音效并行、TalkBack与患者体验。
- 保持同机热点、画质、音频路线、充电及起始温度一致，各模式至少三次连续15分钟；识别P95恶化超过10%或配对温升增加超过1°C时，不记患者试用通过。本轮无新真机热结论。
- 负责人上传后实际Gitee下载、手机更新提示、系统安装确认及更新后不重复提示；本地校验／ADB安装不替代公网与用户安装流程。

500ms观测预算、500ms实声试用目标、250ms／8FPS严格门槛分别报告。未恢复分数、不训练、不扫描阈值、不读封存视频、不修改 `verified`／`release_ready`。

## 原始记录

日志与截图在忽略的 `output/releases/0.4.4/validation/`：`release-package.json`、`handoff-build.txt`、`release-build.txt`、`final-release-runtime-audio.txt`、`release-offline-no-tts.txt`、`final-api34-installed.json`、`debug-audio-learning-match3-update.txt`、`debug-match3-version-rerun.txt`、`old-client-verifies-final-release.txt`、`audio-ui-matrix/matrix.json`及截图、`final-upgrade-performance.json`。JVM XML和lint报告在 `android/app/build/`。没有连接患者手机；APK和清单以交付目录为唯一上传入口，患者原始资料、模型权重及凭据不入Git。

最终 `git diff --check`、Python语法、交付Shell语法及公开仓库扫描通过（1,467个候选文件）。真实素材校验与最终APK门禁通过；开发用合成fixture移至忽略的 `output/test-fixtures/0.4.4/`，发布目录只保留一份真实APK。更新摘要为三行、59字符。
