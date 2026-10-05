package com.openkhub.sensefield;

/** Chinese help copy for setting-specific pages. Kept separate from UI and behavior. */
final class SettingHelpContent {
    private SettingHelpContent() {}

    static String title(String key) {
        switch (key) {
            case "voice_group": return "提醒测试与语音";
            case "speech_engine": return "语音引擎";
            case "speech_rate": return "语速";
            case "test_cue": return "提醒测试";
            case "before_start_read": return "每次开始前打开提醒说明与试听";
            case "reminder_guide": return "提醒说明与试听";
            case "recognition_group": return "识别功能";
            case "recognition_experiment": return "启用小地图识别（实验）";
            case "new_avatar": return "提醒新出现的敌方头像";
            case "preset_group": return "提示方案";
            case "tuning_group": return "提示调节";
            case "channel_group": return "输出方式";
            case "channel_visual": return "视觉提示";
            case "channel_tone": return "提示音";
            case "channel_speech": return "语音";
            case "channel_haptic": return "触觉";
            case "events_group": return "事件提示";
            case "event_near": return "附近敌人提醒（小地图近区）";
            case "event_near_haptic": return "附近敌人震动";
            case "event_far_appear": return "远处新敌人提示音";
            case "event_new_target": return "小地图新目标";
            case "event_edge": return "屏幕边缘威胁";
            case "event_danger": return "危险接近";
            case "event_player": return "玩家死亡／复活";
            case "event_system": return "系统状态";
            case "assistant_group": return "实验助手";
            case "assistant_connection": return "画面服务连接";
            case "assistant_voice": return "连续语音（可以插话）";
            case "assistant_vision": return "画面理解";
            case "assistant_proactive": return "低频主动描述";
            case "assistant_overlay": return "授权左侧小圆点";
            case "presentation_group": return "呈现层实验";
            case "presentation_spatial": return "左右双耳线索";
            case "presentation_haptic": return "距离强度与节奏震动";
            case "presentation_two_word": return "两字方位提示";
            case "capture_screen": return "屏幕采集";
            case "capture_notifications": return "运行通知";
            case "capture_visual": return "允许使用视觉提示";
            case "capture_overlay": return "置顶视觉提示";
            case "capture_battery": return "后台省电策略";
            case "capture_battery_app": return "应用后台运行";
            case "capture_battery_system": return "系统省电优化";
            default: return "设置";
        }
    }

