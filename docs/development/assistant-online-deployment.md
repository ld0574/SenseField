# Assistant 网关自部署说明

本文是自部署操作说明，不是上线报告。生产主机尚未连接或部署，DNS、证书、反向代理和模型请求均未在生产环境验证；请由部署负责人在自己的主机上完成以下步骤。

**推荐先按[直接启动部署说明](../../deploy/assistant/README.md)运行；无需 Docker。** 如需系统服务管理，可用[可选 systemd 部署说明](assistant-native-deployment.md)。 当前是轻量视觉网关，不在服务器运行 ASR 或 VLM；2 核 4GB 可作为少量体验者的试运行起点，并不代表并发或延迟已通过实测。下文保留 Docker 路线，原生路线无需安装 Docker。

目标是使用 Docker Compose、Python 3.12 和轻量 `assistant-vision-gateway` 依赖运行 Qwen 视觉网关。服务使用 `vision_only` 模式，服务端 ASR 关闭，监听只发布在主机 `127.0.0.1:18765`。用户手机以 `https://sf.888413.xyz` 连接已有 HTTPS 反向代理；反代再通过经过严格证书校验的 HTTPS 连接本机网关。

## 准备配置

配置由初始化工具交互生成，不必手工编辑 JSON。工具会提示输入 HTTPS 兼容 API 地址、模型名（默认 `qwen/qwen3.8-27b`）和不回显的上游 API Key；它随机生成两枚设备令牌，并分别保存在配置文件和仅所有者可读的连接码文件。配置位于 `deploy/assistant/runtime/gateway.json`，体验码位于 `deploy/assistant/runtime/体验连接码.txt`。初始化后配置权限为 `0400`，连接码权限为 `0600`；配置和 TLS 目录以只读卷挂入容器，连接码文件不会挂入。不得将它们放进 Git、镜像、Docker build args、镜像 metadata、手机包或日志。不要将连接码文件上传或公开。

配置包含以下环境变量（值均为字符串）：

```json
{
  "ASSISTANT_GATEWAY_ASR_BACKEND": "disabled",
  "ASSISTANT_GATEWAY_ACCOUNT_CONCURRENCY": "1",
  "ASSISTANT_GATEWAY_DEVICE_TOKENS": "<由初始化工具生成的两枚逗号分隔令牌>",
  "ASSISTANT_GATEWAY_VISION_PROVIDER": "compatible",
  "ASSISTANT_GATEWAY_VISION_BASE_URL": "<上游提供商的 HTTPS OpenAI-compatible base URL>",
  "ASSISTANT_GATEWAY_VISION_MODEL": "qwen/qwen3.8-27b",
  "ASSISTANT_GATEWAY_VISION_API_KEY": "<上游提供商密钥>",
  "ASSISTANT_GATEWAY_VISION_MAX_TOKENS": "256",
  "ASSISTANT_GATEWAY_HOST": "0.0.0.0",
  "ASSISTANT_GATEWAY_PORT": "18765",
  "ASSISTANT_GATEWAY_TLS_CERT": "/run/tls/backend.crt",
  "ASSISTANT_GATEWAY_TLS_KEY": "/run/tls/backend.key"
}
```

请从所选上游的私有控制台取得兼容 API 地址和服务端密钥，并确认账户允许该模型。地址必须是无用户名、密码、查询参数或片段的 HTTPS URL。初始化工具生成的两枚设备令牌分别交给两名体验者，通过负责人认可的私下渠道分发；需要更多设备时应另外生成令牌，不要共享已有令牌。不得把令牌写进本文、命令历史、应用源码或构建产物。配置中不设置 `ASSISTANT_GATEWAY_TEST_TEXT_LOG_DIR`，原文追踪因此保持关闭。`ACCOUNT_CONCURRENCY=1` 只限制单进程同时执行的上游调用，并不构成 QPS/突发限流。

## 构建和启动

从仓库根目录在生产主机执行：

```sh
sudo bash deploy/assistant/prepare.sh
docker compose -f deploy/assistant/compose.yaml up -d --build
docker compose -f deploy/assistant/compose.yaml ps
```

初始化脚本在需要时创建有效期一年的后端自签名 TLS 证书和私钥，但不会启动网关、修改反代、配置 DNS 或签发公网证书。Compose 使用 Python 3.12 轻量视觉网关镜像，容器只读运行并移除 Linux capabilities，限制为 1 CPU、512 MiB 内存、128 个进程；ASR 后端关闭，不装服务器 ASR 模型或 PyTorch。配置文件与 TLS 目录以只读卷挂入，JSON 配置不会作为镜像构建输入。Compose 将服务只发布在主机 `127.0.0.1:18765`，不要把该端口直接映射到公网网卡。容器 JSON 日志限制为每个文件 5 MB、最多保留 3 个文件；这不替代对其他主机和反代日志的审查。

