# 听野 SenseField 官网

官网：<https://ld0574.github.io/SenseField/>

## 本地运行

需要 Node.js 22.12 或更新版本。

```sh
cd website
npm ci
npm run release:sync
npm run dev
```

本地地址：<http://127.0.0.1:5173/SenseField/>。

```sh
npm run verify
npm test
npm run build
npm run preview
```

`verify` 检查品牌色、中文默认文案、双语入口、锚点、Lucide 图标、展开式内容和动态下载；`build` 先执行 TypeScript 检查，再输出 `dist/`。测试覆盖中英反馈校验和动态发行信息的解析、下载链接校验及请求失败。

## 自动部署

`.github/workflows/website.yml` 在 `main` 的官网文件更新后构建并部署 GitHub Pages；Pull Request 只检查与构建，不部署。GitHub 仓库 Pages 的 Source 应为 **GitHub Actions**。也可在 Actions 手动运行 **SenseField website**。定时任务每小时的第 17、47 分钟检查 CDN 版本清单；数据未变化时跳过构建与部署。

项目子路径在 `vite.config.ts` 中固定为 `/SenseField/`。更改仓库名或绑定独立域名时，应同步修改此路径与 `index.html` 中的 canonical、Open Graph、结构化数据及 `public/sitemap.xml`。

## 维护与品牌

- 官网沿用 Android `UiKit.java` 的既有色值：暖白 `#FBF7F1`、青蓝 `#236874`、深色 `#193139`、淡青 `#E7F2F2` 与杏色 `#F0C6B2`。
- 原有听野标志与品牌配色没有重设计，所有界面图标统一使用 Lucide。
- 中文是默认语言，页首提供英文切换，文案、控件、无障碍标签和反馈校验一起切换，保留表单填写内容。语言偏好在本地保存，也支持 `?lang=en` / `?lang=zh` 指定语言。版本更新说明沿用 CDN 的中文原文，英文界面注明语言。
- 唯一版本来源为 <https://888413.xyz/apk/latest.json>。首屏与下载区域的 APK 地址、版本、包大小、更新说明和结构化数据统一由 JSON 赋值，不在 HTML 中固定发版地址。
- CDN 目前未发送 CORS 响应头，浏览器无法直接跨域读取。部署任务通过 `scripts/sync-release.mjs` 读取和校验 CDN 清单，生成站内 `latest.json`；官网读取同源 JSON。定时任务自动同步新版本，发版后不需要修改或提交官网源代码。同步通常在下一次半小时检查后完成，GitHub Actions 的调度可能延迟。打开着的页面每 15 分钟重新读取站内清单。
- 版本清单读取失败时保留可访问的码云发行页入口，页面不会显示猜测的版本或失效的固定 APK 地址。同步任务失败时不会覆盖已部署版本。
- 三维声浪由 Three.js 本地生成，不下载模型，不使用粒子；WebGL 不可用时显示 CSS 静态视觉。平滑呼吸与阻尼指针响应自动播放，不需要点击触发；移出视口、隐藏页面或暂停动效时停止动画帧。
- 声音演示在用户点击开启后才播放；页面隐藏或离开演示区域时停止。仅演示短音的左右声道与音调差异，不模拟游戏识别。
- 功能说明、三项视觉示意和常见问题全部展开，直接向下阅读。使用说明书链接出现在首屏、下载指南、常见问题及页脚；页脚保留听野与罕见病开源社区标志，提供 GitHub、Gitee 链接。
- 用户心声支持使用体验、游戏适配和新想法。表单仅整理反馈，用户在 GitHub Issues 检查并发布，需要 GitHub 登录；也可复制整理后的内容。没有伪造用户评价，没有自动提交或隐形上传。无 JavaScript 时仍可将游戏名与正文带到 GitHub。
- 支持系统减少动态效果、手动暂停、键盘导航与静态内容阅读；页面不采集用户资料，不使用分析追踪。
- 若关闭 JavaScript，主要介绍、FAQ、文档与下载仍可访问。

素材来源、图片提示词和许可见 [ASSETS.md](ASSETS.md)。安卓手游照片由 `gpt-image-2.5` 生成，页面标明概念场景；功能插图均为概念示意。
