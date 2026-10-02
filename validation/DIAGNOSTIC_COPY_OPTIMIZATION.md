# DiagnosticPixels 复制开销优化

本记录跟踪 Android 诊断缩略图复制的局部优化。0.3.5 的真机复测统计 658 次上下文复制，累计 23.003 秒，平均约 35 ms。该观测说明复制值得优化；它不能说明每次都耗时相同，也不能单独解释温度或帧率。

## 实现边界

`CaptureService` 从 `Image.Plane` 读取 `pixelStride` 和 `rowStride`；当前入口要求 `pixelStride == 4`，不满足时丢弃此帧。`DiagnosticPixels.copy` 依据 RGBA 四字节像素和每行 `rowStride` 定位。新的实现通过不改变原 buffer 状态的 duplicate 做绝对 `getInt`，固定按大端拆出原逻辑所读的前三个字节，再将 alpha 设为 `0xff`。固定大端避免调用方调整 ByteBuffer byte order 后改变像素结果。

ROI clamp、`min(maxEdge, 1280)` 限幅、`Math.round` 输出宽高、整数 floor 的最近邻源坐标、行 padding 处理和返回尺寸保持原样。复制仍在处理线程同步完成，并返回独立的 `int[]`，ImageReader 可按原时序释放帧；没有引入 Bitmap 或将源帧传给异步任务。

## 验证

新增 JVM 测试以此前逐通道绝对 `get` 算法作 oracle，逐尺寸、逐 ARGB 像素比较。覆盖 heap/direct buffer、裁剪和缩放、输入 buffer 为 little-endian、只读 buffer、紧密行和带 padding 行、末行有效像素刚好到 limit、源 position 保持不变，以及空裁剪、错误尺寸／stride、超大参数和截断 buffer 返回 null。

针对测试已通过：

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/ada/Library/Android/sdk' \
./gradlew :app:testDebugUnitTest --tests com.openkhub.sensefield.DiagnosticPixelsTest
BUILD SUCCESSFUL
```

## Host 微基准

在 Android Studio 自带 JDK 的 macOS host 上，用 2400×1080 direct buffer、`rowStride=9664`、全屏缩到 480 px、预热 5 次并计时 30 次，比较旧逐通道读取与两种候选实现。两次是独立单轮运行，机器和 JVM 时序会影响结果：

| 方案 | 旧算法 | 候选 | 结果 |
| --- | ---: | ---: | ---: |
| 每个采样输出行 bulk get 整段 crop 到 byte[] | 0.318 ms/copy | 0.413 ms/copy | 0.77×，变慢，未采用 |
| duplicate + 单次大端 getInt 读取像素 | 0.322 ms/copy | 0.308 ms/copy | 1.04×，当前实现 |

这只是 host 上合成 buffer 的微基准，第二组差异很小且不代表 Android 设备性能。不能把它当作降低手机发热、恢复旧 8 FPS 门槛或改善游戏体验的证据。需在同一真机用相同会话、相同尺寸和相同诊断设置复测累计／平均复制时长、处理间隔与温度，再评价热表现。
