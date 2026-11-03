# ADB 联调记录自动收集

手机通过无线 ADB 授权联调后，由开发者记录日志、在辅助结束后抓取完整诊断包，测试者不用点击「导出诊断」。普通用户的诊断导出入口继续保留；本流程不上传到服务器。

## 完成一局后直接收集

使用当前连接地址或 ADB 自动发现的同一台手机序列号。先运行 `adb devices -l` 确认设备。辅助仍在运行时，脚本会拒绝安装测试助手或启动导出，不会为了拿日志停止对局。

```bash
python3 scripts/pull_android_diagnostics.py \
  --serial '<手机序列号或 IP:端口>' \
  --output 'validation/private/<本次联调目录>/diagnostics'
```

默认使用 `android/diagnostics-helper/build/outputs/apk/release/diagnostics-helper-release.apk`。诊断助手必须与手机安装的 APP 使用同一签名，可在原有四个 `SENSEFIELD_*` 签名环境变量准备好后构建：

```bash
cd android
./gradlew :diagnostics-helper:assembleRelease
```

助手单独安装，不进入发布 APK，也不需要重新构建或安装手机上的听野。它只依赖 Android API 和诊断文件格式，不引用 APP 类或 R8 混淆映射，因此可以读取不同版本安装包产生的既有记录。主应用的 `androidTest` APK 仍供工程测试使用，收日志不再运行这个会绑定具体构建的测试包。

助手通过同签名 instrumentation 读取应用私有记录，无需 root、不打开导出界面、不改变原记录或用户设置，也没有网络权限。此操作会重启听野进程，所以脚本和助手都检查辅助已停止。导出要求显式传入 `authorizedDiagnosticsExport=true` 和本次请求 ID；每次只生成本次专属目录中的临时副本，校验后清理。协议不匹配或手机只有旧测试助手时，脚本会拒绝运行。

已经安装新版诊断助手时可使用 `--reuse-installed-helper`；指定本地助手 APK 可使用 `--helper-apk`（兼容原 `--test-apk` 参数名）。脚本通过 `--no-incremental -t` 安装仅用于联调的助手，手机系统拦截安装时会直接报告失败，不改装主应用。

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

2026-11-04 已在 K50U 的 0.4.8／code29 Release 上验证独立助手：不重装主应用，成功导出最近三份记录并通过 ZIP 与 SHA-256 校验。该手机返回的 instrumentation 类名采用 `.DiagnosticExportInstrumentation` 简写，脚本同时接受 Android 的完整类名和简写。旧 JUnit 助手不会被当作兼容助手启动；采集工具自身的异常应与对局中原有异常分别记录。
