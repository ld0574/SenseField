# 听野视觉网关：Linux 原生部署

本文面向由负责人自行管理的 Linux 服务器。网关以 Python 和 systemd 运行，不需要 Docker。服务端 ASR 关闭，语音识别由手机本地完成。安装脚本会安装服务文件、准备私有配置并执行 `systemctl daemon-reload`；首次安装还会用 pip 安装 Python 依赖，因此需要访问所配置的 Python 包仓库。它不会启动服务或修改 Nginx，也不会对公网或生产服务执行连接验收。

默认传输模式是 `local-proxy`：Nginx 使用已有的公网 HTTPS 证书接收客户端连接，再经 HTTP 转发到仅监听 `127.0.0.1:18765` 的网关。公网证书终止于 Nginx；这种模式不创建或读取 `backend.crt`、`backend.key`。需要网关自身在回环连接上提供 TLS 时，可显式选择 `backend-tls`，由安装器创建自签名后端证书，并在 Nginx 中启用严格校验。两种模式都保留公网 HTTPS。

## 服务器准备

建议 Ubuntu 22.04+ 或 Debian 12+。Python 3.10–3.13 均在目标范围内；这些发行版可安装基础工具：

```sh
sudo apt update
sudo apt install -y python3 python3-venv python3-pip openssl unzip nginx
```

RHEL 系发行版请安装对应的 Python、venv/pip、OpenSSL 和 Nginx 包；不要在这些系统上使用 `apt` 命令。若系统已有 Nginx，可跳过安装它。如需指定其他已安装的 Python，可设置 `SENSEFIELD_PYTHON=/usr/bin/python3.12`。解释器及其标准库需位于 `/usr`、`/opt` 等服务可读取的位置；用户主目录下的 pyenv/Python 会被服务隔离阻止。

将部署包解压或仓库检出到服务器，然后从源码目录运行安装器。交付 ZIP 的顶层直接包含 `pyproject.toml`、`python/`、`deploy/` 和 `scripts/`；解压时应进入这一层，而不要停留在外包的一层版本目录中：

```sh
sudo bash deploy/assistant/install-native.sh
```

默认应用目录为 `/opt/sensefield-assistant`。若手工检查 `/data/wwwroot/sf/.venv/bin/python` 得到 `command not found`，先核对安装时的 `--app-dir`：没有指定参数时，解释器在 `/opt/sensefield-assistant/.venv/bin/python`。如需使用自定义目录，例如 `/data/wwwroot/sf`，运行：

```sh
sudo bash deploy/assistant/install-native.sh \
  --app-dir /data/wwwroot/sf --transport local-proxy
```

`--app-dir` 必须是专用服务目录，不能是 Nginx、Apache 或其他 Web 服务器实际公开的文档根目录；不要让公网静态文件服务能读取源码、虚拟环境或私有配置。若整个部署包已经解压在该目录，安装器仅在关键生成文件不存在或内容与部署包相同、且没有未标记 `.venv` 时原地采用它。发现冲突文件或现存 `.venv` 时会停止，不会删除或替换它们。检查并安全移走已有内容后再运行。

自定义目录安装完成后，解释器路径为 `/data/wwwroot/sf/.venv/bin/python`；应使用这个绝对路径，不要依赖系统里名为 `python` 的命令。`systemd` 的 `WorkingDirectory` 和 `ExecStart` 会使用所选 `--app-dir`。配置仍放在 `/etc/sensefield-assistant/`，不放进应用目录。

安装器创建专用非 root 用户 `sensefield-gateway`，不会启动服务或更改反向代理。它首次创建配置时会提示输入 HTTPS 兼容上游地址、模型标识和上游 API Key；默认模型为 `qwen/qwen3.8-27b`。凭据不会回显。安装结束前会以服务用户验证所选虚拟环境中的 Python 可启动且 `verify.py` 可读取。私有文件位置如下：

- `/etc/sensefield-assistant/gateway.json`：配置和凭据，权限 `0400`，所有者为 `sensefield-gateway`。
- `/etc/sensefield-assistant/体验连接码.txt`：体验连接码，权限 `0600`，所有者为 `root`。请逐人私下提供，不要放入代码仓库、APK、CDN 或公开文档。
- 只有 `backend-tls` 模式会创建 `/etc/sensefield-assistant/tls/backend.crt` 和 `backend.key`；私钥权限 `0400`，所有者为 `sensefield-gateway`。`local-proxy` 模式不需要后端证书。

systemd 通过 `ASSISTANT_GATEWAY_ENV_FILE` 指向 JSON 配置；凭据不会写进 systemd 环境文件或日志。两种原生模式均监听 `127.0.0.1:18765`，ASR 为 `disabled`，单进程上游并发为 1。服务须已停止才允许更新。迁移应用目录时，安装器根据旧服务自身的路径识别历史听野服务，不再要求新目录预先有安装标记；与听野模板不匹配的其他服务仍不会被覆盖。现有上游凭据和连接码不会被轮换。若配置文件已存在但连接码文件缺失，安装器会停止并要求从私有备份恢复原连接码。

## 配置 HTTPS 反向代理

