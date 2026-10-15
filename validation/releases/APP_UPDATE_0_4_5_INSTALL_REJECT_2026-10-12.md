# 0.4.5 装不上的判定：版本降级轴与两把 debug 证书（2026-10-12）

## 起因与取证边界

安卓端装不上公开的 0.4.5 包，本轮先定成因，再决定要不要动公网清单。本机无 adb 通道，手机侧版本读数与安装器报错原文未取到；下列结论以公开包字节的当轮重新下载回读、本机包实测和仓库现码路径为证，不写成真机验证。

## 公开链实测

| 制品 | versionCode | 大小 bytes | 证书 SHA-256 前 8 位 | ABI | minSdk／target |
| --- | --- | --- | --- | --- | --- |
| GitHub v0.4.4 附件 sensefieldv0.4.4.apk | 21 | 24,222,060 | `5a42a53a` | arm64-v8a | 29／35 |
| GitHub v0.4.5 附件 sensefieldv0.4.5.apk | 22 | 24,364,276 | `5a42a53a` | arm64-v8a | 29／35 |

两包的 SHA-256 与 GitHub 附件元数据逐字符一致，0.4.5 为 `00c3b314…86c4e4`；`apksigner` 判 0.4.5 为 v2 签名有效、v1 关闭、单个 Android Debug signer。公开链的 0.4.4 与 0.4.5 用同一把证书。

## 本机候选链实测

| 制品 | versionCode | 证书前 8 位 |
| --- | --- | --- |
| 本机归档 0.4.31／0.4.32／0.4.33／0.4.34 | 28／29／30／31 | `624a0d84` |
| cand1／cand2 | 32／33 | `624a0d84` |

本机只有一把 keystore（仓外默认 debug keystore，路径不写），`keytool` 实测证书指纹 `62:4A:0D:84:…`，与本机全部包逐位一致；公开链那把 `5a42a53a…` 在本机不存在。

本机与公开分发因此是两条版本线加两把 debug 证书，不是同一条链的前后两版。

## 公网清单现值

`https://888413.xyz/apk/latest.json` 于 2026-10-12 07:37:41 实读为 HTTP200，仍公告 0.4.5／code22，APK 地址指向 Gitee 0.4.5 永久链接。主树 `android/app/build.gradle:80-81` 已是 0.4.6／code23，清单落后主线一个版本、落后本机候选 9～11 个 code。

## 判定

版本轴是主因。0.4.5 的 code 为 22。手机只要已经是 0.4.6／code23（[0.4.6 核对记录](ANDROID_CANDIDATE_0_4_6_2026-10-11.md)记有从 0.4.5 用 `install -r` 覆盖成功），或任一 0.4.3x／cand 包（code 28～33），装 0.4.5 就是降级，系统安装器必拒。

签名轴只在跨链安装时命中。手机装的是本机出的 0.4.3x 或 cand（证书 `624a0d84`），再装公开 0.4.5（证书 `5a42a53a`）会同时多一条证书不符；手机一直走公开 0.4.4 到 0.4.5 则同证书，只有版本轴。装机方拿 cand2 装不上属于两条轴同时中。

应用内另有四道闸门，均为仓库现码，非推测：

- `AppUpdateClient.java:534-537`：清单 `version_code` 低于本机就直接返回空，界面只显示「当前版本 X 无需更新。」（`AppUpdateController.java:154`），连下载都不发起。这是静默失效不是报错，所以对已装新版的人，检查更新永远是绿的假象。
- `AppUpdatePackageVerifier.java:81-83`：包版本号低于已装 → 「更新安装包版本低于当前已安装版本。」
- 同文件 `:91-95`：再按版本名逐段数字比较 → 「此更新会降低应用版本，已阻止安装。」
- 同文件 `:106-108`：证书集合不等 → 「更新安装包使用了不同的应用签名证书。」

## 0.4.6 交付包不在本树

`output/releases/` 现有目录为 0.3.5-alpha.13～21、0.4.2、0.4.3、0.4.30、0.4.31，没有 0.4.4／0.4.5／0.4.6。同根的 `SenseField-cand-ag`、`SenseField-fixpack-20261007` 两棵树与空目录 `SenseField-artifacts` 内，深度 5 以内没有 `output/releases/0.4.6` 目录；本机用户目录内（系统缓存目录除外）全量搜索文件名 `sensefieldv0.4.6.apk`，到 2026-10-12 07:39:52 搜索结束为 0 命中。

而 `validation/STATUS.md` 当前制品表把分发材料写成「新 APK 在 `output/releases/0.4.6/gitee-upload/`」——这条指针在本树不可复现。本记录只登记差集，不改写该表：0.4.6 是在备有原签名的另一棵工作树建出来的，那批制品与回执仍绑定原摘要。

