# 听野助手服务部署

推荐直接在后台启动 Python 后端，无需 Docker，也无需先安装 systemd 服务。公网 HTTPS 使用服务器现有的 Nginx 和证书，后端仅监听 `http://127.0.0.1:18765`；不需要 `backend.crt`。服务器不运行 ASR 或视觉模型，手机负责语音识别，后端转发画面请求到配置的模型 API。

## 1. 准备服务器与源码

需要 Python 3.10–3.13 及对应的 venv 支持。Ubuntu/Debian 可安装：

```sh
sudo apt update
sudo apt install -y python3 python3-venv python3-pip unzip
```

将部署包解压到 `/data/wwwroot/sf`，或将仓库检出到该目录。脚本与部署模板统一放在 `deploy/assistant/`：

```text
/data/wwwroot/sf/
├── pyproject.toml
├── python/
└── deploy/assistant/
    ├── README.md
    ├── start.sh
    ├── start.py
    ├── serve.py
    ├── configure.py
    ├── verify.py
    └── nginx.native.example.conf
```

首次启动会在应用目录创建 `.venv` 并安装依赖。已有虚拟环境可用时复用它；不再在应用根目录生成启动或验收脚本。

## 2. 准备私有配置

**已有 `/etc/sensefield-assistant/gateway.json` 时跳过这一步**，保留原模型和 API 密钥。

首次部署才运行以下命令，按提示输入模型 API 地址、模型名称和密钥：

```sh
cd /data/wwwroot/sf
sudo python3 deploy/assistant/configure.py --runtime native --transport local-proxy \
  --public-access --output /etc/sensefield-assistant/gateway.json
```

配置保留在服务器私有目录，密钥不回显、不进入APK。公开画面服务无需给用户发连接码。用有权限读取配置、并可准备应用目录虚拟环境的账号启动。

## 3. 后台启动

```sh
cd /data/wwwroot/sf
bash deploy/assistant/start.sh
```

脚本自动定位应用目录，也可在任意工作目录用绝对路径启动：

```sh
bash /data/wwwroot/sf/deploy/assistant/start.sh
```

如需选择已安装的 Python，可在首次启动时使用 `SENSEFIELD_PYTHON=/usr/bin/python3.12 bash deploy/assistant/start.sh`。

默认后台运行，启动成功后命令会退出，关闭终端不影响服务。脚本确认本机健康状态后才报告启动成功；重复启动不会创建第二个进程。启动失败会直接报错，原因可从日志查看。若端口已被其他旧服务占用，先停止旧服务。

查看状态、日志，停止或重启：

```sh
bash deploy/assistant/start.sh status
bash deploy/assistant/start.sh logs
bash deploy/assistant/start.sh stop
bash deploy/assistant/start.sh restart
```

日志、PID 和启动锁统一保存在 `deploy/assistant/runtime/`；日志为 `assistant.log`，不在根目录生成文件。需要实时查看日志时运行 `tail -f deploy/assistant/runtime/assistant.log`。

后台启动不会在服务器重启后自动恢复。若需要面板进程守护或开机恢复，面板启动命令使用 `bash /data/wwwroot/sf/deploy/assistant/start.sh --foreground`，让面板直接管理服务进程。这个前台选项也可用于排错，按 Ctrl+C 停止。

启动复用原配置，只在进程环境中设置回环 HTTP、关闭服务端 ASR、忽略旧后端 TLS 路径，原配置文件不会被改写。默认开启免连接码画面入口（`ASSISTANT_GATEWAY_PUBLIC_ACCESS=1`），客户端发送随机安装标识。原私有令牌可继续用于开发接入；公开入口不接受音频，ASR仍在手机完成。若旧配置明确写了 `ASSISTANT_GATEWAY_PUBLIC_ACCESS=0`，改为 `1` 后重启。通用CLI默认仍为私有认证模式。

## 4. 接入已有 HTTPS 站点

确认 `sf.888413.xyz` 的 DNS 与公网证书已就绪。参考同目录的 [Nginx 示例](nginx.native.example.conf)，把 `/health`、`/v1/visual` 两条代理规则合入现有 HTTPS 站点，转发目标为 `http://127.0.0.1:18765`；示例中的限流区放在 Nginx 的 `http` 上下文。继续使用当前公网证书路径，HTTP 站点仅跳转 HTTPS。应用源码目录不作为公开静态文件目录。

