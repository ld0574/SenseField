# 患者反馈改进 Goal：学习、设置与震感

日期：2026-10-05。范围：0.4.1/code18 本地工程候选，尚未发布或安装到真实手机。

## Goal 与完成条件

落实患者提出的单项试听、就近说明、震感调节及大屏阅读意见。保留既有本地预警规则，完成源码、必要回归、模拟器界面检查和符合度证据记录。这里的完成指本地工程交付；实际感知与患者验收单独列为待测。

- [x] 目录可按单项试听、重听和停止，完整说明有独立入口。
- [x] 对应设置旁有明确命名、可读屏的说明入口；打开说明不改设置。
- [x] 可选择原有／短促节奏及设备默认／较轻／较强震感，并单独试听。
- [x] 宽屏阅读区域居中并可随尺寸缩小，长标签允许换行。
- [x] 按用户补充意见，密集说明正文最多采用系统 150% 字号；标题、按钮、开关和状态保留系统字号。
- [x] 完成 JVM、构建、lint、Android 14 模拟器界面回归、截图检查及公开仓库扫描。
- [x] 更新符合度方案与公开能力说明，明确真实设备和玩家证据缺口。

## 需求 → 实现 → 证据 → 未覆盖部分

| 患者意见 | 本轮实现 | 工程证据 | 未覆盖部分 |
| --- | --- | --- | --- |
| 不必每次从头听完整介绍 | `ReminderGuideCatalog` 按当前开启的通道和事件生成目录；`ReminderGuideActivity` 分开目录、单项解释与完整说明。目录和完整说明页进入时静音；单项进入后播放一次，开启触摸探索时由播放按钮触发。可再次播放或停止 | `ReminderGuideCatalogTest` 验证独立示例、声道、两字设置、距离样例和稳定 ID；`ReminderGuidePlaybackTest` 验证停止及迟到回调；instrumentation 检查目录与完整说明不自动创建播放器 | 实际耳机声道、中文 TTS、TalkBack 导航和玩家理解尚未验证 |
| 希望震动更短、更容易分辨 | `HapticPolicy` 统一原节奏、短促节奏和震感档位；`HapticSettingsActivity` 读取公开振动能力并通过 `CuePlayer` 试听 | `HapticPolicyTest` 覆盖默认不变、脉冲顺序、距离叠加及无振幅控制时的时长近似；instrumentation 检查进入页面不自动播放 | API 受理不证明用户感受到；不同手机的舒适度、强弱和辨识率待测 |
| 设置旁就能看到用途 | `SettingHelp`、`SettingHelpActivity`、`SettingHelpContent`；配置、提示偏好、助手和授权页分别接入。开关／标题与说明同排，说明具有具体读屏名称，最小触控高度 72dp | instrumentation 核对对应开关旁的入口、名称、heading 与偏好不被修改 | TalkBack 实际焦点顺序、读完是否理解及设置效率待患者验证 |
| 平板、折叠屏和大字阅读 | `UiKit.page` 最大阅读宽度 720dp，`PageGeometry` 按可用宽度重新计算；品牌标签用剩余宽度换行。`UiKit.readingBody` 仅限制帮助正文、单项解释和完整说明卡正文的字号上限，保留完整文本、行距和滚动 | `PageGeometryTest`；instrumentation 测量 1400dp→320dp 的居中与缩小，普通／200% 字号下复核正文上限及控件完整缩放；首屏截图人工查看 | 不是折叠屏或平板的实际游戏验证；ROI、投影、旋转及悬浮窗对齐仍待真机 |

## 默认与生命周期

默认仍为原有节奏、设备默认震感。附近提醒波形为 `[0,100,140,100]` ms；短促模式保留双震，将其缩为 `[0,35,80,35]` ms。距离实验先生成波形，再叠加短促与震感设置，仍默认关闭。支持振幅控制时请求不同振幅；不支持时以脉冲时长近似，不宣称马达类型、轴向、左右独立马达或感知效果。

单项示例使用实际呈现入口；触觉示例不等待中文 TTS，也不要求媒体音量大于零。两款实时辅助服务运行时不能开始试听。停止、离开页面和切换震感设置会清理播放器并取消其拥有的震动，旧播放器不能取消新播放器的震动。完整说明需要朗读时才准备语音引擎；单项播放保留当前声音与呈现设置。

