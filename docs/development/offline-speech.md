# 0.4.4 离线提示音频与正式包构建

2026-10-08 女声再修订：按用户已选中的 Fish「游戏向导」替换 24 条固定提示女声，网页速度 0.9x，不额外添加逗号或停顿。408 个女声语速文件重制，448 个男声及完整说明文件摘要保持一致；男声继续默认。本批记录见 [游戏向导女声验证](../../validation/audio/FISH_GAME_GUIDE_0_4_4_2026-10-08.md)，原双解说音色保留为 [历史记录](../../validation/audio/FISH_VOICE_0_4_4_2026-10-08.md)。

固定游戏提示使用内置录音，不初始化手机 TTS 或 ASR。界面显示「游戏解说 · 男声」与「游戏向导 · 女声」。24 条固定提示各有 80%～240%、每档 10% 的 17 档保音高音频，两种音色都以用户接受的网页 0.9x 录音作为 APP 100% 语速基准。未设置或未知音色默认男声，已明确选择的女声保持女声；旧晓晓映射女声、云希映射男声。已有手动语速、音量不覆盖。完整说明按原文拆成 40 句，只保留原晓晓，播放时使用 Android 的保音高变速。音量在 AudioTrack 播放时调整。两字方位开关、提醒设置和助手默认关闭继续保留。

动态消消乐文本与助手回复仍使用用户选择的手机离线中文引擎。动态文本引擎设置与内置提醒音色分开，不能把内置提示可离线等同于任何手机都能合成动态中文。

## 音频来源和适用范围

