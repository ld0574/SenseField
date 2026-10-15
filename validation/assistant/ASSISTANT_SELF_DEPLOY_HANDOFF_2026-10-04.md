# 0.4.1 服务端自部署交付（2026-10-04）

用户最终要求先由自己部署生产服务端，再生成对应配置的最终 APK。生产域名明确为 `sf.888413.xyz`；没有连接生产主机、部署生产、验证生产 HTTPS 或上传 CDN/发布 Release。版本继续为0.4.1/code18。

## 本轮交付

ignored 目录 `output/releases/0.4.1/server-deploy-2026-10-04/` 包含服务端部署 ZIP、说明、SHA256和交付摘要。ZIP为721686 bytes，SHA256为 `c2ec0d9287d1a21b40d7a258643fddfefc076775d1ac454be2a640e00df888f6`，76个条目；逐条校验CRC并核对没有运行时密钥、设备令牌、私钥、模型权重或APK。包含源码及依赖定义，Docker镜像需由负责人在生产构建；不是离线预构建镜像。

部署入口为 `deploy/assistant/compose.yaml` 和 `prepare.sh`，详见[自部署说明](../../docs/development/assistant-online-deployment.md)。Python3.12、轻量vision-only依赖、服务端ASR关闭；后端只映射到回环18765，配置JSON及TLS只读挂载，以非root用户运行，限制CPU/内存/进程数并轮转日志。供应商密钥通过部署时不回显的交互输入写入私有配置，生成两枚独立体验连接码；没有把开发环境凭据打入包。Docker构建上下文采用明确白名单。

Nginx示例为公开域名提供TLS入口，对后端HTTPS严格验证；视觉接口设每IP30次/分钟、突发3次和最多3连接。该示例未在生产运行；生产反代为容器时须配置共同网络，不能把容器自己的回环地址当宿主机。公网DNS与证书由负责人完成。默认日志不保存问题/回答正文；`verify.py`默认只验health/401/422/413，显式`--visual`额外调用一次真实上游合成图，不自动fallback。

## 手机准备与验证

源码增加可配置的 `ASSISTANT_DEFAULT_ENDPOINT`，只在未保存地址时采用默认值；已有配置保留，设置页可选择线上服务，连接码和上传授权分别管理。预构建带 `https://sf.888413.xyz`，253项JVM、Debug构建和lint通过（0错误、22条既有警告）。预构建只留在工作区，没有交付、安装或进行生产端到端验证；部署就绪后再按用户要求生成最终APK和匹配SHA的同版本更新清单。

相关Python122项通过，1条既有Starlette弃用警告；其中4项覆盖容器配置权限、拒绝环境注入、生成独立私下连接码且不回显密钥。新增初始化用例先发现 `Path.open(opener=...)` 不支持的问题，改用内置open后通过。Compose YAML及本地安全结构、bash/Python语法、公开仓库和diff检查通过。当前工作站没有Docker CLI，未执行Compose启动或Nginx实际解析；不能把静态检查等同于生产可用。

## 测试机操作与更正

用户更正前误将所给测试机作为上线目标，已创建独立测试容器 `sensefield-assistant-041`、专用运行用户、私有配置及后端TLS文件，目录为 `/opt/sensefield-assistant`、`/etc/sensefield-assistant` 和 `/var/lib/sensefield-assistant`；只映射该机回环18765，未改现有网站入口。health200/vision_only、无认证401通过；空JSON实际为422，临时smoke错误预期400而退出，未执行后续413/付费视觉步骤。

用户明确要求不要再连接测试机后，没有再次连接、停止容器或删除上述文件。负责人需要时在该测试机执行 `docker stop sensefield-assistant-041`；不能宣称已回滚或推断其当前状态。本次生产部署包不含测试机SSH连接程序或配置，不会自动访问该机。

本轮仍缺少生产部署、正式手机端到端、外部真实发声、受控热对照及患者效用证据。历史评分、5/15秒原帧新鲜度、本地预警参数、`verified` / `release_ready`不变。
