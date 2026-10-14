# 听野助手服务部署

推荐直接在后台启动 Python 后端，无需 Docker，也无需先安装 systemd 服务。公网 HTTPS 使用服务器现有的 Nginx 和证书，后端仅监听 `http://127.0.0.1:18765`；不需要 `backend.crt`。服务器不运行 ASR 或视觉模型，手机负责语音识别，后端转发画面请求到配置的模型 API。

## 已有部署更新：0.4.7 需要配套更新 Python 服务端

**本轮中文游戏搭子提示词、通用游戏问题与手动截图回答协议都包含服务端改动。安装 APK 不会更新服务器；需要更新 Python 源码、核对私有配置并重启服务。** 10 月 14 日 14:22 已验证用户部署的 Kimi-K3 服务可读合成图；随后实局发现的时效问题属于后续修订，尚未安装或部署。本地交付部署包也不会自动上传或重启生产服务器。

以下使用已有的 `/data/wwwroot/sf` 直接后台部署。先停止现有服务，再选择一种源码更新方式：

```sh
cd /data/wwwroot/sf
bash deploy/assistant/start.sh stop
```

- **Git 部署**：在服务器的 `main` 检出中执行 `git pull --ff-only origin main`。
- **ZIP 部署**：核对随包 `SHA256SUMS.txt`，将对应本轮修订的源码包上传到服务器 `/tmp/`，用 `unzip -o` 覆盖到 `/data/wwwroot/sf`。ZIP 顶层直接包含 `pyproject.toml`、`python/`、`deploy/`，包括 `python/mapassist/assistant_gateway/vision_prompt.txt`。早先的 `sensefield-assistant-0.4.7-20261014.zip` 绑定 `5662c2a`，不含随后新增的手动截图回答协议；不能用旧包验收新修订。

保留原 `.venv`、`deploy/assistant/runtime/` 和 `/etc/sensefield-assistant/gateway.json`；不要重新运行首次初始化或清空额度数据库。部署包只含已提交源码与说明，不含这些私有或运行时文件。

如切换到本次验证的中转模型，在现有私有 JSON 中合并以下字段，值均为字符串，保留其他配置：

```json
{
  "ASSISTANT_GATEWAY_VISION_PROVIDER": "compatible",
  "ASSISTANT_GATEWAY_VISION_BASE_URL": "https://www.tokens1688.com/v1",
  "ASSISTANT_GATEWAY_VISION_MODEL": "Kimi-K3",
  "ASSISTANT_GATEWAY_VISION_DISABLE_THINKING": "1",
  "ASSISTANT_GATEWAY_VISION_TIMEOUT_SECONDS": "12"
}
```

同时在私有配置中更新对应的 `ASSISTANT_GATEWAY_VISION_API_KEY`，保留仅所有者可读的文件权限。接口地址不加 `/chat/completions`，客户端会自行追加。默认加载随源码交付的中文提示词；若已有 `ASSISTANT_GATEWAY_VISION_PROMPT_FILE` 指向旧文件，需更新该自定义文件，或移除此字段以使用新默认文件。检查现有 Nginx `/v1/visual` 的 `proxy_read_timeout` 至少为 `16s`；若修改反代，执行 `sudo nginx -t` 成功后再重载。

完成后启动并实际验收一次公网读图：

```sh
cd /data/wwwroot/sf
bash deploy/assistant/start.sh start
.venv/bin/python deploy/assistant/verify.py \
  --config /etc/sensefield-assistant/gateway.json --visual
```

验收会调用模型一次；应得到 HTTP 200 并读出合成图文字 `SYNTHETIC GATEWAY TEST`。仅 `/health` 正常不能证明新中转账户或图片能力可用。原本由 systemd 或面板管理的服务继续用原管理器停启；Docker 部署则更新源码后重建镜像，参见对应部署说明。确认失败时先查网关和反代日志，未完成该步骤前不能记为生产恢复。

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

### 所有页面都提示画面理解不可用

