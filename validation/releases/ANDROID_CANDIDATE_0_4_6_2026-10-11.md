# Android 0.4.6 候选版本核对（2026-10-11）

用户选择改用 0.4.6，避免继续修订已发布的 0.4.5。当前分支 `feature/jev-l2-judgment` 的应用版本、构建脚本、APK 验证器与语音隔离 fixture 同步为 0.4.6／code23；生产规则／CV Java 及识别资产未在本轮版本递增中修改。原 0.4.5 开发证据保留原制品版本和哈希。

本页下表保留最初版本递增制品的绑定；后续红藤蔓修订候选见文末及[对应工程记录](../match3/MATCH3_RED_VINE_CV_0_4_6_2026-10-11.md)，不能用最新包替换下表旧测试的SHA。

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

## 后续红藤蔓工程修订候选

在最初0.4.6完成第84～85关实局后，补充两个开发审阅红藤蔓姿态，规则Java、fixed UI、原gallery和HUD字形不变；版本仍0.4.6／code23。修订主包24,526,914 bytes，SHA `0b82665369119ad22d94dfef02bc132cb602df7dce9e7b6ea8734c25b1a3598b`，源码SHA `20368e57acdb71fef2f88c248708a4d088d9c37be2c37e7bab6bdddd31b04b92`；本地原证书混淆Release及包完整性通过，lint Error0／Warning33。

39项Python、561项JVM、新红藤蔓专项3项及完整混淆Release76项通过；手机覆盖安装后回读双包SHA一致，32项固定输入／规则检查通过、无跳过。详细资产、成本、旧回放对照及失败保留范围以[红藤蔓工程记录](../match3/MATCH3_RED_VINE_CV_0_4_6_2026-10-11.md)为准。最初候选的实局14／16记录不会改写成新候选实局已识别16／16。

## 后续提示窗口清晰度候选

版本仍0.4.6／code23，主包24,527,134 bytes、SHA `c3246a9e19cd8be48a1ed8c913b75e08ffce8f6a8dcb10442b30b23d9683a22f`，源码SHA `e000bd049869d93e46095f1feaa852bafa23de513d422bbd5c90266dbfa954e4`。窗口提升到预算内优先960长边；39项Python、562项JVM、完整Release79项和手机35项通过。双包回读匹配、UID保持，完整性通过、lint Error0／Warning33；旧10盘743格状态及HUD一致。首次测试／私有助手失败及本地原证书范围保留，详见[窗口记录](../match3/MATCH3_CONTEXT_QUALITY_0_4_6_2026-10-11.md)。本地交付目录已刷新，旧包和旧清单保留，未公开上传或发布。

## 后续动态语音故障恢复候选

版本仍0.4.6／code23，主包24,528,158 bytes，SHA `aa4da1d53e3fca2c311c5b9bc57c33bd233fe64bf15171eca3288f21cb266558`，源码SHA `2deda20385c8c7aba3719799196bc837038ba5ea39803a094d0e14ec494613f1`。563项JVM、模拟器17项关联混淆Release及手机5项终态注入／只读快照通过，无跳过；双包回读匹配、原证书与包完整性通过、lint Error0／Warning33。实际发声未通过静态注入升级，原候选和实局分别绑定；见[本局与声音故障记录](../match3/MATCH3_LIVE_AUDIO_0_4_6_2026-10-11.md)。本地交付材料刷新，未公开上传或发布。

## 当前手机候选：定位颜色成本修订（2026-10-12）

后续花团、冷路径和分离棋盘工程记录分别保留原候选绑定；第97～98关自然两局使用`fa66c03b…`候选。当前已安装0.4.6／code23，源码SHA `47cc25253fc0b2fc5dea835c569f96e55cf7d99831f7fba680639fa47a73209b`，主APK SHA `f28db40ab86895ad653931116160d691bc51849d4dabee1463f9e35dbb449dac`，标准辅助APK SHA `2ef619b83d4842275ce6996478fdf10327e54803d25df54e4298402be0ab3aae`。原证书、混淆／非debuggable及包完整性通过，lint Error0／Warning33；569 JVM、完整Debug111／Release86和手机6项定位／RGB穷举通过。安装前后238个原诊断成员及棋盘／语音偏好保持，实际双包SHA匹配。

55图4,050格完整输出与原候选一致；本次只降低等价背景颜色计算成本，手机新实局和完整持续运行仍待本候选自己的证据，不能借原900秒通过。当前冻结双包保存在忽略目录`validation/private/match3-background-cost-20261012/release-standard/`；本次工程修订结束时旧`output/releases/0.4.6/`分发材料没有刷新，本页此前各项回执仍对应原字节。没有公开上传或修改公网清单；完整范围见[定位成本记录](../match3/MATCH3_GEOMETRY_COST_0_4_6_2026-10-12.md)。

## 队友体验交付刷新（2026-10-12）

用户选择先生成文件、自己上传 Gitee 给队友测试。直接复制冻结且已装手机的`f28db40a…`主APK，保持24,540,215 bytes和原签名，不重新构建或签名；从其实际字节生成0.4.6／code23新清单，重新执行包完整性检查。`output/releases/0.4.6/`交付包括唯一主APK及校验文件、Gitee发布正文、上传说明与队友复测说明；旧`aa4da1d5…`整套交付归档到`output/releases/history/`，旧0.4.5材料保持。源码摘要仍为`47cc2525…`，原工程回执不改写为新测试。

本次仅文件交付，没有上传 Gitee、创建tag或修改公网清单，没有发送消息给队友。配套`cdn-upload/latest.json`生成备用，本轮不发布公网自动更新。发布说明明确原证书本地混淆Release、非debuggable及各项未完成验收；不能因此提高`verified`或`release_ready`。说明以[0.4.6更新说明](../../docs/releases/0.4.6/RELEASE_NOTES.md)和[队友复测步骤](../../docs/releases/0.4.6/复测说明.md)为准。