    static String text(String key) {
        switch (key) {
            case "voice_group":
                return "这里可以选择系统语音引擎、调整提醒语速，并试听提醒。语速只影响语音播报；调好后可用“测试提醒”确认听感。";
            case "speech_engine":
                return "选择“跟随手机系统”会使用系统当前的文字转语音引擎，也可以指定手机已安装的引擎。不同引擎的音色和可用语言由手机提供。更换引擎后请试听一次。";
            case "speech_rate":
                return "这个滑杆只调整听野提醒语音的播放速度，不改变识别速度或提示音。数字越大，语音越快；如果方向词不容易听清，可以适当调慢。";
            case "test_cue":
                return "试听会按当前附近敌人提醒设置发送一次测试提示。若相关事件或输出通道已关闭，页面会提示原因；测试期间媒体音量和应用提示音量都需要大于零。";
            case "before_start_read":
                return "开启后，每次开始新的辅助会话前会打开“提醒说明与试听”目录。进入完整说明后，可以按朗读按钮播放；点击某一项会打开解释并播放该项示例。开启系统屏幕阅读器时，请使用页面内的播放按钮，以免朗读与示例抢声。关闭此选项后仍可手动打开目录，不影响其他提醒。";
            case "reminder_guide":
                return "目录列出当前开启的声音、语音和振动示例。每项可以单独反复播放与停止；完整说明有独立入口。示例使用当前设置，辅助运行时请先停止对局辅助再试听。";
            case "recognition_group":
                return "识别会从共享画面中读取游戏提示。页面上的实验开关和新头像开关分别控制识别配置与新头像提醒；系统按当前游戏配置处理画面。";
            case "recognition_experiment":
                return "此开关允许游戏配置中标记为实验的识别器参与识别。关闭后，未验证配置中的实验识别器不会启用；已验证配置不受此开关限制。不同机型和游戏画面可能影响识别效果。";
            case "new_avatar":
                return "开启后，识别到小地图新出现的敌方头像时可以发出提醒。新头像只说明观察到头像变化，不代表敌人距离近或正在接近。若选择视觉提示，还需要置顶显示权限。";
            case "preset_group":
                return "精简、标准和详细是预设，会一起设置多项输出通道和事件开关。选择预设后再单独更改开关，会形成自定义方案；阅读此说明不会应用或重置预设。";
            case "tuning_group":
                return "提示音量调整应用提醒声音，手机媒体音量也会影响实际声音。最短提示间隔主要用于画面事件；小地图附近敌人有独立的去重与再次提醒规则。中央免提示区用于画面边缘提示，不改变小地图近区范围。滑杆改动会保存到当前游戏配置。";
            case "channel_group":
                return "输出通道决定提醒可以用什么方式呈现。一个事件还需要自身开启，并且其方案支持该通道，才会实际发出。视觉提示另需系统置顶权限。";
            case "channel_visual":
                return "开启后，支持视觉呈现的提醒可以显示为屏幕上的视觉标记。它需要系统允许听野置顶显示；关闭此通道不会关闭语音、提示音或触觉。";
            case "channel_tone":
                return "开启后，支持提示音的事件可以播放短音。手机媒体音量、应用提示音量和具体事件设置也会影响是否听到声音。";
            case "channel_speech":
                return "开启后，支持语音的事件可以通过手机文字转语音播报。是否成功还取决于已安装的语音引擎及其语言支持；可在声音配置页试听。";
            case "channel_haptic":
                return "开启后，支持触觉提示的事件可以请求手机震动。实际感觉受手机硬件和系统设置影响；此通道不提供可靠的左右方向信息。";
            case "events_group":
                return "事件开关决定哪些已识别情况可以提醒。关闭某一事件后，该类提醒会停用；输出方式还会受上方通道和当前提示方案影响。";
            case "event_near":
                return "控制小地图近区敌人的提醒。方向按小地图中以玩家为中心的位置判断；没有听到提醒不能说明周围一定没有敌人。声音、语音和震动还受对应输出设置影响。";
            case "event_near_haptic":
                return "单独控制附近敌人事件的震动。总触觉通道也需要开启；此项只影响附近敌人，不控制其他事件的震动。震动不表示左右方向。";
            case "event_far_appear":
                return "控制附近以外出现新敌方头像时的提示音。它表示小地图观察到新头像，不提供距离判断，也不替代附近敌人提醒。";
            case "event_new_target":
                return "控制识别到小地图新敌方头像后的提醒。该事件与“提醒新出现的敌方头像”识别开关需要同时开启；提醒方式还会受提示方案和输出通道影响。";
            case "event_edge":
                return "控制主画面边缘威胁提示。此类提示通常通过短音呈现，方向来自画面边缘位置；不代表小地图中的敌人位置。";
            case "event_danger":
                return "控制游戏危险标记的提醒。它表示识别到相应的游戏提示标记，不是对战局风险的独立判断。";
            case "event_player":
                return "控制玩家阵亡或复活状态的提醒。识别结果可能受游戏画面遮挡或显示变化影响；可以按需要关闭这类提示。";
            case "event_system":
                return "控制辅助运行状态类提示，例如截屏授权结束或恢复失败时的状态语音。遇到重新授权提示时，请返回授权页面重新开始。";
            case "assistant_group":
                return "实验助手包含连续语音和可选画面理解。语音输入走手机侧识别；只有开启画面理解后，问题文字或授权画面才会发送到配置的画面服务。";
            case "assistant_connection":
                return "服务地址与体验连接码仅用于画面理解连接。本地语音识别不需要这两项；请只填写可信服务提供方给出的 HTTPS 地址和连接码。保存或更改连接后需要重新开始辅助。";
            case "assistant_voice":
                return "开启后，听野会使用手机侧语音识别处理连续语音，首次启动需要等待本地模型加载，并需要麦克风权限。音频在识别过程中临时处理；若同时使用画面理解，问题文字可能随请求发送到配置的画面服务。游戏开麦或录音路线不明确时，输入会暂停。";
            case "assistant_vision":
                return "提问时，当前共享画面和最多两张最近画面的缩图会发送到配置的画面服务及其视觉模型；开启低频主动描述后，也会发送当前画面用于观察。画面可能带有聊天或通知，请先确认共享范围。近期画面只缓存在内存，服务方按其条款处理收到的数据。";
            case "assistant_proactive":
                return "开启后，助手会在低频观察中主动描述画面。此功能需要同时开启画面理解，会把当前共享画面发送到配置的画面服务；不需要时可关闭主动描述或画面理解。";
            case "assistant_overlay":
                return "置顶权限用于显示助手左侧小圆点。没有此权限时，连续语音仍可使用，也可以通过运行通知查看或操作辅助状态。授权系统设置由手机显示。";
            case "presentation_group":
                return "这些呈现方式仍属实验。不同手机、耳机和游戏画面会影响体验，开启或更改后请按页面提示重新开始辅助，并自行确认是否清楚。";
            case "presentation_spatial":
                return "开启后会尝试用立体声左右线索呈现方向，需使用支持立体声的双耳耳机。它不等同于完整 HRTF；单声道播放或外放可能听不出左右差异。";
            case "presentation_haptic":
                return "开启后会尝试用震动强度和节奏表达距离层级。此呈现仍需玩家验证，实际强弱和节奏感受受手机马达及系统设置影响，不能据此判断精确距离。";
            case "presentation_two_word":
                return "开启后会尝试用更短的两字方位语音提示，减少播报长度。方向仍按当前提醒规则解释；此呈现需要在实际游戏中确认是否容易听懂。";
            case "capture_screen":
                return "每次开始新的辅助会话，系统都会单独询问是否共享屏幕。授权范围由系统弹窗说明；开始前请确认共享内容，并可在运行通知中暂停或停止辅助。";
            case "capture_notifications":
                return "运行通知用于显示辅助当前状态，并提供暂停、继续或停止等操作。Android 13 及以上需要单独允许通知；未允许时，部分设备也会阻止辅助正常启动。";
            case "capture_visual":
                return "此项控制视觉提示通道。开启视觉提示并启用小地图新头像标记时，需要置顶显示授权；只使用语音、提示音或触觉时，可以关闭此项。";
            case "capture_overlay":
                return "授权置顶显示后，听野才能在其他应用上方显示视觉标记。此权限只影响置顶视觉提示；关闭视觉通道后，语音和触觉仍可使用。";
            case "capture_battery":
                return "省电策略用于降低游戏过程中应用被系统或手机厂商后台管理结束的概率。建议允许后台运行；具体选项名称和效果由手机系统与厂商决定。";
            case "capture_battery_app":
                return "打开应用详情页后，可检查听野的电池和后台运行限制。不同品牌的菜单名称不同，按手机系统提供的选项允许后台运行即可。";
            case "capture_battery_system":
                return "此入口打开系统省电优化设置。允许后台运行可能有帮助，但标准省电状态无法代表所有厂商的后台限制；如仍中断，请查看手机厂商的应用管理设置。";
            default:
                return "此说明只介绍当前功能，不会更改任何设置。返回后可以继续使用原来的设置页面。";
        }
    }
}