负责人需自行将 `sf.888413.xyz` 指向服务器，并确认公网域名的 HTTPS 证书有效。若公网证书已由面板或 ACME 工具配置，继续使用那张证书即可；`local-proxy` 不要求额外的 `backend.crt`。根据公网证书位置审阅并手工合并 [`nginx.native.example.conf`](../../deploy/assistant/nginx.native.example.conf)。它将只允许精确的 `/health` 和 `/v1/visual` 路径，其他路径返回 404。两条代理规则只放入 HTTPS 站点；已有 HTTP 入口保留到 HTTPS 的跳转，不要在明文站点直接代理接口。限流区指令须放在 Nginx `http` 上下文中。

仅选择 `--transport backend-tls` 时，改用 [`nginx.native.tls.example.conf`](../../deploy/assistant/nginx.native.tls.example.conf)，不要同时启用两个示例。该模式的 Nginx 上游会校验证书链和 `localhost` 名称；不要关闭校验或改成明文后端。

手工调整 Nginx 后，检查配置并重载：

```sh
sudo nginx -t
sudo systemctl reload nginx
```

公网 SSL 与后端传输是两个独立连接：公网客户端始终访问 `https://sf.888413.xyz`；本机代理模式仅让 Nginx 到 `127.0.0.1:18765` 的连接使用 HTTP。不要把应用源码目录设为网站文档根目录，也不要增加可浏览目录或通配代理规则。

## 已有服务与传输模式迁移

先停止服务，再运行带目标目录和传输模式的安装命令即可，不需要删除旧服务或手工创建安装标记：

```sh
sudo systemctl stop sensefield-assistant
sudo bash deploy/assistant/install-native.sh \
  --app-dir /data/wwwroot/sf --transport local-proxy
```

安装器按旧服务实际的 `WorkingDirectory` 与完整服务模板识别历史听野安装，允许由 `/opt/sensefield-assistant` 迁移到新目录。旧应用和虚拟环境不会删除。覆盖服务单元前会在 `/etc/sensefield-assistant/service-before-migration-*.service` 保存私有备份。

选择传输模式时，安装器会先将原 `gateway.json` 字节完整备份为 `/etc/sensefield-assistant/gateway.json.before-migration-*`（root所有、权限0600），再原子更新传输字段：监听127.0.0.1:18765、ASR关闭，以及所选模式的TLS字段。上游接口、模型、API密钥、设备令牌和其他配置值保持原样。新配置保留原所有者，权限为0400；配置已经匹配时不会重写或重复备份。

`local-proxy` 会移除后端TLS证书/私钥路径并设置本地反代模式，不删除已有证书文件。`backend-tls` 则恢复后端证书路径，仍要求Nginx严格验证。不要删除整个配置后重新初始化，以免丢失原连接码。

## 启动与验收

确认 Nginx 配置和公网证书就绪后，再启动网关：

```sh
sudo systemctl enable --now sensefield-assistant
sudo systemctl status sensefield-assistant --no-pager
sudo journalctl -u sensefield-assistant -n 80 --no-pager
```

默认的公网验收会检查 HTTPS、健康状态、认证和请求大小限制，不调用视觉模型，也不发送上游推理请求。默认应用目录使用：

```sh
sudo -u sensefield-gateway /opt/sensefield-assistant/.venv/bin/python \
  /opt/sensefield-assistant/verify.py \
  --config /etc/sensefield-assistant/gateway.json
```

若安装在 `/data/wwwroot/sf`，将上面两处应用路径替换为该目录：

```sh
sudo -u sensefield-gateway /data/wwwroot/sf/.venv/bin/python \
  /data/wwwroot/sf/verify.py \
  --config /etc/sensefield-assistant/gateway.json
```

本机健康检查也可按传输模式执行。`local-proxy` 使用 loopback HTTP：

```sh
sudo -u sensefield-gateway /data/wwwroot/sf/.venv/bin/python \
  /data/wwwroot/sf/verify.py --health-only --base-url http://127.0.0.1:18765
```

`backend-tls` 使用经校验的 loopback HTTPS：

```sh
sudo -u sensefield-gateway /data/wwwroot/sf/.venv/bin/python \
  /data/wwwroot/sf/verify.py --health-only \
  --base-url https://localhost:18765 \
  --ca /etc/sensefield-assistant/tls/backend.crt
```

如需确认真实视觉链路，可在公网验收命令末尾追加 `--visual`。它会以合成图片调用上游一次，可能产生费用；不要使用真实游戏画面或个人数据做此项检查。检查失败时查看服务状态、journald、Nginx 日志和证书链，不要关闭 TLS 校验来绕过错误。

## 资源预期

2 核 CPU、4 GB 内存可作为低流量试运行起点：视觉模型在外部 API 运行，服务器不运行 ASR 模型或本地推理。systemd 将服务限制为最多使用 100% CPU、512 MiB 内存和 128 个任务，并启用 `PrivateTmp`、`ProtectHome`、`ProtectSystem=strict` 等隔离设置。这些是起始保护措施，不能证明可承受的用户数、请求量或响应时间。试运行时观察进程 RSS、CPU、OOM 记录和上游响应耗时，再根据实际情况评估规格。

本文尚未在目标 Linux、systemd 或 Nginx 主机运行验证；本地配置测试不代表服务器部署或公网链路已验收。