结论：把公网清单升到 0.4.6 这一步在本机做不成。[CDN 发布步骤](../../deploy/assistant/CDN发布.md)要求 0.4.6 只接受带原证书的正式 Release、缺签名或完整内置语音即停止、不回退 Debug；本机既没有那两个交付文件，也没有 `5a42a53a` 那把 keystore，四项发布凭据环境变量全未配置（`android/app/build.gradle:9`）。本轮公网清单未改动，公开附件未被覆盖，也没有自签一个 Debug 包冒充发布。

### 外部现状两查（2026-10-12 08:10 前后实测）

清单能不能翻，取决于 0.4.6 的 APK 在不在公开下载地址上。两条直连探针（绕开本机代理）：

| 探测 | 结果 |
| --- | --- |
| Gitee 0.4.6 永久下载地址 sensefieldv0.4.6.apk | HTTP 404，包不存在 |
| Gitee 0.4.5 永久下载地址（对照） | HTTP 200，跳转到临时签名地址，整包 24,364,276 bytes 可下 |
| GitHub Release tag v0.4.6 | API 返回 Not Found |

所以此刻把 `latest.json` 的 `version_code` 改成 23、`apk_url` 指向 Gitee 0.4.6，等于给所有手机一个 404 地址：现在旧清单的故障表现是「检查更新永远显示无需更新」的静默假绿，改完会变成「下载失败」的活故障，属于把问题改大而不是改小。

清单也不能靠转抄造出来。`scripts/build_app_update_manifest.py` 的 `--apk` 是必填项，`version_code`、`version_name` 由 aapt2 从包内读、`apk_bytes` 与 `apk_sha256` 由该文件现算（`:227` 起）；没有那份最终字节，生成器跑不动，硬写出来的清单就是把案卷转抄值当仪器读数用。判卷规格在 `tests/test_app_update_manifest.py`，未改动。

站点侧覆盖也没有本机通道：仓库里只有 `output/sync_gitee_0.4.2.py` 这类需要 `--token` 传入 Gitee 私人令牌的脚本，其注释记明 Windows 凭据管理器里的 gitee.com 凭据已失效；`888413.xyz/apk/latest.json` 由负责人自行覆盖，本机无该站点凭据。


## 下一步

对外动作由具备原签名与语音资产的工作站执行，顺序不能颠倒——清单先动会让手机拿到一个还没上传的 APK 地址：

1. 在那台工作站核对 `output/releases/0.4.6/gitee-upload/sensefieldv0.4.6.apk` 的字节与 SHA，案卷现值为 24,528,158 bytes、`aa4da1d5…6558`（动态语音恢复修订那批）。
2. 先上传到 Gitee 的 0.4.6 Release，用永久下载地址回读一次，大小与 SHA 与本地一致才算过。
3. 用最终包现生成清单（参数形态按 `scripts/build_app_update_manifest.py:263-273` 实测）：`python scripts/build_app_update_manifest.py --apk output/releases/0.4.6/gitee-upload/sensefieldv0.4.6.apk --apk-url https://gitee.com/leda/SenseField/releases/download/0.4.6/sensefieldv0.4.6.apk --output output/releases/0.4.6/cdn-upload/latest.json`，可选 `--notes-file` 传更新弹窗摘要（最多三行、100 字符内）。
4. 把生成的清单覆盖到 `888413.xyz/apk/latest.json`（站点侧由负责人执行，本机无该站凭据），覆盖后实读一次清单，确认 code 从 22 变成 23。

语音模型两个分片继续留在 0.4.1 Release，不随 0.4.6 重传，也不许清理。

不涉及发布的即时解法：别装 0.4.5。手机已是 0.4.6 就停在 0.4.6；确实要回到 0.4.5 必须先卸载，代价是丢本机设置与标定读数。

定案还差一个手机侧读数：应用信息里那一行版本号，或系统安装器的报错原文。拿到它就能在本记录上把「版本轴为主」升级为「唯一成因」，或补上签名轴。

## 保留与边界

- 手机侧读数本轮未取，版本轴判定依赖公开包 code 与案卷覆盖安装记录，签名轴判定依赖本机包与公开包的证书实测。
- 本轮只读下载与仪器取数，未构建、未跑 JVM 门禁、未改代码，因此不存在门禁退级；案卷改动为本记录与 `validation/releases/README.md` 两处指针。
- 0.4.6 各修订包的实局、真实收益、受控成本、温升与外部实声仍待验收，状态全部沿用原记录，未因本轮改写。
