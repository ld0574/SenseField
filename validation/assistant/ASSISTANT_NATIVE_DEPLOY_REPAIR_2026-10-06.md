# 原生部署交付修订（2026-10-06）

用户在自有生产主机执行 `/data/wwwroot/sf/.venv/bin/python .../verify.py` 时得到 `command not found`。该错误发生在 Python 启动前，不能归因为网关配置、视觉模型或后端证书。上一批安装器固定使用 `/opt/sensefield-assistant`，部署 ZIP 本身不包含服务器虚拟环境；搬运源码或直接调用验收工具不能代替安装步骤。

本次交付支持 `install-native.sh --app-dir /data/wwwroot/sf --transport local-proxy`。专用应用目录可以是部署源码解压目录；安装器拒绝覆盖无标记的既有虚拟环境、冲突生成文件或运行中的服务。它在目标主机创建 venv、安装依赖、生成对应应用路径的 systemd 单元，并以 `sensefield-gateway` 用户确认解释器和验收工具可以启动。私有配置仍留在 `/etc/sensefield-assistant`，已有凭据和连接码不会被自动覆盖或轮换；旧配置只按部署说明迁移传输字段。

用户选择复用已有公网 HTTPS 证书。原生安装默认采用 `local-proxy`：Nginx 对手机提供 HTTPS，Python 以真实生产模式仅监听 `127.0.0.1:18765` 的 HTTP。该模式不需要 `backend.crt` 或私钥；设备令牌仍强制，ASR 必须关闭，Uvicorn 不解释转发头，应用另外按 socket peer 限制 HTTP/WebSocket 为 `127.0.0.1` 来源。公网不得改成 HTTP。显式 `backend-tls` 及原容器模式仍保留严格后端证书验证，没有加入 `proxy_ssl_verify off` 或客户端跳过校验。

`verify.py` 保留默认公网 HTTPS 验收，不调用上游模型。它输出脱敏的分阶段状态和最终 JSON，区分地址、连接、证书、健康状态、私有配置和接口结果。只有明确的 `--health-only` 才允许直连字面 `127.0.0.1` HTTP，并绕过环境代理；不在该明文检查中读取或发送设备令牌。公网 HTTPS 仍校验证书链和主机名，跨主机/协议重定向被拒绝。TLS 成功前不会报告已验证。

本机真实进程还复现了旧验收工具的大包竞态：网关按 Content-Length 提前返回 413 时，urllib 继续写入大包可能产生 BrokenPipe。修订的超限探针只声明超限长度而不发送数据，验证服务能提前拒绝；它不是一次完整超大图片上传测试，也不触发视觉推理。

## 工程验证

- Python 3.12 下174项网关、传输、配置和部署验收测试全部通过，1条既有 Starlette 弃用警告。Shell/Python语法与 diff 检查通过。
- Mac 上启动真实生产模式网关，仅用合成配置。HTTP回环健康检查返回 `vision_only`、`asr_ready=false`；本机独立 TLS 前置代理夹具经过可信测试CA和主机名验证，完整验收取得200/401/422/413；未信任测试CA时明确拒绝。没有后端证书，没有模型调用。
- 此前从本机以正常校验方式检查 `https://sf.888413.xyz/health`（含绕过环境代理的直连）均遇到证书主机名不匹配，未读取到公网 health。这是当时外部观测，不能证明用户服务器上的具体证书文件或 Nginx 配置。已有公网证书必须覆盖 `sf.888413.xyz`，且该站点实际返回匹配证书。
- 未通过 SSH 连接测试或生产服务器，未运行目标 Linux/systemd/Nginx 安装器；本机前置代理夹具不是 Nginx。没有部署生产、制作或安装新 APK、发布Release、上传CDN，也不修改评分、`verified` 或 `release_ready`。

交付与证据位于 `output/releases/0.4.1/server-native-deploy-2026-10-06/`。ZIP顶层直接提供源码与部署脚本，不附带本机venv、私有配置、连接码、模型权重或APK。操作入口为[原生部署说明](../../docs/development/assistant-native-deployment.md)；10月5日原TLS交付的摘要与验证范围保留在[原批次记录](ASSISTANT_NATIVE_DEPLOY_HANDOFF_2026-10-05.md)。

## 同日迁移检查修复

