# 听野助手：直接启动

`gateway` 是原代码对这个 Python 后端的命名，它就是一个进程。现在可以在解压目录直接运行，systemd 安装和迁移作为可选的运维方式保留。

已有部署包解压在 `/data/wwwroot/sf`，并已配置 `/etc/sensefield-assistant/gateway.json` 时，运行：

```sh
cd /data/wwwroot/sf
bash start.sh
```

第一次会在当前目录创建 `.venv` 并安装依赖，随后前台启动。已有环境和依赖可用时直接启动。需要 Python 3.10–3.13 和对应的 venv 支持；可用 `SENSEFIELD_PYTHON=/usr/bin/python3.12 bash start.sh` 选择已安装的解释器。

后端复用原 API 密钥、模型和设备连接码；旧 TLS 设置只在本进程内改为同机反代模式，配置文件原文不变。监听为 `127.0.0.1:18765`，服务器不加载 ASR 模型。现有 Nginx 继续以公网 HTTPS 转发到 `http://127.0.0.1:18765`。当前进程用户需要能读取原私有配置；没有指定用户、安装标记或服务注册的前提。

前台窗口可直接查看日志，按 Ctrl+C 停止。若18765已被旧进程占用，先停止旧的 `sensefield-assistant` 服务或原运行进程，再启动本进程，不要同时运行两份。

前台确认启动后，可以使用服务器面板的进程守护，启动命令仍为 `bash /data/wwwroot/sf/start.sh`。也可以在已有权限读取配置的账号下后台运行：

```sh
umask 077
nohup bash /data/wwwroot/sf/start.sh > /var/log/sensefield-assistant.log 2>&1 &
```

这种 nohup 方式不会在服务器重启后自动恢复。需要自动恢复时由已有面板守护或另行配置systemd；不再要求先安装systemd才能启动。

如果尚未准备配置，先在服务器交互初始化一次（需要能创建 `/etc/sensefield-assistant` 的权限）：

```sh
python3 deploy/assistant/configure.py --runtime native --transport local-proxy \
  --output /etc/sensefield-assistant/gateway.json \
  --codes-output /etc/sensefield-assistant/体验连接码.txt
chmod 600 /etc/sensefield-assistant/gateway.json
```

已有配置不要重新初始化。工具不回显API密钥，连接码只写入私有文件。

仅检查本机健康状态：

```sh
.venv/bin/python verify.py --health-only --base-url http://127.0.0.1:18765
```

默认检查和启动不调用视觉模型。用户实际提问画面或显式 `--visual` 验收时，才会发送截图请求。
