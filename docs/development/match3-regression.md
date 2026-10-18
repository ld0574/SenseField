# 消消乐自动回归

入口为 `scripts/verify_match3_regression.py`，覆盖识别、推荐、语音回调、高亮和界面流程。日志、用例结果、源码与测试包摘要保存在 `validation/private/`。

## 日常检查

```sh
python3 scripts/verify_match3_regression.py
python3 -m unittest discover -s tests -p test_match3_regression_gate.py
```

默认运行 JVM 回归，不连接手机。覆盖合法交换、镜像与转置、矩形棋盘、空洞、覆盖层、任务排序及输入不变。生成布局用于工程检查，数量不等于已验证的游戏关卡数。

## 运行模式

| 模式 | 用途 |
| --- | --- |
| 默认 | JVM 回归 |
| `debug` | Debug 界面、原生与投影回归 |
| `full` | Debug 与最终 minified Release 检查 |
| `accuracy` | 真实引擎输出与人工标签评分 |
| `cost` | 冻结输入下的软件成本对照 |

完整检查使用 arm64 Android 模拟器，明确提供已获准的开发回归图与对应清单。参数和用例所需素材以脚本为准：

```sh
python3 scripts/verify_match3_regression.py --help
python3 scripts/verify_match3_regression.py --mode full \
  --serial emulator-5554 --fixtures /绝对路径/开发回归图目录
```

`full` 需要原交付证书对应的 `SENSEFIELD_KEYSTORE_PATH`、`SENSEFIELD_KEY_ALIAS`、`SENSEFIELD_KEYSTORE_PASSWORD` 与 `SENSEFIELD_KEY_PASSWORD`。私有图只进入测试包，不放入生产 APK 或 Git；封存留出不作开发素材。

## 核对重点

- 几何：整盘、矩形、分离行与空洞；遮挡和动画时等待当前稳定证据，不借用旧像素。
- 元素：身份、覆盖状态与交换权限分别确认，未知机制不获得普通动物权限。
- 任务：HUD 身份和数字独立读取，按当前已确认的任务贡献排序。
- 高亮：有效提示持续可见，交换、弹窗和画面失效后撤下；覆盖像素不污染后续采样。
- 语音：方位与操作对应推荐；普通棋盘变化时，已开始的交换提示说完后再选择新提示，失效旧框立即隐藏，尚未开始的旧语音取消；停止、弹窗和会话失效仍立即中断。取消和迟到回调不影响新提示；软件回调与实际听感分开验证。
- 界面：大字、读屏标签、权限取消、旋转恢复、校准保存和停止流程。

脚本只操作 `emulator-*`，发现已有辅助会话时停止。核对实际测试数、完整结果和零跳过；缺素材的检查不计为通过。

语音连续性由 `Match3HintPlaybackPolicyTest`、`CueDispatcherTest` 和 `Match3HintInstrumentedTest` 覆盖。单独运行 `Match3SpeechCompletionInstrumentedTest` 可检查已安装中文 TTS 的实际开始、完成和停止回调；此检查要求媒体音量可听，不改变用户设置，不计作玩家听感确认。

## 交付与评测

交付使用完成检查的同一份签名 APK，核对摘要后生成更新清单。重新构建导致字节变化时，为新包重新记录对应检查。

独立识别与排序评估见 [图库流程](match3-flywheel.md) 和 [留出格式](../../validation/match3/holdout/README.md)。实际发声、玩家找格体验和同机运行负载使用 [真机测试模板](../../validation/protocols/SESSION_TEMPLATE.md)，与模拟器工程结果分别记录。
