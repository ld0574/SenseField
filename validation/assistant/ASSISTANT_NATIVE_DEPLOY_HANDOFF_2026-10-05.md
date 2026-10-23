# 0.4.1 原生服务端自部署交付（2026-10-05）

用户说明生产主机没有 Docker，规格为 2 核 4GB。当前服务器只运行鉴权、截图校验及外部 Qwen API 网关，服务端 ASR 已关闭，语音识别留在手机端。此规格可作为少量体验者的试运行起点，不是容量、内存峰值或时延实测结论；之前针对服务器 ASR 的较高起步规格不适用于本轮轻量网关。

## 原生部署入口

新增 `deploy/assistant/install-native.sh`、`sensefield-assistant.service` 与 `nginx.native.example.conf`，操作见[原生部署说明](../../docs/development/assistant-native-deployment.md)。Linux/systemd、Python3.10–3.13、venv及OpenSSL即可，无需Docker。Ubuntu/Debian的安装命令与其他发行版前提分开说明；尚未得到用户具体发行版。

安装器只在负责人本机执行，不含SSH或远程自动化。非root服务用户为 `sensefield-gateway`，应用/虚拟环境位于 `/opt/sensefield-assistant`；配置JSON和后端私钥位于 `/etc/sensefield-assistant`，均为服务用户owner/0400；体验连接码root/0600私下分发。入口沿用owner-only配置加载，systemd环境仅存配置路径，不写上游密钥。原生初始化采用 `--runtime native`，强制初始配置为回环127.0.0.1、ASR disabled、上游并发1和持久化TLS路径；容器模式仍保持原默认。

systemd设置CPUQuota100%、MemoryMax512M、TasksMax128并开启记账，限制core dump，使用只读系统、隔离home/tmp/devices及非root运行。后端HTTPS只监听本机18765，Nginx严格验证后端证书并为sf域名提供公网TLS。安装脚本不启动服务、不改反代或全机日志保留；journald遵循既有主机策略。初始化不覆盖已有配置/连接码；非受管目录和运行中服务拒绝覆盖；初始化中断造成连接码文件缺失时明确停止，避免误报安装完整。

## 交付及验证

ignored目录 `output/releases/0.4.1/server-native-deploy-2026-10-05/` 包含“听野v0.4.1 服务端部署包（无Docker）.zip”、简明说明、SHA256和摘要。ZIP为728831 bytes、76个条目，SHA256 `7fa14b1259f4629664958d823d182325579a8b48f346df32bc087013a996bd3f`。从明确源码白名单打包并校验CRC，不含APK、运行时凭据、连接码、私钥、模型权重或远程连接程序；不属于离线依赖包，安装仍需访问Python包源。

Python3.12相关网关/游戏问答测试150项通过，1条既有Starlette弃用警告；新增原生回环与TLS路径参数覆盖和已有配置/连接码拒绝覆盖检查。Shell/Python语法、diff检查和公开仓库扫描通过（486个候选文件；添加本记录后再扫为487）。pip绝对路径extras通过解析检查，没有实际安装。子agent只读复核发现初始化部分写入的连接码缺失边缘情况，已加停止检查。

本轮没有连接测试机或生产机，没有运行Linux/systemd/Nginx部署，没有调用视觉上游或重建/安装手机APK。上述静态和本地测试不证明生产运行、容量、真实声学延迟或患者效用。CPU、RSS、OOM/重启及上游耗时须由部署后的试运行验证；服务器升级也不能单独保证外部模型响应更快。此前测试机容器剩余状态见[10月4日记录](ASSISTANT_SELF_DEPLOY_HANDOFF_2026-10-04.md)，本轮未处理它。

交付顺序仍为用户自行部署 `sf.888413.xyz`，服务检查通过后再生成对应配置的0.4.1/code18 APK。没有push、CDN上传或Release；历史评分、本地预警参数、封存视频及 `verified` / `release_ready` 不变。
