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

交付与证据位于 `output/releases/0.4.1/server-native-deploy-2026-10-06/`。ZIP顶层直接提供源码与部署脚本，不附带本机venv、私有配置、连接码、模型权重或APK。操作入口为[原生部署说明](../docs/development/assistant-native-deployment.md)；10月5日原TLS交付的摘要与验证范围保留在[原批次记录](ASSISTANT_NATIVE_DEPLOY_HANDOFF_2026-10-05.md)。
