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

现有项目根目录继续使用 `/data/wwwroot/sf/`，私有模型放在 `/data/wwwroot/sf/game-models/`，与源码一起交付。完整私有包没有外层 `source/`，按 [0.4.8 服务端更新](../releases/0.4.8/SERVER_UPDATE.md) 执行停止、覆盖、启动即可。

覆盖包只交付 `bundle-index.json` 和按版本存储的载荷，不覆盖本机运行索引、签名密钥或配额数据库。`start.sh start` 自动校验载荷、合并索引并保留旧版本。首次启动默认使用项目内的模型目录；默认分发模式跟随此启动器现有的 `ASSISTANT_GATEWAY_PUBLIC_ACCESS`，复用持久化配额。显式配置始终优先，私有配置文件不被改写。

需要自定义目录或仅使用连接码的部署，可以在现有私有配置中设置下面字段；常规覆盖更新无需添加：

```json
{
  "ASSISTANT_GATEWAY_GAME_MODELS_DIR": "/data/wwwroot/sf/game-models",
  "ASSISTANT_GATEWAY_GAME_MODELS_PUBLIC": "0"
}
```

`0` 只允许设备 Bearer 连接码领取凭证；`1` 复用已启用的公开访问和持久化配额。通用 CLI 的默认策略仍是 `0`；现有公开体验启动器默认跟随公开访问模式。公开分发有期限和配额，不能当作付费权益认证。

Docker 部署需额外挂载私有模型目录到容器中相应路径并赋予 UID 10001 访问权限；只读容器内不能临时创建签名密钥，应使用可写的私有卷。systemd 部署需允许原服务用户读写该路径。新目录使用 0700 权限；已有其他私有路径时，可沿用该路径和 `.download-key`，无需为使用新版客户端而强制迁移。

现有 Nginx 模型路由已接通，覆盖更新不修改它。新站点参考三份 Nginx 示例：代理 `/v1/game-models/`，透传 `Authorization`、`X-SenseField-Installation`、`Range` 并关闭缓存，拒绝 `/game-models/` 静态访问。随包目录权限为 0700、载荷权限为 0600，不把模型目录映射为静态 `alias`。

发布前仍应由维护者验证客户端首次下载和离线复用；这些验收不属于每次更新所需的手工操作。未部署资源时返回明确的 `models_unconfigured`。

## 扩展游戏

为新游戏增加推理适配和资源组，生成版本索引。运行入口调用 `GameModelPreparation.prepare(activity, gameId, readyCallback)`，推理代码通过 `GameModelStore.file(context, gameId, filename)` 获取已验证的本地文件。通用下载器不依赖具体游戏名称，不需要重复实现断点、校验或缓存。

网络请求只发生在资源准备线程，识别线程不发起下载。临时凭证不写入磁盘、不放到 URL、不输出日志；过期或服务配置变化后重新领取。应用私有目录并不构成对设备所有者的绝对保密，详见 `MODEL_POLICY.md`。

## 发布检查

`scripts/check_public_repo.py` 拒绝权重和当前六类学习模板进入 Git。`scripts/verify_android_release.py` 要求 APK 存在资源索引并拒绝其中列出的载荷；Gradle 对这些名称及常用模型后缀执行排除。

既有已公开版本的检查记录见 [2026-11-01 模型公开情况核查](../validation/model-distribution-audit-2026-11-01.md)。删除当前树中的模型不能撤回历史 Git 对象和已发布 APK，也不能追溯撤销已有授权；后续新增未公开成果采用本方案独立分发。
