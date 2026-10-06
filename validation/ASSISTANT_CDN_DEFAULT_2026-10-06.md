# CDN语音资源与默认线上助手（2026-10-06）

用户批准将语音权重移到CDN，默认使用听野线上服务，无需用户填写地址或连接码；随后授权通过无线ADB安装到手机。保持0.4.1 / code18及原测试签名，不发布Release、不修改评分或verified/release_ready。

## 实现及边界

- 同一个SenseVoiceSmall int8模型，固定模型与词表哈希不变。APK保留运行库、许可证和校验元数据，排除两个权重文件。手机已有noBackup缓存校验后复用，进程内避免每次会话重复计算大文件摘要。
- 首次开启连续语音自动准备资源；设置页显示进度，支持重试。HTTPS同源下载、断点续传、完整包及逐文件SHA-256校验；拒绝跨域/降级跳转、路径穿越、重复条目和超尺寸。下载/解压在工作线程，关闭语音停止后续下载，已下载部分保留。短EOF也保留前缀；不在识别线程编码或下载。
- 默认服务sf.888413.xyz，旧地址/令牌偏好不会覆盖默认服务；移除用户连接控件，保留独立授权。私有开发服务由隐藏的显式配置选择，合成测试同步选择该模式。
- 新后端显式公开视觉入口，用随机安装UUID做请求额度计数，不属于硬件认证。SQLite原子计数保留重启前额度，默认全站1000/UTC日、每安装300/UTC日、12/分钟桶；无效请求体不扣，有效尝试即扣。公开入口不接受音频，通用私有部署仍需令牌。数据库故障拒绝模型请求。
- 统一部署入口deploy/assistant/start.sh默认后台启动，自动开启公开视觉入口；复用现有私有模型配置与公网反代。配置工具支持--public-access，不生成玩家连接码。systemd具有可写的持久化StateDirectory，旧服务的严格迁移匹配保留。

## 制品与验证

- APK：output/releases/0.4.1/apk-cdn-default-2026-10-06/听野v0.4.1 安卓测试安装包.apk，49,503,191 bytes（47.21 MiB），SHA-256 e85250f43ce5e5c7d4099119cbc64ca6155d535a90b0071f649268f6aa44c489。相较先前214,861,660 bytes减少76.96%。Android 10/API29+、target35、arm64。
- 模型包：160,304,482 bytes，SHA-256 d863ab6f0b202ee31e74f2a3eadabaf24a41ea7acfe9fc6f85a1efaed2e37a8c，固定URL https://888413.xyz/apk/models/sensevoice-int8-v1.zip。ZIP内模型与词表大小/摘要与原固定元数据一致。
- 仍为Debug测试候选，证书SHA-256 5a42a53a8f06850e89c46ea193931e9853e3ce7cff99551b42e8b414a1eaaf68，与原手机候选相同。
- 串行重跑Android JVM 295项全通过、lintDebug与构建通过。下载夹具覆盖网络中断/短EOF续传、服务器忽略Range、错误Content-Range、损坏包/逐文件摘要、路径穿越、重复条目、超尺寸、取消和旧缓存复用。
- Python 159项通过，包括私有认证保留、公开访问/配额、持久化/并发全站上限、公开音频拒绝及旧原生服务识别。
- 本机实际TLS套接字+生产处理器+合成视觉适配器通过verify.py的401、422、413和有效合成问答。该11ms结果仅说明本机协议通路，不能作为真实模型速度或生产端到端指标。
- API33模拟器3项通过：默认服务忽略旧连接、随机安装标识持久化、设置页没有连接输入、语音资源入口，以及预先放入固定缓存后的真实ASR合成问题。三次推理139/235/276ms，仅为模拟器合成音频样本，不作为手机P95。
- 两项准备阶段失败留档：首次ASR夹具因缓存拷贝命令引用不正确而未播种，修正后通过；并行Gradle造成目录竞争，改为串行--rerun-tasks后通过。未把这些失败当成模型性能证据。
- 实际APK ZIP CRC、模型排除/许可证保留、BuildConfig生产地址与CDN清单、SDK/ABI、签名核验通过。CDN latest.json摘要和大小对应当前APK，原大包留档在archive-before-cdn-default-2026-10-06。

## 手机与生产状态

用户新授权后连接10.10.10.16:37041，覆盖安装成功并打开GameSelectionActivity。手机包版本0.4.1/code18，安装base.apk SHA-256与上述新包完全一致。未自动开启麦克风、截屏或对局。已为当前应用PID启动20分钟限定的logcat记录，路径output/assistant/0.4.1/phone-cdn-default-2026-10-06/phone-app.log；应用重启后需要重新选择PID。

只读检查：模型URL返回200且Content-Length匹配，尾部Range请求返回正确206及Content-Range，续传服务可用；未下载整份线上模型核对摘要。生产/health返回200、production/vision_only，但没有public_vision_access，仍属旧服务。线上latest.json仍指向21,400,339 bytes的旧APK，尚不对应本包。未登录或改动生产服务器，未上传新版APK/清单。

需要用户覆盖新版服务端并restart，上传/核对模型，再覆盖APK及latest.json并刷新CDN缓存。患者真机连续对局、外放/耳机实声、热负载、真实模型延迟和生产免连接码问答仍待验收。

所有制品、日志、public-readiness.json、handoff.json位于上述dated目录；部署步骤为deploy/assistant/README.md，上传文件整理在output/releases/0.4.1/cdn-upload/。
