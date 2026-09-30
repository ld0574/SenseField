# 0.3.0-alpha.1 GitHub pre-release 检查清单

这份清单用于明日 developer preview 发布前的可审查准备。未完成的项目保留为未勾选，不能在 Release 文案中写成已验收。

## 版本和候选文件

- [x] `android/app/build.gradle` 为 `versionName '0.3.0-alpha.1'`、`versionCode 7`。
- [x] `android/app/build.gradle` 只声明 `arm64-v8a`；页面注明最低 API 29、目标 API 35。
- [x] 用 `bash scripts/build_android_preview.sh` 构建并运行 `apksigner verify`。
- [x] 没有完整签名环境变量时，附件名称明确包含 `debug-candidate`，并在 Release body 说明它不是正式 release 签名包。
- [ ] 有签名 candidate 时，四个 `SENSEFIELD_*` 环境变量来自发布者的已有 keystore；记录 APK SHA-256 和签名验证输出。
- [x] 发布者没有生成、上传或提交发布 keystore、密码、`.env`、`local.properties` 或其他签名材料；Debug candidate 使用 Android Gradle 的标准 debug signing。

## 功能边界和权限文案

- [x] Release body 链接 README 和本 release notes；明确这是 pre-release developer preview、不是最终验收版本。
- [x] 写明实验小地图模型尚未通过独立留出对局验收；开发指标不写成独立测试成绩。
- [x] 明确 release APK 仍绑定单类 `minimap_enemy`；安全双类 v8 只作开发诊断，未导出或放入 Android assets。
- [x] 写明公共默认 profile 关闭主画面边缘分类器，相关数据未复核前不进入 Release 配置。
- [x] 写明 APK 只支持 `arm64-v8a`，并附 Android 最低版本和 SHA-256。
- [x] 说明 `MediaProjection` 用于用户授权的整屏帧、悬浮窗权限用于非交互提示层、通知权限用于前台服务状态；说明处理在本地完成。
- [x] 核对 README、Release notes、应用设置文案中的默认开关和关闭方式一致。

## 公开仓库和证据

- [x] `python3 scripts/check_public_repo.py` 通过。
- [x] `git diff --check` 通过，逐个查看将要提交的 README、版本配置、验证状态、发布文档和脚本。
- [x] 没有私有录像、标注数据库、预测结果、训练权重、模型 checkpoint 或玩家个人信息进入发布附件。
- [x] `validation/STATUS.md` 中的历史 0.2.x 证据仍标为历史；新版本准备状态不冒充实体机最终验收。
- [x] 真实录像、截图和音频只有在取得公开授权后才作为附件；默认只发布源码和经过复核的 APK。

## GitHub 操作

- [ ] 在目标提交上创建 tag `v0.3.0-alpha.1`，确认 tag 指向的版本配置为 versionCode 7。
- [ ] 创建 GitHub Release 时勾选 **Set as a pre-release**，标题包含 `0.3.0-alpha.1`。
- [ ] 上传经过签名核验的候选 APK 和脚本生成的 `.sha256` 文件；附件文件名保留 `debug-candidate` 或 `signed-candidate`。
- [ ] Release body 保留实验模型、主画面边缘分类器、arm64-v8a、权限用途和本地处理限制。
- [ ] 发布后从干净浏览器页面检查附件可下载、校验和可见、README 链接有效；把最终 URL 和校验和回填团队记录。
- [ ] 不使用脚本自动发布 GitHub Release；发布按钮由完成复核的团队成员最后确认。