先查看诊断中的 `AssistantVisual event=HTTP_ERROR`。若为 `httpStatus=502 source=provider upstreamHttpStatus=401`，表示手机已经到达网关，模型服务拒绝了服务端访问；换页面不会改变结果。新网关会返回 `vision_provider_auth_failed`，模型返回 403 时为 `vision_provider_access_denied`。手机到网关自身的 HTTP 401 是另一类连接认证问题，不能混为同一故障。

先检查提供商账户余额、套餐和模型权限，再由负责人在服务器私有配置 `/etc/sensefield-assistant/gateway.json` 中核对 `ASSISTANT_GATEWAY_VISION_BASE_URL`、`ASSISTANT_GATEWAY_VISION_MODEL` 与 `ASSISTANT_GATEWAY_VISION_API_KEY`。部分中转站也用 HTTP 401 表示账户或额度不可用，仅凭这个状态不能断定密钥错误。本次 Hero 反馈由负责人确认是账户余额耗尽。若选择 GLM 后端，则核对其 `ZHIPU_API_KEY`。根据提供商后台结果修正实际错误项，保留其他配置和文件权限，随后重启听野网关。不要将密钥发给玩家或写进 APK、日志和仓库。

更换中转站时，在私有配置中成套替换接口地址、模型和密钥；兼容接口继续选择 `ASSISTANT_GATEWAY_VISION_PROVIDER=compatible`。手机仍连接听野网关，模型密钥留在服务器。用合成文字图核查新接口确实接受图片、能读出文字，并记录响应耗时；模型名称或普通文本接口成功不代表视觉可用。团战提前提醒由本地轨迹处理，云视觉用于按需读屏与解释，响应速度以实际请求测量为准。

修复后运行一次完整的合成读图验收：

```sh
bash deploy/assistant/start.sh restart
.venv/bin/python deploy/assistant/verify.py \
  --config /etc/sensefield-assistant/gateway.json --visual
```

若使用 systemd，由该部署方式重启服务。验收必须实际收到 HTTP 200 并读出测试文字；`/health` 的 `vision_ready=true` 只表明客户端初始化完成，没有向模型提供商验证账户与访问权限。新手机候选在访问被拒绝后暂停本次会话的自动读屏，手动读屏仍可用于验证修复，收到正常响应后恢复自动观察；本地游戏预警继续运行。

### 通用画面理解与中文提示词

视觉模型以“正在为低视力用户服务的游戏搭子”为身份，帮助读画面、回答游戏问题并分析选择。出装、选人、技能升级和区域观察都是例子；用户问题保持原意，根据实际画面、用户目标与可靠规则分析，没有固定英雄榜单或出装表。观察野区时只能报告截图中可辨的敌方标记，未见标记不能证明安全。即时团战提示由手机本地预警处理。默认只传当前截图，用户明确要求比较近期变化时才加入最多两张近期缩图。

默认提示词全部以中文书写，位于 [vision_prompt.txt](../../python/mapassist/assistant_gateway/vision_prompt.txt)，身份、交流方式、可见证据边界和输出协议集中维护。部署者可把它复制为服务器上的文本文件，在私有配置中设置 `ASSISTANT_GATEWAY_VISION_PROMPT_FILE` 指向该文件；只调整提示词时，修改后重启服务即可生效，不必改 Python 代码或重打 APK。本轮新增的截图回答协议仍需要同步更新 Python 和 APK。文件为 UTF-8、非空、不超过 32 KiB；路径无效或文件无效时启动明确失败，不静默改用另一份提示词。保留文件末尾的响应字段约定。

本次中转实测建议先用 `Kimi-K3`，兼容地址为 `https://www.tokens1688.com/v1`，参数名是 `ASSISTANT_GATEWAY_VISION_PROVIDER`。详见[速度对照](../../validation/assistant/VISION_RELAY_BENCHMARK_2026-10-14.md)。`glm-5.3` 的当前路由拒绝图片，模型列表中的标签不足以证明能用；不要默认给所有模型发送关闭思考扩展参数。

