# Assistant Gateway Linux 合成部署测试

本文说明如何复现隔离的 Linux 网关合成冒烟测试。网关只绑定回环地址，通过 SSH direct-tcpip 隧道访问；测试使用经校验的自签名 TLS 证书、固定版本的 CPU Paraformer、独立设备令牌，以及服务端 Zhipu API Key 完成一次合成 GLM 请求。

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
