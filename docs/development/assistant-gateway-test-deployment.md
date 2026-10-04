# Assistant Gateway Linux 合成部署测试

本文记录历史 Linux 网关合成冒烟测试及当前后续验证边界。历史服务只绑定回环地址，通过 SSH direct-tcpip 隧道访问；当时使用经校验的自签名 TLS 证书、固定版本的 CPU Paraformer、独立设备令牌，以及服务端 Zhipu API Key 完成合成 GLM 请求。后续源码新增了可显式选择的 ASR 后端，但不代表该后端已部署在历史 Linux 主机。

2026-10-04 的后续修订增加服务端视觉提供商选择。`ASSISTANT_GATEWAY_VISION_PROVIDER=zhipu` 使用智谱原生接口，模型由 `ASSISTANT_GATEWAY_GLM_MODEL` 指定，默认仍是 `glm-4.6v-flash`。compatible API 在私有服务端环境中配置 `ASSISTANT_GATEWAY_VISION_BASE_URL`、`ASSISTANT_GATEWAY_VISION_MODEL`、`ASSISTANT_GATEWAY_VISION_API_KEY`。按用户先 MiniMax、再千问的顺序测试后，当前明确选择上游 `/models` 返回的 `qwen/qwen3.8-27b`：合成选项图通过完整 TLS 网关，单次往返 1350 ms。MiniMax-M3 在本轮返回 HTTP 200 空正文或耗尽输出预算，不等同于 429 限流；此前无 namespace 的千问单次 503 保留为历史，不能仅凭新结果确定其全部原因。该接口图片使用 JPEG data URL，智谱接口仍使用原始 Base64。APK 不包含供应商密钥，客户端无需更换模型专用安装包。模型/HTTPS 地址在启动前校验，`/health` 仅显示模型名称等安全状态；没有自动回退或切换提供商。详见[本轮记录](../../validation/ASSISTANT_LIVE_0.4.1_2026-10-04.md)。

