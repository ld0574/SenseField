# 素材与许可

- `public/images/brand-mark.png`：复用本项目 `assets/sensefield-mark.png`，听野现有标志。
- `public/images/social-preview.png`：复用本项目 `assets/sensefield-social-preview.png`，社交分享预览。
- `public/images/openkhub-logo.png`：复用本项目 `assets/openkhub-logo.png`，罕见病开源社区现有标志，保留原图颜色及比例。
- `public/images/android-play.webp`：按用户指定的 `config.toml` 模型提供商配置，通过 imagegen 技能的 CLI 调用 `gpt-image-2.5` 生成，1536 × 1024，优化为 WebP。原始输出在本地忽略目录 `output/imagegen/android-play.png`；最终提示词为 [android-play.prompt.txt](assets/android-play.prompt.txt)。场景是双手横握安卓手机操作触屏游戏；屏幕为原创虚构游戏，未使用主机控制器、品牌标志或既有游戏截图。页面明确标为概念场景，非产品实测画面或用户证言。
- 界面图标：Lucide，ISC License，<https://lucide.dev/license>。通过 NPM 依赖打包。
- 英文字体：Manrope，SIL Open Font License 1.1，由 `@fontsource-variable/manrope` 提供，在构建时打包并自托管；中文沿用系统字体。
- 连续立体声浪、地图、棋盘与波形：项目内代码生成。首屏不包含粒子。

第三方游戏名称与商标归各自权利人所有。项目独立开发，与所提及游戏官方无隶属、合作或认可关系。
