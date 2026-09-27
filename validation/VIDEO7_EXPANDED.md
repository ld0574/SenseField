# video7 扩展 AI 辅助标注与交叉审计记录

更新于 2026-09-27。该数据属于开发集，不是独立留出成绩。本记录描述的是 AI 辅助初标和 AI 交叉审计，不是人工真值；仍建议队友抽查。

## 抽样边界

- 来源：`video7.mp4`，2712×1220、30 FPS、高码率人机局。
- 有效区间：60,000–1,045,000 ms。
- 新增 240 个系统均匀抽样时间点，全部属于 `train`。
- 抽样过程不读取检测结果，清单记录 `predictions_used_for_selection=false`。
- 排除旧 video7 111 个标签时间点前后 1,000 ms；新旧时间点实际最近间隔 2,552 ms。
- 原始队列清单 SHA-256：`93841144694fa06f38b8836909ba3638737a567a473b4672a692e01f2de05231`。

全部 240 张抽帧已核对为实际横屏像素 2712×1220，且没有 EXIF 旋转标记。

## 抽帧后生成的辅助建议

建议框在抽样完成后生成，不参与选择帧：

| 项目 | 值 |
| --- | --- |
| ONNX SHA-256 | `5072feba5a33dfa18c98ddf3bdf0b0d12bbd67453a599c690b7ef805c6288e32` |
| 输入 | 320×320 小地图裁剪 |
| confidence / NMS | 0.10 / 0.5 |
| 预标注 SHA-256 | `bad70fe6cd6fbe48c0a19cab83e31dc8e709ec4c2cc44b7f16687b620c23a0ba` |
| 有建议的图片 | 213 / 240 |
| 建议框 | 442 |
| 每图建议框中位数／最大值 | 2 / 7 |
| 附加建议后清单 SHA-256 | `94363bb38528e54d835a1e519d662eade9a25be6c54445b3749f5c90a2e69508` |

预标注导出时已修复裁剪 `floor/ceil` 造成的不足 1 像素 ROI 越界，所有建议框均严格位于标注 ROI 内。

## AI 辅助初标与交叉审计结果

三路 Luna Max 按帧段分工完成初标。随后进行两路独立、分层的 Luna Max 交叉审计：

- `1–120`：检查 `88/120` 帧；
- `121–240`：检查 `99/120` 帧。

交叉审计覆盖全部 `negative`、`excluded`、`skip` 帧，所有高风险边缘框和重叠框，以及固定抽样；审计没有修改任何标签。自动检查确认数据库与 review manifest 一致，且没有框越出标注 ROI。

| 最终状态 | 数量 |
| --- | ---: |
| `corrected` 图片 | 186 |
| `corrected` 框 | 334 |
| `negative` | 33 |
| `excluded` | 16 |
| `skip` | 5 |
| `pending` | 0 |

最终 review manifest SHA-256：`d05eade78eea2721773d06c4288e338770d8c46810175eda9d7f7b1296547e8a`。

这些计数记录 AI 辅助初标后的队列状态，不代表 240 张都经过人工逐帧确认。仍建议队友抽查这些标签，尤其是边缘和重叠目标。新模型不得再把 video8 当成可重复调参的独立测试集；最终成绩需要另一场预先冻结、从未参与开发的真人对局。

## 导出与开发集微调

`finalize_review` 纳入 219 张／334 框：186 张 `corrected` 和 33 张 `negative`；`excluded` 与 `skip` 不进入训练。与既有数据合并后，7 场开发录像共 1,079 张／1,666 框。按整场录像分组，train 使用 video1–5 与 video7，共 927 张／1,443 框（video7 合计 330 张／523 框）；val 只使用 video6，共 152 张／223 框。整个流程未读取 video8。

| 产物 | SHA-256 |
| --- | --- |
| combined manifest | `67e4894df081660453228a2435f478b5aeaebe90c2f902cd4a288eb249092a09` |
| train annotations | `75767b5a33d3fceff95937bb1f06bd0898ac8f55ea53f94faf3e300e33ed0303` |
| val annotations | `954c5687978012f280c2f83b6dedf71e4e9568062cc4fd999aca96227f64b981` |
| dense baseline checkpoint | `68b86a7a10c97d9b2c738f72b1f49a0b1dcc76d10751ee9f4e61380ee4fb93bc` |
| best checkpoint (epoch 10) | `49d8d21900603f78a595e08362895d70011201a9b26457c5fa388f915a80ae99` |
| metrics | `fd7349e74a4c4772682217bebe51633c9668dec7be31b6798129ad06155899f5` |
| fixed evaluation | `b4dc2d50b77d9df1d443135d2413eab8374171485d3b2a155b735ca6eee968e9` |

微调使用 Apple MPS、seed `20260926`、输入 320、batch size 16、`lr_scale=0.1`，最多 40 轮，第 30 轮早停；最佳 checkpoint 为 epoch 10，评估 confidence 为 `0.43`。在 video6 开发验证集上 TP/FP/FN 为 `158/17/65`，precision / recall / F1 为 `90.2857% / 70.8520% / 79.3970%`，几何方向正确率为 `97.3856%`。

相对 dense baseline，precision / recall / F1 分别变化 `−0.2171 / −1.7937 / −1.2000` 个百分点；相对困难误报加权候选，分别变化 `+0.0154 / −4.0359 / −2.4657` 个百分点。因此不替换 APK 模型。该结果只是 video6 开发集比较，不是独立留出成绩；video7 的 AI 辅助标签仍建议队友抽查。