字号上限通过独立资源配置使用 Android 的 SP 换算，包括 Android 14 非线性放大规则；不修改全局字体、系统偏好或其他页面的 `UiKit.body`。短状态、标题和可操作控件仍遵循用户系统字号。

模型、profile、识别阈值、近区范围、热档与截图采样未修改。没有读取封存视频、训练或扫描阈值。`verified`／`release_ready` 与历史评分保持原状态。

## 本轮验证

| 项目 | 结果与范围 |
| --- | --- |
| JVM | 269 项，0 失败／错误／跳过；含既有预警、声音协调、设置、更新与助手回归 |
| Android 构建 | `testDebugUnitTest`、`assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 通过；最后文案调整后重建 Debug/Test APK 与 lint |
| lint | 0 错误，22 项已有警告；新增说明页的冗余版本检查警告已消除 |
| UI | Android 14/API34 arm64 本地模拟器，`PatientLearningInstrumentedTest` 的同 5 个场景分别在普通、200% 系统字号通过，不能写成 10 个不同场景 |
| 截图 | 目录、帮助、震感、提示偏好首屏，普通与 200% 字号逐张查看；保留换行和纵向滚动，未见首屏水平裁切。震感首帧曾在取图前尚未提交，测试取图增加300ms等待后补跑同一场景的两种字号；这不是额外场景或应用时延样本。截图只含测试 UI，不是患者或游戏画面 |
| 静态检查 | `git diff --check` 和 `scripts/check_public_repo.py` 通过，扫描499个候选文件；原始患者资料、APK、测试日志与截图保留在忽略的 output 中 |

复现命令（本地 Android SDK/JDK 路径按环境设置）：

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
# 只在本地模拟器上安装 debug 与 androidTest APK 后执行：
adb -s emulator-5554 shell am instrument -w -r -e class com.openkhub.sensefield.PatientLearningInstrumentedTest com.openkhub.sensefield.test/androidx.test.runner.AndroidJUnitRunner
```

本地证据目录：`output/patient-feedback/2026-10-05/`，包含 `build-final.log`、`ui-font100-final.log`、`ui-font200-final.log` 与 `screenshots-final/`。模拟器的临时字体／动画设置在检查后恢复；没有连接测试服务器、生产服务器或用户手机，没有模型 API 请求。

本地保存的验证 APK：`sensefield-patient-feedback-0.4.1-local-debug.apk`，0.4.1/code18，214,823,228 bytes，SHA-256：

```text
9fb1222b1dfb919836456ab736192a7736b6dcdd5e0017f208571ea35773f0d0
```

这是 UI 工程候选，不是最终生产交付。仍按[服务端先部署再 APK](ASSISTANT_NATIVE_DEPLOY_HANDOFF_2026-10-05.md)的顺序进行；没有 push、Release、CDN 上传或真实手机覆盖安装。

## 下一轮真人与设备验收

- [ ] 患者无需完整听介绍即可找到某个提醒、单独重听、停止；比较学习时间和理解错误，不提前给出音效测试答案。
- [ ] TalkBack 开启时完成目录、单项播放、停止、设置说明及返回；记录实际焦点顺序、重叠声音和按钮命名问题。
- [ ] 在有／无可调振幅的真机比较原有与短促节奏、默认／较轻／较强、距离实验；记录是否有震动、是否辨出次数与差异、是否打扰。
- [ ] 平板／折叠屏展收、横屏 90°↔270°、投影恢复、两侧悬浮窗、安全区及 200% 字号；独立核对小地图 ROI、自身标记和视觉层对齐。不能由页面最大宽度推导游戏适配通过。
- [ ] 实声、助手响应与温升继续按既有门禁取得外部录制及同机至少 3 次连续 15 分钟对照。当前界面测试不为延迟、误报、发热或符合度得分背书。

本轮主要补强附录 A #5／#32 的可定制学习路径与 #17 的可检查触觉设置；#17 的左右独立马达要求仍未满足。真实患者证据形成后再交由评审判断，不预填恢复分数。
