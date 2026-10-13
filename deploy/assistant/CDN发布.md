# 听野 Gitee 下载分发与后续发布

更新日期：2026-10-13。面向负责打包、上传和发包的队友。当前 GitHub 公开版本为 `0.4.6 / versionCode 23`，普通 Release、Latest；唯一最新本地交付位于 `output/releases/0.4.6/`，本轮 Gitee 和公网清单由用户同步。助手服务部署另见 [README](README.md)。

## 0.4.5 公开发布记录（历史）

0.4.5 消消乐代码已在当时合入 GitHub `main`；GitHub 普通 Release、Latest 及 Gitee 的发布核对见[公开发布记录](../../validation/releases/GITHUB_RELEASE_0_4_5_2026-10-10.md)。该版本交付保留在：

```text
output/releases/0.4.5/
├── gitee-upload/sensefieldv0.4.5.apk
└── cdn-upload/latest.json
```

历史 APK 为 24,364,276 bytes，SHA-256 `00c3b31434d26d105eb0b96f6cad30506756f3d7d550fa752074a5ac2e86c4e4`，永久地址为 [sensefieldv0.4.5.apk](https://gitee.com/leda/SenseField/releases/download/0.4.5/sensefieldv0.4.5.apk)。当时的实际试用问题及验收范围见[合并反馈记录](../../validation/match3/MATCH3_TASK_FEEDBACK_0_4_5_2026-10-10.md)，不改绑为最新 0.4.6 的证据。

## 下载地址怎么分工

大文件由 Gitee Release 分发，原网站只保留固定版本清单。后续发版始终更新同一个 `latest.json`，APP 从清单读取最新 APK 地址。

| 内容 | 公开地址 | 后续如何维护 |
| --- | --- | --- |
| 版本清单 | [https://888413.xyz/apk/latest.json](https://888413.xyz/apk/latest.json) | 每次发布最后覆盖，文件由最终 APK 自动生成 |
| 本次要更新的 APK | [sensefieldv0.4.6.apk](https://gitee.com/leda/SenseField/releases/download/0.4.6/sensefieldv0.4.6.apk) | 本地配套清单指向此文件；用户上传并核对公开下载后，再更新公网清单 |
| 语音模型第一片 | [sensevoice-int8-v1.zip.part01](https://gitee.com/leda/SenseField/releases/download/0.4.1/sensevoice-int8-v1.zip.part01) | 上传一次，后续 APK 继续复用 |
| 语音模型第二片 | [sensevoice-int8-v1.zip.part02](https://gitee.com/leda/SenseField/releases/download/0.4.1/sensevoice-int8-v1.zip.part02) | 上传一次，后续 APK 继续复用 |

**保留 0.4.1 Release 和两个模型分片。** 即使以后发布新版本、清理旧 APK，也不能一起删除这两个资源附件。新手机首次开启连续语音仍需要它们；已校验的手机模型缓存继续复用。实验 AI 助手默认关闭。

清单和模型配置只填写上述永久 Release 链接。浏览器下载时可能跳转到 `foruda.gitee.com` 临时签名地址，APP 已适配本项目的跳转；不要把临时地址复制进配置或清单。不要把清单移到固定的 0.4.1 Release，否则旧 APP 无法通过固定入口发现后续版本。

## 当前 0.4.6 同版本修订交付

用户明确选择继续 0.4.6／code23 并更新 Gitee 已有附件。最新王者两字开关修订为 `output/releases/0.4.6/sensefieldv0.4.6.apk`；主 APK 为 24,586,457 bytes，SHA-256 `535a653a927f57744922951f63ec9014041e04959823404bb1662a6d80b4f080`。沿用原证书的混淆非 debuggable Release，609 项 Release JVM、Release lint 和实际包完整性检查通过。GitHub 普通 Release、Latest 已回读，四个附件与本地相同；三局修订的手机回放及软件成本结果保持原 `ead5e38a…` APK 绑定。

更新已有 Gitee **0.4.6** Release 的同名 APK 附件和校验单。发布正文使用交付目录 `publishing/GITEE_RELEASE.md` 的三行玩家摘要，`publishing/RELEASE_DETAILS.txt` 可作为完整说明附件；使用及验证范围见[版本说明](../../docs/releases/0.4.6/RELEASE_NOTES.md)和[复测说明](../../docs/releases/0.4.6/复测说明.md)。实际上传 APK 使用版本根目录 `sensefieldv0.4.6.apk`，同目录校验单覆盖 APK、使用说明和清单。

配套清单已由最终 APK 生成，位于 `output/releases/0.4.6/latest.json`。先下载核对 Gitee APK 与本地大小和 SHA 一致，再更新固定 `/apk/latest.json`。客户端按 SHA 识别同版本修订；模型分片继续复用 0.4.1。本轮 Gitee 与公网清单上传由用户执行；独立准确率、最终包实声和受控温升继续待验收。

当前唯一交付结构：

```text
output/releases/0.4.6/
├── sensefieldv0.4.6.apk
├── installation-guide.txt
├── latest.json
├── SHA256SUMS.txt
├── publishing/
└── validation/
```

2026-10-13 已按用户要求删除其他 0.4.6 安装包及重复交付目录；旧源码和工程回执仍按原 APK SHA 集中保存，详见[制品清理记录](../../validation/releases/ANDROID_ARTIFACT_CLEANUP_0_4_6_2026-10-13.md)。0.4.5 历史公开附件、清单和记录保留。语音模型分片继续留在 **0.4.1 Release**，无需重新上传到 0.4.6。

## 后续发布步骤

### 1. 构建最终 APK 和配套清单

本次已测 APK 可直接从上述唯一交付目录上传，无需重新构建。以下用于后续生成新制品：在已准备 Android 构建环境、模型资源、内置语音和原签名的工作站，将四个 `SENSEFIELD_KEYSTORE_PATH`、`SENSEFIELD_KEY_ALIAS`、`SENSEFIELD_KEYSTORE_PASSWORD`、`SENSEFIELD_KEY_PASSWORD` 设为本机环境变量，再从仓库根目录运行。缺少完整语音或签名便停止，不回退 Debug；生成、来源限制及校验见[离线音频构建](../../docs/development/offline-speech.md)。

构建命令：

```sh
bash scripts/build_android_preview.sh
```

构建脚本原有临时输出路径如下；本次 0.4.6 已测交付以版本根目录为准：

```text
output/releases/<版本>/
├── gitee-upload/sensefieldv<版本>.apk
└── cdn-upload/latest.json
```

**这两个文件必须成对使用。** 脚本从签名后的 APK 读取版本、包名、文件大小和 SHA-256，生成清单；它不会上传文件。重新构建或重新签名后，必须使用重新生成的清单。

当前已安装版本沿用原测试证书。队友机器自动生成的 debug 签名通常不同，不能直接给现有用户覆盖更新。由持有原签名的构建负责人出包；签名证书 SHA-256 应为：

```text
5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68
```

如果 APK 已由其他流程构建，使用已有工具从那一份最终文件生成清单；需要 Android SDK 的 `aapt2`：

```sh
python3 scripts/build_app_update_manifest.py \
  --apk output/releases/0.4.6/sensefieldv0.4.6.apk \
  --apk-url https://gitee.com/leda/SenseField/releases/download/0.4.6/sensefieldv0.4.6.apk \
  --output output/releases/0.4.6/latest.json
```

可用 `--notes-file docs/releases/0.4.6/UPDATE_SUMMARY.txt` 添加更新弹窗专用摘要。不要手工修改清单的版本、大小或哈希。

**弹窗摘要最多三条、总计不超过100字符（含标点和换行），每条一句短话，不加版本标题或空行。** 只写玩家最需要知道的变化；完整更新记录和使用说明留在 GitHub／Gitee Release 页面，不整篇复制到 `release_notes`。生成工具会拒绝超过三行或100字符的输入，不会自动截断。

0.4.6 使用以下摘要，源文件为 `docs/releases/0.4.6/UPDATE_SUMMARY.txt`：

```text
已在消消乐前100关中的典型关卡完成真机实测。
保留毛球、末块雪块、魔力鸟组合及交换语音修复。
修复王者关闭“两字方位提示”后，密集场景仍播两字的问题。
```

只调整摘要时，从同一份最终 APK 重新生成 `latest.json` 并覆盖网站清单即可，无需重新构建 APK；核对版本、下载地址、大小和 SHA-256 均未改变。

### 2. 上传 Gitee 附件并核对

本次更新已有0.4.6 Release，替换 `sensefieldv0.4.6.apk` 和校验文件；未来真正递增版本时再新建对应Release。模型分片继续使用 0.4.1 的固定资源链接；无需再次上传。

在 Gitee 后台确保永久下载链接对应新附件。若后台不能直接覆盖，按后台能力替换同名旧 APK；避免留下多个同名 APK。此时先保留网站旧清单，下载新版公开 APK，核对它与本地最终包的大小和 SHA-256。

下面以本次 0.4.6 交付为例，所有命令在仓库根目录运行；未来版本相应替换 URL、文件名和本地清单路径：

```sh
mkdir -p output/cdn-check
curl --fail --location --proto '=https' --proto-redir '=https' \
  --connect-timeout 10 --max-time 180 \
  --output output/cdn-check/sensefieldv0.4.6.apk \
  https://gitee.com/leda/SenseField/releases/download/0.4.6/sensefieldv0.4.6.apk

python3 - <<'PY'
from pathlib import Path
import hashlib
import json

manifest = json.loads(Path('output/releases/0.4.6/latest.json').read_text())
apk = Path('output/cdn-check/sensefieldv0.4.6.apk')
digest = hashlib.sha256()
with apk.open('rb') as stream:
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
if apk.stat().st_size != manifest['apk_bytes'] or digest.hexdigest() != manifest['apk_sha256']:
    raise SystemExit('公开 APK 与本地清单不匹配：先核对附件和缓存，暂不发布清单。')
print('公开 APK 大小与 SHA-256 均匹配。')
PY
```

浏览器能下载不代表附件已更新。发现哈希不匹配时，核对同名旧附件、后台替换结果与缓存；确认公开下载匹配后再继续。Gitee 管理的缓存需要按其后台能力处理。

### 3. 最后发布固定版本清单

将配套 `latest.json` 上传到原网站 `/apk/latest.json`，使公开地址仍为 `https://888413.xyz/apk/latest.json`。原网站不用再上传 APK 或模型。

该路径设置 `Cache-Control: no-cache`，覆盖后刷新自有站点/CDN 的清单缓存。不要只替换 APK 而漏掉清单，否则用户可能收不到修订通知，或下载后校验失败。

读取线上清单，与本地文件核对：

```sh
curl --fail --proto '=https' --connect-timeout 10 --max-time 30 \
  --output output/cdn-check/latest.json \
  https://888413.xyz/apk/latest.json

python3 - <<'PY'
from pathlib import Path
import json

local = json.loads(Path('output/releases/0.4.6/latest.json').read_text())
online = json.loads(Path('output/cdn-check/latest.json').read_text())
if online != local:
    raise SystemExit('线上清单不一致：核对上传目录和清单缓存。')
print('线上清单与本地交付一致。')
PY
```

最后在已安装迁移版的安卓手机上停止辅助，点击“检查更新”，实际验证下载、系统安装及更新后不重复提示。首次开启连续语音还要验证模型准备成功。这些手机验证不能用电脑下载核对替代。

## 清单正常，为什么手机仍提示无法检查更新

先按上面的步骤核对 **Gitee 永久链接实际返回的 APK**，再确认手机安装的是同一份文件。文件名和版本号都显示 `0.4.1`，仍可能是不同修订；不能只看版本名称判断附件已替换。

2026-10-06曾出现：网站清单已经对应迁移包 `3fe263c1…`，Gitee同名附件和手机实际安装的仍是旧包 `dceb7260…`。旧包只接受原网站的同源下载地址，读取到Gitee地址便拒绝清单；界面把具体原因统一显示为“暂时无法检查更新”。

当时手动覆盖正确迁移包后，自动和手动检查均显示“当前版本 0.4.1 无需更新”。当前候选升级到 0.4.6/code23，不再混用 0.4.1 的多个修订；需要 APK 与新清单成对上传。详见[迁移故障排查记录](../../validation/releases/APP_UPDATE_GITEE_MIGRATION_2026-10-06.md)。其他检查失败仍需核对网络、清单响应和手机实际错误，不能一概归因为旧包。

## 同版本修订与真正新版本

| 发布情况 | 版本和下载地址 | 要上传什么 |
| --- | --- | --- |
| 同版本修订（仅负责人明确选择时） | 保持该版本名称和 code，以 SHA-256 区分修订；本次不再修订 0.4.1 | 新 APK 和重新生成的 `latest.json`；模型不动 |
| 本次同版本修订 | 保持 `0.4.6 / code23` 和现有下载地址，按用户选择更新已有Release附件 | 新APK及校验文件；公网推送时再配套更新固定网站清单，模型不动 |
| 语音模型确实变更 | 新建独立资源名称/地址，更新 APK 内固定资源元数据，再构建 | 新模型分片、新 APK、配套清单；保留仍有客户端依赖的旧模型 |

本次按用户选择继续0.4.6，旧安装包和重复交付已删除，历史源码及工程证据按SHA保留；未发布0.4.7候选撤回，已发布0.4.5记录保留。以后可按发布策略选择同版本修订或新版本；真正升级时，开发者需同步修改 `android/app/build.gradle` 的 `versionName/versionCode`、`scripts/build_android_preview.sh` 的 `PREVIEW_VERSION_NAME/PREVIEW_VERSION_CODE` 及 `scripts/verify_android_release.py` 的身份校验；脚本会按新版本自动生成 Gitee APK URL，固定清单地址保持不变。仅创建 Gitee 新 Release 不会自动通知旧 APP，仍需最后更新网站清单。

每个版本的分发目录只保留一份 APK 和配套清单；0.4.2保留在自己的目录作本轮设计对照，不混入0.4.6上传目录。临时下载用于校验的 APK 在校验后删除。`output/` 被 Git 忽略，队友需从当前交付路径取得构建产物。更高 `versionCode` 的客户端不能通过普通更新安装较低序号的旧包。

## 模型分片与容量

| 附件 | bytes | SHA-256 |
| --- | ---: | --- |
| `sensevoice-int8-v1.zip.part01` | 90,000,000 | `8f10f3eb21a42da17453241658e8bd3cdb4998c18b91e5b8bfd71df2f93048f5` |
| `sensevoice-int8-v1.zip.part02` | 70,304,482 | `f60da186700ee0465a5e2ebabe9e8c80a19d798ff63ae40fc2e2f5bed8077220` |

两片均小于单附件 100MB，合计 160,304,482 bytes。APP 自动按顺序拼接、校验和解压，用户无需手动合并。它们是原 ZIP 的字节分片，不能各自解压，也不要重新压缩或改名。模型配置在 [sensevoice.metadata.json](../../android/app/src/main/assets/sensevoice.metadata.json)；打包工具为 [package-asr-model.py](package-asr-model.py)。

当前两片模型与一个 0.4.6 本地 APK 合计约 184.8MB。按普通仓库总附件 1GB 规划，仓库其他附件与历史 APK 也计入配额，以 Gitee 后台实际统计为准。后续不重复上传模型；清理历史附件时保留两个模型分片。拆片解决单文件限制，不减少总空间或下载量。

## 本次公开核对记录

2026-10-06已完整读取这两个公开分片，大小与 SHA-256 均匹配 APP 配置，下载使用 APP 的实际 HTTPS 传输类在 Mac JVM 运行。证据见 [Gitee 分发验证](../../validation/releases/GITEE_DISTRIBUTION_2026-10-06.md)。0.4.1 公网 APK 与清单的历史错配已另行核对，见上述迁移排查记录；0.4.6 的公网附件和清单需由负责人上传后按上述步骤验收。
