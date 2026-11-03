# Jev 判定开发工具

此目录集中存放独立判定创作工具、Node 后端适配器、合成样例和设计规格。

- `无障碍判定创作工具.html`：浏览器直接打开的创作与试跑页面。
- `backend/`：渠道适配、契约检查及本地评估工具。
- `samples/`：合成输入样例。
- `specs/`、`prompts/`：协议、设计参考与开发提示词。

规格中的相对开发路径以本目录为起点。正式 Android 实现与样例在仓库的 `android/app/src/`；评估脚本直接读取该处资源，避免维护第二份 Android 源码。

无头检查见 `specs/tool-spec.md` 第 6 节。Python 画面服务的部署入口仍是仓库的 `deploy/assistant/`。
