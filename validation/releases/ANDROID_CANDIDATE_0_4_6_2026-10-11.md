# Android 0.4.6 候选版本核对（2026-10-11）

用户选择改用 0.4.6，避免继续修订已发布的 0.4.5。当前分支 `feature/jev-l2-judgment` 的应用版本、构建脚本、APK 验证器与语音隔离 fixture 同步为 0.4.6／code23；生产规则／CV Java 及识别资产未在本轮版本递增中修改。原 0.4.5 开发证据保留原制品版本和哈希。

## 公开版本核对

只读检查 GitHub：[`v0.4.5`](https://github.com/ld0574/SenseField/releases/tag/v0.4.5) 发布于 2026-10-10 06:59:31 UTC，当前为 Latest。固定网站 `https://888413.xyz/apk/latest.json` 为 0.4.5／code22，指向 Gitee 0.4.5 永久链接。清单与 GitHub APK 附件元数据均记录 24,364,276 bytes、SHA `00c3b31434d26d105eb0b96f6cad30506756f3d7d550fa752074a5ac2e86c4e4`。本次没有重新下载公开 APK，不把元数据相同当作公开字节重新验收。

## 新制品与检查

| 项目 | 本次结果 |
| --- | --- |
| 应用身份 | `com.openkhub.sensefield`，0.4.6／code23，minSdk29，arm64-v8a |
| 构建 | 混淆／资源收缩 Release 与 Release 测试包构建通过；lint Error0／Warning33 |
| 主 APK | 24,524,970 bytes，SHA `b06c0638ed8d58775826abd2a1176a9da6d510fd048a344f1555b339bc15ac0b` |
| 测试 APK | SHA `746d64ad4dfafcf403cc0236c8af3d85a28990698c4fcd406800683984956fb2`；私有图片仅进入此包 |
| 证书 | 原 Android debug 证书 SHA `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`；本地 init 指定，不是四项正式发布凭据 |
| 包完整性 | 非 debuggable、v2 签名、原生库／DEX 压缩、16KB 对齐、模型摘要和完整真实内置音频均通过 |
| 配套清单 | 从最终 APK 自动生成 0.4.6／code23、同一大小／SHA 与 Gitee 0.4.6 地址；三行摘要在100字符上限内 |
| Python | 清单生成、HTTPS fixture、内置语音共33项通过 |
| JVM | 更新客户端与取消共11项通过，失败／错误／跳过0 |
| 提交范围 | 公开仓库扫描通过；扫描器既有15项测试通过；私有制品与回执保持忽略 |
| 手机 | 覆盖安装后为0.4.6／code23，应用UID保持；主包与测试包拉回SHA一致，21项规则／CV固定输入检查通过、跳过0 |
| 交付与历史 | 新目录 `output/releases/0.4.6/`；旧0.4.5上传APK及清单哈希未变 |

当前包验证器拒绝原0.4.5／code22候选，确认旧版本不能混入0.4.6交付路径。手机覆盖后应用版本与code实际回读为0.4.6／23；公网清单前后读取完全一致，仍为0.4.5／22。

源码摘要 `fa53a7c166fc2ecc6042c5510f1fa702d7f223ed6b4facd00b50288681121b99`，由现有消消乐门禁摘要函数生成，包含版本配置、Java 和相关识别资产。原 gallery、规则、fixed UI、藤蔓补充、HUD 字形和元素参考 SHA 与 `ba1d8c9` 一致。

初次 Python 命令使用系统 Python，因缺少 pytest 未执行测试；回执保留为 `python-checks.txt`。改用仓库既有 `.venv/bin/python` 后33项通过，未安装新依赖或覆盖初次日志。

私有构建、安装、回读与测试回执在 `validation/private/release-0.4.6/`。交付文件为 `output/releases/0.4.6/gitee-upload/sensefieldv0.4.6.apk`、`cdn-upload/latest.json` 与本地 `handoff.json`；截图、设备标识、APK 和原始日志未提交 Git。

## 验证边界

本次只递增版本与核对包／更新／固定输入链路，没有启动游戏、执行交换或采集新实局。不重复计入此前完整 Debug／Release 门禁，旧回执不改名。[前批规则与 CV 工程结果](../../docs/development/match3-rules-validation.md)的首次 Debug 失败、strict 两条颜色分歧、手机 cold wall P95 +10.29% 失败、四次动作 `not_observed` 均保留。

独立人工留出、连续实局稳定和真实任务收益、受控手机成本／端到端P95／3×15分钟温升／外部实声及正式发布继续待条件。0.4.6 未创建公开标签／Release、未上传 Gitee／CDN；公网仍为0.4.5。
