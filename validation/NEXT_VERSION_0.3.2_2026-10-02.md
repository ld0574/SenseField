# 0.3.2 周期检查点、异常恢复与调度验证

版本：`0.3.2`，Android `versionCode=10`。用途：内部诊断候选；下一次包含新改动的交付为 `0.3.3`。本轮未发布 GitHub Release，也未创建 tag。

## 本轮 goal 与改动

让诊断最后状态不再依赖成功处理横屏帧；应用异常退出后能恢复可导出的摘要；修正密集战斗中旧高分持续压制新近区语音的问题。自动 profile 选择、模型资产、识别阈值、`max_observation_age_ms=250`、统一授权、大字布局及开始前提醒释义保持现有行为。

- 使用独立定时线程，启动即保存检查点，随后约每 5 秒保存。采用固定延迟调度，避免进程恢复时集中补跑。检查点读取不可变快照，不获取识别锁；正常暂停和竖屏准备单独标为 `not_expected`，不会被当成断流。
- 记录最近到帧／实际处理完成时间、当前未完成间隔、定位／关系状态、密集模式、累计帧及截图数、丢弃计数、设备温度／电量／音量。处理完成统计移到原生推理与提示路由之后。
- 新进程启动时清除旧辅助运行标志，在诊断 IO 队列恢复未正常结束的记录。保留原检查点、事件和截图，摘要标明 `interrupted=true`、`recovered=true`、`end_observed=false`；实际结束时刻、总时长和原因未知，仅报告最后已知状态和时长下限。既有完整摘要保持不变，重复恢复不重复追加事件。
- 正常结束先写入原子摘要，再清理检查点；写摘要失败时保留最后检查点。JSON 文件通过临时文件、同步写入与替换落盘。截断的末尾事件单独保存原始字节，导出 ZIP 保留这份证据；缺失的设备元数据明确标为不可用。
- 密集战斗只比较实际 `NEAR_ENTER` 事件，约 800 ms 的评分窗口到期后重新选优。非事件帧不能建立语音赢家，窗口内更高分可替换赢家，低分只抑制语音，保留短音与震动。优先级映射保留正常分数范围内的顺序，关键提示仍保留优先级 100。

## 验证结果

| 检查 | 结果与边界 |
| --- | --- |
| JVM 回归 | 132 项通过，0 失败／错误／跳过；新增 19 项覆盖无帧及暂停检查点、正常／异常摘要、活跃记录保护、缺损文件、截断日志、重复恢复、ZIP 证据、摘要写失败和密集评分窗口 |
| Android | `testDebugUnitTest`、`assembleDebug`、`lintDebug` 通过；lint 仅剩既有 `mipmap-anydpi-v26` 警告 |
| 竖屏准备 | Android 14 arm64 模拟器通过真实系统 MediaProjection 授权。初始检查点和约 5 秒周期持续保存，已观察 4 个检查点；横屏处理帧为 0，状态为 `waiting_for_landscape`／`not_expected` |
| 暂停／恢复 | 通过通知栏暂停、继续。暂停期间观察到至少 3 个周期检查点，状态为 `paused`／`not_expected`，设备采样继续；恢复并旋转后产生横屏帧和关联的整屏／小地图截图 |
| 强制停止恢复 | `am force-stop` 后没有正常摘要；重开应用后自动生成 `process_interrupted` 摘要并保留原检查点。`ended_at_ms`、`duration_ms` 为 null，`last_known_at_ms` 与原检查点一致；首页开始按钮恢复可用、停止按钮禁用 |
| 诊断导出 | 从“测试记录与反馈”保存 ZIP 到 Downloads，并核对字节一致。包内 metadata／events／summary／原 checkpoint／feedback 齐全；JSONL 可解析，152 条帧事件与 66 张截图按帧序号／采样时间关联，42 个检查点、1 条恢复事件。截图均为模拟器应用页面，无真实游戏事件真值 |
| 公开仓库 | `scripts/check_public_repo.py` 通过；相关 Python 测试 6 项通过；`git diff --check` 与预览脚本语法检查通过 |
| APK 核验 | `0.3.2`／10、仅 `arm64-v8a`、minSdk 29、targetSdk 35；Android Debug 证书 v2 签名通过。JSON 测试依赖仅用于 JVM，不加入设备 runtime |

模拟器展示的是应用自身页面，用于检查记录与恢复链路。它没有中文 TTS，不能验证实际发声、耳机声像或震动感知；电量和温度读数也不能用作真实游戏发热结论。原始 UI、JSONL、检查点和 ZIP 留在忽略目录 `validation/private/next-version-032-20261002/`，不公开。

异常恢复 ZIP 的 SHA-256 为 `00370fe3c608dbbef4fd1f4e01474a3ac0ad2f5db7163456a47a2b6ba14c1956`，大小 `1,422,921 bytes`。恢复摘要的帧数来自最后检查点（150），日志末尾还有 2 条后续帧事件；摘要不冒充完整最终计数。

## 构建复现

```sh
cd android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/ada/Library/Android/sdk' \
ANDROID_SDK_ROOT='/Users/ada/Library/Android/sdk' \
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
cd ..
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/ada/Library/Android/sdk' \
ANDROID_SDK_ROOT='/Users/ada/Library/Android/sdk' \
bash scripts/build_android_preview.sh
PYTHONPATH=python .venv/bin/python -m pytest -q tests/test_check_public_repo.py tests/test_public_repo.py
python3 scripts/check_public_repo.py
```

APK：`output/releases/0.3.2/sensefield-0.3.2-arm64-v8a-debug-candidate.apk`。

SHA-256：`00441e8a51c8e8751e6f8ac97b55bdf8cc991fe2fdffce7c6728c93357dee957`。

大小：`19,735,057 bytes`。签名证书 SHA-256：`5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`。本机未配置完整发布签名环境变量，因此交付类型为 Debug candidate。

## 下一步真机门禁

本轮没有连接实体手机，没有新独立对局、真值标注或真实声音／热量测量。vivo S17 的漏报／迟报原因、ROI 是否匹配、定位回退、TTS 可感知性仍待用完整诊断包逐条核对。

1. 新对局必须导出 `metadata.json`、`events.jsonl`、`summary.json` 与关联截图；异常恢复包只提供最后已知状态，不能冒充完整连续会话。
2. 新对局标注至少 20 个“敌方英雄刚进入主画面边缘”的时刻，至少 15 个有效样本后才应用 `R_enter` 标定。video9、video12 继续封存，未读取或运行。
3. 同时报覆盖率、近区 precision／recall、错误提示／分钟、实际发声延迟 P50／P95、有效帧率、最大断流、温度电量变化和玩家主观反馈。目标为错误或无关提示 `<1 次/分钟`、P95 `≤500 ms`、至少 15 分钟完整连续会话。
4. 在独立真机门禁通过前，仅给患者做有陪同的实验试用，不宣称稳定 release。开发集 enemy／player F1 仍仅作为开发诊断。
