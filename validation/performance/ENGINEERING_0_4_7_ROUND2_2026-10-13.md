# 0.4.7 第二轮工程：像素精确比较降成本

2026-10-13。承接[第一轮](ENGINEERING_0_4_7_ROUND1_2026-10-13.md)，基于 `063755e`。复用已有画面，在模拟器完成工程检查；应用版本仍为 0.4.6／code23，公开 APK 与清单不变。

本轮保留一个生产优化：对 HUD 和棋盘缓存的当前像素做原生精确比较。相对第一轮冻结功能包，三组混淆 Release 成对测量的批次内全进程 CPU 中位降幅 **22.42%**，墙钟 P95 中位下降 **4.98%**，每组墙钟／worker P95 均通过原 10% 上限。Debug 的对应降幅为 80.05%／75.25%；两种构建分别比较，不能将 Debug 的大幅收益外推到手机。

本轮不宣称真机降温、独立识别准确率或 0.4.7 发布就绪。两款游戏的前台、真实声音和受控 3×15 分钟温升继续待验。

## 为什么选择这条路径

旧三组 Debug 回放中，采样／确认与 HUD／排序的 worker CPU 占比约为 41%／55%／4%。1ms 方法采样进一步显示，同像素缓存检查进入 `ArraysSupport.vectorizedMismatch`／`Unsafe.getInt`，占用了主要的工作线程时间。采样有额外开销，只用于热点归因，不进入成本门禁。

第一轮 +13.07% 的整包重装 P95 失败仍保留，根因没有被追溯确证。其原始批次存在明显尖峰；本轮没有删去这些样本、增加到“足够通过”的热身次数或重跑旧试验覆盖失败。

## 生产改动与不变语义

- [Match3NativeMath](../../android/app/src/main/java/com/openkhub/sensefield/Match3NativeMath.java) 的 Java 入口处理同引用、null 和长度；缺少本地库时沿用 `Arrays.equals`。
- [JNI](../../android/app/src/main/cpp/match3_math_jni.cpp) 在长度确认后短暂固定两个 int 数组，用 `memcmp` 比较全部字节，并释放两个数组。固定期间只执行有界比较；HUD 最大 432×200，棋盘描述为 16×16。
- [HUD](../../android/app/src/main/java/com/openkhub/sensefield/Match3HudReader.java) 与[棋盘目录](../../android/app/src/main/java/com/openkhub/sensefield/Match3VisualCatalog.java) 仅替换原来的精确相等检查。RGBA 的每一位都参与，包括 alpha；任何不同仍走原来的重新读取／识别及证据失效路径。

本轮没有新模板、阈值、识别授权或缓存时效改动。相等比较不读取历史身份代替当前像素。

## 测量控制

[成本生产器](../../android/app/src/androidTest/java/com/openkhub/sensefield/Match3PerceptionCostInstrumentedTest.java) 新增批次墙钟、阶段 CPU、ART 分配／GC 快照及可选采样。采样报告和缺少这些字段的旧报告不能进入新门禁。

[成对执行工具](../../scripts/benchmark_match3_processing.py) 固定三组、交替安装顺序，在每次运行前核对安装回读 SHA；两侧使用同一测量 test APK。要求一个实际执行测试、零跳过、相同输入／源码回执及完整样本数。失败目录不会被覆盖。

- 输入：原 26 张 JPEG，2,106 格；manifest／每张 JPEG 的 SHA 保持冻结。
- 时间轴／工作量：6 轮热身、15 个测量批次，每张图连续 3 次，共 1,170 次。HUD 每张图前清空，包含首次读取和随后相同像素读取；逐帧独立 sampler 在热身后主要测缓存路径。这不是连续实局动态画面的成本模型。
- 每次安装均请求 ART `speed`，记录实际编译状态：Debug 仍是 `verify`，Release 是 `speed`。没有把命令成功当成 Debug 已 AOT 编译。
- 全进程 CPU 是测量批次内的 `Process.getElapsedCpuTime` 差值；worker CPU 单列。排除采集、录音／语音、异步诊断 IO、悬浮窗及游戏本身。
- 保留条件：全进程 CPU 配对降幅中位数至少 10%；每组墙钟和 worker P95 增幅不超过 10%；生产回放语义一致。

### 同包重复控制

同一第一轮 Debug APK 的 A/A 对照先执行，没有生产差异：

