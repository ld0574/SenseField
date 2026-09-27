# GitHub 发布检查清单

本清单用于公开仓库和比赛提交前的最后检查。自有代码已采用 Apache License 2.0；题方资料、录像、游戏画面、模型数据来源和第三方组件仍需分别核对授权边界。

## 1. 权利和隐私

- [x] 团队已在根目录加入 Apache License 2.0，并填写 2026 年团队版权信息。
- [ ] 确认题方 PDF、赛手手册、启动会逐字稿和调研材料是否允许公开。目前 `.gitignore` 默认排除现有 `docs/` 参考资料，只放行项目自写的运行和发布文档。
- [ ] 确认录像中所有玩家、账号名、语音和聊天内容的授权范围。原始录像默认永久留在 `video/` 或 `data/private/`，不进入公开仓库。
- [ ] 检查截图、演示视频和 README 图片，遮盖玩家昵称、账号、群号及其他个人信息。
- [ ] 不提交标注数据库、私有 profile、预测结果、训练数据、签名密钥、`.env` 或 `local.properties`。
- [ ] 确认公开 Git 仓库不包含 Roboflow 图片、模型权重、训练检查点或其他训练产物；如另行分发 APK，单独核对其中模型的来源、训练数据授权和可分发范围。
- [ ] 当前 GitHub 仓库已公开，旧提交 `e887714` 含有模型文件。团队需决定是否要从公开历史彻底移除；这需要确认历史改写与 force-push，并通知协作者重新同步。当前索引删除不会清理旧提交，本次未改写历史。
- [x] ncnn、YOLOX 与 pnnx 的版本、用途和许可证已登记在根目录 `THIRD_PARTY_NOTICES.md`，完整上游许可文本同时打包进 APK。

如果获得某份资料的公开授权，应单独记录授权来源、范围和日期，再精确调整 `.gitignore`；不要一次放开整个 `docs/` 或 `data/` 目录。

## 2. 内容和结果表述

- [ ] README 明确当前功能、默认关闭项和未验证项。
- [ ] `video1` 至 `video6` 全部标为开发数据，不作为独立测试成绩。
- [ ] video6 的均匀盲测只作为历史过程；后续已参与模型和阈值选择。
- [ ] 合成录像和模拟器结果只描述为链路验证。
- [ ] 真实准确率、召回率、方向正确率只引用冻结后的独立留出对局。
- [ ] P95 端到端延迟只引用实体 Android 13/14 手机上，外部录像测得的“可见证据到实际发声”时间。
- [ ] 没有通过验收的事件类型在现场配置中保持关闭。

## 3. 自动和人工检查

从仓库根目录执行：

```sh
python3 scripts/check_public_repo.py
PYTHONPATH=python python3 -m pytest -q
```

安卓代码有改动时再执行：

```sh
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug lintDebug
cd ..
```

人工查看即将提交的文件：

```sh
git status --short --ignored
git diff --check
git diff --cached --stat
git diff --cached
```

抽查关键私有路径确实被忽略：

```sh
git check-ignore -v video/video1.mp4
git check-ignore -v data/private/minimap-review-v3/annotations.sqlite3
git check-ignore -v android/local.properties
```

`scripts/check_public_repo.py` 会检查准备提交的文件、过大文件、常见密钥格式和许可证。自动扫描不能证明没有隐私信息，发布前仍需逐个查看暂存区。

## 4. 首个公开版本

许可证和授权完成后：

```sh
git add .
python3 scripts/check_public_repo.py
git status --short
git commit -m "Initial public prototype"
git remote add origin <GitHub 仓库地址>
git push -u origin main
```

建议先在 GitHub 创建空仓库，不自动生成 README、`.gitignore` 或 LICENSE，以免首次推送产生冲突。仓库可见性先设为 Private，团队复核暂存文件和 GitHub 页面后再切换为 Public。

## 5. Release 和比赛交付

- [ ] 用 Git tag 标记演示版本，例如 `v0.2.0-demo`。
- [ ] APK 放到 GitHub Release 附件，不把 `android/app/build/` 提交到源码仓库。
- [ ] 同时发布 APK 的 SHA-256、Android 最低版本、测试设备和已知限制。
- [ ] Release 不包含私有 profile；如需演示配置，只发布不含玩家数据、经过复核的配置。
- [ ] 保留一段可复现的合成演示；真实录像只有在取得授权后才能作为公开演示。
- [ ] 核对 [当前验证状态](../validation/STATUS.md)，避免把待验证项目写成已完成。
