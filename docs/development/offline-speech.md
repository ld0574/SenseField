# 内置语音与正式包构建

固定游戏提示使用内置录音，提供“游戏解说 · 男声”和“游戏向导 · 女声”，可调整音量与语速；固定提示不依赖网络 TTS。动态消消乐文本和助手回复使用手机的中文 TTS，与内置提醒音色分别设置。

## 来源与许可

固定音色来自 [Fish 游戏解说男声](https://fish.audio/app/m/6bc140b33c39420b9cd7180f76e03846) 与 [游戏向导女声](https://fish.audio/app/m/49076f44a1d94065897bc0856aa70412)。说明音频使用既有晓晓素材，来源包括 [text-to-speech.cn](https://www.text-to-speech.cn/) 与 [VoiceCraft](https://tts.wangwangit.com/)。原始音频、来源和生成回执保存在本地，清单记录文本、请求及文件摘要。

VoiceCraft 声明用于技术研究、学习和非商业体验。Fish 免费导出不代表已获得 APK 分发或商业使用许可；本项目记录的使用场景为非商业黑客松，素材分发与后续商业使用须分别核对授权，必要时替换为明确授权的音源。完整来源见 [第三方声明](../../THIRD_PARTY_NOTICES.md)。

## 资源准备

实际文本从 Java 说明目录导出，不另写近似文本：

```sh
python3 scripts/prepare_bundled_speech.py web-batches
```

网页批次需使用与导出一致的分隔，输入为 16kHz、单声道、16bit PCM WAV。工具按文本和分隔核验后导入：

```sh
python3 scripts/prepare_bundled_speech.py import-web-batches \
  --source-dir output/speech-source/text-to-speech-cn \
  --destination output/speech-source/neutral-assets
```

该命令准备中性音源。Fish 固定提示逐句生成，以真实来源清单导入，保留另一音色及说明资源：

```sh
python3 scripts/prepare_bundled_speech.py import-fish-fixed \
  --source-dir output/speech-source/fish-game-guide-female-20261008 \
  --base-assets android/app/src/main/assets/speech \
  --destination output/speech-source/game-guide-reimport-stage
```

先在空的临时目录导入并校验，确认后替换应用资源，旧素材留在本地 `output/`。固定语速档位预生成为保音高 OGG/Opus，来源、文本和文件摘要写入 `assets/speech/manifest.json`。正式构建前执行：

```sh
python3 scripts/prepare_bundled_speech.py verify
```

## 正式包与签名

在本机准备四个环境变量：`SENSEFIELD_KEYSTORE_PATH`、`SENSEFIELD_KEY_ALIAS`、`SENSEFIELD_KEYSTORE_PASSWORD`、`SENSEFIELD_KEY_PASSWORD`，不要将值写入仓库。随后运行：

```sh
bash scripts/build_android_preview.sh
```

构建脚本核对版本、签名、arm64、模型摘要、运行库和实际音频；素材或签名不完整时停止。Release 启用 R8 与资源裁剪，保留必要的 JNI 接口。合成音频 fixture 仅用于隔离测试，不能作为正式资源交付。

当前发布说明与摘要位于 `docs/releases/0.4.7/`。二进制及清单输出到本地构建和交付目录；上传与公开下载核对按 [CDN 发布](../../deploy/assistant/CDN发布.md) 执行。音频播放回调验证与真机实际听感分别记录。