| 配对 | 全进程 CPU 变化 | 墙钟 P95 变化 | worker P95 变化 |
| --- | ---: | ---: | ---: |
| 1 | +1.55% | +3.49% | +3.36% |
| 2 | −0.90% | −1.07% | −1.37% |
| 3 | +3.60% | +1.80% | +1.39% |

CPU／墙钟 P95 中位变化为 +1.55%／+1.80%。该控制说明本次时段内的重复差异较小，不解释或撤销旧失败。

### 原生精确比较：保留

| 构建／配对 | 基线墙钟 P95 | 优化墙钟 P95 | 墙钟变化 | worker 变化 | 全进程 CPU 变化 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Debug 1 | 18.997ms | 4.711ms | −75.20% | −75.55% | −80.15% |
| Debug 2 | 18.879ms | 4.672ms | −75.25% | −75.57% | −80.05% |
| Debug 3 | 18.944ms | 4.685ms | −75.27% | −75.55% | −79.95% |
| Release 1 | 2.722ms | 2.586ms | −4.98% | −5.46% | −22.42% |
| Release 2 | 2.718ms | 2.556ms | −5.98% | −5.41% | −22.47% |
| Release 3 | 2.703ms | 2.621ms | −3.03% | −2.92% | −18.60% |

三个 Release 配对的 worker 阶段总 CPU 中位数：采样 562.7→401.7ms，确认／HUD 1,036.5→776.5ms，排序 185.7→180.8ms。收益主要落在实际替换的两个缓存比较入口；GC 时间仍保留，没有计为像素比较收益。

### 公开 APK 直接对照：未产生测量

尝试安装实际公开 `535a653a…` Release，用相同新 test APK 测量。首次执行即因旧 R8 方法名与新测试的外部调用不兼容，出现 `NoSuchMethodError`，没有成本样本。失败回执保存为 `public-cost-release/receipt.json`。

这不是公开包的游戏运行失败，也不能填为成本通过。当前 Release 优化结论的比较对象是第一轮工程功能包 `7593fba3…`；实际公开 APK 的完整前台成本对照继续待验。

### 旧 0.4.6 源码 Debug 整体对照：新修订通过

沿用第一轮的 `8ad97af` 源码 Debug APK `44e88ddd…`，使用本轮相同生产器和控制条件，比较包含 W2／V1 及本轮优化的完整工程 Debug APK：

| 配对 | 旧基线墙钟 P95 | 新修订墙钟 P95 | 墙钟变化 | worker 变化 | 全进程 CPU 变化 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 18.737ms | 4.710ms | −74.86% | −75.01% | −79.85% |
| 2 | 18.816ms | 4.792ms | −74.53% | −74.90% | −79.81% |
| 3 | 19.037ms | 4.693ms | −75.35% | −75.15% | −79.94% |

三组通过。该结果说明当前修订在同类固定输入处理任务上已收住旧源码 Debug 的成本预算；旧失败保留为旧功能包及旧测量条件的结果，不改写为通过。本项仍不等于实际公开 Release APK 的前台对照。

## 回归与制品

| 检查 | 结果 |
| --- | --- |
| JVM | 616 项，失败／错误／跳过均 0；新增缺库时的精确 Java 回退检查 |
| 完整 Debug／混淆 Release | 111／77 项，跳过 0；包含实际 JNI 的 null、长度、同引用与每个像素位对照，覆盖 16×16 和最大 HUD 缓冲 |
| 消消乐回放 | 26 张／2,106 格反例、10 张／743 格旧集、三局 10 张：相对第一轮，完整状态、几何、HUD、大物体和排名输出全部相同；当前 Debug／Release 亦相同 |
| 王者回放 | 12 张：原始检测、ROI、实体、关系及事件相同；Debug／Release 相同。原 engine age 与成本分别保存，不归一成“相同”。输入仍是开发重建图，locator_enabled=false，不作为定位准确率验收 |
| 每种构建的主动回放 | 消消乐两套留出、三局、王者共 4 次，各执行 1 项、跳过 0；不把缺少输入的 Assume 算作通过 |
| Python | 成对工具 5 项、源码／回归门禁 6 项及 8 个源码输入子例、公开仓库 15 项通过；测试的临时目录别名断言首次失败，改为比较解析路径后通过，旧日志保留 |
| 包完整性 | 非 debuggable 混淆 Release、原证书、模型、856 份真实语音资产和 ELF 16KB 对齐检查通过；lint 0 错误／31 警告 |
| 公开边界 | 1,764 个已跟踪／未忽略文件检查通过，暂存差异检查通过；玩家画面、采样 trace、日志和中间 APK 均留在忽略目录 |