负责人实际运行自定义路径安装命令时，被“已有同名服务不是本安装器创建的”挡住。安装器错误地用新应用目录的标记判断旧服务归属；新目录未初始化，并不表示旧服务是外来服务。修复后读取旧单元自身的应用路径并匹配历史/现行听野模板，允许旧目录迁移，也兼容旧应用目录已经被删除但服务单元残留的情况，而真正不匹配的单元仍不会覆盖。覆盖前保存单元备份，旧应用目录和venv不删除。

同时消除下一步要求手工编辑传输配置的阻塞：安装器先私下保存原JSON字节备份，再原子调整选定模式的传输字段，保留原API密钥、模型、设备令牌等其他值。24项迁移与配置测试通过（15项新增迁移场景和9项已有配置场景），包括真实旧版服务单元夹具、跨目录识别、外来服务拒绝、原文备份、权限、凭据不变、重复执行和TLS往返迁移。Shell语法与diff检查通过。这是安装器逻辑的本地验证，没有在负责人Linux主机执行安装，不复用174项网关测试宣称新安装器已通过Linux验收。

## 同日简化为直接启动

按负责人“直接启动”的选择，增加顶层 `start.sh` 和 `start.py`。主入口在当前目录准备venv与依赖后前台运行，仅监听127.0.0.1:18765；不检查安装标记、不注册systemd、不迁移旧服务。复用原私有配置，在进程环境内覆盖为同机反代和ASR关闭，原JSON字节保持不变，设备认证保留。systemd成为可选运维方式。

7项启动配置测试通过；Mac上真实执行 `bash start.sh`，使用无效的旧TLS路径及旧ASR配置的合成私有JSON，启动为production/vision_only、health200、未认证401、ASR关闭，原文件字节未变。没有真实模型请求，也未在生产或目标Linux执行。本次验证仅覆盖现有依赖下的直接启动，未实测Linux首次venv/pip初始化或服务器容量。见[直接启动说明](../../deploy/assistant/README.md)。

## 同日统一部署目录

按负责人要求，启动入口改为 `bash deploy/assistant/start.sh`。`start.sh`、`start.py`、配置加载入口 `serve.py` 与已有 `verify.py` 统一放在 `deploy/assistant/`，移除顶层启动脚本和 `scripts/` 中的部署入口。操作文档合并到同目录的 README，首页及文档索引提供入口。直接启动仍自动定位应用根目录、复用 `.venv` 和原私有配置。

原生安装器不再向应用根目录生成 `serve.py` / `verify.py`；systemd、Docker 和 Compose 的入口同步使用部署子目录。旧根目录入口的严格模板匹配仍保留，避免再次阻断原服务迁移；不自动删除用户服务器上的旧文件。新完整部署包与补丁均保持相同目录布局，旧包已在本地留档。

71项启动、私有配置、旧单元迁移、验收工具与回环传输测试通过。最终部署ZIP解压到带空格的临时目录，从另一个工作目录实际启动，复用本机测试venv；取得production/vision_only、health200、未认证401，新路径验收命令成功，原配置字节未变。Shell语法、启动帮助、diff检查与公开仓库扫描通过。证据为 `output/releases/0.4.1/direct-start-2026-10-06/layout-smoke.json` 和 `layout-process.log`。未执行目标Linux安装、生产部署或模型请求。

## 同日默认后台启动与代理配置核对

按负责人反馈，`bash deploy/assistant/start.sh` 改为默认启动独立后台会话，标准输入断开，输出写入 `deploy/assistant/runtime/assistant.log`，关闭启动终端不影响服务。启动器等待本机健康状态后再确认成功；文件锁避免同时启动，PID记录含进程启动时间与命令，用于识别已有进程和拒绝误停复用PID。支持 `status`、`logs`、`stop`、`restart`；`--foreground` 保留给排错及面板守护。后台方式不提供自动开机恢复，文档已明确。

75项相关测试通过，包含新增真实后台进程、独立会话、重复启动、重启、停止、配置失败、端口冲突及陈旧PID拒绝场景。使用合成私有配置与本机测试venv，不调用上游模型，未执行目标Linux或生产部署。

负责人提供的 `sf.conf` 中，两处后端 `proxy_pass` 使用HTTPS，与当前回环HTTP后端不匹配。修正版仅将两处协议改为HTTP，公网证书路径与其他规则保持原样；限流区定义仍须位于Nginx的http上下文。未在目标OpenResty执行配置检查或重载。
