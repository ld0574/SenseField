# PROGRESS

## 开工回执（2026-10-06，alpha.17 修复轮）
- 理解的目标：修 detectGridCount 的 getPixels 参数错位（真机每帧必崩→格数自检失效→回退 8×8 切 7×7→满屏河马），对齐桌面替身语义，真机帧 49/49 验证，回归门禁，发 alpha.17（产物只落 output/），推 feature/jev-l2-judgment + PR。
- 顺序：任务0 核验 → 1 修 422 行 → 2 修替身+反向验证 → 3 真机帧 49/49 → 4 gradlew 门禁 → 5 alpha.17 产物 → 6 提交推送+PR。
- 最大风险：替身改签名后 java-bench 内调用点/测试可能未全同步（run-tests.sh 的 sha256 核对会拦）；反向验证需制造红→绿证据。
- 基线（任务 0 实测）：run-tests.sh=30 tests OK（0.554s）；gradlew :app:testDebugUnitTest --offline=tests=190 failed=0 skipped=0（注：任务书引用 179，因 main 分叉第三方提交新增测试，以实测 190 为基线）。
- 现状核对：Match3Sampler.java:422＝getPixels(rowBuf, cw, 0, x0, y, cw, 1) 逐字一致 ✓；Bitmap.java:24＝(colors, stride, offset, ...) 逐字一致 ✓；.gitignore:37＝output/ ✓。
## 任务进度（2026-10-06）
- 任务 1 ✓：Match3Sampler.java:422 改为 getPixels(rowBuf, 0, cw, x0, y, cw, 1)（offset=0, stride=cw）。git diff 仅此一行。
- 任务 2 ✓：替身 Bitmap.java 签名改 (colors, offset, stride, sx, sy, w, h)，实现从 offset 起写、按 stride 跨行，并加真机 checkPixelsAccess 同款参数检查（offset<0/stride<0/末像素越界→抛数组越界）；副本 cp 同步。run-tests.sh 30 tests OK、skipped=0。
- 任务 2 反向验证 ✓：422 行临时改回错误调用→RealFrame 跑 f_30.0.png：detectGridCount(默认框) 抛「last pixel out of bounds: 1057 length: 529」、detectGridCount(auto 框)= -1、auto 框+8×8 读出垃圾（红）；还原后 detectGridCount(auto 框)=7（绿）。证明验证器会响。
- 任务 3 ✓：real01.jpg（1080×2400 真机截图）autoDetectBoard=13/38/86/71、detectGridCount(auto 框)=7、auto 框 7×7 命中 49/49 弃权 0 错判 0。f_30.0.png（576×1280 视频帧）detectGridCount(auto 框)=7、命中 42/49——7 个「错判」格经裁剪视觉核实**实际画面与 Java 识别完全一致（真值文件建错 7 格）**，以画面为准 49/49。真值问题记 BLOCKED.md。
- 任务 4 ✓：gradlew :app:testDebugUnitTest --offline=tests=190 failed=0 skipped=0（=基线）。
- 白名单说明：任务 5 要求 versionName=0.3.5-alpha.17、versionCode=20，需改 android/app/build.gradle 第 43-44 行——任务书白名单未列 build.gradle 但任务 5 明确要求版本号变更，按任务 5 要求执行并在此记录（改动仅限版本号两行）。
## alpha.18 适配开心消消乐上线（2026-10-06）
- GameCatalog: 开心消消乐「体验版」→「可用」
- Match3LiveService: 集成 DiagnosticRecorder，通知栏新增「标记问题」+「停止」按钮
- Match3AssistActivity: 新增「诊断记录」卡片，可导出诊断包
- versionCode 21, versionName 0.3.5-alpha.18
- 产物: arm64 = `68f30397...` (12.9MB), x86_64 = `036c0875...` (22.5MB)
- 待办: 等用户提供真实游戏截图做真机帧验证（不同关卡/不同棋盘尺寸）