这家中转的 Kimi-K3 已接受关闭思考参数，可在其私有配置中显式设置 `ASSISTANT_GATEWAY_VISION_DISABLE_THINKING=1`。其他模型保留默认 `0`，更换提供商后重新验证：Kimi-K2.6 实测拒绝该参数，Flash 也没有稳定执行。使用中文搭子提示词后的两张合成决策图实际回答约 5.2／7.2 秒；默认思考探测中的格式失败和超时保留。明确询问“现在团战怎么操作”时，网关直接说明本地预警的职责，不调用视觉模型；“团战容易死，接下来怎么出装”仍走出装规划。

如试用 Kimi-K2.6，需同时设置 `ASSISTANT_GATEWAY_VISION_MODEL=Kimi-K2.6` 与 `ASSISTANT_GATEWAY_VISION_DISABLE_THINKING=0`，再重启并执行真实读图验收；只改模型名会继续携带它拒绝的扩展参数。旧完整提示词每组3次的完整回答中位为 K3 2.27 秒、K2.6 4.09 秒，短提示词组中 K2.6 波动较小；这些结果不能保证当前中文提示词下的延迟或504故障率。先查超时来源，再按相同提示词和图片对照，不自动把本次未知来源504归因于K3。

当前服务默认模型等待预算为 12 秒，可用 `ASSISTANT_GATEWAY_VISION_TIMEOUT_SECONDS` 调整；0.4.7 手机请求上限为 14 秒，Nginx 示例读取上限为 16 秒。可等待的决策附带 `decision_scene`：选人、出装或其他游戏决策；原受保护的建议最多使用截图后 20 秒，要求新鲜画面且未发生持续明显页面变化。截图哈希只提供粗粒度检查，不能证明微小阵容或装备变更都被发现。

新手机的手动请求明确发送 `snapshot_read=true`，网关仅对可靠的手动局内回答返回 `answer_scope=snapshot`。手机始终播报“约 N 秒前截图显示”，允许在原截图后 20 秒内使用已请求的截图信息，页面变化不抹掉这份历史信息；重复时重新计算截图年龄，不延长有效期。暂停、换问题、热保护及本地预警优先级继续有效。普通未标记 HUD 仍为 5 秒，稳定文字为 15 秒，自动观察不会取得截图范围。旧手机或旧网关没有新标记时沿用原规则。敌人位置等信息不能据此当成实时状态。

[本次实局复盘](../../validation/assistant/USER_TRIAL_0_4_7_2026-10-14.md)记录了 6 次云请求、3 次保护丢弃和 1 次 HTTP 504。504 在手机端明确提示“画面读取超时”，来源仍需服务器日志确认；不将未知来源直接归因于 Nginx 或中转商。本轮修订还需要配套更新后的公网和手机验证，当前未自动部署或安装。

## 已使用旧部署包

覆盖解压新包后改用 `bash deploy/assistant/start.sh`。旧包根目录的 `start.sh`、`start.py`、`serve.py`、`verify.py` 不再使用；确认它们来自旧听野部署包并停止旧进程后，可删除这四个文件。原 `.venv` 和 `/etc/sensefield-assistant/` 私有配置继续保留。

可选的 [systemd 部署](../../docs/development/assistant-native-deployment.md)与 [Docker 部署](../../docs/development/assistant-online-deployment.md)仍有说明；直接启动无需运行这些安装器。2核4GB可用于少量用户试运行，容量需实测。当前启动链路已在本机验证，目标 Linux、Nginx 和生产端到端仍由部署者验收。

## 6. Gitee语音资源与安装包

队友后续发包按 [Gitee 下载分发与后续发布](CDN发布.md) 操作：包括同版本覆盖、新版本发布、固定清单更新、公开下载核对及模型分片复用。

手机连续语音仍使用同一个SenseVoiceSmall int8离线模型，助手默认关闭。APK只保留运行库、许可证和固定校验元数据；首次开启连续语音才下载资源，旧模型缓存和兼容的旧ZIP断点均可复用。

Gitee单附件限制100MB，原ZIP为160,304,482 bytes，现拆成两份，固定放在0.4.1 Release中：