## DNS、TLS 和反向代理

由部署负责人将 `sf.888413.xyz` 的 DNS 指向目标入口，并为该域名配置有效的公网 HTTPS 证书。复用已有 HTTPS 反代时，可参考 [`deploy/assistant/nginx.example.conf`](../../deploy/assistant/nginx.example.conf)，按本机证书、私有 CA 和网关后端证书路径调整；示例中的两个 `limit_*_zone` 指令要放在 Nginx `http` 上下文中。示例按来源 IP 限制 `/v1/visual` 到 30 次/分钟、允许突发 3 次并最多同时 3 个连接；共享公网 IP 的用户会共用额度。

反代到网关时必须使用 `https://127.0.0.1:18765`，并开启上游证书校验。反代须信任签发网关证书的 CA、发送正确的 SNI/证书主机名，并校验证书名称和有效期；不可使用 `proxy_ssl_verify off`、跳过校验的客户端或明文 HTTP 后端。网关自身也以生产 TLS 模式运行。客户端的 HTTPS 由手机系统校验；测试 CA 不应装入正式手机包。

## 部署后验收

以下是负责人上线后自行执行的验收标准。Compose 与反代示例从未在生产运行；相关配置只做过语法和本地测试验证。以下命令没有由本文作者对生产执行。

1. 通过系统信任链验证公开 HTTPS 与健康状态：

   ```sh
   curl --fail --silent --show-error https://sf.888413.xyz/health
   ```

   预期 HTTP 200，JSON 为 `mode=production`、`status=vision_only`、`asr_backend=disabled`、`asr_ready=false`、`vision_ready=true`，并显示模型 `qwen/qwen3.8-27b`。health 不应包含设备令牌或上游 API key。

2. 在网关容器内执行正式域名安全检查：

   ```sh
   docker compose -f deploy/assistant/compose.yaml exec gateway python /app/deploy/assistant/verify.py
   ```

   工具默认检查 `https://sf.888413.xyz` 的证书、健康状态、未认证请求 HTTP 401、无效请求体 HTTP 422，以及超过网关上限（约 2.17 MB）的请求 HTTP 413；默认不请求视觉模型，不产生视觉推理费用。反代示例的请求体上限为 3 MB，高于网关上限。

3. 需要验证真实视觉链路时，可追加一次合成读图：

   ```sh
   docker compose -f deploy/assistant/compose.yaml exec gateway python /app/deploy/assistant/verify.py --visual
   ```

   此命令使用测试 JPEG 调用已配置的视觉上游一次；预期 HTTP 200 且回答读出 `SYNTHETIC GATEWAY TEST`。只发送合成内容，不上传真实游戏画面或个人数据。该请求可能产生费用并遵循上游数据处理条款。它不会因失败自动切换模型或重试到备用提供商。命令会打印验收摘要与往返耗时，不打印凭据。

也可单独核对容器内网关的本地 TLS health：

```sh
docker compose -f deploy/assistant/compose.yaml exec gateway python /app/deploy/assistant/verify.py \
  --health-only --base-url https://localhost:18765 --ca /run/tls/backend.crt
```

验收任何失败都应先检查容器日志、TLS 链与反代配置；不要关闭证书校验来绕过错误。Nginx 示例提供按 IP 限速和并发连接限制，但应用本身没有设备级或跨实例全局 QPS 限流。日志保留与轮转由主机负责人设置，并应按实际数据保留要求审查反代、Docker 和主机日志。

## 手机连接与未部署状态

按用户最新顺序，先交付本服务端部署包，由负责人部署；服务就绪后再生成对应配置的最终手机包，继续使用 `0.4.1/code18`。此前带默认地址 `https://sf.888413.xyz` 的本地预构建仅保留在工作区，不作为本轮 APK 交付，也未安装或验证生产端到端。源码不会自动覆盖现有用户配置。两枚体验连接码由初始化工具保存在主机私有文件中，由负责人逐人私下发送，不应写入文档、代码仓库、CDN 或公开渠道。

测试机上此前启动过名为 `sensefield-assistant-041` 的独立测试容器，只绑定该机回环地址 `127.0.0.1:18765`。历史检查中，health 返回 HTTP 200 和 `vision_only`，未认证请求返回 401；提交空 JSON `{}` 得到 422，这是当前 schema 校验结果。临时 smoke 脚本错误地预期 400，因此在此处退出；没有继续检查 413，也没有做后续或付费视觉请求。这不代表全流程验收通过。

用户已要求不要再连接测试机；其后没有再次连接或停止容器。若要停止该测试容器，需由测试机上的用户自行执行：

```sh
docker stop sensefield-assistant-041
```

该命令尚未由本文作者执行。不要据此认为测试容器已自动回滚、生产服务已启动或公网域名可用。
