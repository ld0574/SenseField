# 游戏识别资源按需下载

## 已实现的分层

- APK：推理引擎、规则、游戏配置、模型运行 metadata、`game-models.json` 资源索引；不含自训权重或学习得到的模板。
- 私有资源包：按游戏和内容版本分目录。同一版本的文件集合及 SHA-256 固定。王者包包含 320/512 模型；消消乐包包含固定 UI、图库、藤蔓、毛球、反馈模板和 HUD 字形。规则表继续属于公开代码。
- 服务端：校验部署资源，签发只允许下载指定游戏和版本的临时凭证。签名密钥只保存在资源根目录 `.download-key`，不进入代码、客户端或下载响应。
- 手机：开始辅助前，后台下载相应资源并展示进度；支持取消、断点续传、空间检查、版本隔离、SHA-256 和文件大小验证。全包通过后才原子发布，再进入系统截屏授权。已下载资源可断网复用；失败的新版本不覆盖旧版本。

ASR 继续使用现有第三方 SenseVoice 分片下载与校验机制，保留许可证。它不属于本项目需要收紧许可的自训游戏模型。

## 打包私有资源

维护者本地原始模型和模板可暂存在已忽略的 `android/app/src/main/assets/`，用于既有离线回归。它们即使存在也会被 Gradle 的 APK 资源排除规则过滤。干净克隆可以构建客户端；涉及真实模板的识别回归需另行准备私有资源。

```sh
.venv/bin/python scripts/package_game_models.py
```

输出：

- `output/model-packs/`：服务器私有载荷及资源索引，禁止上传 Git 或公开 Release 附件。
- `android/app/src/main/assets/game-models.json`：可提交的完整性索引，不含下载密钥、权重或模板数据。

打包脚本保留旧版本目录及服务端 `versions` 索引，服务端可同时向新旧客户端分发其各自绑定的版本。资源变更时生成新内容版本；部署时合并更新私有目录，保留旧索引版本和文件。APK 只带当前版本索引。此基础版本不自动推送不兼容模型，客户端加载前仍核对 profile 的权重摘要与张量合同。旧包的回收应在确认不再支持对应客户端之后进行。

## 服务器部署

1. 先更新 Python 源码。将 `output/model-packs/` 内容传到 **Web 静态目录之外** 的 `/var/lib/sensefield-assistant/game-models/`，确保服务进程可读写该根目录，以便生成私有签名密钥。不要把整个目录映射到 Nginx `alias`。
2. 在现有服务器私有配置 JSON 中新增以下字段，保留现有 Key、模型供应商和其他配置：

```json
{
  "ASSISTANT_GATEWAY_GAME_MODELS_DIR": "/var/lib/sensefield-assistant/game-models",
  "ASSISTANT_GATEWAY_GAME_MODELS_PUBLIC": "0"
}
```

`0` 为默认值，只允许现有设备 Bearer 连接码领取凭证；对应手机使用自定义服务配置。现有公开体验服务若要让普通安装用户免连接码下载，需明确改为 `1`，并保持 `ASSISTANT_GATEWAY_PUBLIC_ACCESS=1` 与持久化配额数据库。此模式提供有期限、有配额的分发，不能当作付费权益认证。

Docker 部署需额外挂载私有模型目录到容器中相应路径并赋予 UID 10001 访问权限；只读容器内不能临时创建签名密钥，应使用可写的私有卷。systemd 部署推荐使用上面的 StateDirectory 子目录，沿用服务用户和 0700 权限。

3. Nginx 增加 `/v1/game-models/` 代理路径，见仓库三份 Nginx 示例。必须透传 `Authorization`、`X-SenseField-Installation`、`Range`，关闭缓存；该下载路径允许更长的连续传输，不修改画面理解接口的时限。
4. 重启现有服务。先验证客户端首次下载，再断网重启应用验证缓存复用，然后发布瘦身 APK。未部署资源时返回明确的 `models_unconfigured`；不能先发布一个没有资源下载服务的新客户端。

## 扩展游戏

为新游戏增加推理适配和资源组，生成版本索引。运行入口调用 `GameModelPreparation.prepare(activity, gameId, readyCallback)`，推理代码通过 `GameModelStore.file(context, gameId, filename)` 获取已验证的本地文件。通用下载器不依赖具体游戏名称，不需要重复实现断点、校验或缓存。

网络请求只发生在资源准备线程，识别线程不发起下载。临时凭证不写入磁盘、不放到 URL、不输出日志；过期或服务配置变化后重新领取。应用私有目录并不构成对设备所有者的绝对保密，详见 `MODEL_POLICY.md`。

## 发布检查

`scripts/check_public_repo.py` 拒绝权重和当前六类学习模板进入 Git。`scripts/verify_android_release.py` 要求 APK 存在资源索引并拒绝其中列出的载荷；Gradle 对这些名称及常用模型后缀执行排除。

既有已公开版本的检查记录见 [2026-11-01 模型公开情况核查](../validation/model-distribution-audit-2026-11-01.md)。删除当前树中的模型不能撤回历史 Git 对象和已发布 APK，也不能追溯撤销已有授权；后续新增未公开成果采用本方案独立分发。
