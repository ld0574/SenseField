# 第二台手机语音输入未进入识别：0.4.7

2026-10-15，用户反馈王者对局中无法识别人声。无线 ADB 配对并连接第二台手机后，先保存系统日志和音频状态，再用同签名诊断辅助包导出最近三份已结束会话。输入在耳机路由检查处暂停，未进入本地 ASR；当前材料不能作为识别准确率或模型性能故障的证据。

## 包与记录

第二台手机型号 V2282A，安装 0.4.7／code24，手机端 APK SHA-256 为 `9a00dc7343d0d168413cae1087faedbd7a6fe962f6c66c1e00161135619e85fb`，是上一轮毒液适配包。没有覆盖主程序；诊断辅助包仅在辅助服务已停止后运行，原始会话记录保留。

| 会话时长 | 处理帧／诊断图 | ASR START／FINAL／INPUT_AUDIO | 手动读图结果 |
| --- | --- | --- | --- |
| 2,220,248ms | 1,740／30 | 0／0／0 | 3 次返回，2,057／2,130／4,749ms |
| 33,896ms | 153／24 | 0／0／0 | 1 次返回，3,863ms |
| 269,145ms | 632／42 | 0／0／0 | 1 次返回，2,854ms |

三份会话均为王者辅助会话，均正常记录结束、0 丢弃事件；会话不等于独立完整游戏局数。读图耗时为客户端结果日志的 `elapsedMs`，不包含说话、ASR 或全部播报等待。收到结果不能证明答案正确或完整发声：最新会话的助手播报有一次 STARTED 后 FAILED，中间会话有一次 STARTED 后 CANCELLED，仍需单独核对播报终态。

## 语音没有进入 ASR 的原因

三份诊断起始均记录 `AssistantVoice event=INPUT_STATE flags=8 routedDeviceType=2`，随后 `audio_input_route_unverified`。类型 2 是内置扬声器，标志 8 是耳机路由未核验；现有连续语音路径只在核验耳机路由、麦克风未被系统静音、无其他录音会话时将声音交给 VAD 和 ASR。三份记录均未观察到 READY 路由状态或识别 START，因此没有可供判断识别对错的 FINAL。

最新会话另在 81,076～82,922ms 记录 `8→10→14→12→8`：扬声器路由限制持续存在，其间叠加约 1.85 秒的系统静音／其他录音会话。不能认定其他录音会话造成整局无识别，也不能据这些标志确定是哪一个应用占用了麦克风。导出前系统显示主用户已授权 RECORD_AUDIO，并有允许的麦克风使用记录；使用记录不表示声音已进入 ASR。

代码对应 [VoiceInputSafetyPolicy.java](../../android/app/src/main/java/com/openkhub/sensefield/VoiceInputSafetyPolicy.java)、[AssistantAudioRouteProbe.java](../../android/app/src/main/java/com/openkhub/sensefield/AssistantAudioRouteProbe.java) 和 [AssistantVoiceInput.java](../../android/app/src/main/java/com/openkhub/sensefield/AssistantVoiceInput.java)。路由探测来自应用自己的静音游戏音轨，不将“有蓝牙设备连接”直接当作当前声音已走耳机。

## 后续修订方向与边界

先让语音入口持续、明确显示暂停原因及恢复状态，避免用户把尚未开放的输入当作模型识别失败；若要支持外放问答，应设计显式的一次性说话入口及回声、播报打断处理，再验证实际录音与识别链路。直接放开连续外放可能把游戏声音和助手播报重新识别为用户问题，不能用取消检查代替录音验证。

本次完成取证与定位，没有改动语音策略、切换模型或安装新主 APK。三份诊断没有保存麦克风原音与识别原文；本次没有要求用户重玩，也没有开启新的录音或上传原始资料。

私有证据目录：`validation/private/assistant-second-phone-20261015-180749/`。手机与本机逐包校验的归档 SHA-256：

- 最长会话：`9d62e50d9bdbf542be8404a06d4788e81f6c31886868ff6c087cd82f21be3dfd`。
- 中间会话：`6b7ebb376595bd04d7b1c0526292823d9b129cf8974c7b6f6408a79309588fec`。
- 最新会话：`23f559494e32e57085abbb71de95ea09054f34a258585dbd6c99faf5448cfb1b`。
