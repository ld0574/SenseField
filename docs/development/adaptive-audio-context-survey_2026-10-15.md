# AAC（自适应音频上下文）任务 0 代码库梳理

> 本文件是《Adaptive Audio Context / 环境底噪自适应调节》方案（v1.1 储备）的任务 0 调研结论，
> 从 PROGRESS.md 中单独摘出，便于后续启动时直接引用、避免重复调研。
> 配套阻塞理由与接口预留见仓库根目录 `BLOCKED.md` 第 6 节。

用户提交该方案，要求作 v1.1 储备并做接口预留。**先梳理后结论**——实测发现该方案的技术主张大部分已在仓库实现。

### 逐条核对（AAC 主张 → 仓库实际）
| AAC 主张 | 仓库实际 | 判定 |
|---|---|---|
| RMS 能量特征提取 | `VoiceActivityGate.accept()` 已算 rms | **已实现** |
| 噪声底噪自适应 | `VoiceActivityGate`：`noise=80` 初值 + EMA 更新 `noise*.98+rms*.02`，阈值 `max(350, noise*3.2)` | **已实现** |
| 三档滞后比较器防抖 | `VoiceActivityGate`：进入需 `voicedFrames>=15`（150ms）、退出需 `quietFrames>=45`（450ms） | **已实现（二档）** |
| 缓冲区 ~23ms @44.1kHz | 实际 **16000 Hz**，10ms/帧（`samples.length==160` 硬校验） | **方案参数错** |
| `VOICE_COMMUNICATION` 音源 | 实际 `VOICE_RECOGNITION` | **与现况不同** |
| 不生成音频文件/不留历史 | `VoiceInputMetrics` 注释明写 "never retains microphone samples"；pre-roll 有界 30 帧 | **已实现** |
| RECORD_AUDIO 高危权限新增成本 | 已在 `AndroidManifest.xml`，助手语音输入已在使用 | **已落地，非新增** |
| 默认关闭+二次确认 | `AssistantSettings.ENABLED="assistant_enabled"`；已有 ASSISTANT_DEFAULT_OFF validation 记录 | **已有先例** |
| 音量增益曲线 | `CuePlayer.volume()` 读 `GameProfile` 的 `volume` 键（默认 45%）→ `track.setVolume` | **有挂点，未自适应** |
| 高频增强 1k–4kHz +3dB | 全仓无频谱/EQ 代码 | **真新增** |
| 触觉兜底降级 | `HapticSemantics`（本轮新建，旁挂未接入） | **半成品，可作接缝** |
| Toast 环境提示 | 仓库已有 Toast 用法先例（`CaptureService` 等） | **可做** |

### 结论：AAC 真正的缺口只有「输出映射层」
感知层（RMS + 噪声底噪 + 滞后防抖 + 隐私边界）**已在 `VoiceActivityGate` 生产运行**，
且被 `AssistantVoiceInput` 实际 import（非死代码）。AAC 要新增的是：
把已有的噪声底噪读数 → 驱动音量增益曲线 + 触觉兜底降级 + Toast 提示。

**原 BLOCKED 三条理由中有两条已不成立**：权限已在用、隐私边界已保证。
仍成立的只有"阈值需真实场景数据校准"——且噪声底噪跟踪本身已在生产运行，校准对象
应从"从零定阈值"改为"复用已有 EMA 定档位边界"。

### 两项接口预留的实际情况（与原方案不符，未擅动）
1. **`noise_fallback` 字段无处可加**：全仓**没有用户模板 Schema**（已实测，0 命中），
   "任务 1 的 Schema"不存在。已转为 BLOCKED 里的设计约束记录。
2. **`trigger_source` 不能加在 `HapticPolicy.create()`**：该签名被 `HapticPolicyTest` 冻结，
   改它必破坏存量测试（任务书十红线亦禁止）。可选的干净接缝是旁挂的 `HapticSemantics`
   （纯 JVM、未接入任何播放路径）——是否在此加，待领导定。

### 遗留规格缺口（复用 VoiceActivityGate 的前置风险）
`VoiceInputMetrics` 有单测，但 **`VoiceActivityGate` 没有单测**。AAC 要复用它的噪声底噪
读数，应先补测，否则复用的是一个无回归保护的生产组件。