743 格当前评估仍是 correct699／miss25／misclassify2／false_swap1／swap_miss4，未发生新增差异；其中旧保守 SPECIAL 标签与魔力鸟准入的口径缺口沿用第一轮解释，不改冻结标签。两张箭头模板带来的 11／22 个已审阅开发正例完整可用同样保持，剩余 11 格继续弃权。

完整源码门禁摘要：`64307b145b668d92b65ea4b06edde6a598d4386f0fab12c37fd72d6a525bf299`。

| 制品 | bytes | SHA-256 |
| --- | ---: | --- |
| 本轮 Debug app | 29,337,413 | `af122714bdfb7ee5a09fbb7453d39527b31294c4f46dcc4b69b10606d5df78cf` |
| 本轮混淆 Release app | 24,585,301 | `45e1f8e1c1201cddf2948d4d63e661c56037a4b609e4a5410cc551d19f0d3edc` |
| Debug 完整回归 test APK | 25,639,250 | `4d41eb20f8cbc48d1fcb89af44e7bae18a2a6aef1681dbb810fc20f5c9dcb842` |
| Debug 成本 test APK | 25,638,579 | `9ff1670bba724b761be305b41bf97c7a4098066cd3544bb94c9d2940bd57fce1` |
| Release 回归／成本 test APK | 24,723,597 | `c7ad4bca425c853e46e9f4c5b740653e242a6dc4b4211bb288b234bed8b119a0` |

Debug 的成本生产器在新增 JNI 对照测试前冻结，完整回归 APK 另含该测试；每组成本对照两侧始终使用同一份测量 APK。本轮应用包相对第一轮只增加 92／312 bytes。

原始结果保存在忽略目录 `validation/private/goal-0-4-7-round2-20261013/`，不上传玩家画面。下列为已完成回执的字节摘要：

| 回执 | SHA-256 |
| --- | --- |
| 同包控制 `control-debug/receipt.json` | `3408dfe26e42e9308eaea12751118ea1e2cd7881b2b572d9da04a35ee227ab23` |
| Debug 优化 `proposal1-cost-debug/receipt.json` | `d92ab2aaf585b9ca67d8867d657f103bcfa9ab52c1e6978c5454ff4a3f078c16` |
| Release 优化 `proposal1-cost-release/receipt.json` | `d6329082f5b80b4019bc2c3e14d3b4d2a82454253f3bf2efbbd9c9ce05dcb65b` |
| 旧源码整体对照 `functional-cost-debug/receipt.json` | `291218f80341c8f30a07d42657388a6d8597540489c6497fb2beb3ef44b8fc3d` |
| 公开包接口失败 `public-cost-release/receipt.json` | `4ec533a1c4f8f65b44c84689004068426c5e12880b991d5796872d5696512281` |
| 完整 Debug `round2-debug-gate.json` | `ec795a984491f505f7c912b0194c58c11502e13de0f818e5477586864234f942` |
| 完整 Release `round2-release-gate.json` | `56c3719e0065a2d18e82ac29bfc7f1eb678e7ab99db2c372794285ec75c36841` |
| 生产语义对照 `parity.json` | `95b50a02bf51c2ac4ea70cf575cfb14a2220b5299f574a656790d56482241568` |
| Release 包核验 `release-package.json` | `ffc92a765fa93b08a885e5522a31d7b4cf8e0f7579d8654eaef8600ffd8a703c` |

实际公开 APK 保持 24,586,457 bytes／`535a653a927f57744922951f63ec9014041e04959823404bb1662a6d80b4f080`。公开包成本尝试绑定原 `source-receipt.json` 文件摘要 `35b9f711d9d22647cce7f0d5620ff5bae5a378c2d6dd6f1a4163ba3d4c647364`；这与本轮门禁源码算法的摘要分别标识，不能互换。

## 后续范围

本轮先收住固定输入处理中的重复比较成本。王者 forward、实际采集／输出和动态画面的整体成本不在这项收益内；已有王者模型和定位路径保持原样。

第一轮两项回退方案、旧整包成本失败和此次公开包测试接口失败均保留。后续候选冻结仍需要当前代码的唯一 APK／版本递增与分发材料，以及最终包两款游戏的前台成本、受控温升和实际声音；公开 README 的下载入口继续指向现有 0.4.6。