```sh
sudo nginx -t
sudo systemctl reload nginx
```

## 5. 验收

先在服务器检查本机进程：

```sh
cd /data/wwwroot/sf
.venv/bin/python deploy/assistant/verify.py \
  --health-only --base-url http://127.0.0.1:18765
```

再检查公网 HTTPS、健康状态和访问校验：

```sh
.venv/bin/python deploy/assistant/verify.py \
  --config /etc/sensefield-assistant/gateway.json
```

健康状态应为 `production` / `vision_only`，`asr_ready=false`、`public_vision_access=true`。公开模式验收自动使用随机安装标识，不读取或填写连接码；无标识请求仍返回401。默认验收不请求模型；需要实际验证视觉 API 时，在公网验收命令末尾加 `--visual`，会上传一张合成测试图，可能产生一次模型费用。

## 已使用旧部署包

覆盖解压新包后改用 `bash deploy/assistant/start.sh`。旧包根目录的 `start.sh`、`start.py`、`serve.py`、`verify.py` 不再使用；确认它们来自旧听野部署包并停止旧进程后，可删除这四个文件。原 `.venv` 和 `/etc/sensefield-assistant/` 私有配置继续保留。

可选的 [systemd 部署](../../docs/development/assistant-native-deployment.md)与 [Docker 部署](../../docs/development/assistant-online-deployment.md)仍有说明；直接启动无需运行这些安装器。2核4GB可用于少量用户试运行，容量需实测。当前启动链路已在本机验证，目标 Linux、Nginx 和生产端到端仍由部署者验收。

## 6. CDN语音资源与安装包

手机连续语音仍使用同一个SenseVoiceSmall int8离线模型。APK只保留运行库、许可证和固定校验元数据；首次开启连续语音从以下地址下载资源，显示进度，网络中断后可续传。已缓存资源直接校验复用。

- 模型URL：`https://888413.xyz/apk/models/sensevoice-int8-v1.zip`
- 资源大小：160,304,482 bytes（约153 MiB）；手机需预留约400 MiB。
- ZIP SHA-256：`d863ab6f0b202ee31e74f2a3eadabaf24a41ea7acfe9fc6f85a1efaed2e37a8c`
- CDN需提供HTTPS，原样返回ZIP，支持`Range`/`206`和准确`Content-Range`；不支持Range时手机会重新下载完整文件。禁止跨域重定向。
- 先手动上传并核对模型，再上传APK，最后覆盖`latest.json`并刷新APK与清单缓存。此模型URL和摘要固定，不覆盖为不同内容；换模型要同时更新APK元数据。

模型打包工具为`python3 deploy/assistant/package-asr-model.py`（用`--help`查看参数）。准备构建权重仍使用`scripts/prepare_android_asr_assets.py`；该权重不会打进本轮APK。

## 7. 免连接码服务额度

公开入口为`/v1/visual`，仅接受有效随机安装UUID；这不是硬件认证，安装标识可能被重建。Nginx原有每IP限流继续保留。服务端在调用模型前扣减持久化额度，所有有效的公开请求尝试（包括上游失败、取消）计数，无效请求体不计数：

| 配置项 | 默认上限 |
| --- | --- |
| `ASSISTANT_GATEWAY_PUBLIC_GLOBAL_DAILY` | 全站1000次/UTC日 |
| `ASSISTANT_GATEWAY_PUBLIC_INSTALLATION_DAILY` | 每安装300次/UTC日 |
| `ASSISTANT_GATEWAY_PUBLIC_INSTALLATION_MINUTE` | 每安装12次/分钟桶 |

可在私有JSON配置中以字符串调整这些值，改后重启。全站日上限用于控制模型调用次数，费用仍取决于所选服务商和图片/文字用量。计数保存在`deploy/assistant/runtime/public-quota.sqlite3`，重启后继续累计；只保存安装标识的哈希及计数，旧日数据自动清理。数据库不可写时拒绝公开模型请求，不绕过额度。不要在部署时清空runtime。systemd部署使用`/var/lib/sensefield-assistant/public-quota.sqlite3`。

新APK需要配套此版服务端。覆盖解压新包后执行`bash deploy/assistant/start.sh restart`，按上节验收；只更换APK而不更新服务端仍会得到401。新版本仍需用户授权麦克风、画面共享并开启相应功能，不自动上传画面或开启录音。
