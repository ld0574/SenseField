# 0.4.6 唯一交付与旧包清理

2026-10-13，按用户要求确认最新 0.4.6 并删除其他旧包和重复安装包。最新为包含王者两字开关修复及此前消消乐修复的同版本 APK，与 GitHub `v0.4.6` 公开附件字节一致。

## 唯一最新交付

| 项目 | 当前值 |
| --- | --- |
| APK | `output/releases/0.4.6/sensefieldv0.4.6.apk` |
| 版本 | 0.4.6／versionCode 23 |
| 大小 | 24,586,457 bytes |
| SHA-256 | `535a653a927f57744922951f63ec9014041e04959823404bb1662a6d80b4f080` |
| 源码 | 已合入 main 的 `29cb75b8e1cc154c903570f7d8213e33279c6ba6` |
| GitHub | [v0.4.6](https://github.com/ld0574/SenseField/releases/tag/v0.4.6)，当前普通 Release、Latest |

版本根目录只保留四个公开附件：APK、`installation-guide.txt`、`latest.json`、`SHA256SUMS.txt`，均与 GitHub 资产的大小及 `digest` 一致。配套清单仍使用 Gitee 永久 URL；本轮 Gitee 与公网清单由用户同步。

`publishing/` 保存当前发布正文、完整说明、上传及队友复测说明。`validation/current-package/` 保存本包的源码、签名、工程和 Git 回执；`validation/artifact-history/` 只保存旧交付的历史验证记录，各结果保持原 APK SHA 绑定。

## 删除与保留范围

- 使用 Android SDK `aapt2` 读取仓库内 563 个 APK 的实际包身份，筛出应用包 `com.openkhub.sensefield`、版本名 `0.4.6` 的 190 份旧包、开发包及重复回读包，全部删除后只留下新统一目录中的一份最新 APK。
- 删除 2 份对应签名／摘要侧文件，删除 5 个旧交付子目录和 `output/releases/history/` 下 5 个 0.4.6 历史交付目录。净减少 APK 占用 5,012,468,561 bytes，约 5.01 GB。
- 根目录旧上传说明和校验单曾分别引用 `ead5e38a…`、`340e3b0f…` 修订，已用唯一最新交付及当前说明替代。
- 集中保留 76 份历史源码、清单及工程回执；原始游戏日志、诊断资料和 Git 历史保留。删除 APK 不改变原测试结果及其适用制品。

清理前生成精确路径、包身份、字节数与 SHA 清单，并确认候选文件均未受 Git 跟踪。先准备、核对最新包及配套附件，再按清单删除旧包。GitHub Release 和标签未被修改；当前普通 Release、Latest 状态来自接口回读。

## 核对与边界

清理前后核对最新 APK 及三个配套公开附件的摘要，清单的版本、大小和 SHA 与 APK 一致；1,215 个构建输入与原源码回执逐项一致。公开扫描、文档链接和 Git 差异检查随文档更新执行，后置包身份扫描确认唯一 0.4.6 主包。

本次整理只调整本地制品和文档，没有重建、重签或安装 APK。609 项 JVM、Release lint 和包完整性检查保留原结果；手机仍为旧修订，最终包实际发声、受控温升、独立准确率及完整最终门禁继续待验，`release_ready=false`。

完整清理清单、当前 GitHub 接口快照及核对结果在 `output/releases/0.4.6/validation/cleanup-2026-10-13/`。最新使用范围见[版本说明](../../docs/releases/0.4.6/RELEASE_NOTES.md)，公开发布过程见[GitHub 发布记录](GITHUB_RELEASE_0_4_6_2026-10-13.md)。
