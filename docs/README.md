# 听野文档索引

按用途查阅产品方案、实施计划、开发指南和发布记录。文档中的历史实验结论保留原始日期，最新验证结论见[当前验证状态](../validation/STATUS.md)。

需求与赛事资料、项目自写方案、开发与发布说明，以及整理后的公开调研摘要可纳入公开仓库；具体范围见[公开文档范围](公开文档范围.md)。原始会议与完整检索稿保留本地。

## 快速入口

| 需要做什么 | 从这里开始 |
| --- | --- |
| 了解玩家问题与产品边界 | [赛题背景](design/赛题背景.md) |
| 安装公开体验版 | [0.3.8 安装与使用说明](releases/0.3.8/RELEASE_NOTES.md) |
| 查看最新版本变化 | [0.3.8 公开体验版](releases/0.3.8/RELEASE_NOTES.md) |
| 查看当前源码候选 | [0.4.1 自动更新候选说明](releases/0.4.1/RELEASE_NOTES.md) · [CDN 同版本修订记录](../validation/APP_UPDATE_CDN_0.4.1_2026-10-04.md)（未发布，CDN 清单上传待完成） |
| 部署助手服务 | [直接启动与部署步骤](../deploy/assistant/README.md)（部署脚本统一在 `deploy/assistant/`） |
| 参与开发或标注 | [团队协作与本地运行](development/团队协作与本地运行.md) |
| 查看版本历史与发布要求 | [发布索引](releases/README.md) |

## 产品与技术方案 · `design/`

| 文档 | 内容 |
| --- | --- |
| [赛题背景](design/赛题背景.md) | 用户问题、辅助方式与需要验证的能力 |
| [技术方案](design/技术方案.md) | 系统架构、数据与模型流程、性能及演示边界 |
| [视野记忆](design/视野记忆.md) | 事件状态机、提示规则和相关性过滤 |
| [端侧事件感知与可靠性增强](design/端侧事件感知与可靠性增强技术方案.md) | 输出调度、玩家状态与屏幕采集恢复 |
| [设置页分组上下文说明](design/SETTINGS_CONTEXTUAL_HELP.md) | 标题摘要、手机底部／宽屏右侧面板、按需详细帮助与可访问性约定 |

## 实施计划 · `plans/`

| 文档 | 内容 |
| --- | --- |
| [黑客松方案收敛与实施路线](plans/黑客松方案收敛与实施路线.md) | 已定决策、工作包、日程与实施记录 |
| [符合度复核与 0.4.0 证据计划](plans/符合度改造方案.md) | 35 项历史评估的证据边界与 0.4.0 网络数据路径 |
| [0.4.0 目标、接口与验收记录](../validation/GOAL_0.4.0_2026-10-03.md) | 开发中的语音与画面助手目标、接口和待验证条件 |

## 开发与数据 · `development/`

| 文档 | 内容 |
| --- | --- |
| [团队协作与本地运行](development/团队协作与本地运行.md) | 环境安装、标注、回放、Android 构建与协作约定 |
| [外部数据引入与预训练](development/外部数据引入与预训练.md) | 来源审计、导入要求与训练顺序 |
| [Android 真实助手传输测试](development/assistant-android-transport-test.md) | 合成输入、临时证书、真实 GLM/CPU ASR 与正式客户端联调 |
| [Linux 测试网关部署](development/assistant-gateway-test-deployment.md) | 隔离测试部署、CPU 依赖、HTTPS/WSS 与未覆盖门禁 |
| [Android 自动更新](development/app-update.md) | 0.4.1/code18：CDN APK GET/hash 仍为旧包，`latest.json` 当前 404；本地同版本 UI 安装已验证，手动上传与缓存刷新待完成。见[CDN 修订记录](../validation/APP_UPDATE_CDN_0.4.1_2026-10-04.md) |

## 发布记录 · `releases/`

[发布索引](releases/README.md)汇总 0.3.0-alpha.1、0.3.1–0.3.8、0.4.0 历史工程候选与 0.4.1 自动更新候选说明，并提供[版本命名规范](releases/版本命名规范.md)和[GitHub 发布检查清单](releases/GITHUB发布检查清单.md)。公开体验版、本地候选和开发中的源码版本分别记录。

## 需求与赛事资料 · `requirements/`

需求书、赛题背景 PDF、符合度评估书和赛事手册在 `requirements/` 中公开收录，清单与阅读链接见[需求与赛事资料说明](requirements/README.md)。

## 公开调研摘要 · `research/`

[游戏无障碍案例与需求验证](research/游戏无障碍案例与需求验证.md)整理可追溯的公开项目案例、社区维护经验与听野待验证的问题。原始检索稿保留在 `references/research/`。

## 本地参考资料 · `references/`

启动会资料归入 `references/meetings/`，需求与案例调研归入 `references/research/`。文件清单见[参考资料说明](references/README.md)；原始资料继续由 Git 忽略，干净克隆不包含这些文件。

## 目录维护约定

- `docs/` 只保存文档，包括 Markdown 与已有 PDF。APK、ZIP、校验文件和交付元数据留在 `output/releases/<版本>/`；发布文档可记录文件名、大小、SHA-256 和公开下载地址。
- 部署脚本、反代和服务模板及部署操作入口统一放在 `deploy/assistant/`；部署主文档为该目录的 `README.md`。
- 验证、诊断和逐场实验记录继续放在 `validation/`，训练命令放在 `training/README.md`。
- 新增文档时更新本索引；新增交付时在 `releases/<版本>/` 保存说明，并更新发布索引。
- 项目自写文档按文件加入 `.gitignore` 与公开仓库检查脚本的允许清单，原始参考资料保留本地。
