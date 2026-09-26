# Android 模拟器链路验证（更新于 2026-09-26）

测试设备是本机 AOSP ARM64 虚拟设备，分别运行 API 33（Android 13）和 API 34（Android 14），横屏为 2400×1080。这里播放的是程序生成的合成图形，**没有使用《王者荣耀》画面**。

## 复现素材

从项目根目录运行：

```sh
cmake -S native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native -j4
PYTHONPATH=python python3 -m mapassist.synthetic_android --output-dir build/synthetic_android
```

生成的 `fixture-android-loop.mp4` 是约 63 秒的合成录像。`android-screen-profile.json` 内嵌放大后的敌人及危险信号模板，用于验证模板识别链路；`android-ring-profile.json` 启用小地图红环、主画面模板和危险信号模板，用于单独确认红环识别经过 JNI、事件排序和 Android 音频队列的完整链路。两份配置仅适用于 AOSP Gallery 将 320×180 视频以 6 倍大小显示、左侧留 304 像素空白的 2400×1080 屏幕。播放器布局或分辨率变化后，应重新计算区域与模板。配置的 `verified` 仍为 `false`，不可当作游戏配置。

将 APK 安装到该模拟器后，可用以下命令放入测试文件：

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb push build/synthetic_android/android-screen-profile.json /sdcard/Download/mapassist-generated-screen-profile.json
adb push build/synthetic_android/android-ring-profile.json /sdcard/Download/mapassist-generated-ring-profile.json
adb push build/synthetic_android/fixture-android-loop.mp4 /sdcard/Movies/mapassist-synthetic-loop.mp4
```

在应用中导入 JSON，勾选“允许未通过真人录像评测的实验识别器”，接受通知和整屏截屏授权，然后切到横屏播放合成录像。所用 AOSP Gallery 可通过以下命令打开：

```sh
adb shell am start -n com.android.gallery3d/.app.MovieActivity -a android.intent.action.VIEW -d file:///sdcard/Movies/mapassist-synthetic-loop.mp4 -t video/mp4
adb logcat -d -v time -s 'MapAssistCapture:*'
```

## 实测结果

| 系统 | 已观察到的结果 |
| --- | --- |
| Android 13 / API 33 | APK 安装成功，通知权限和整屏截屏授权成功；前台服务与 MediaProjection 活跃。系统设置页面滚动后处理帧数从 1 增到 62；横屏尺寸更新为 2400×1080。模板配置中三类事件均出现 `Cue kind=1/2/3`，对应方向为左／右／不指定，三类均有 `audioQueued=true`。改用红环配置后再次出现 `Cue kind=2 direction=2 audioQueued=true`，确认小地图红环事件已进入 Android 音频队列。 |
| Android 14 / API 34 | 同一 APK 安装并授权成功；横屏采集、通知暂停／继续、停止均已操作。模板配置中三类事件均出现 `Cue kind=1/2/3` 且 `audioQueued=true`；竖屏授权后切横屏也观察到持续取帧和三类事件。生成器输出的 JSON 已在应用中导入，应用私有文件与生成文件的 SHA-256 一致。改用红环配置后出现 `Cue kind=2 direction=2 audioQueued=true`；同一配置中的 `kind=1/3` 也被触发。 |

追加的 API 34 方向切换复测：竖屏时通知标题为“等待横屏”；9 秒内帧计数从 231 增至 313，提示计数保持 6。切回横屏后通知恢复“正在处理画面”，提示计数增至 9，日志再次出现三类 `audioQueued=true` 事件。最后一次仅调整通知帧计数的文字和竖屏识别耗时显示，已重新构建 APK；上述方向切换行为由调整前的同一识别逻辑版本验证。

历史 `0.1.2` 所含的诊断计数和停止竞态修复已经追加复测。红环合成配置运行时日志出现两次 `audioQueued=true` 和两次 `Dropped stale cue`，通知同步显示“音频排队 2/检测 4 · 过期 2”。设置页按钮能够暂停、恢复和停止服务。初测发现主动停止会被稍后的 `MediaProjection.onStop()` 覆盖成“系统截屏授权已结束”；修复为首个停止原因优先后，最终 APK 再次授权并主动停止，服务退出、私有首选项和设置页均显示“截屏已停止”。

## YOLOX ncnn 冒烟测试

`0.2.0` 在 API 34 ARM64 模拟器上使用 APK 内置开发配置复测。启用实验识别器后，设置页状态从 `0/5` 变为 `1/5`；接受 MediaProjection 授权后服务成功进入 `mediaProjection` 前台状态，证明 JNI 会话、ncnn、Focus 自定义层和随 APK 打包的 param／bin 均成功初始化。切换横屏后日志记录 `Capture resized to 2400x1080`，处理帧数持续增长到 99，没有 `UnsatisfiedLinkError`、模型加载失败、JNI 错误、SIGSEGV 或 SIGABRT。

通知中抽查到的整段 native 处理时间为 11、12、57、73 ms，多数约 11–12 ms。测试画面是系统设置页，因此 0 检测／0 音频排队符合预期。这些数字来自模拟器调度，只用于发现明显接入错误，不能作为真实手机性能或端到端延迟成绩。

冒烟测试后又增加了构建期模型哈希校验、前台服务先于模型加载，以及 SoundPool 加载期间的短时提示暂存；这些改动已通过构建和 lint，但尚未重复模拟器交互测试。native 模型、预处理和解码代码没有改变，仍需在实体手机会话中整体复核。

代码中已处理一次真实发现的旋转停帧：旧 `ImageReader` 对应的 Surface 必须先从虚拟显示断开，再关闭并接入新 Surface；同时每 500 ms 独立检查屏幕尺寸，避免只依赖后续帧触发调整。修改后两种 API 的横屏处理帧数继续增长。

`audioQueued=true` 表示 `SoundPool.play()` 接受了播放请求。这两台模拟器以 `-no-audio` 启动，因此**未验证扬声器实际发声**。日志中的 `frameAgeMs` 是图像时间戳到音频排队的时间，不包含听到声音的延迟，也不是端到端 P95；红环复测中也观察到超过 250 ms 有效期的事件按设计记录为 `Dropped stale cue`。模拟器数据不能证明真实手机上的游戏兼容性、准确率、帧率、发热或 250 ms 验收目标。
