# 听野 SenseField 官网

官网：<https://ld0574.github.io/SenseField/>

## 本地运行

需要 Node.js 22.12 或更新版本。

```sh
cd website
npm ci
npm run dev
```

本地地址：<http://127.0.0.1:5173/SenseField/>。

```sh
npm run verify
npm test
npm run build
npm run preview
```

`verify` 检查品牌色、表情符号、锚点、Lucide 图标和公开下载链接；`build` 先执行 TypeScript 检查，再输出 `dist/`。

## 自动部署

`.github/workflows/website.yml` 在 `main` 的官网文件更新后构建并部署 GitHub Pages；Pull Request 只检查与构建，不部署。GitHub 仓库 Pages 的 Source 应为 **GitHub Actions**。也可在 Actions 手动运行 **SenseField website**。

项目子路径在 `vite.config.ts` 中固定为 `/SenseField/`。更改仓库名或绑定独立域名时，应同步修改此路径与 `index.html` 中的 canonical、Open Graph、结构化数据及 `public/sitemap.xml`。

## 维护与品牌

- 官网沿用 Android `UiKit.java` 的既有色值：暖白 `#FBF7F1`、青蓝 `#236874`、深色 `#193139`、淡青 `#E7F2F2` 与杏色 `#F0C6B2`。
- 原有听野标志与品牌配色没有重设计，所有界面图标统一使用 Lucide。
- 下载入口与公开版本信息写在 `index.html`；发布新 APK 后，更新可见版本、下载链接与结构化数据。没有依赖 GitHub API 的首屏请求。
- 三维感知场由 Three.js 本地生成，不下载三维模型；WebGL 不可用时显示 CSS 静态视觉。
- 声音演示在用户点击开启后才播放；页面隐藏或离开演示区域时停止。仅演示短音的左右声道与音调差异，不模拟游戏识别。
- 首屏粒子在 GPU 上计算流动与指针扰动；点击画面或「释放感知」按钮触发扩散与弹簧回聚。移出视口、隐藏页面或暂停动效时停止动画帧。
- 用户心声支持使用体验、游戏适配和新想法。表单仅整理反馈，用户在 GitHub Issues 检查并发布，需要 GitHub 登录；也可复制整理后的内容。没有伪造用户评价，没有自动提交或隐形上传。无 JavaScript 时仍可将游戏名与正文带到 GitHub。
- 支持系统减少动态效果、手动暂停、键盘导航与静态内容阅读；页面不采集用户资料，不使用分析追踪。
- 若关闭 JavaScript，主要介绍、FAQ、文档与下载仍可访问。

素材来源和许可见 [ASSETS.md](ASSETS.md)。页面不是游戏截图，功能插图均为概念示意。
