# ADB 联调记录自动收集

手机通过无线 ADB 授权联调后，由开发者记录日志、在辅助结束后抓取完整诊断包，测试者不用点击「导出诊断」。普通用户的诊断导出入口继续保留；本流程不上传到服务器。

## 完成一局后直接收集

使用当前连接地址或 ADB 自动发现的同一台手机序列号。先运行 `adb devices -l` 确认设备。辅助仍在运行时，脚本会拒绝安装测试助手或启动导出，不会为了拿日志停止对局。

```bash
python3 scripts/pull_android_diagnostics.py \
  --serial '<手机序列号或 IP:端口>' \
  --output 'validation/private/<本次联调目录>/diagnostics'
```

默认使用 `android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk`。这个测试助手必须与手机安装的 APP 使用同一签名，可在签名环境准备好后构建：

```bash
cd android
./gradlew :app:assembleReleaseAndroidTest -PsensefieldTestBuildType=release
```

测试助手单独安装，不进入发布 APK。它通过同签名 instrumentation 读取应用私有记录，无需 root、不打开导出界面、不改变原记录或用户设置。此操作会重启听野进程，所以只在辅助停止后执行。导出测试还要求显式传入 `authorizedDiagnosticsExport=true`，不能混在普通回归里隐式运行。

## 提前监听一局并自动抓取

```bash
python3 scripts/pull_android_diagnostics.py \
  --serial '<手机序列号或 IP:端口>' \
  --output 'validation/private/<本次联调目录>/diagnostics' \
  --wait-for-session-end
```

监听必须先观察到王者或消消乐的辅助服务启动，再观察到辅助停止，并等待 15 秒保存记录。每 5 秒只检查服务状态；默认最多等待两小时，收完一次就结束。无线 ADB 断开不算辅助结束。局中 logcat 和低频温度记录仍由联调监听器另行保存，不增加 APP 截图、推理或服务器上传。

## 核对与保存

脚本抓取最近三份会话，以及限定范围的棋盘和声音偏好，不读取服务密钥。每个 ZIP 都检查结构及手机／本机 SHA-256，写入 `receipt.json`；成功后只删除手机上本次导出的临时副本，原私有诊断继续保留。

原始画面和玩家记录留在忽略的 `validation/private/`，不能放入公开 APK、文档、测试资产或 Git。公开验证记录只写必要统计与结论，明确区分真实对局和测试棋盘。
