# 0.4.8 服务端覆盖更新

2026-11-04 的 code30 是客户端修订。已经按下述方式部署完整服务包的服务器无需再次更新 Python、模型或 Nginx。

使用本地完整私有包 `sensefield-assistant-0.4.8-private-full-20261103.zip`，上传到服务器 `/tmp/`。包内就是项目目录结构，包含服务端源码和全部王者、消消乐模型，没有 `source/` 外层目录。

现有 `/data/wwwroot/sf/` 部署只需停止、覆盖、启动：

```sh
cd /data/wwwroot/sf
bash deploy/assistant/start.sh stop
unzip -o /tmp/sensefield-assistant-0.4.8-private-full-20261103.zip -d /data/wwwroot/sf
bash deploy/assistant/start.sh start
```

启动会自动识别 `/data/wwwroot/sf/game-models/`，校验随包资源并合并版本索引，保留旧模型和 `.download-key`。无需单独导入模型或修改私有配置；原有显式目录、分发模式及启动参数继续生效。

包内不含 Key、`gateway.json`、`.venv`、`deploy/assistant/runtime/`、配额数据库或运行中的模型索引，因此覆盖不会替换这些已有数据。随包新索引为 `game-models/bundle-index.json`，由启动程序合并到本机 `game-models.json`。

现有 Nginx 继续复用。本轮只读探测中 `/v1/game-models/ticket` 已返回 JSON 405（该接口接受 POST），无需重新配置站点。包内模型目录权限为 0700，模型文件为 0600；仍通过带临时凭证的 API 分发。

完整私有包仅用于服务器更新，不上传公开 Release 或 CDN。APK 与网站 `latest.json` 继续按原 Gitee 发布流程同步。
