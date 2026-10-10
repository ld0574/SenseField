# 听野文档索引

按用途查阅产品方案、实施计划、开发指南和发布记录。文档中的历史实验结论保留原始日期，最新验证结论见[当前验证状态](../validation/STATUS.md)，逐批证据见[验证记录索引](../validation/README.md)。

需求与赛事资料、项目自写方案、开发与发布说明，以及整理后的公开调研摘要可纳入公开仓库；具体范围见[公开文档范围](公开文档范围.md)。原始会议与完整检索稿保留本地。

## 快速入口

| 需要做什么 | 从这里开始 |
| --- | --- |
| 了解玩家问题与产品边界 | [赛题背景](design/赛题背景.md) |
| 安装当前版本 | [GitHub 0.4.5](https://github.com/ld0574/SenseField/releases/tag/v0.4.5) · [安装与使用说明](releases/0.4.5/RELEASE_NOTES.md) |
| 查看最新版本变化 | [版本与交付索引](releases/README.md) |
| 查看当前主线与验证边界 | [0.4.5 说明](releases/0.4.5/RELEASE_NOTES.md) · [当前验证状态](../validation/STATUS.md) |
| 查找测试与排查记录 | [按主题查找验证记录](../validation/README.md) |
| 部署助手服务 | [直接启动与部署步骤](../deploy/assistant/README.md)（部署脚本统一在 `deploy/assistant/`） |
| 上传 APK、模型与后续发包 | [Gitee 下载分发与后续发布](../deploy/assistant/CDN发布.md)（同版本覆盖、新版本、固定清单和下载核对） |
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
| [符合度复核与迭代证据](plans/符合度改造方案.md) | 历史评估、逐批实现／验证边界及当前待验证项目 |
| [消消乐通关价值排序](plans/消消乐目标价值排序.md) | 本地任务读取、独立棋子状态、一步收益排序与独立验收边界 |
| [消消乐新元素可信识别 Goal](plans/消消乐新元素可信识别.md) | 外观身份、交换权限、目标效果分别核验；陌生家族自动归集与验收边界 |
| [0.4.0 目标、接口与验收记录](../validation/assistant/GOAL_0.4.0_2026-10-03.md) | 开发中的语音与画面助手目标、接口和待验证条件 |

## 开发与数据 · `development/`

| 文档 | 内容 |
| --- | --- |
| [团队协作与本地运行](development/团队协作与本地运行.md) | 环境安装、标注、回放、Android 构建与协作约定 |
| [外部数据引入与预训练](development/外部数据引入与预训练.md) | 来源审计、导入要求与训练顺序 |
| [消消乐自动回归](development/match3-regression.md) | JVM、原生故障序列、投影、最终 Release 与门禁复现 |
| [消消乐元素证据与扩展](development/match3-element-evidence.md) | 不凭颜色猜规则，按家族补一次证据，诊断图片与缓存的边界 |
| [Android 真实助手传输测试](development/assistant-android-transport-test.md) | 合成输入、临时证书、真实 GLM/CPU ASR 与正式客户端联调 |
| [Linux 测试网关部署](development/assistant-gateway-test-deployment.md) | 隔离测试部署、CPU 依赖、HTTPS/WSS 与未覆盖门禁 |
| [Android 自动更新](development/app-update.md) | 0.4.1/code18：固定网站清单与 Gitee APK 下载、同版本 SHA 修订及校验规则；历史同源 CDN 结果单独保留。实际发包见[Gitee发布步骤](../deploy/assistant/CDN发布.md) |

## 发布记录 · `releases/`

[发布索引](releases/README.md)汇总当前主线的发布与使用说明、此前版本及历史交付，并提供[版本命名规范](releases/版本命名规范.md)和[GitHub 发布检查清单](releases/GITHUB发布检查清单.md)。公开分发与实际体验验收分别记录；消消乐仍为体验版。

## 需求与赛事资料 · `requirements/`

需求书、赛题背景 PDF、符合度评估书和赛事手册在 `requirements/` 中公开收录，清单与阅读链接见[需求与赛事资料说明](requirements/README.md)。

## 公开调研摘要 · `research/`

[游戏无障碍案例与需求验证](research/游戏无障碍案例与需求验证.md)整理可追溯的公开项目案例、社区维护经验与听野待验证的问题。原始检索稿保留在 `references/research/`。

## 本地参考资料 · `references/`

启动会资料归入 `references/meetings/`，需求与案例调研归入 `references/research/`。文件清单见[参考资料说明](references/README.md)；原始资料继续由 Git 忽略，干净克隆不包含这些文件。

## 目录维护约定

- `docs/` 只保存文档，包括 Markdown 与已有 PDF。APK、ZIP、校验文件和交付元数据留在 `output/releases/<版本>/`；发布文档可记录文件名、大小、SHA-256 和公开下载地址。
- 部署脚本、反代和服务模板及部署操作入口统一放在 `deploy/assistant/`；部署主文档为该目录的 `README.md`。
- 验证、诊断和逐场实验记录按主题放在 `validation/` 子目录，并更新[验证索引](../validation/README.md)；当前状态放在 `validation/STATUS.md`，旧状态快照进入 `validation/archive/`。训练命令放在 `training/README.md`。
- 新增文档时更新本索引；新增交付时在 `releases/<版本>/` 保存说明，并更新发布索引。
- 项目自写文档按文件加入 `.gitignore` 与公开仓库检查脚本的允许清单，原始参考资料保留本地。
