# AI助手默认关闭修订

日期：2026-10-06。基于main合并消消乐后的`5fade68`，保持0.4.1/code18及原测试签名。

## 用户反馈与选择

玩家反馈助手回应慢、回答偏长且常未完整播完。用户要求先暂停大模型增强，默认关闭，仅在设置中保留手动开启入口。此选择不代表响应速度、回答质量或实际可听性已修复，不据此恢复符合度分数。

## 实现

- 新增`assistant_enabled`总开关，默认false。新安装与旧测试设置升级都默认停用助手；旧`assistant_voice`、`assistant_vision`、`assistant_proactive`与授权记录保留，但不能绕过总开关。用户主动开启后持久保存选择，不在每次启动时重置。
- 设置页关闭状态只显示总开关及简短实验说明，语音资源、画面理解、主动描述与助手悬浮点授权按开启状态展开；本地呈现选项独立保留。
- `AssistantSettings`统一控制有效语音、画面和主动描述能力。既有`CaptureService`启动门控因此不创建默认关闭的助手，不启动助手麦克风前台类型或助手录音。`AssistantController.start()`另有禁用保护。
- 语音资源准备和取消也检查总开关及语音授权；打开旧设置页不会因历史语音开关为true而自动下载。运行中关闭总开关会作废轮次、取消助手播放和请求、清理近期画面并关闭录音/ASR/小圆点。重新开启需重新开始辅助。
- README、设置帮助、0.4.1说明与《符合度改造方案》同步说明实验状态及玩家报告的不足。服务端、模型选择与回答策略没有修改。

## 验证

- 串行Gradle：JVM322项，失败/错误/跳过均0；Debug/Test APK构建与lint通过。lint为0错误、34警告，未将警告数当作功能验收。
- API34/arm64模拟器：`AssistantDefaultServiceInstrumentedTest`5项与`AssistantControlsInstrumentedTest`25项，共30项通过。覆盖旧模式不能自动启用、手动选择持久化、总开关不能绕过分项授权、旧设置页不自动准备资源、控件展开/收起、关闭后的旧回复失效与重新开启须新会话，以及既有取消/过期/局内命令回归。
- APK签名与原手机测试候选一致，包名/0.4.1/code18、最低API29/target35、单一arm64 ABI、生产服务/CDN常量、总开关字符串、ZIP CRC和ASR权重排除检查通过。配套清单大小与SHA-256对应APK。
- 原始构建与instrumentation记录：`output/assistant/0.4.1/opt-in-default-2026-10-06/`。这批为工程及模拟器验证，未进行患者对局、蓝牙实际发声、真实模型延迟或受控温升复测。

## 交付

- APK：`output/releases/0.4.1/cdn-upload/sensefieldv0.4.1.apk`
- 清单：`output/releases/0.4.1/cdn-upload/latest.json`
- 大小：49,490,357 bytes，约47.20 MiB。
- SHA-256：`f619044159b71487d167a3cc052d430058b1562e3d2bddd410502d43b7d740cc`
- 证书SHA-256：`5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`
- 中文APK、玩家使用说明与两件套ZIP已同步；本轮制品与handoff位于`apk-assistant-default-off-2026-10-06/`，上一批配套文件留档至`archive-before-assistant-default-off-2026-10-06/`。模型ZIP保留原文件。

本轮未安装用户手机、上传CDN、连接生产主机、push或发布Release。由用户按APK先、latest.json后的顺序手动覆盖并刷新缓存。整体`verified`／`release_ready`及评分保持原值。
