# 消消乐断流修复出包与局域网测试位（2026-10-12）

## 目的

上一轮把「进入游戏却没有完整采集」定性为消消乐链路缺断流检测与恢复，并落了源码。本轮把这份改动做成能装到测试机上的包，把下载位重新拉活，同时把本机出 Release 候选包的真实前提量清楚——上一轮我说「本机出不了包，缺原签名与语音资产」，其中「缺语音资产」这半句被本轮实测否证。

## 本轮携带的代码改动

源码即规格，此处只登记改动面与判卷口径，不复述实现：

- `android/app/src/main/java/com/openkhub/sensefield/Match3LiveService.java`：`git diff --stat` 实测 +94/−8。新增断流状态机接线、surface 重挂恢复、通知按状态分档。
- `android/app/src/main/java/com/openkhub/sensefield/Match3CaptureStatus.java`：新文件 25 行，纯类，把「显示给玩家的采集状态」从服务里剥出来便于单测。
- `android/app/src/test/java/com/openkhub/sensefield/Match3CaptureStatusTest.java`：新文件 54 行，7 条用例。

## 门禁（2026-10-12 10:12 同轮实测）

命令：`cd android && ./gradlew :app:testDebugUnitTest --offline --rerun-tasks` → `BUILD FAILED in 28s`（失败只因 7 条红），`25 actionable tasks: 25 executed` 零 UP-TO-DATE。

聚合 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 四属性相加：files 76、tests 575、failures 7、skipped 0、errors 0。

7 条红逐名核对，与冻结名单一致，无第 8 条：`AsrModelStoreTest` 6 条（corruptSecondPartPreservesFirstAndRetriesOnlySecond、ignoredRangeRestartsInsteadOfAppending、interruptedDownloadResumesThenReusesVerifiedCache、oldSingleFilePrefixAndVerifiedModelCacheRemainReusable、shortEofPreservesPrefixForRetry、splitDownloadResumesCurrentPartAndIgnoresRangeWithoutLosingFirstPart）加 `AssistantTtsCacheTest.sweepNeverFollowsAnOwnedLookingSymlinkOutsideCache`。全部为 Windows 文件系统环境红，口径按 2026-10-10 裁决一长期豁免。

## 出包四条本机前提（逐条实测，不是仓库坏了）

