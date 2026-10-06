# 0.4.1 Gitee附件分发迁移

日期：2026-10-06。用户改用Gitee Release分发APK，并给出`https://gitee.com/leda/SenseField/releases/download/0.4.1/sensefieldv0.4.1.apk`；语音ZIP超过其普通项目100MB单附件限制，总附件额度按用户提供的1GB计算。本轮保持0.4.1/code18和原签名，准备可手动上传的迁移包，不自动上传、推送或发布。

## 下载与资源

固定更新清单保留`https://888413.xyz/apk/latest.json`（几百字节），用于发现未来版本；APK URL指向本项目Gitee Release。固定某个版本的Gitee清单不能发现后续tag，因此没有把默认清单钉到0.4.1。旧客户端只接受同源下载，不能直接处理新的Gitee清单地址；此迁移APK先手动覆盖安装一次，之后自动下载仍经过Android系统安装确认。

APK地址只允许原同源HTTPS，或自有固定清单指向`gitee.com/leda/SenseField/releases/download/`。资源和APK均使用`ReleaseDownloadPolicy`，适配实测跳转：

```text
gitee.com/leda/SenseField/releases/download/0.4.1/<文件>
 → gitee.com/leda/SenseField/attach_files/<数字>/download/<同名文件>
 → foruda.gitee.com/attach_file/<数字>/<同名文件>?<临时签名>
```

限制为HTTPS、443、无用户凭据/片段、本项目及同名文件；临时签名只允许foruda实际附件路径，重试始终重新请求原Release URL，不保存/记录签名URL。其他跨域、其他项目、主机后缀欺骗、改文件名和非TLS跳转仍拒绝。APK大小、SHA、包名、版本、签名及同版本不同字节检查保留。

`sensevoice-int8-v1.zip`原为160,304,482 bytes；ZIP哈希仍是`d863ab6f0b202ee31e74f2a3eadabaf24a41ea7acfe9fc6f85a1efaed2e37a8c`，按原字节切成：

| 文件 | bytes | SHA-256 |
| --- | --- | --- |
| sensevoice-int8-v1.zip.part01 | 90,000,000 | 8f10f3eb21a42da17453241658e8bd3cdb4998c18b91e5b8bfd71df2f93048f5 |
| sensevoice-int8-v1.zip.part02 | 70,304,482 | f60da186700ee0465a5e2ebabe9e8c80a19d798ff63ae40fc2e2f5bed8077220 |

两包都小于十进制100MB；原ZIP不上传。手机后台按顺序写入一个临时ZIP，逐包校验、再整包校验、解压后再校验model/tokens。模型、revision、运行库、线程和推理参数不变，旧模型缓存继续复用；同字节的旧ZIP下载断点也可跨迁移复用。无需额外合并文件副本，空间要求仍约400 MiB。

支持Range/206时按当前包偏移续传；Gitee当前参考APK的`Range: bytes=0-31`探针被忽略，返回200完整包，所以不承诺服务器支持Range。返回200时只重下未完成那一包；完整已校验的包保留。坏包只删除其后缀，取消/短EOF保留有界前缀，不能先解包未经全部校验的内容。拆包不会节省总存储/下载量。

模型仅固定上传一次，后续Release继续复用0.4.1资源链接，不每版复制。模型加当前约49MB APK总附件约209MB；仓库其他附件和旧APK也计入额度，可用容量以Gitee后台为准。负责人可按需要保留/清理旧APK，代码不自动删除远端附件，不承诺Gitee的CDN服务等级或无限配额。

打包工具集中在`deploy/assistant/package-asr-model.py`，部署/上传说明在`deploy/assistant/README.md`第6节；`scripts/build_android_preview.sh`默认生成Gitee APK清单。分包spec钉在APK的`sensevoice.metadata.json`，不用远端不受控模型清单覆盖模型。更新模型须另建资源版本并重新构建APK。

## 已验证与边界

