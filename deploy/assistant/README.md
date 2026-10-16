# 听野助手服务部署

手机完成语音识别，独立 Python/FastAPI 服务接收问题文本和授权截图，调用配置的多模态模型 API。公网入口使用 Nginx HTTPS，Python 后端仅监听 `127.0.0.1:18765`；API 密钥仅保存在服务器。

## 首次部署

准备 Python 3.10～3.13 与项目源码，在项目根目录运行：

```sh
python3 deploy/assistant/configure.py \
  --runtime native --transport local-proxy --public-access \
  --output /etc/sensefield-assistant/gateway.json
bash deploy/assistant/start.sh start
```

配置向导交互读取服务商地址、模型标识和 API Key，密钥不回显，配置仅所有者可读。已有配置时直接复用，首次初始化不会覆盖文件。启动脚本准备本地 `.venv` 和服务依赖，服务器不需下载 ASR 或视觉模型权重。

## 模型配置

在服务器私有 `gateway.json` 中设置与服务商匹配的接口、模型和凭据。当前接入示例：

```json
{
  "ASSISTANT_GATEWAY_VISION_PROVIDER": "compatible",
  "ASSISTANT_GATEWAY_VISION_BASE_URL": "https://maas.qianwenaiapi.com/compatible-mode/v1",
  "ASSISTANT_GATEWAY_VISION_MODEL": "qwen3.8-flash",
  "ASSISTANT_GATEWAY_VISION_DISABLE_THINKING": "1",
  "ASSISTANT_GATEWAY_VISION_MAX_TOKENS": "256",
  "ASSISTANT_GATEWAY_VISION_TIMEOUT_SECONDS": "12",
  "ASSISTANT_GATEWAY_SNAPSHOT_TIMEOUT_SECONDS": "17"
}
```

另在私有配置中填写 `ASSISTANT_GATEWAY_VISION_API_KEY`，不要放入 APK、静态站点或 Git。接口基础地址不追加 `/chat/completions`。服务商和模型需支持图像输入；更换模型后实际验证能力与返回格式。

默认提示词随 `python/mapassist/assistant_gateway/vision_prompt.txt` 交付。使用自定义 `ASSISTANT_GATEWAY_VISION_PROMPT_FILE` 时同步更新内容，保留结构化输出要求。

## HTTPS 反向代理

参考 [Nginx 示例](nginx.native.example.conf)，把 `/health` 与 `/v1/visual` 代理规则合入现有 HTTPS 站点，转发至 `http://127.0.0.1:18765`。限流区位于 Nginx 的 `http` 上下文，继续使用现有证书；项目源码目录不作为公开静态目录。

手动截图模型等待预算默认为 17 秒，示例反代读取超时为 21 秒。同步核对手机、后端与反代预算，修改配置后先执行 `nginx -t`，再使用现有管理器重载。

## 启停与源码更新

```sh
bash deploy/assistant/start.sh status
bash deploy/assistant/start.sh logs
bash deploy/assistant/start.sh stop
bash deploy/assistant/start.sh restart
```

更新源码时保留 `.venv`、`deploy/assistant/runtime/` 与 `/etc/sensefield-assistant/gateway.json`。APK 与服务端分开交付；包含协议或提示词调整时同步更新服务器源码并重启。

后台启动不会自动配置开机恢复。需要进程守护时，让现有面板或服务管理器执行 `bash deploy/assistant/start.sh --foreground`，避免重复启动多个实例。

## 连通性验证

本机健康检查不调用模型：

```sh
.venv/bin/python deploy/assistant/verify.py \
  --health-only --base-url http://127.0.0.1:18765
```

公网健康与访问校验使用服务器私有配置：

```sh
.venv/bin/python deploy/assistant/verify.py \
  --config /etc/sensefield-assistant/gateway.json
```

需要确认实际读图能力时追加 `--visual`。该选项上传合成测试图并调用模型，可能产生费用；应识别 `SYNTHETIC GATEWAY TEST`。健康接口正常仅表示网关状态，不替代真实模型请求验证。

## 访问与额度

公开画面接口使用随机安装 UUID，保留 Nginx 的 IP 限流。安装标识不等同于硬件认证。默认额度为：

| 配置项 | 默认值 |
| --- | --- |
| `ASSISTANT_GATEWAY_PUBLIC_GLOBAL_DAILY` | 全站每日 1000 次，UTC 日界 |
| `ASSISTANT_GATEWAY_PUBLIC_INSTALLATION_DAILY` | 每安装每日 300 次 |
| `ASSISTANT_GATEWAY_PUBLIC_INSTALLATION_MINUTE` | 每安装每分钟 12 次 |

在私有配置中以字符串调整，修改后重启。有效公开请求尝试在调用模型前计数；额度保存在 `deploy/assistant/runtime/public-quota.sqlite3`，不要在更新时清空。存储仅包含安装标识哈希与计数。

助手默认关闭，用户按需授权。公开入口不接收音频；语音识别在手机完成。截图和文本通过 HTTPS 发往服务端及模型提供商，响应时间受网络和上游服务影响。APK 与语音模型分发见 [CDN 发布](CDN发布.md)。
