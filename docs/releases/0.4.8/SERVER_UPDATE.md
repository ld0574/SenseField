# 0.4.8 服务端原地更新

现有项目目录是 `/data/wwwroot/sf/`，公网服务是 `https://sf.888413.xyz`。继续使用已配置的 Nginx、证书、端口和服务管理方式。不要重新运行首次安装或配置向导。

## 更新文件

本版公开源码包为 `sensefield-assistant-0.4.8-20261103.zip`，可从 [GitHub 0.4.8 Release](https://github.com/ld0574/SenseField/releases/tag/v0.4.8) 下载。ZIP 按项目相对路径组织，可直接覆盖现有项目根目录，不包含 Key、私有配置、运行数据或私有模型。

保留原 `.venv`、`deploy/assistant/runtime/`、`/etc/sensefield-assistant/gateway.json` 和配额数据库；若原部署使用其他私有配置路径，继续使用原路径。当前模型供应商、模型名称和 Key 均不需要因本次更新而改变。

将 ZIP 放到服务器 `/tmp/`，核对随包 SHA-256 后更新：

```sh
cd /data/wwwroot/sf
bash deploy/assistant/start.sh stop
unzip -o /tmp/sensefield-assistant-0.4.8-20261103.zip -d /data/wwwroot/sf
bash deploy/assistant/start.sh start
curl -fsS https://sf.888413.xyz/health
```

若原进程由面板或 systemd 管理，用原管理器停启；不要额外启动第二个后台实例。使用自定义配置或端口时沿用原启动参数。

## 只核对模型下载路径

2026-11-03 的只读检查得到：`/health` 返回 200，而 `/v1/game-models/ticket` 返回 HTML 404。原有画面服务在线，但尚未确认新下载路径接通。

在已存在的 `sf.888413.xyz` HTTPS `server` 块中检查 `location /v1/game-models/`。已有该块就核对当前生效配置，不重复添加。缺少时只合入 [增量 location](../../../deploy/assistant/nginx.game-models.location.conf)，`proxy_pass` 继续使用已正常工作的 `/health` 上游协议与端口。不要覆盖整份 Nginx 配置，也不改原 `/v1/visual` 超时。检查通过后再重载：

```sh
sudo nginx -t
sudo nginx -s reload
curl -sS -o /dev/null -w '%{http_code}\n' https://sf.888413.xyz/v1/game-models/ticket
```

最后一条是 GET 探测；新服务只接受 POST，正常接入时应为 JSON 405。它只证明路由接通，不能代替模型下载验收。

## 私有模型沿用现有目录

已有 `ASSISTANT_GATEWAY_GAME_MODELS_DIR` 时继续使用原值，不迁移目录、不重置 `.download-key`，保留旧版本资源。尚未导入模型的服务器，将本地 `game-models-private.zip` 合并解压到这个私有目录；该 ZIP 不上传 GitHub、Gitee Release 或 CDN。模型目录应位于公开静态目录之外。

若相关配置尚未建立，按 [游戏模型按需下载](../../development/game-models.md) 在原私有配置中补充目录和分发模式，保留全部原字段。公开体验服务使用 `ASSISTANT_GATEWAY_GAME_MODELS_PUBLIC=1`，同时复用已启用的公开访问与持久化配额；设备连接码模式使用 `0`。

用没有模型缓存的客户端确认王者、消消乐各自首次下载、取消续传和离线复用。本轮授权验收手机已预置缓存，不计作生产首次下载通过。确认后再按原流程上传 Gitee APK，并最后更新网站 `/apk/latest.json`。