1. **JDK 要在 PATH 上**。`android/app/build.gradle:278` 注册的 `verifyBundledSpeechAssets` 会调 `scripts/prepare_bundled_speech.py`，该脚本第 41 行取 `JAVA_HOME/bin/javac` 时不带 `.exe` 后缀，Windows 上 `is_file()` 恒假，于是回落 `shutil.which`；PATH 上没有 javac 就报 `Missing JDK binary: javac`。本轮把 JDK 17 的 bin 目录加进进程 PATH 解决，未改脚本。
2. **JVM 输出编码要钉 UTF-8**。脚本第 58 行用 `text=True` 读子进程 stdout，而 catalog 类打印的是含中文的 JSON，JDK 17 在中文 Windows 上按 GBK 编码 stdout，父进程按 UTF-8 解码即抛 `UnicodeDecodeError`，读线程崩掉后 stdout 变 None，再报成 `TypeError`。本轮用 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8` 解决。
3. **语音闸门在 Windows 上有路径分隔符假阳性**。见下一节。
4. **native 钉值取数只认一条 URL**。见再下一节。

## 内置语音闸门读数

`assembleRelease` 默认串到 `verifyBundledSpeechAssets`，实跑报 `Orphan or missing audio files`（脚本第 191 行）。这不是缺音频：清单里的 path 存的是正斜杠 `guide/….ogg`，而脚本第 189 行用 `str(p.relative_to(assets))` 扫盘，Windows 给出反斜杠 `guide\….ogg`，两个集合因此永不相等。POSIX 上这条判据正确，本机这条必假。

为不把描述当成结论，本轮在仓外起了一个只改分隔符口径的驱动（`_scratch_diag/_tmp_speech_verify_posix.py`，import 仓库脚本本体、把 `PurePath.__str__` 归一成正斜杠后调 `main()`），跑出与仓库脚本同一套检查的读数：

- `{"verified_files": 856, "bytes": 4332445, "test_fixture": false}`，退出码 0。
- 独立复扫：清单声明 856 条、去重后 856、盘上实扫 856，missing 0、orphan 0。

即内置语音本体是齐的，856 个 ogg 逐字节 sha256 校验、mono Opus 判定、时长与预算上限全部通过。上一轮「缺语音资产」的说法作废。

修法待裁：把脚本第 189 行改成 `.as_posix()` 即根治，本轮未动——脚本不在白名单内地界，且改动会让「闸门是否修好」混进「断流修复是否有效」这条判据里。

## native 钉值取数读数

`android/app/src/main/cpp/CMakeLists.txt` 钉了四个第三方源，本机 `.cxx` 冷树时都要现取：

- ncnn、speexdsp、webrtcvad、以及 sherpa-onnx 的 46 MB 运行时归档，URL 全在 `github.com`，直连可用（归档实测 `http=200`、46,093,321 bytes、sha256 `2ff63469…` 与钉值逐位一致）。
- 唯独 sherpa-onnx 的 C API 头文件（第 250 行）只认 `raw.githubusercontent.com`。本机这条路被重置：直连 302 后 `Recv failure: Connection was reset`；`raw.gitmirror.com`、`kkgithub.com`、`gh-proxy.com`、`cdn.jsdelivr.net` 四个镜像同轮实测全部 `http=000`。`api.github.com` 可用，配合 `Accept: application/vnd.github.raw` 取回 167,657 bytes，sha256 `2a1b9508…` 与钉值逐位一致。
- 试过「预热构建目录」这条不改仓库的路：把头文件放进 `.cxx/RelWithDebInfo/…/sherpa-onnx-v1.13.8/`，用同一个 cmake 复算哈希确认与钉值一致（`cmake -P` 回 `2a1b9508…`），但 Gradle 的 `HandleStaleOutputsStep` 在重跑该任务前会清掉它不认识的产物，连同目录的探针标记文件一起消失，故预放留不住。
- 最终是靠本机代理（`127.0.0.1:7897`，实测在听、经它取头文件回 `http=200` 167,657 bytes）让 cmake 自己完成那次下载。代理一关，下次构建会退回原样。

## 包身份与装机代价

`assembleRelease --offline -x verifyBundledSpeechAssets` 于 09:28 出包：`BUILD SUCCESSFUL in 1m 42s`，51 tasks 全执行。产物 `app-release-unsigned.apk` 17,886,913 bytes（Release 未配签名 env 即出未签名包，见 build.gradle 第 9 至 31 行的四项 env 判定）。随后用本机 Android debug keystore 签名，落 `output/_serve_lan/SenseField-0.4.6-stallfix-arm64-release-cand.apk`，实测 mtime 2026-10-12 09:29:47、17,979,324 bytes、sha256 `04c377602d8c9146f09175181adb111fd1efe6278c0d365f708b20f73b7c3853`。

- `aapt2 dump badging`：package `com.openkhub.sensefield`、versionCode 23、versionName 0.4.6、native-code 仅 `arm64-v8a`、label 听野、launchable `GameSelectionActivity`。
- `apksigner verify --print-certs`：Signer #1 证书 SHA-256 `624a0d84c5bfcfeb…`（DN 为 Android Debug）。
- 包内标记（python 读全部 dex 拼字节再计数）：`capture_starvations`、`capture_health`、`CAPTURE_STARVED` 三个新字符串常量各命中 1 次，采集线程名 `m3live-capture` 亦命中 1 次；`assets/speech/` 下 `.ogg` 856 个；`lib/arm64-v8a/` 7 个 so。断流修复确实在包里。
- 诊断导出没被 debug 门关掉：`BuildConfig.DEBUG` 在本模块只门控 `Match3LiveService.testInstance()` 与调试实例登记（第 46、1220 行），导出走 `DiagnosticsActivity` 的界面入口；`GameProfile.java:238` 读的 `FLAG_DEBUGGABLE` 是被辅助游戏的属性，不是本应用。
- 装机代价：**必须先卸载**。测试机现有公开线包签名是另一条证书（`5a42a53a` 系），本机这枚是 `624a0d84` 系，覆盖安装必报签名不符；卸载丢消消乐标定与本地设置。降级轴这次不拦——versionCode 23 高于公开线的 22。
- 分发边界：这包用 debug keystore 签，按发布硬规则 0.4.6 只接受正式 Release，因此它只做测试分发，不进公网清单、不覆盖公开附件。
- 两处 CDN 依赖没打进包，闸门只警告不拦：小地图 YOLOX 私有模型缺失（绑它的 profile 跑不了）、本机 ASR 权重缺失（持续语音报不可用且不回落云端）。与本轮改动无关，但会影响装机观感。

## 分发与回读

服务：以隐藏窗口方式起 `python -m http.server 8777 --bind 0.0.0.0`，工作目录即下载位所在目录，实测 PID 8732、`netstat` 回 `TCP 0.0.0.0:8777 LISTENING`。

- 地址实测：`Get-NetIPAddress -InterfaceAlias WLAN` 回 192.168.0.107。地址随 DHCP 漂，每次给链接前重取（沿用待裁第 22 条口径）。
- 本机 curl 一律带 `--noproxy '*'`，否则走 127.0.0.1:7897 代理得到 502。
- 回环与局域网地址两条都回 `HTTP/1.0 200 OK`、`Content-Length: 17979324`。
- 从局域网地址取回落盘复比哈希：sha256 与原件逐位一致。
- 防火墙：三个 profile 均 Enabled，当前网络被归类为 Public，`Get-NetFirewallRule` 实测存在一条放行本机 Python311 解释器的入站 Allow——即启动服务用的那个解释器；Public 的 `DefaultInboundAction` 读数 NotConfigured。手机侧连通仍属未证，见下节。
- 装机链接：`http://<本机WLAN地址>:8777/SenseField-0.4.6-stallfix-arm64-release-cand.apk`。

## 未证与待裁

1. 断流后 surface 重挂能否真叫醒镜像虚拟屏，未经真机证明；本轮全部结论只以 JVM 单测加代码路径为证。
2. 分不清「生产者停帧」与「handler 线程卡死」两种断流，故没有另起看门狗线程。
3. `FAILED`（三次恢复失败）当前只改通知、不停服务，是否应像王者链路那样停服务并要求重新授权，待裁。
4. 语音闸门的 Windows 分隔符假阳性是否单开一轮修脚本，待裁。
5. 手机能否连通 8777 未证——本轮只做了同机自测；若装机拉不到包，先查 WLAN 网段与防火墙，不要先怀疑包。

## 边界与未动

- 未 commit、未 push、未 tag。工作树改动仍是上轮那三个源码文件加本文件与索引行；`output/_serve_lan/` 与 `_scratch_diag/` 留作仓外未跟踪。
- `android/app/build.gradle`、CMakeLists 的 ncnn 与 sherpa 钉值、判卷三件冻结文件（含全部既有 Match3 测试类与 `docs/plans/消消乐目标价值排序.md`）一字未改。
- 一次性脚本与构建日志在 `SenseField/_scratch_diag/`，不入库；本轮删除了自己落的未签名中间副本。
