# 听野视觉网关：Linux 原生部署

本文面向由负责人自行管理的 Linux 服务器。服务以 Python 和 systemd 运行，不需要 Docker。网关只调用外部视觉 API；服务端 ASR 关闭，语音识别由手机本地完成。安装脚本只安装服务文件、生成私有配置并执行 `systemctl daemon-reload`，不会启动服务或修改 Nginx。

## 服务器准备

建议 Ubuntu 22.04+ 或 Debian 12+。Python 3.10–3.13 均在目标范围内；在这些发行版上优先使用 Python 3.10、3.11 或 3.12，最终兼容性以安装验证为准。需要约 2 核 CPU、4 GB 内存作为小规模试运行起点；这不是容量或延迟测试结论。

仅在 Ubuntu/Debian 上，可安装基础工具：

```sh
sudo apt update
sudo apt install -y python3 python3-venv python3-pip openssl unzip nginx
```

RHEL 系发行版请安装对应的 Python、venv/pip、OpenSSL 和 Nginx 包；不要在这些系统上使用 `apt` 命令。若系统已有 Nginx，可跳过安装它。

如需指定其他已安装的 Python，可用 `sudo env SENSEFIELD_PYTHON=/usr/bin/python3.12 bash deploy/assistant/install-native.sh`。解释器及其标准库需在 `/usr` 或 `/opt` 等服务可读取的位置；用户主目录下的 pyenv/Python 会被服务隔离阻止。

将部署包解压或仓库检出到服务器后，从项目根目录运行安装器：

```sh
sudo bash deploy/assistant/install-native.sh
```

按提示输入 HTTPS 兼容上游地址、模型标识和上游 API Key。默认模型为 `qwen/qwen3.8-27b`。安装器创建专用非 root 用户 `sensefield-gateway`，在 `/opt/sensefield-assistant` 安装源码、虚拟环境和校验工具，并安装 systemd 单元。私有文件位置如下：

- `/etc/sensefield-assistant/gateway.json`：配置和凭据，权限 `0400`，所有者为 `sensefield-gateway`。
- `/etc/sensefield-assistant/体验连接码.txt`：体验连接码，权限 `0600`，所有者为 `root`。请逐人私下提供，不要放入代码仓库、APK、CDN 或公开文档。
- `/etc/sensefield-assistant/tls/backend.crt` 和 `backend.key`：网关后端 TLS 文件；私钥权限 `0400`，所有者为 `sensefield-gateway`。

systemd 服务通过 `ASSISTANT_GATEWAY_ENV_FILE` 指向 JSON 配置，由启动入口校验文件权限并载入配置；凭据不会写进 systemd 环境文件或日志。监听地址为 `127.0.0.1:18765`，ASR 为 `disabled`，单进程上游并发为 1。安装不下载模型权重，也不安装 PyTorch。已有安装不会被自动覆盖；重跑仅支持带有受管标记且服务已停止的受管目录，并保留已有配置和连接码。若初始化在生成 JSON 后中断，连接码文件缺失，安装器会停止并要求从现有私有配置恢复原连接码，不会自动轮换令牌。

## 配置 HTTPS 反向代理

部署负责人需自行将 `sf.888413.xyz` 指向服务器入口，并为公开域名配置有效的 HTTPS 证书。根据服务器证书位置审阅并手工合并 [`nginx.native.example.conf`](../../deploy/assistant/nginx.native.example.conf)；安装器不会更改 Nginx 配置。示例后端信任证书路径为 `/etc/sensefield-assistant/tls/backend.crt`，后端地址为 `https://127.0.0.1:18765`，并开启上游证书校验和 `localhost` 名称校验。保留 HTTPS，不要关闭校验或改成明文后端。示例的限流区指令须放在 Nginx `http` 上下文中。

手工调整 Nginx 后，检查配置并重载：

```sh
sudo nginx -t
sudo systemctl reload nginx
```

确认 DNS 和公网证书准备就绪后，启动网关：

```sh
sudo systemctl enable --now sensefield-assistant
sudo systemctl status sensefield-assistant --no-pager
sudo journalctl -u sensefield-assistant -n 80 --no-pager
```

systemd 将服务限制为最多使用 100% CPU、512 MiB 内存和 128 个任务，并启用 `PrivateTmp`、`ProtectHome`、`ProtectSystem=strict` 等隔离设置。应用通过专用非 root 用户运行。日志写入 journald；日志保留遵循主机已有策略，本部署说明不会更改全机日志设置。默认日志不记录请求正文。

## 验收

公网 DNS、HTTPS 反代和网关运行后，以下命令检查公开域名的 TLS、健康状态、认证和请求大小限制；默认不调用视觉模型：

```sh
sudo -u sensefield-gateway /opt/sensefield-assistant/.venv/bin/python \
  /opt/sensefield-assistant/verify.py \
  --config /etc/sensefield-assistant/gateway.json
```

也可直接检查网关本机 TLS 健康状态：

```sh
sudo -u sensefield-gateway /opt/sensefield-assistant/.venv/bin/python \
  /opt/sensefield-assistant/verify.py \
  --health-only \
  --base-url https://localhost:18765 \
  --ca /etc/sensefield-assistant/tls/backend.crt
```

如需确认真实视觉链路，可在第一条命令末尾追加 `--visual`。它会以合成图片调用上游一次，可能产生费用；不要使用真实游戏画面或个人数据做此项检查。健康检查和默认验收不产生视觉推理调用费用。检查失败时查看服务状态、journald、Nginx 日志和证书链，不要关闭 TLS 校验来绕过错误。

## 资源预期与上线顺序

2 核 4 GB 可作为低流量试运行规格：视觉模型在外部 API 运行，服务器不运行 ASR 模型或本地推理。systemd 的资源限制和单进程并发为 1 是起始保护措施，不能证明可承受的用户数、请求量或响应时间。试运行时观察进程 RSS、CPU、OOM 记录和上游响应耗时，再根据实际情况评估规格。Nginx 示例的请求限流按来源 IP 计算，共享公网 IP 的用户会共用额度。

可用 `sudo systemctl show sensefield-assistant -p MemoryCurrent -p CPUUsageNSec -p NRestarts -p Result` 查看内存、累计 CPU、重启次数和最近状态；上游与网关各阶段耗时记录在服务日志中。先确认服务器上的其他网站/服务仍有资源余量，再做两部手机的低频试用。本机配置升级并不能保证外部模型更快。

按当前交付顺序，负责人先自行部署并确认 `https://sf.888413.xyz/health` 与客户端链路就绪，再制作最终手机包。本轮没有连接测试或生产服务器；此前测试机操作与剩余状态见[10月4日交付记录](../../validation/ASSISTANT_SELF_DEPLOY_HANDOFF_2026-10-04.md)。

本地 Python 3.12 相关测试 150 项通过（1 条既有 Starlette 弃用警告），包含原生配置回环绑定、TLS 路径、私有连接码与拒绝覆盖检查；Shell/Python 语法和 diff 检查通过。本文尚未在原生 Linux、systemd 或 Nginx 环境运行验证，这些结果不代表生产部署或端到端验证。
