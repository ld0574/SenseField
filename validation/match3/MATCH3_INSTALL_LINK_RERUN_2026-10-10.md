# 消消乐装机链路重测与主树快进（2026-10-10）

## 目的

cand2 那只候选包（0.4.35-cand2／code33）装机读数迟迟未回，局域网下载位上一轮已清、服务已停；本轮把装机链路重测活，并把主树维护到远端最新。

## 装机链路（2026-10-10 08:39 起实测）

- 下载位重建：`output/candidates/SenseField-0.4.35-cand2-arm64-debug-20261008T0852.apk`（12,946,538 bytes）复制为 `output/_serve_lan/SenseField-0.4.35-cand2-arm64-debug.apk`，sha256 逐位一致 `047ec9358fa5987d309eca34bfa6ab515667c1b8bf878f7aaf28023c47c44ee6`。
- 服务以脱离作业方式拉起（`Win32_Process Create`，python http.server，PID 26192），监听 `0.0.0.0:8777`（netstat 实测 LISTENING）。
- 本机链路：`curl -sI http://127.0.0.1:8777/SenseField-0.4.35-cand2-arm64-debug.apk` 回 `200`、`Content-Length: 12946538`（08:41:35 实测）。
- 局域网链路：主机当前地址 `<本机WLAN地址>`（WLAN，`Get-NetIPAddress` 实测；172.23.64.1 是 WSL 虚拟网卡，不用）。直连 `curl -sI --noproxy '*' http://<本机WLAN地址>:8777/...` 回 `200`、`Content-Length: 12946538`（08:42:34 实测）。注意：本机 curl 默认走 127.0.0.1:7897 代理，局域网自测必须先 `--noproxy`，否则 502（这是代理行为，不是链路故障）。
- 装机链接（手机与本机同网段时用）：`http://<本机WLAN地址>:8777/SenseField-0.4.35-cand2-arm64-debug.apk`。地址仍随 DHCP 漂，每次给链接前先实测（沿用上一份待裁第 22 条）。

## cand2 装机读数判定

- 未回。截至 08:47 实测，外部接收目录（本机聊天软件的接收目录，不写名）内 `sensefield-diag-*.zip` 最新 mtime 为 2026-10-07 20:59（尾号 1791377802441，555,345 bytes）；解开核验 `app_version = 0.4.35-cand1`，`0.4.35-cand2`、`BoardShape`、`CollectScoring` 三处标记 0 命中——是 cand1 时代的包。
- Downloads、Desktop、主工作区三个落点找 10-08 之后新到的诊断包 0 个。
- 结论：完成条件④（装机读数判挂牌采点）仍判不了；标定操作卡（原 BLOCKED 第 1 条）保持待命，装机包回传后照卡改常量。

## 主树快进（c654051 → d862069）

- 远端比本地多 4 个提交：`4fc075e`（目标进度排序 feat）、`c785fe9`（merge 目标原型）、`51bad0a`（待机误修与美术）、`d862069`（新布局证据校验）。`git merge --ff-only` 快进，51 文件、+3,128/−181，`git rev-list` 对齐 0/0。
- 案卷惯例变化：原根目录 `PROGRESS.md`／`BLOCKED.md` 被远端 rename 进 `validation/match3/history/`（相似度 96%／93%），按轮记录改为 `validation/match3/MATCH3_*_YYYY-MM-DD.md`，索引在 `validation/match3/README.md`。本文件即按新惯例落。
- 版本线：build.gradle `versionCode 22`／`versionName '0.4.5'`，`-fixture` 候选版本机制在第 164 行起。cand2 的 0.4.35 手改版本名在新线上不成立，沿用上一份待裁第 26 条（rebase 与否留裁）。

## 新基线门禁（2026-10-10 08:44 落盘实测）

- 命令：`cd android && ./gradlew :app:testDebugUnitTest --offline --rerun-tasks` → `BUILD FAILED in 1m 50s`（失败只因 7 条红），`25 actionable tasks: 25 executed` 零 UP-TO-DATE。
- 聚合（TEST-*.xml 四属性相加）：files=68、tests=465、failures=7、skipped=0、errors=0。
- 7 条红仍为同名 Windows 既存红：`AsrModelStoreTest` 6 条（ignoredRangeRestartsInsteadOfAppending、interruptedDownloadResumesThenReusesVerifiedCache、oldSingleFilePrefixAndVerifiedModelCacheRemainReusable、shortEofPreservesPrefixForRetry、splitDownloadResumesCurrentPartAndIgnoresRangeWithoutLosingFirstPart、corruptSecondPartPreservesFirstAndRetriesOnlySecond）加 `AssistantTtsCacheTest.sweepNeverFollowsAnOwnedLookingSymlinkOutsideCache`（第 55 行 `Files.createSymbolicLink` 要特权）。
- 465 的构成含远端新增的 Match3 单测（CrossLevel／GoalConfirmation／GoalOutcome／GoalValue 等）；okhttp 依赖已在缓存，本轮 `--offline` 直接跑通，不再需要先联网拉依赖。
- 远端自己的交付口径（见 MATCH3_LIVE_FEEDBACK_0_4_5_2026-10-10）是 455 项 0 失败，那是 Release／仪器口径；本机这条是 JVM 全量、Windows 环境，7 条文件系统红照旧豁免。

## 边界与未动

- 未 commit、未 push、未 tag：本文件与 `output/_serve_lan/` 留在工作树；`_scratch_diag/` 仍未跟踪。
- cand2 候选包分发仍只走局域网直连，无公开位置。
- 待裁沿用：③ DHCP 静态地址（第 22 条）。

## 两条待裁裁决（2026-10-10 09:10 领导指示「按推荐方案执行」，按本执行者推荐落地）

1. **7 条 Windows 既存红：门禁口径长期豁免，不单开专修轮。** 依据：a) 7 条红全是 Windows 文件系统环境问题（`.part` 共享锁 ×6、`Files.createSymbolicLink` 要特权 ×1），与消消乐功能零相关，Linux/macOS 与 CI 上不存在；b) 远端自己的交付口径（MATCH3_LIVE_FEEDBACK_0_4_5_2026-10-10，455 项 0 失败）本就不含这 7 条；c) 修它们要动生产代码文件句柄生命周期或测试临时目录/符号链接策略，风险面大于收益。落地：本机门禁口径从「423/7/0/0（7 条红必须恰好是这 7 个名字）」更新为「tests 只增不减、failures 恰好等于这 7 个名字、skipped 恒 0、errors 恒 0」——红的必须是且仅是这 7 条，第 8 条红即失败。豁免不自动放宽：新增任何红（含改名）都判失败，豁免名单冻结在本文件上轮门禁小节。
2. **cand-ag 树：先收完 cand2 装机读数再谈 rebase，本轮一行不动。** 依据：a) 装机读数是唯一能定挂牌采点常量的真机证据，rebase 会改版本线（0.4.35 手改 → 0.4.5 fixture 机制）并重跑门禁，若读数回来要求改常量，rebase 前后的改动面要各做一遍；b) 撞车面实测只有 `Swap` 八字段构造与 `collected` 六参重载一处，风险可控，不急着先并；c) 装机链接已测活（本文件装机链路小节），收读数的关键路径是手机装包+回传诊断包，不是代码。触发条件：cand2 诊断包回传且读数判完（或领导改判），再按「旧基线读数收完→rebase 到 1c09949 线→按 fixture 规则重做版本名→重跑门禁→出新候选」顺序执行。

两条裁决均只改本机口径与后续顺序，未改任何生产代码、测试或 build 配置；服务保持在线等装机包。