用户指定通过网页生成素材。最初两批固定提示来自 [text-to-speech.cn](https://www.text-to-speech.cn/)，该站额度耗尽后，用户指定 [VoiceCraft](https://tts.wangwangit.com/) 生成完整说明。最终固定提示由用户试听选中的 [Fish 王者游戏解说男声](https://fish.audio/app/m/6bc140b33c39420b9cd7180f76e03846)（徐震）及 [游戏向导女声](https://fish.audio/app/m/49076f44a1d94065897bc0856aa70412)提供，使用 S2.1 Pro、0.9x 逐句生成，界面不再标成晓晓／云希。合成页下载在 Codex 内置浏览器里未成功，正常「保存到资产库 → 更多操作 → 下载」已导出真实 MP3；免费账户可以下载。浏览器操作连接恢复后，剩余男声已在内置浏览器完成导出；本批游戏向导直接从合成页「历史 → 下载」导出，分享链接的 `modelId`／`taskId` 与每条 MP3 一并核对。没有调用私有接口或绕过额度。原始录音、请求和生成成功截图在本机 `output/speech-source/`、`output/speech-audition/` 与 `output/releases/0.4.4/validation/`，不进入 Git。

VoiceCraft 声明仅供技术研究、学习和非商业体验，语音与音频权利归原提供方。Fish 的免费导出成功不证明 APK 分发或商业使用授权，具体范围尚未独立验证。本批记录为非商业黑客松候选；不是官方 Azure 配置生成的素材，也不构成商业分发许可证明。素材清单中的 `source_selected_by_user` 只表示用户选择来源。后续商业使用需换入具有相应授权的素材并重新构建、验证。

## 重建与校验

文本由实际 Java 说明目录导出，不能另写一份近似文本：

```bash
python3 scripts/prepare_bundled_speech.py web-batches
```

三份 SSML 使用固定 3 秒分隔停顿，正常语速、正常音高。网页下载后保存为 `xiaoxiao-fixed.wav`、`yunxi-fixed.wav`、`xiaoxiao-guide.wav`。必须是 16kHz、单声道、16bit PCM WAV；MP3 可先用 FFmpeg 转换。导入要求目标目录为空，修改前保留旧素材；分隔数量不符直接失败，不猜测句子对应位置：

```bash
python3 scripts/prepare_bundled_speech.py import-web-batches \
  --source-dir output/speech-source/text-to-speech-cn \
  --destination output/speech-source/neutral-assets
```

上面命令只准备原中性音源，不能直接替代当前游戏音色。Fish 每种音色的 24 条提示各自独立合成，不按 `[long pause]` 猜测切分。生产请求为 `[emphasis]` + 原文 + `。`；男声按用户要求移除逗号，但 Java 原文与文本摘要不变。通过合成历史核对请求后逐条下载（当前页面可直接导出，原男声使用资产库）；`source.json` 记录音色、网页速度、每条原文／文本摘要、实际请求、生成 ID、原始 MP3 文件名及摘要。本批只替换女声，保留当前男声和说明：

```bash
python3 scripts/prepare_bundled_speech.py import-fish-fixed \
  --source-dir output/speech-source/fish-game-guide-female-20261008 \
  --base-assets android/app/src/main/assets/speech \
  --destination output/speech-source/game-guide-reimport-stage
```

导入在临时目录生成、完整验证后才移动到目标目录；目标必须为空，不能覆盖源目录。通过后把生成目录换入 `android/app/src/main/assets/speech`，并把原素材保留在忽略的 `output/` 下，随后运行 `python3 scripts/prepare_bundled_speech.py verify`。本批仅指定游戏向导女声源，448 个男声与说明文件摘要均保留。新 UI 的裸 `taskId` 必须与实际分享页面的 `modelId`、`taskId` 相符；旧 `audio_<id>` 记录仍可核对。重复、漏句、错文、实际请求不符、错误语速、源文件摘要变动、非法路径或编码失败都不能留下部分 APP 素材。FFmpeg `atempo` 预生成固定提示语速，OGG/Opus 单声道约 32kbps，Android 平台解码后缓存当前音色与语速的 16kHz PCM。全库为 856 个音频，连清单 4,332,445 bytes；只有两种固定提示音色。文本、音色、语速、实际请求、原录音及生成文件摘要均记入 `assets/speech/manifest.json`。修改说明原文后，文本清单不匹配会阻止正式包构建。

`BundledSpeechAssets` 的索引、摘要和解码在工作线程进行；固定提示缓存最多两个音色／语速组合，说明最多缓存三句。已准备好的附近敌人语音与短音使用同一 PCM 轨道。提示音试听复用已准备的音效；音频完成按播放头判断。快速重播取消旧轮次，过期回调不能结束新播放。内置资源失败不回退网络 TTS。

## Release 与签名

交付脚本只接受完整现有签名配置，缺素材、缺签名或校验不符都停止，不退回 Debug。将 `SENSEFIELD_KEYSTORE_PATH`、`SENSEFIELD_KEY_ALIAS`、`SENSEFIELD_KEYSTORE_PASSWORD`、`SENSEFIELD_KEY_PASSWORD` 配置在本机环境中，随后执行：

```bash
bash scripts/build_android_preview.sh
```

保留 0.4.3 签名证书 SHA-256：`5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68`。Release 启用 R8 和资源裁剪；保留 JNI 名称及仪器测试跨 APK 调用的有限音频／识别接口，没有保留整个应用。测试专用注解规则位于 `proguard-test-rules.pro`。原生库与 DEX 使用 AGP 的压缩打包配置，OGG 保持可直接打开的 asset。原生库在安装时展开，下载体积不等于安装占用。

脚本先验证实际 APK 的版本、签名、非 Debug、arm64、两份小地图模型摘要、六个运行库、16KB ELF 对齐、压缩设置和真实音频，再进入交付目录：

- `output/releases/0.4.4/gitee-upload/sensefieldv0.4.4.apk`
- `output/releases/0.4.4/cdn-upload/latest.json`

更新说明从 `docs/releases/0.4.4/UPDATE_SUMMARY.txt` 读取，最多 3 行／100 字。按 [CDN 发布文档](../../deploy/assistant/CDN发布.md) 先上传 Gitee APK，再替换网站的 `latest.json`；本轮未自动上传或发布。

## 验证与待测

运行带 `-PsensefieldTestBuildType=release` 的 `testReleaseUnitTest`、`lintRelease` 和设备测试。当前女声批次记录见 [游戏向导记录](../../validation/audio/FISH_GAME_GUIDE_0_4_4_2026-10-08.md)，原双解说音色见 [10 月 8 日历史记录](../../validation/audio/FISH_VOICE_0_4_4_2026-10-08.md)，前一候选的完整流程见 [10 月 7 日记录](../../validation/audio/OFFLINE_SPEECH_0_4_4_2026-10-07.md)。保留的少量测试接口用于测试实际 minified Release；依赖私有字段反射的旧 UI 测试使用 Debug 辅助构建并分别记录，不拿它们替代正式包证据。

合成音频 fixture 使用独立包名、明确的 fixture 版本，只能在没有真实素材时生成；交付门禁拒绝它。Android 10、16KB 系统的实际安装、真机外部录音与受控温升未验证时必须保持待测。声音是否清楚、音效与语音的实际间隙、患者体验不能由播放回调代替。
