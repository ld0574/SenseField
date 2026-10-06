# 听野 Gitee 下载分发与后续发布

更新日期：2026-10-07。面向负责打包、上传和发包的队友。本次交付版本为 `0.4.3 / versionCode 20`，由负责人上传后生效；助手服务部署另见 [README](README.md)。

## 下载地址怎么分工

大文件由 Gitee Release 分发，原网站只保留固定版本清单。后续发版始终更新同一个 `latest.json`，APP 从清单读取最新 APK 地址。

| 内容 | 公开地址 | 后续如何维护 |
| --- | --- | --- |
| 版本清单 | [https://888413.xyz/apk/latest.json](https://888413.xyz/apk/latest.json) | 每次发布最后覆盖，文件由最终 APK 自动生成 |
| 当前 APK | [sensefieldv0.4.3.apk](https://gitee.com/leda/SenseField/releases/download/0.4.3/sensefieldv0.4.3.apk) | 本次新建 0.4.3 Release，上传这一份 APK |
| 语音模型第一片 | [sensevoice-int8-v1.zip.part01](https://gitee.com/leda/SenseField/releases/download/0.4.1/sensevoice-int8-v1.zip.part01) | 上传一次，后续 APK 继续复用 |
| 语音模型第二片 | [sensevoice-int8-v1.zip.part02](https://gitee.com/leda/SenseField/releases/download/0.4.1/sensevoice-int8-v1.zip.part02) | 上传一次，后续 APK 继续复用 |

**保留 0.4.1 Release 和两个模型分片。** 即使以后发布新版本、清理旧 APK，也不能一起删除这两个资源附件。新手机首次开启连续语音仍需要它们；已校验的手机模型缓存继续复用。实验 AI 助手默认关闭。

清单和模型配置只填写上述永久 Release 链接。浏览器下载时可能跳转到 `foruda.gitee.com` 临时签名地址，APP 已适配本项目的跳转；不要把临时地址复制进配置或清单。不要把清单移到固定的 0.4.1 Release，否则旧 APP 无法通过固定入口发现后续版本。

## 本次 0.4.3 上传什么

唯一交付位置相对于仓库根目录：

```text
output/releases/0.4.3/
├── gitee-upload/
│   └── sensefieldv0.4.3.apk
└── cdn-upload/
    └── latest.json
```

先把 APK 上传到 Gitee 的 **0.4.3 Release**，确认永久下载地址与本地大小和 SHA-256 相同，再把配套 `latest.json` 覆盖到原网站 `/apk/latest.json`。文件大小、摘要和版本以这份自动生成的清单为准。

本次只需上传这两个文件，语音模型不变。模型分片继续留在 **0.4.1 Release**；本地位于 `output/releases/0.4.1/gitee-upload/`，不要重复上传到 0.4.3。

旧 0.4.1 APK 本地交付副本已清理。本轮只交付一份0.4.3 APK，0.4.2作为设计对照保留在独立目录，上传时使用0.4.3目录中的文件。线上仍使用仅支持同源下载的旧包时，需要先手动下载 0.4.3 并覆盖安装一次；支持 Gitee 的迁移客户端可正常应用内升级。安装保留应用数据，仍由 Android 系统确认。

## 后续发布步骤

### 1. 构建最终 APK 和配套清单

在已准备 Android 构建环境、模型资源和原签名的工作站，从仓库根目录运行：

```sh
bash scripts/build_android_preview.sh
```

脚本生成：

```text
output/releases/0.4.3/
├── gitee-upload/sensefieldv0.4.3.apk
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
  --apk output/releases/0.4.3/gitee-upload/sensefieldv0.4.3.apk \
  --apk-url https://gitee.com/leda/SenseField/releases/download/0.4.3/sensefieldv0.4.3.apk \
  --output output/releases/0.4.3/cdn-upload/latest.json
```

可用 `--notes-file /path/to/更新说明.txt` 添加面向玩家的简短说明。不要手工修改清单的版本、大小或哈希。

### 2. 上传 Gitee 附件并核对

本次新建 0.4.3 Release，上传 `sensefieldv0.4.3.apk`。模型分片继续使用 0.4.1 的固定资源链接；无需再次上传。

在 Gitee 后台确保永久下载链接对应新附件。若后台不能直接覆盖，按后台能力替换同名旧 APK；避免留下多个同名 APK。此时先保留网站旧清单，下载新版公开 APK，核对它与本地最终包的大小和 SHA-256。

下面以本次 0.4.3 交付为例，所有命令在仓库根目录运行；未来版本相应替换 URL、文件名和本地清单路径：

```sh
mkdir -p output/cdn-check
curl --fail --location --proto '=https' --proto-redir '=https' \
  --connect-timeout 10 --max-time 180 \
  --output output/cdn-check/sensefieldv0.4.3.apk \
  https://gitee.com/leda/SenseField/releases/download/0.4.3/sensefieldv0.4.3.apk

python3 - <<'PY'
from pathlib import Path
import hashlib
import json

manifest = json.loads(Path('output/releases/0.4.3/cdn-upload/latest.json').read_text())
apk = Path('output/cdn-check/sensefieldv0.4.3.apk')
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

local = json.loads(Path('output/releases/0.4.3/cdn-upload/latest.json').read_text())
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

当时手动覆盖正确迁移包后，自动和手动检查均显示“当前版本 0.4.1 无需更新”。现在交付统一升级到 0.4.3/code20，不再混用 0.4.1 的多个修订；需要 APK 与新清单成对上传。详见[迁移故障排查记录](../../validation/APP_UPDATE_GITEE_MIGRATION_2026-10-06.md)。其他检查失败仍需核对网络、清单响应和手机实际错误，不能一概归因为旧包。

## 同版本修订与真正新版本

| 发布情况 | 版本和下载地址 | 要上传什么 |
| --- | --- | --- |
| 同版本修订（仅负责人明确选择时） | 保持该版本名称和 code，以 SHA-256 区分修订；本次不再修订 0.4.1 | 新 APK 和重新生成的 `latest.json`；模型不动 |
| 本次发布新版本 | `0.4.3 / code20`，新建 `0.4.3` tag，APK 名为 `sensefieldv0.4.3.apk` | 新 Release 的 APK 和固定网站上的新 `latest.json`；模型仍复用 0.4.1 |
| 语音模型确实变更 | 新建独立资源名称/地址，更新 APK 内固定资源元数据，再构建 | 新模型分片、新 APK、配套清单；保留仍有客户端依赖的旧模型 |

本次已按负责人要求升级到 0.4.3，避免继续覆盖 0.4.1 的混淆。以后可按发布策略选择同版本修订或新版本；真正升级时，开发者需同步修改 `android/app/build.gradle` 的 `versionName/versionCode` 与 `scripts/build_android_preview.sh` 的 `PREVIEW_VERSION_NAME/PREVIEW_VERSION_CODE`；脚本会按新版本自动生成 Gitee APK URL，固定清单地址保持不变。仅创建 Gitee 新 Release 不会自动通知旧 APP，仍需最后更新网站清单。

每个版本的分发目录只保留一份 APK 和配套清单；0.4.2保留在自己的目录作本轮设计对照，不混入0.4.3上传目录。临时下载用于校验的 APK 在校验后删除。`output/` 被 Git 忽略，队友需从当前交付路径取得构建产物。更高 `versionCode` 的客户端不能通过普通更新安装较低序号的旧包。

## 模型分片与容量

| 附件 | bytes | SHA-256 |
| --- | ---: | --- |
| `sensevoice-int8-v1.zip.part01` | 90,000,000 | `8f10f3eb21a42da17453241658e8bd3cdb4998c18b91e5b8bfd71df2f93048f5` |
| `sensevoice-int8-v1.zip.part02` | 70,304,482 | `f60da186700ee0465a5e2ebabe9e8c80a19d798ff63ae40fc2e2f5bed8077220` |

两片均小于单附件 100MB，合计 160,304,482 bytes。APP 自动按顺序拼接、校验和解压，用户无需手动合并。它们是原 ZIP 的字节分片，不能各自解压，也不要重新压缩或改名。模型配置在 [sensevoice.metadata.json](../../android/app/src/main/assets/sensevoice.metadata.json)；打包工具为 [package-asr-model.py](package-asr-model.py)。

当前两片模型与一个 APK 合计约 209MB。按普通仓库总附件 1GB 规划，仓库其他附件与历史 APK 也计入配额，以 Gitee 后台实际统计为准。后续不重复上传模型；清理历史附件时保留两个模型分片。拆片解决单文件限制，不减少总空间或下载量。

## 本次公开核对记录

2026-10-06已完整读取这两个公开分片，大小与 SHA-256 均匹配 APP 配置，下载使用 APP 的实际 HTTPS 传输类在 Mac JVM 运行。证据见 [Gitee 分发验证](../../validation/GITEE_DISTRIBUTION_2026-10-06.md)。0.4.1 公网 APK 与清单的历史错配已另行核对，见上述迁移排查记录；0.4.3 的公网附件和清单需由负责人上传后按上述步骤验收。
