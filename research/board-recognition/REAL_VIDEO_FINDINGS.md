# 真机视频对拍记录（开心消消乐实战录屏）

本文件是代码里 6 处 `REAL_VIDEO_FINDINGS.md` 引用的落点（`Match3Sampler.autoDetectBoard`／`detectGridCount`、`Match3LiveService` 两处、`CloudVision`、`Match3AutoDetectTest`）。只记文字与数字：**真机截图和录屏一律不入本仓**（隐私与体积），帧提取在仓外临时目录完成。

## 复现方式

```bash
cd research/board-recognition/java-bench
bash run.sh                      # 同步仓库源文件（sha256 核对）＋编译
java -Dsun.stdout.encoding=UTF-8 -cp out com.openkhub.sensefield.RealFrame <真机帧.png>
# 带真值逐格诊断：RealFrame <png> 7 7 14 38 87 71 <49 字符真值>
```

`RealFrame` 不带真值时也会打印：默认手工标定 8×8 的读数、`detectGridCount(默认框)`、`autoDetectBoard` 的框、`detectGridCount(auto 框)` 与自动框＋自检格数下的读盘和成品播报。

## 素材

领导提供的三段真机录屏（开心消消乐第 3 关，7×7 棋盘，仅 4 种动物：棕熊／小鸡／青蛙／蓝河马），1fps 抽帧后取若干静止帧对拍。画面 576×1280（抽帧缩放后）。

## 症状 → 两个生产级缺陷（均已修，`e485fed`）

1. **自动格数自检每帧必崩**：`detectGridCount` 里 `frame.getPixels(rowBuf, 0, cw, x0, y, cw, 1)` 把 `stride`／`offset` 写反（签名是 `getPixels(colors, stride, offset, x, y, w, h)`），真机稳定抛 `ArrayIndexOutOfBoundsException: Index 529 out of bounds for length 529`；异常被实时 tick 的 `catch (Exception e) { Log.w(...) }` 吞掉，只剩一行 warning。后果：自动格数从来没生效过，永远回退固定 8×8。
2. **最小播报间隔内的走子被永久吞掉**：`MIN_ANNOUNCE_GAP_MS`（6s）闸门判断之前就先写了 `lastAnnouncedMatrix = matrix`，被闸门拦下的那帧从此被标记成「已播过」。视频实锤：t=21s 有 18 格变化（一次真实走子），之后无播报；t=30.8s 播出的是过时的犹豫提示。

### 音频节拍证据（解释「同一句话一直重复报」）

- 播报起点间隔实测 **15.0–15.3 s**，正好等于 `IDLE_HINT_MS = 15000` → 反复听到的是犹豫提示按 15s 定时重播，不是局面更新。
- 音频包络相似度：局面播报 vs 犹豫#1 = **0.286**（内容不同）；犹豫#1 vs 犹豫#2 = **0.715**（同一模板），与 `hintCount < 2` 上限一致。
- 棋盘像素在 t=1..16 完全不变 → 那段时间只有定时器在说话。

## 修后实测（同一批真机帧）

| 项 | 读数 |
| --- | --- |
| `autoDetectBoard` | `14/38/87/71`（百分比框） |
| `detectGridCount(auto 框)` | **7**（抽帧逐帧一致） |
| 逐格读数 vs 人工目视标注 | **49/49**，7 行全部「错0」 |
| 弃权格 `'.'` 数 | 0 |

格心平均色按字母聚成四簇，且**远离 HSV 分桶边界**：

- 棕熊 `O`：h 28–29°，s 0.84–0.86，v 0.77–0.82，心-角色差 d 81–101
- 小鸡 `Y`：h 52–56°，s 0.65–0.72，v 0.89–0.97，d 97–120
- 青蛙 `G`：h 105–107°，s 0.73–0.78，v 0.79–0.82，d 74–79
- 河马 `B`：h 191–192°，s 0.75–0.76，v 0.88–0.89，d 72–108

**这条聚簇本身就是几何正确的证据**：若 7×7 被按 8×8 切（或框选偏了），格心会落在格缝与棋子边缘上，同一字母的色相必然被抹开、并出现大量弃权格。旧路径（默认 8×8）读出来正是那样——整行整行报「蓝、蓝、蓝、蓝、蓝、蓝、蓝、蓝」（棋盘底色越过 HSV 闸门被认成河马）。

## 边界条件（用之前要知道）

- `detectGridCount` **只在按棋盘裁剪后的框内可信**。喂默认宽框（`4/18/96/82`，含天空与底部道具栏）会返回 **9**——不是崩溃、是自信地数错。调用方必须先有正确的 `autoDetectBoard` 框，格数自检才是增益而不是灾难。
- 动画未落定的帧（例如本批 `f002`）在 7×7 下读出大量 `'.'` 与底色 `'B'` 混杂 → 三帧逐格多数票（`STABLE_FRAMES = 3`）是承重结构，撤不得。
- 人工真值是先看过机器矩阵再逐格目视标注的，所以 49/49 只支撑「几何对齐正确」，不单独充当「颜色分类正确」的盲测证据；后者由上面四簇远离桶边界来支撑。

## 已知未修（留给下一轮）

- **播报词表分裂**：`Match3Board.scanSpeech`／`charName` 念颜色词（红橙黄绿蓝紫），`Match3Coach.pieceName` 念动物名（红狐狸／棕熊／小鸡／青蛙／河马／紫猫），同一块棋盘两套名字；弃权符 `'.'` 被 `charName` 念成「空」，与真正的空格混为一谈。
- 空格判定阈值 12 只有合成集标定（见 `BLOCKED.md` §7 的输入需求）。