compatible API 可配置 `ASSISTANT_GATEWAY_VISION_MAX_TOKENS`，范围 64–1024，默认与当前值均为 256；智谱原生路径仍固定 256。仅 exact `MiniMax-M3` 使用其[官方支持](https://platform.minimax.io/docs/api-reference/text-chat-openai)的 `thinking: {"type":"disabled"}`，其他模型不继承该扩展。网关跟踪客户端断连并取消上游调用；同会话、generation 不旧的手动请求可先取消主动请求，等待其释放后执行，避免网关调用重叠。释放等待上限 2 秒，之后的模型调用上限 8 秒；等待超时返回 busy 并保留旧占用。不能据此保证供应商内部计算已停止。取消、优先级与所有权测试通过，手机实际发声和热/负载仍待验证。

取消清理依赖 provider coroutine 响应取消；若自定义 adapter 忽略取消，断连清理会继续等待并保留会话占用，不能把 2 秒手动交接上限当作所有清理操作的硬上限。当前真实客户端使用可取消的 httpx 流。

## 准备隔离主机

使用 Linux x86_64、Python 3.10–3.13，并为固定模型快照和 CPU PyTorch wheel 预留足够磁盘空间。本次主机使用 Python 3.11.13 和 `torch==2.8.0+cpu`，无 CUDA 和 NVIDIA 软件包。若 CPU wheel 已放入私有 wheelhouse，可这样安装网关依赖：

```sh
python3.11 -m venv .venv
ASSISTANT_GATEWAY_WHEELHOUSE=/path/to/wheelhouse \
PYTHON=.venv/bin/python \
  scripts/assistant_gateway_install_deps.sh
```

Linux 安装器选择 CPU 版 PyTorch。若包索引无法解析该 wheel，`ASSISTANT_GATEWAY_WHEELHOUSE` 可让安装器使用预先准备的 wheel。

将 `funasr/paraformer-zh-streaming` 的固定 revision `fd2af606b37d7fb8b3b8a218c5be5b07b53ef6ba` 放入 Hugging Face 缓存布局：

```text
<model-cache>/models--funasr--paraformer-zh-streaming/snapshots/fd2af606b37d7fb8b3b8a218c5be5b07b53ef6ba/
```

测试快照包含 `model.pt`、`LICENSE`、`README.md`、`config.yaml`、`configuration.json`、`tokens.json`、`am.mvn` 和 `seg_dict`。启动时会校验清单中固定文件的摘要。需在 Python 导入网关前设置 `HF_HUB_OFFLINE=1`，使进程只使用本地固定快照，不回退访问 Hugging Face Hub。

## 分开放置凭据和 TLS 文件

将网关环境保存到仅所有者可读的文件，例如 `/etc/mapassist/assistant-gateway-test.env`，并设置权限 `0600`。设备令牌应新生成，且与 Zhipu Key 分开：

```dotenv
ASSISTANT_GATEWAY_DEVICE_TOKEN=<fresh-random-device-token>
ASSISTANT_GATEWAY_MODEL_CACHE=/var/lib/mapassist/models
ASSISTANT_GATEWAY_ACCOUNT_CONCURRENCY=1
ASSISTANT_GATEWAY_PORT=18765
ZHIPU_API_KEY=<server-side-zhipu-api-key>
```

另将设备令牌单独保存到权限为 `0600` 的文件，供合成测试客户端读取。不要把任何凭据放入命令参数、终端输出、instrumentation 参数、APK、Git、报告或文档。生成 SAN 覆盖测试访问地址的 TLS 证书；私钥权限设为 `0600`，日志使用 `UMask=0077` 限制访问。

每次复现都应新生成 TLS 证书和匹配的私钥，并生成新的设备令牌。以下命令仅为本地回环测试示例：

```sh
mkdir -p output/assistant/0.4.0/server-test
chmod 700 output/assistant/0.4.0/server-test
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
  -keyout output/assistant/0.4.0/server-test/gateway-test-key.pem \
  -out output/assistant/0.4.0/server-test/gateway-test-cert.pem
chmod 600 output/assistant/0.4.0/server-test/gateway-test-key.pem
```

启动生产模式网关时启用离线模型加载、TLS 和关闭访问日志，并且只监听回环地址。例如 systemd unit 可设置 `EnvironmentFile=/etc/mapassist/assistant-gateway-test.env`、`Environment=HF_HUB_OFFLINE=1` 和 `UMask=0077`，再运行：

```ini
ExecStart=/opt/mapassist/.venv/bin/mapassist-assistant-gateway --host 127.0.0.1 --port 18765 --tls-cert /etc/mapassist/tls/test-cert.pem --tls-key /etc/mapassist/tls/test-key.pem
```

测试客户端可通过 SSH 隧道访问：

```sh
ssh -N -L 127.0.0.1:18765:127.0.0.1:18765 <ssh-user>@<test-host>
```

使用信任测试证书的 TLS context，并连接证书 SAN 覆盖的地址。不要关闭证书校验。依次检查 `/health`、确认无 bearer token 的视觉请求被拒绝、向 `/v1/visual` 发送合成图像，并通过 WSS 向 `/v1/audio` 流式传输批准的合成 PCM。复现时使用当前有效的 SSH 凭据。

## 2026-10-04 测试结果

Linux 网关通过校验过的 HTTPS 返回生产模式健康状态，对未认证的视觉请求返回 `401`，并通过真实 `glm-4.6v-flash` 后端读出了合成标签，类型为 `ui_text` 且 `uncertain=false`。本次单点中，GLM 网关调用耗时 **1,804 ms**，经过 SSH 隧道的 HTTPS 请求往返耗时 **1,868 ms**。

真实 CPU Paraformer 通过 WSS 识别了离线 Tingting 合成语音，最终文本为 `请读出当前比分`。输入为 56,636 字节、16 kHz、单声道、signed 16-bit PCM，时长 **1,770 ms**。ASR 推理耗时 **313 ms**，从 speech-end 到 finalization 为 **199 ms**，从 speech-start 到 final 为 **1,982 ms**。最后一项包含 1,770 ms 的输入播放时长，不代表额外 1,982 ms 的推理。

更早一次 Mac 直接调用真实 GLM 的合成 HUD API smoke 记录为 **1,089 ms** 单点。它与本次 Linux 网关路径和采样条件不同，不应直接比较，也不是 Android 到 Mac 的传输耗时。Android 正式客户端的 transport 测试另见 [assistant-android-transport-test.md](assistant-android-transport-test.md)；该测试通过，但没有报告 1,089 ms 这个计时。上述单点都不支持 P95 或服务级别承诺。

Linux 客户端首次运行完成 HTTPS 和 GLM 检查后，本地 harness 在建立 ASR WebSocket 前因 `AttributeError` 退出。这是客户端 import 问题，不是网关故障。修复 import 后，合成 WSS ASR 测试通过。两次尝试都关闭了测试监听并撤销了临时设备令牌。

本次只测试了经 SSH 隧道访问的 Linux 网关自身，没有测试 Android 应用连接 Linux 网关、Android 麦克风、真实游戏截图或物理音频播放；图像与语音均为合成输入。

脱敏结果保存在被 Git 忽略的 `output/assistant/0.4.0/server-test/server-test-result.json`。测试结束后已停止网关、关闭监听、撤销远端测试令牌，并删除远端 TLS 证书、私钥和 PCM 副本。远端原有 Zhipu 环境文件和测试日志可保留在权限 `0600` 下；访问日志已关闭。本地只保留测试公钥证书，私钥已删除。下次复现需重新生成匹配的证书和私钥，并重新生成设备令牌；完成测试后删除私钥和令牌，不长期保留。

## 手机端 ASR 安装验证、服务器比较实验与主机快照（2026-10-04）

当前部署使用下面的 `vision_only` 方式。此前章节中的 PyTorch、Paraformer、模型快照和 WSS 命令是0.4.0历史服务端比较流程，不是当前手机离线识别的服务器安装要求。

### 当前轻量部署与 Android 资产准备

在项目根目录创建 Python 3.10–3.13 的虚拟环境，安装轻量网关依赖：

```sh
python3 -m venv .venv
PYTHON=.venv/bin/python bash scripts/assistant_gateway_install_deps.sh --vision-only
```

服务环境文件必须设置 `ASSISTANT_GATEWAY_ASR_BACKEND=disabled`，再按现有方式配置设备令牌、视觉提供商和TLS。该模式不初始化ASR，`/v1/audio`关闭；配置好的 `/v1/visual` 仍经认证可用，health报告 `vision_only` / `asr_ready=false`。轻量extra不安装PyTorch、FunASR、Hugging Face或模型缓存；旧CPU对照安装方式仅在显式需要比较时使用。CLI mock参数测试覆盖轻量路径、旧Linux路径与非法参数，没有实际执行包安装或远端迁移。

Android大模型不入Git。下列脚本按 `sensevoice.metadata.json` 的完整revision、大小与SHA获取官方公开文件，只在主动执行时下载；构建本身不会隐式下载ASR权重。已有文件可传 `--source-dir`，本地源缺失或校验失败时不回退网络。

```sh
python3 scripts/prepare_android_asr_assets.py
python3 scripts/prepare_android_asr_assets.py --check-only
```

模型许可与runtime许可在APK的 `assets/sensevoice/` 中；构建校验拒绝半套或不匹配的资产，启动再次核验并复制至不备份目录。缺失模型时语音明确不可用，既有本地预警继续，不会转到服务器ASR。

当前约214MB APK超过旧更新器100MiB上限；后续候选上限修为256MiB，并保留流式大小、SHA、同源HTTPS、包名和签名校验。旧客户端首次进入此候选需人工覆盖安装；尚未制作过渡包或上传CDN。已装手机包与用户离开后的源码候选分别记录，不宣称新版上限已在手机中生效。

HOT目前停止VAD/ASR处理并废弃结果，但AudioRecord仍读入并丢弃帧；没有宣称释放麦克风或零采音负载。真实麦克风、游戏开麦让路、物理时延及热/负载门禁仍待实测。后续候选修复了native失败与取消并发时引擎被释放却仍显示加载中的状态，失败会独立通知会话，不回传旧文本。

历史服务器 ASR 对照代码包含 CPU Python Paraformer；此前显式 `ASSISTANT_GATEWAY_ASR_BACKEND=sensevoice_int8` 与 `ASSISTANT_GATEWAY_SENSEVOICE_MODEL_DIR` 可选 CPU SenseVoiceSmall int8 ONNX。两个比较后端都使用连续监听/VAD 分段，在完整语音段结束后识别一次，不输出 partial；SenseVoice 使用 `sherpa-onnx==1.13.8`，manifest 固定约 239 MB 的 `model.int8.onnx` 与 tokens 文件。FunAudioLLM/Alibaba SenseVoiceSmall 的模型许可是 [FunASR Model Open Source License Agreement 1.1](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)，不是 Apache-2.0。Paraformer/SenseVoice server-cache 只属于历史服务端对照安排；新的本地候选允许模型 asset 随 APK 分发，但二进制不入 Git，需随包注明 FunASR Model License 1.1、Sherpa Apache-2.0、ONNX Runtime MIT。网关代码为 MIT。

已安装的 APK 为 Android 10+ 手机端 ASR：bundled SenseVoiceSmall int8 作为约 239 MB APK asset，本地复制并校验哈希；JNI sherpa-onnx 1.13.8 + ONNX Runtime 1.28.2 单线程 CPU 运行，不依赖系统 on-device service。音频不联网、不落盘；只有用户问画面时才把文本问题和授权截图送到视觉网关。取消时保留单 slot 至 native 退出，设备 HOT 时暂停输入，无云端 fallback。该 APK 已无线安装至小米 Android 14，版本 `0.4.1/code18`，大小 214121822 bytes，SHA-256 `4218bf94b03153e48e9e313fd28900139c14c0bba000e534289cd4f799df5759`。服务端 Paraformer/SenseVoice 数据只作为比较实验，不代表手机端效果。

Mac 上对同一段 1,769 ms 合成 PCM 的单点记录为：旧协议 Paraformer（4 threads）speech-end finalization 2,324 ms；新协议记录 `inference_ms=185 ms`。SenseVoice 对 score、build、draft 三类合成样本的直接识别耗时分别为 85、139、145 ms，预设关键词判断通过。服务器 ASR 合成 WSS 对照的 speech-end 至 final 为 274 ms 单点，这些服务端数值都来自合成输入。手机端 instrumentation 的纯合成 PCM 测试新增 1 项，比分、出装、选人三例分别为 170、294、352 ms，关键词和 `isRequest` 断言通过。此测试不是物理麦克风采音、真实发声、P95 或温升测量；直接 recognizer inference、WSS speech-end-to-final、instrumentation 场景耗时和实际客户端端到端也不是同一计时范围。历史 Linux Paraformer WSS 的 199 ms finalization / 313 ms inference 仍是较早服务记录。

在 2026-10-04 的一次只读检查中，所提供的 Linux 主机 SSH 可达，显示 8 个在线逻辑 CPU、AMD Ryzen 9 5900X；当时预期环境文件、`/opt/mapassist` 项目与 Python runtime、固定 ASR 模型缓存均不存在，回环服务端口拒绝连接。该次检查没有发起 HTTPS/WSS ASR，也没有运行直接推理，是有明确时间点的主机快照。此后网关已设 `ASSISTANT_GATEWAY_ASR_BACKEND=disabled` 并重启；健康检查 HTTP 200，报告 `vision_only` / `asr_ready=false`，服务器 ASR 已停用且不加载模型，视觉 Qwen 配置未变。此前已安装的 `0.4.1/code18` APK 对应构建回归通过 225 项 JVM、10 项 instrumentation、103 项 Python 网关测试，以及 Debug/Test APK 构建和 lint；ASR instrumentation 为上述纯合成 PCM 用例。真实麦克风、物理发声、P95 和温升仍未验证。
