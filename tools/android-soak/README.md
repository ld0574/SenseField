# Android 固定素材持续运行驱动

此目录通过 `--init-script ../tools/android-soak/soak.init.gradle` 显式加入测试APK；不进入生产源码、Manifest或标准辅助包。输入为冻结的19张开发图，只验收定位、识别、排序和生产诊断请求，不操作游戏。原始画面、输出及APK保存在被忽略的 `validation/private/`。这项后台工程验收不证明独立准确率、投屏／浮层／语音、玩家动作收益或受控温升。

## 构建与候选绑定

在 `android/` 目录使用候选原证书配置及所需fixture路径构建：

```sh
./gradlew --offline -PsensefieldTestBuildType=release \
  --init-script ../tools/android-soak/soak.init.gradle :app:assembleReleaseAndroidTest
```

构建后核对主APK与既有候选SHA一致，再安装测试辅助包；不为测试重签或覆盖主包。分别固定生产源码摘要、主APK、测试APK、此目录全部Java／Manifest／Gradle文件、图库及输入清单的SHA。测试驱动变更必须重建辅助包，不能把旧harness的通过或失败改写为新驱动结果。恢复时用冻结的标准辅助包，不以增量构建产物代替。

## 输入与启动

实体设备要求明确授权参数 `authorizedPhoneAcceptance=true`、生产辅助与采集服务已停止。模拟器工程持续运行使用独立参数 `authorizedEmulatorAcceptance=true`；两种模式按实际硬件分别校验，报告写明 `device_kind` 和 scope，模拟器结果不作为手机验收。不得同时运行其他验收、构建或启动游戏自动化。原会话归档先导出并逐成员固定SHA，同时记录棋盘／音频偏好。

文件放在目标应用外部目录 `match3-soak-inputs/`，其中 `manifest.json` 包含 `independent:false` 和长度为19的 `frames` 数组，每项为 `{"file":"<文件名>","sha256":"<64位摘要>"}`；文件不能越出输入目录。`soakRun` 每轮使用新的小写字母／数字／连字符名称，输出目录已存在即失败。完整负载为900秒，20秒只检查驱动。

先在后台启动**测试包自身**的instrumentation：

```sh
adb -s "$SENSEFIELD_ADB_SERIAL" shell am instrument -w -r \
  -e authorizedPhoneAcceptance true -e soakRun "$SENSEFIELD_SOAK_RUN" \
  -e soakSeconds 900 \
  com.openkhub.sensefield.test/com.openkhub.sensefield.Match3SoakWakeBootstrapInstrumentation
```

它在测试UID内绑定服务，不打开Activity。等待测试包外部目录 `match3-soak-bootstrap/<run>/ready.json`，再从另一启动器运行主测试：

```sh
adb -s "$SENSEFIELD_ADB_SERIAL" shell am instrument -w -r \
  -e class com.openkhub.sensefield.Match3PhoneSoakInstrumentedTest \
  -e authorizedPhoneAcceptance true -e soakRun "$SENSEFIELD_SOAK_RUN" \
  -e soakSeconds 900 -e match3SourceSha256 "$SENSEFIELD_SOURCE_SHA256" \
  -e phoneHarnessSha256 "$SENSEFIELD_HARNESS_SHA256" \
  -e listener com.openkhub.sensefield.Match3SoakResultListener \
  com.openkhub.sensefield.test/androidx.test.runner.AndroidJUnitRunner
```

无线ADB运行宜通过设备端 `setsid`／`nohup` 启动脚本，分别保留两个instrumentation的原始输出和退出标记；断连不能当作测试结束。不要用shell直接启动后台Service：本次手机明确拒绝该启动；未启动的跨包绑定还被小米自启动规则拒绝。自指向instrumentation在测试包权限内创建绑定，主测试随后请求锁；不用调整系统设置。

模拟器执行同样的两段启动与清理流程，命令显式选择 `adb -s emulator-5554`，并将两处授权参数换为 `authorizedEmulatorAcceptance true`。保留完整 900 秒、全部 19 图、JUnit 回调、诊断排空、设置／原会话保持及锁释放门禁；仅设备证据范围不同。

## 权限、负载与清理

辅助包声明普通 `WAKE_LOCK` 权限；有时限的CPU锁由它自己的独立UID持有。Binder逐次检查调用UID签名，acquire要求随机nonce及1000～960000ms时限，后续状态／释放请求核对同一owner与nonce。bootstrap等待最多负载时长加60秒；未acquire的服务30秒到期，已acquire按请求到期；正常释放后bootstrap解绑退出，服务销毁释放资源。主测试每10秒检查锁仍持有，任何连接、锁或清理失败均失败。

负载保持800ms间隔、2712长边、19图循环、生产异步诊断的隔离目录及43°C／SEVERE热停止条件。每帧执行自动定位、识别、确认、排序与诊断请求；墙钟／线程CPU计时不含图像读取／解码／放大、投屏、浮层及TTS。只有结束、排空、原会话名称和偏好检查均成功才通过，不用短时成功代替900秒。

驱动不创建UiAutomation、不借shell权限身份。

## 完整回执

`Match3SoakResultListener` 将实际JUnit `Result` 和开始／结束／失败／跳过／assumption回调写入目标包外部目录 `match3-soak-junit/<run>/junit.json`。要求 `complete:true`、`successful:true`、执行1／失败0／忽略0／assumption失败0，且测试开始／结束对应本测试。JUnit根suite名称可能为 `"null"`，不据此拒绝真实回执。不能生成或补写 `OK (1 test)`。

主报告在目标包 `match3-soak/<run>/report.json`。主机还须核对源码／驱动／清单SHA、完整负载≥900000ms、19图全部执行排序、诊断结束和排空、错误为空、原偏好与会话名称保持，以及测试包 `match3-soak-bootstrap/<run>/result.json` 的实际释放／解绑结果和一致helper UID。原始shell回执独立保留，缺失不得伪造。

结束后确认两个测试目标已退出、测试服务和CPU锁已释放，恢复冻结的标准辅助包；只读导出原会话，与基线逐成员SHA及偏好比较，再从实际安装路径核主APK。恢复成功与负载验收通过分别记录。失败保留原样本，先定位原因，不拼接时长、忽略资源清理错误或重试挑选通过。
