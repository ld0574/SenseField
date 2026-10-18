# 3 分钟实机视频分发

使用已确认的 3 分钟 v5 成片，不使用 5 分钟版本。上传保持原文件字节，不重新编码。

| 项目 | 值 |
| --- | --- |
| 公开视频文件 | <https://www.modelscope.cn/api/v1/studio/Khub/SenseField/repo?Revision=master&FilePath=web/videos/sensefield-demo-3min-v5-55875bd1.mp4> |
| 魔搭创空间 | <https://www.modelscope.cn/studios/Khub/SenseField> |
| 创空间仓库内路径 | `web/videos/sensefield-demo-3min-v5-55875bd1.mp4` |
| 本地来源 | `demo-video/final-demo-3min-v5.mp4`（不提交源码仓库） |
| 视频 SHA-256 | `55875bd105da4b7a536f0e998919ccc2a427a990be9a88df087e8ed505407859` |
| 文件字节数 | `155944011` |
| 编码 | 180.000 秒，1920×1080，30fps，H.264，AAC 48kHz 双声道，MP4 索引前置 |
| 官网入口 | <https://ld0574.github.io/SenseField/#demo> |

播放器在用户点击后播放，默认保留原声，并提供浏览器全屏和进度控制。README 的封面指向官网播放器，并另给同一视频文件直链，避免依赖 Markdown 平台的视频嵌入支持。封面是成片 2.2 秒的等比缩小画面。

魔搭仓库用 Git LFS 保存 MP4；网站源码仓库仅保存封面与公开地址。视频文件名包含版本和 SHA 前缀，后续替换应使用新文件名，避免沿用旧缓存。更新视频时，核对公开文件大小与 SHA-256、`video/mp4` 类型和字节范围请求，再同步官网、README 和本记录。魔搭网页镜像保留 `web/videos/`，重建页面时只替换当前构建产物。

官方文件接口可作为 `<video>` 的 MP4 源；浏览器直接打开该接口时会下载文件，在线观看使用官网播放器。外链统一采用文件接口，避免独立创空间域名在浏览器导航时跳转到平台外框。
