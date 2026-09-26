# Android 模拟器链路验证（最终回归，更新于 2026-09-26）

本页记录的是 0.2.0 固定 ROI APK 的历史模拟器证据。0.3.0 已改为内置自适应 GameProfile，但尚未在模拟器或实体手机复测，因此下面的数据不能证明新版定位器的 Android 运行表现。

测试设备是本机 AOSP ARM64 虚拟设备，分别运行 API 33（Android 13）和 API 34（Android 14），横屏为 2400×1080。早期模板和旋转测试使用程序生成图形；最终 YOLOX 回归使用 video7 人机局 80,520 ms 的一张 2712×1220 完整游戏帧，经系统 Gallery 全屏显示后由 MediaProjection 采集。该私有测试帧不进入仓库。

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
| Android 13 / API 33 | 使用本次 APK 全新安装；启用 YOLOX 后设置页为 `1/5`，系统授权文字明确为整个屏幕，横屏采集尺寸为 2400×1080。暂停、恢复、停止均已操作。最终 `processedFrames=210`、`landscapeProcessedFrames=187`、`detected=5`、`queued=4`、`stale=1`、`audioFailures=0`；真实游戏帧触发的非过期 `kind=2` 事件进入音频队列。 |
| Android 14 / API 34 | 使用本次 APK 全新安装；启用 YOLOX 后设置页为 `1/5`，整屏授权与 `mediaProjection` 前台服务类型正常，横屏采集尺寸为 2400×1080。暂停、恢复、停止均已操作。最终 `processedFrames=235`、`landscapeProcessedFrames=217`、`detected=5`、`queued=4`、`stale=1`、`audioFailures=0`；真实游戏帧触发的非过期 `kind=2` 事件进入音频队列。 |

两台模拟器的最终回归均使用同一个 APK，且每台都从全新安装开始；`1/5` 是 YOLOX 识别器状态，不是事件数量。`CueEvent` 来自实际 ncnn 推理链路，不是仅由模板演示生成的日志。

APK SHA-256：`2b21a5c71034feaab5b6dbca7cf1fce9ca99abe6430bc9c37e30d8e337d7b8d1`

最终 APK 在两种 API 上都没有出现动态库／模型加载错误、`FATAL EXCEPTION`、JNI 错误、SIGSEGV 或 SIGABRT；停止后前台服务和通知均被移除。SoundPool 在首个检测前已经加载完成，因此本轮没有自然覆盖“加载期间暂存提示”分支。

## 历史模板与旋转回归

此前 API 34 方向切换复测中，竖屏时通知标题为“等待横屏”，提示计数不增长；切回横屏后通知恢复“正在处理画面”。模板配置曾触发三类 `audioQueued=true`，红环配置也触发 `kind=2`。这些历史结果只验证模板／红环、旋转和 JNI 事件链路，最终 YOLOX 结论以上表同一哈希 APK 的全新安装回归为准。

历史 `0.1.2` 所含的诊断计数和停止竞态修复已经追加复测。红环合成配置运行时日志出现两次 `audioQueued=true` 和两次 `Dropped stale cue`，通知同步显示“音频排队 2/检测 4 · 过期 2”。设置页按钮能够暂停、恢复和停止服务。初测发现主动停止会被稍后的 `MediaProjection.onStop()` 覆盖成“系统截屏授权已结束”；修复为首个停止原因优先后，最终 APK 再次授权并主动停止，服务退出、私有首选项和设置页均显示“截屏已停止”。

## YOLOX ncnn 冒烟测试

`0.2.0` 最终 APK 在 API 33 和 API 34 上均从 APK 内置配置加载 ncnn param／bin 与 Focus 自定义层，并从完整真实游戏帧产生 `kind=2` 检测。API 33 两条非过期事件的 `nativeMicros` 样本为 140,741 和 37,978；API 34 为 106,319 和 121,193。它们受模拟器调度影响，只能证明模型实际执行且没有明显接入错误，不能作为实体手机耗时或端到端 P95。

代码中已处理一次真实发现的旋转停帧：旧 `ImageReader` 对应的 Surface 必须先从虚拟显示断开，再关闭并接入新 Surface；同时每 500 ms 独立检查屏幕尺寸，避免只依赖后续帧触发调整。修改后两种 API 的横屏处理帧数继续增长。

`audioQueued=true` 表示 `SoundPool.play()` 接受了播放请求。这两台模拟器没有实际扬声器音频，因此**未验证扬声器实际发声，不能替代真机**。日志中的 `frameAgeMs` 是图像时间戳到音频排队的时间，不包含听到声音的延迟，也不是端到端 P95；红环复测中也观察到超过 250 ms 有效期的事件按设计记录为 `Dropped stale cue`。模拟器数据不能证明真实手机上的游戏兼容性、准确率、帧率、发热或 250 ms 验收目标。
