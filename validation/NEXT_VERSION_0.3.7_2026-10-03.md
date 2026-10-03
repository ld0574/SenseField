# 0.3.7 复制性能与对照工具

日期：2026-10-03。延续[0.3.6补强阶段](NEXT_VERSION_0.3.6_2026-10-03.md)，本轮候选为 `0.3.7 / versionCode 15`。公开下载仍为0.3.5。目标是减少可证明冗余的复制，并让下一次同机测试能够直接比较证据；没有新真机温升或玩家反馈。

## 实现与行为边界

1. 诊断缩略图的 direct buffer 改为一次JNI复制，逐像素处理留在native。Java保留原裁剪、尺寸、采样计算；native验证capacity、显式limit、stride、裁剪范围与输出数组长度后同步填充独立int数组。保留RGB、不透明alpha和nearest-neighbor源坐标；不将ImageReader帧交给异步任务。heap buffer或native不可用时仍走0.3.6的Java路径。
2. YOLOX预处理直接按原plane行步长读取ROI，省掉每次推理前的临时RGBA数组与逐行memcpy。仍使用固定ncnn20260526的相同RGBA→BGR、resize和114边框；ROI、缩放尺寸、模型、阈值与检测解码未改。生产路径与parity测试共用 `yolox_preprocess.h`。
3. 新增[离线诊断对照](DIAGNOSTIC_COMPARE.md)，可并列两份ZIP或报告的聚合指标、输入哈希、人工比较条件和证据缺口。对照工具本身不生成降温或验收结论。

近区提示范围、频率、500ms观察新鲜度预算、热档节流、截图分辨率和事件前后窗口与0.3.6一致。严格最终门槛及模型的 `verified=false` / `release_ready=false`不变；未读取video9/video12，未训练或搜索阈值。

## 已取得的代码验证

- Android JVM：160项，0失败/错误/跳过；`assembleDebug`、`assembleDebugAndroidTest`和`lintDebug`成功。既有Gradle提示及host JVM native-access提示保留。
- Host CTest：4/4通过；新的诊断像素测试含5,000组随机对照及padding、边缘、无效参数、溢出。额外ASAN/UBSAN检查发现测试夹具有效调用的输出数组长度不足，已改为真实6项；修正后无内存/未定义行为报告。新target加 `-UNDEBUG`，防止Release下跳过断言。
- Android 14 arm64模拟器：4项instrumentation通过，48组direct/read-only/slice JNI逐像素对照、buffer状态、非法参数、heap fallback及配对合成微基准。最终基准在计时前对相同输入及输出尺寸直接断言JNI copy成功并核对像素，避免把fallback算成native样本。
- ncnn预处理：模拟器上96组合成尺寸/ROI/stride，320和512输入的有效tensor及padding逐元素bit-identical。未运行模型或真实游戏。
- Python工具：报告/审计/实声测量35项回归通过，最终对照工具10项通过；真实0.3.5报告JSON集成成功。同一报告重复输入的冒烟示例明确标为管线演示，不是独立复测，也未复制图片或原Cue/image/session ID。310个公开仓库候选文件检查通过。

## 本地交付

`output/releases/0.3.7/`包含canonical debug-candidate、`听野v0.3.7 安卓安装包.apk`、同名校验文件、`听野v0.3.7 使用说明.txt`和仅含中文APK/校验/说明的体验ZIP。实际Manifest核验为0.3.7/code15、minSdk29、target35、arm64-v8a；v2签名有效，debug证书与0.3.6及0.3.5一致。APK为19,447,293 bytes，SHA-256 `fb4bc1c1f23279625868942a659f259b3c22e5869cc5d0ce862b78f8aa4036b7`。

9个assets与0.3.6逐字节一致；native库仅 `libmapassist_jni.so`变化，与本轮JNI和预处理代码匹配。未新建tag或发布Release，公开体验包仍为0.3.5。原始日志、生成报告、APK和测试依赖继续留在ignored目录。

## 合成微基准

仅为Android 14 arm64模拟器上的一轮合成buffer对照：2400×1080、rowStride9664，每路径预热8次，各31次计时并交错先后顺序；包含输出数组分配与JNI写回。原始输出保存在ignored `build/score-recovery/0.3.7/instrumentation.txt`。

| 全屏缩略图长边 | 0.3.6 Java P50/P95 | 0.3.7 native P50/P95 |
| --- | --- | --- |
| 480px | 2.731 / 3.012ms | 0.364 / 0.475ms |
| 960px | 10.678 / 17.383ms | 1.406 / 2.769ms |

微基准只说明这条复制路径在该模拟器上的差异，不代表手机温度、游戏FPS或实际提示延迟。旧实机35ms上下文复制包含screen和map，不能直接拿它与本表的单张全屏复制相减。

## 复现与下一轮

在`android/`设置Android Studio JDK与SDK后运行；`connectedDebugAndroidTest`需只连接本轮待测设备：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

连接设备时明确选择待测设备。单独复现固定ncnn预处理对照可从仓库根目录运行：

```sh
bash scripts/check_android_preprocess_parity.sh <adb-device-serial>
```

该脚本要求arm64、NDK28.2.13676358及已构建的固定ncnn缓存，核对归档哈希后只运行合成像素测试，结束删除设备上的测试程序。Host像素测试：

```sh
cmake -S native -B build/native-tests
cmake --build build/native-tests -j4
ctest --test-dir build/native-tests --output-on-failure
```

后续用同一手机比较0.3.5或0.3.6与0.3.7，分别至少15分钟；记录相近起始温度、充电、游戏画质、音频路线和实际版本，保留外部影音与诊断ZIP。比较复制耗时、处理间隔、丢图、提示及时性和温度变化。玩家试用继续使用[既有记录表](player-trial.html)，按提示时点核对方向，并记录帮助和干扰。