- 330项JVM通过，包括逐包下载、第二包中断、Range被忽略、坏第二包仅重下第二包、旧下载前缀/缓存复用、metadata分包大小与重复校验，及Gitee/foruda允许路径和拒绝路径。
- 20项Python通过（分包与清单），覆盖逐包摘要、完整ZIP重建、原字节复用、错误模型/ZIP、单附件限制与不安全Release地址。另对实际90MB/70.3MB两包重建完整SHA，结果与原ZIP一致。
- 使用APP实际`AsrModelStore.HttpsSource`在Mac JVM读取已上传Gitee参考APK全量48,676,351 bytes，耗时23.795秒，SHA为`dceb7260cb1c2880040da83b2a2d8c2feb03351b0bb2bfdba62e88b5a3888a34`，与迁移前本地交付完全一致。此为APP资源传输类的真实HTTPS/跳转验证，不是Android更新界面或设备安装；该参考URL当时仍是上一版APK，不是本次迁移包。
- arm64构建与lint通过（0错误、34警告）。核对原证书、0.4.1/code18、Android10+、16KB页对齐、包内分包配置、近区策略和消消乐。助手仍默认关闭；无ASR权重进入APK。

初次交付时两个新分包未上传，迁移APK公网匹配、Android实际下载/安装或连续语音未验收。后续负责人上传分片并提供公开链接，核对结果见下节。没有手机ADB连接，未新跑instrumentation。公网清单仍由负责人最后更新；迁移APK与清单须另行核对，避免同名旧附件缓存/旧附件仍被解析。

## 后续模型公开核对与队友操作文档

2026-10-06，负责人提供了两个永久Release下载链接。使用本次APP编译出的`AsrModelStore.HttpsSource`在Mac JVM完整读取两包，均为HTTP200，实际大小与SHA-256均匹配APK固定配置：

| 文件 | 实际bytes | SHA-256 | 本次下载耗时 |
| --- | ---: | --- | ---: |
| sensevoice-int8-v1.zip.part01 | 90,000,000 | 8f10f3eb21a42da17453241658e8bd3cdb4998c18b91e5b8bfd71df2f93048f5 | 43.465秒 |
| sensevoice-int8-v1.zip.part02 | 70,304,482 | f60da186700ee0465a5e2ebabe9e8c80a19d798ff63ae40fc2e2f5bed8077220 | 34.097秒 |

两包并行核对；以上为本机网络单次结果，不是手机速度、P95、Android模型解压/准备或更新安装结果。公开模型字节与APP HTTPS跳转链路已核对；本次没有重新核对公网APK和latest.json，没有安装手机、上传或修改远端文件。

证据保存在`output/releases/0.4.1/gitee-public-models-2026-10-06/verification.json`及逐包日志。新增队友操作入口[CDN发布.md](../deploy/assistant/CDN发布.md)，说明永久链接、固定清单、原签名、上传先后、同版本覆盖、新版本code/tag、模型复用与公开字节核对。部署README、项目README及文档/发布索引已链接该入口；自动更新开发文档中的旧同源口径修正，历史摘要另行保留。

提交前全量Python回归首次发现启动测试遗留环境变量，使后续两项配置测试受影响。修正`tests/test_assistant_direct_start.py`的环境隔离，并让其子进程显式继承测试环境；未修改生产启动或模型配置。再次全量运行为768项通过、1项跳过，耗时43.92秒；另有1条FastAPI/Starlette测试客户端弃用警告。该结果不增加手机或生产端到端验收结论。

## 手工交付

先上传`output/releases/0.4.1/gitee-upload/`内的两个分包，再覆盖同一Release中的`sensefieldv0.4.1.apk`，最后把`output/releases/0.4.1/cdn-upload/latest.json`覆盖到原网站。三个大附件均不足100MB。旧交付对与说明留档至`archive-before-gitee-2026-10-06`；中文APK别名和体验ZIP同步。

迁移APK为48,676,535 bytes，SHA-256 `3fe263c1bd33cec98ab55d0a155ee83f0f1f9e932969aebbbf45ed3dc5be5f2c`；原证书SHA-256 `5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`不变。三个大附件合计208,981,017 bytes，清单对应本次最终APK，中文别名及体验ZIP内摘要相同。DEX中确认包含分包下载和Gitee策略，原过密修订也保留。

摘要、证书及测试结果见`output/releases/0.4.1/gitee-migration-2026-10-06/verification.json`和`output/releases/0.4.1/交付核验.json`。公开仓库检查通过。本轮未改变近区频率修订、音频呈现或助手回答策略，不把分发迁移当作延迟/可听性/温升改善；评分与`verified`／`release_ready`保持原值。