| 附件 | 大小 | 下载地址 |
| --- | --- | --- |
| sensevoice-int8-v1.zip.part01 | 90,000,000 bytes | [第一包](https://gitee.com/leda/SenseField/releases/download/0.4.1/sensevoice-int8-v1.zip.part01) |
| sensevoice-int8-v1.zip.part02 | 70,304,482 bytes | [第二包](https://gitee.com/leda/SenseField/releases/download/0.4.1/sensevoice-int8-v1.zip.part02) |

手机按顺序写入同一个临时ZIP，逐包检查大小和SHA-256，再核对完整ZIP及解压后model/tokens的摘要；分包不能单独解压。原ZIP SHA-256仍为`d863ab6f0b202ee31e74f2a3eadabaf24a41ea7acfe9fc6f85a1efaed2e37a8c`，模型内容、版本与推理参数未变，需预留约400 MiB。原大ZIP仅本地留档，不上传。

下载只允许HTTPS；Gitee初始地址限定听野仓库，适配该仓库`attach_files`及实测`foruda.gitee.com/attach_file/`签名地址，不放开任意跨域。实际已上传APK的Range探针返回200完整内容，不能承诺服务器支持断点；支持206时按当前包偏移续传，不支持时只重下未完成的一包，已经校验完成的包保留。服务端不能改变包字节、命名、校验值。

模型只上传一次，后续APK一直复用上述模型地址，不把模型复制到每个Release。按用户提供的普通仓库总附件1GB限制，仅模型与当前约49MB APK就约209MB；加上仓库其他附件后可用空间以Gitee后台为准。历史APK可由负责人按需要清理，程序不自动删除附件，也不宣称Gitee提供无限容量/流量或CDN服务保证。

手工发布顺序：先上传两个分包到0.4.1，再把最终APK覆盖为`sensefieldv0.4.1.apk`，最后更新`https://888413.xyz/apk/latest.json`。几百字节的固定清单用来发现后续版本，大文件走Gitee；不要把清单地址固定到某一版本的Release。旧APK只允许同源下载，这次迁移版先手动覆盖安装一次，之后支持Gitee自动更新。同版本修订仍以SHA区分，无需新增0.4.2；Android系统安装确认保留。

模型打包工具：

```bash
python3 deploy/assistant/package-asr-model.py --archive output/releases/0.4.1/model-cdn-2026-10-06/sensevoice-int8-v1.zip
```

分包与生成的`model.json`在`output/releases/0.4.1/model-gitee-2026-10-06/`，`--help`可查看其他参数。最终可上传的三个大文件统一放在`output/releases/0.4.1/gitee-upload/`；版本清单仍在`output/releases/0.4.1/cdn-upload/latest.json`。准备构建权重仍使用`scripts/prepare_android_asr_assets.py`，权重不会打进APK。构建/分包工具均不自动上传。

## 7. 免连接码服务额度

公开入口为`/v1/visual`，仅接受有效随机安装UUID；这不是硬件认证，安装标识可能被重建。Nginx原有每IP限流继续保留。服务端在调用模型前扣减持久化额度，所有有效的公开请求尝试（包括上游失败、取消）计数，无效请求体不计数：

| 配置项 | 默认上限 |
| --- | --- |
| `ASSISTANT_GATEWAY_PUBLIC_GLOBAL_DAILY` | 全站1000次/UTC日 |
| `ASSISTANT_GATEWAY_PUBLIC_INSTALLATION_DAILY` | 每安装300次/UTC日 |
| `ASSISTANT_GATEWAY_PUBLIC_INSTALLATION_MINUTE` | 每安装12次/分钟桶 |

可在私有JSON配置中以字符串调整这些值，改后重启。全站日上限用于控制模型调用次数，费用仍取决于所选服务商和图片/文字用量。计数保存在`deploy/assistant/runtime/public-quota.sqlite3`，重启后继续累计；只保存安装标识的哈希及计数，旧日数据自动清理。数据库不可写时拒绝公开模型请求，不绕过额度。不要在部署时清空runtime。systemd部署使用`/var/lib/sensefield-assistant/public-quota.sqlite3`。

新APK需要配套此版服务端。覆盖解压新包后执行`bash deploy/assistant/start.sh restart`，按上节验收；只更换APK而不更新服务端仍会得到401。新版本仍需用户授权麦克风、画面共享并开启相应功能，不自动上传画面或开启录音。
