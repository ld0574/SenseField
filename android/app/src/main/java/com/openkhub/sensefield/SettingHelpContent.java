package com.openkhub.sensefield;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Chinese help copy for setting-specific pages. Kept separate from UI and behavior. */
final class SettingHelpContent {
    private SettingHelpContent() {}

    static String title(String key) {
        switch (key) {
            case "voice_group": return "提醒测试与语音";
            case "speech_engine": return "选择语音引擎";
            case "speech_rate": return "语速";
            case "test_cue": return "测试提醒与振动";
            case "haptic_settings_link": return "震感与节奏";
            case "before_start_read": return "每次开始前打开提醒说明与试听";
            case "reminder_guide": return "提醒说明与试听";
            case "recognition_group": return "识别功能";
            case "recognition_experiment": return "启用小地图识别（实验）";
            case "new_avatar": return "提醒新出现的敌方头像";
            case "preset_group": return "提示方案";
            case "preset_compact": return "精简";
            case "preset_standard": return "标准";
            case "preset_detailed": return "详细";
            case "preset_channels_events": return "提示通道与事件";
            case "tuning_group": return "提示调节";
            case "tuning_volume": return "提示音量";
            case "tuning_interval": return "最短提示间隔";
            case "tuning_center": return "中央免提示区";
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
            case "assistant_connection": return "画面服务连接";
            case "assistant_service_address": return "画面服务地址（HTTPS）";
            case "assistant_experience_code": return "体验连接码";
            case "assistant_online_service": return "使用听野线上服务";
            case "assistant_save_connection": return "保存服务连接";
            case "assistant_group": return "语音与画面交互";
            case "assistant_voice": return "连续语音（可以插话）";
            case "assistant_vision": return "画面理解";
            case "assistant_proactive": return "低频主动描述（需要画面理解）";
            case "assistant_overlay": return "授权左侧小圆点";
            case "presentation_group": return "呈现层实验（默认关闭）";
            case "presentation_spatial": return "左右双耳线索（需双耳耳机）";
            case "presentation_haptic": return "距离强度与节奏震动";
            case "presentation_two_word": return "两字方位提示";
            case "capture_screen": return "屏幕采集";
            case "capture_screen_grant": return "授权截屏并开始";
            case "capture_notifications": return "运行通知";
            case "capture_notifications_grant": return "授权运行通知";
            case "capture_overlay": return "置顶视觉提示";
            case "capture_visual": return "允许使用视觉提示";
            case "capture_overlay_grant": return "授权置顶显示";
            case "capture_battery": return "后台省电策略";
            case "capture_battery_app": return "设置后台省电策略";
            case "capture_battery_system": return "系统省电优化设置";
            case "haptic_rhythm": return "提醒节奏";
            case "haptic_rhythm_original": return "保留原有节奏（默认）";
            case "haptic_rhythm_short": return "短促节奏（保留单／双震）";
            case "haptic_strength": return "震感档位";
            case "haptic_strength_system": return "设备默认（默认）";
            case "haptic_strength_light": return "较轻";
            case "haptic_strength_strong": return "较强";
            case "haptic_preview": return "试听";
            case "haptic_preview_button": return "试听当前触觉设置";
            default: return "设置";
        }
    }

    /** One-sentence descriptions shown beside each group's help entry. */
    static String summary(String key) {
        switch (key) {
            case "voice_group": return "选择语音、测试提醒与振动，并查看试听。";
            case "recognition_group": return "设置实验识别与新头像提醒。";
            case "preset_group": return "选择预设，调整通道与事件。";
            case "tuning_group": return "调整音量、间隔和中央免提示区。";
            case "channel_group": return "选择视觉、提示音、语音和触觉。";
            case "events_group": return "开启或关闭各类提醒事件。";
            case "assistant_connection": return "连接画面服务；本地语音无需配置。";
            case "assistant_group": return "选择语音、画面问答和主动描述。";
            case "presentation_group": return "调整仍在验证中的实验呈现方式。";
            case "capture_screen": return "每次启动辅助前，按系统提示授权屏幕。";
            case "capture_notifications": return "查看运行状态，并暂停或停止辅助。";
            case "capture_overlay": return "视觉提醒需打开通道并授权置顶显示。";
            case "capture_battery": return "调整后台运行设置，减少意外中断。";
            case "haptic_rhythm": return "保留原有节奏或使用短促震动。";
            case "haptic_strength": return "选择默认、较轻或较强的震感。";
            case "haptic_preview": return "试听当前节奏与震感。";
            default: return "查看本组各项设置的作用与使用说明。";
        }
    }

    /** Ordered setting keys explained by a group. */
    static List<String> itemKeys(String key) {
        switch (key) {
            case "voice_group":
                return keys("speech_engine", "speech_rate", "test_cue",
                        "haptic_settings_link", "before_start_read", "reminder_guide");
            case "recognition_group":
                return keys("recognition_experiment", "new_avatar");
            case "preset_group":
                return keys("preset_compact", "preset_standard", "preset_detailed",
                        "preset_channels_events");
            case "tuning_group":
                return keys("tuning_volume", "tuning_interval", "tuning_center");
            case "channel_group":
                return keys("channel_visual", "channel_tone", "channel_speech", "channel_haptic");
            case "events_group":
                return keys("event_near", "event_near_haptic", "event_far_appear",
                        "event_new_target", "event_edge", "event_danger", "event_player",
                        "event_system");
            case "assistant_connection":
                return BuildConfig.ASSISTANT_DEFAULT_ENDPOINT.isEmpty()
                        ? keys("assistant_service_address", "assistant_experience_code",
                                "assistant_save_connection")
                        : keys("assistant_service_address", "assistant_experience_code",
                                "assistant_online_service", "assistant_save_connection");
            case "assistant_group":
                return keys("assistant_voice", "assistant_vision", "assistant_proactive",
                        "assistant_overlay");
            case "presentation_group":
                return keys("presentation_spatial", "presentation_haptic", "presentation_two_word");
            case "capture_screen":
                return keys("capture_screen_grant");
            case "capture_notifications":
                return keys("capture_notifications_grant");
            case "capture_overlay":
                return keys("capture_visual", "capture_overlay_grant");
            case "capture_battery":
                return keys("capture_battery_app", "capture_battery_system");
            case "haptic_rhythm":
                return keys("haptic_rhythm_original", "haptic_rhythm_short");
            case "haptic_strength":
                return keys("haptic_strength_system", "haptic_strength_light",
                        "haptic_strength_strong");
            case "haptic_preview":
                return keys("haptic_preview_button");
            default:
                return Collections.emptyList();
        }
    }

    private static List<String> keys(String... keys) {
        return Collections.unmodifiableList(Arrays.asList(keys));
    }

    static String text(String key) {
        switch (key) {
            case "voice_group":
                return "选择语音引擎、调整语速后，可以测试附近敌人提醒当前开启的声音和振动。提醒说明与试听目录会按当前开启的声音、语音和震动设置提供示例；可选择每次开始前自动打开，也能随时手动查看。测试或试听时请先停止游戏辅助或实时对局。";
            case "speech_engine":
                return "选择“跟随手机系统”会使用系统当前的文字转语音引擎，也可以指定手机已安装的引擎。不同引擎的音色和可用语言由手机提供。更换引擎后请试听一次。";
            case "speech_rate":
                return "页面会显示当前语速倍数。这个滑杆只调整听野提醒语音的播放速度，不改变识别速度或提示音；数值越大，语音越快。如果方向词不容易听清，可以适当调慢。";
            case "test_cue":
                return "点击“测试提醒与振动”会按当前附近敌人事件、提示方案和输出通道，发送一次近区提醒示例，并使用当前震感档位与节奏。声音与振动可一起测试；媒体静音或媒体／应用提示音量为 0 时会跳过声音，仍可测试已开启的振动。辅助或实时对局运行时不能测试。请确认是否听到声音或感觉到振动。";
            case "haptic_settings_link":
                return "打开“震感与节奏”后，可以调整所有触觉提醒使用的节奏和震感档位，并试听当前设置。";
            case "before_start_read":
                return "开启后，每次开始新的辅助会话前会打开“提醒说明与试听”目录。进入完整说明后，可以单独听某一段，也可以播放全文、暂停后继续；点击目录中的某一项会打开解释并播放该项示例。开启系统屏幕阅读器时，请使用页面内的播放按钮，以免朗读与示例抢声。关闭此选项后仍可手动打开目录，不影响其他提醒。";
            case "reminder_guide":
                return "目录列出当前开启的声音、语音和振动示例，并显示各项名称；方位语音只保留一个方向示例。每项可以单独反复播放与停止；完整说明有独立入口，可按段收听、播放全文和暂停后继续。示例使用当前设置，辅助运行时请先停止对局辅助再试听。";
            case "recognition_group":
                return "识别配置由应用自动选择，画面位置会按屏幕比例适配；如果设备不支持高清模型，会自动回退到兼容配置。实验开关和新头像开关分别控制实验识别器与新头像提醒。";
            case "recognition_experiment":
                return "此开关允许游戏配置中标记为实验的识别器参与识别。关闭后，未验证配置中的实验识别器不会启用；已验证配置不受此开关限制。不同机型和游戏画面可能影响识别效果。";
            case "new_avatar":
                return "开启后，识别到小地图新出现的敌方头像时可以发出提醒。新头像只说明观察到头像变化，不代表敌人距离近或正在接近。若选择视觉提示，还需要置顶显示权限。";
            case "preset_group":
                return "“精简”“标准”和“详细”是预设，会一起设置多项输出通道和事件开关。“提示通道与事件”可逐项自定义这些设置。选择预设后再单独更改开关，会形成自定义方案；阅读此说明不会应用或重置预设。";
            case "preset_compact":
                return "“精简”会保留附近敌人提醒，用短音提示且不启用近区震动；小地图新头像使用视觉标记。其他提醒仍受对应事件和输出通道设置限制。";
            case "preset_standard":
                return "“标准”会用方位语音和震动提醒附近敌人；小地图新头像使用视觉标记和短音，远处新敌提示音与危险接近默认关闭。其他提醒仍受对应事件和输出通道设置限制。";
            case "preset_detailed":
                return "“详细”会用方位语音和震动提醒附近敌人；小地图新头像可使用视觉、短音、语音和触觉提示，并开启远处新敌提示音与危险接近。各提醒仍受对应事件和输出通道设置限制。";
            case "preset_channels_events":
                return "打开“提示通道与事件”可以分别设置视觉提示、提示音、语音、触觉，以及每类提醒事件。";
            case "tuning_group":
                return "页面滑杆依次为“提示音量”“最短提示间隔”“中央免提示区”。提示音量调整应用提醒声音，手机媒体音量也会影响实际声音。最短提示间隔主要用于画面事件；小地图附近敌人有独立的去重与再次提醒规则。中央免提示区用于画面边缘提示，不改变小地图近区范围。滑杆改动会保存到当前游戏配置。";
            case "tuning_volume":
                return "“提示音量”调整应用提醒声音的音量。手机媒体音量也会影响实际声音。";
            case "tuning_interval":
                return "“最短提示间隔”主要用于限制画面事件提示的频率；小地图附近敌人有独立的去重与再次提醒规则。";
            case "tuning_center":
                return "“中央免提示区”用于画面边缘提示，不改变小地图近区范围。";
            case "channel_group":
                return "输出通道决定提醒可以用什么方式呈现。一个事件还需要自身开启，并且其方案支持该通道，才会实际发出。视觉提示另需系统置顶权限。";
            case "channel_visual":
                return "开启后，支持视觉呈现的提醒可以显示为屏幕上的视觉标记。它需要系统允许听野置顶显示；关闭此通道不会关闭语音、提示音或触觉。";
            case "channel_tone":
                return "开启后，支持提示音的事件可以播放短音。手机媒体音量、应用提示音量和具体事件设置也会影响是否听到声音。";
            case "channel_speech":
                return "开启后，支持语音的事件可以通过手机文字转语音播报。是否成功还取决于已安装的语音引擎及其语言支持；可在“提醒测试与语音”中试听。";
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
            case "assistant_connection":
                return "“画面服务地址（HTTPS）”和“体验连接码”只用于画面理解连接；本地离线语音不需要这两项。填好后点击“保存服务连接”，更改连接后需要重新开始辅助。预置线上地址的安装包会显示“使用听野线上服务”按钮；点击只会填入地址，仍需体验连接码，也不会开启画面理解。请只填写可信服务提供方给出的 HTTPS 地址和连接码。";
            case "assistant_service_address":
                return "画面理解会使用此 HTTPS 地址连接画面服务。本地离线语音不需要服务地址；请只填写可信服务提供方给出的地址。";
            case "assistant_experience_code":
                return "体验连接码用于连接画面服务。请只使用服务提供方交给你的连接码，不要把它公开分享。";
            case "assistant_online_service":
                return "预置线上地址的安装包会显示“使用听野线上服务”。点击后只会填入服务地址；仍需要负责人提供的体验连接码，也不会开启画面理解。";
            case "assistant_save_connection":
                return "点击“保存服务连接”会保存当前服务地址和体验连接码。更改连接后请重新开始辅助；保存连接不会开启画面理解。";
            case "assistant_group":
                return "“语音与画面交互”包括“连续语音（可以插话）”“画面理解”“低频主动描述（需要画面理解）”和“授权左侧小圆点”。实时预警和中文语音识别在手机本地运行，支持 Android 10 及以上；连续语音首次使用需要等待离线模型加载并允许麦克风。请戴耳机并确认游戏声音也在耳机中；游戏开麦或录音路线不明时助手会暂停输入。只有开启画面理解后，问题文字或授权画面才会发送到配置的画面服务；近期画面只缓存在内存。授权画面可能包含游戏聊天或系统通知，请先确认共享范围。低频主动描述需要同时开启画面理解。";
            case "assistant_voice":
                return "开启后，听野会使用手机侧语音识别处理连续语音，首次启动需要等待本地模型加载，并需要麦克风权限。音频在识别过程中临时处理；若同时使用画面理解，问题文字可能随请求发送到配置的画面服务。游戏开麦或录音路线不明确时，输入会暂停。";
            case "assistant_vision":
                return "提问时，当前共享画面和最多两张最近画面的缩图会发送到配置的画面服务及其视觉模型；开启低频主动描述后，也会发送当前画面用于观察。画面可能带有聊天或通知，请先确认共享范围。近期画面只缓存在内存，服务方按其条款处理收到的数据。";
            case "assistant_proactive":
                return "开启后，助手会在低频观察中主动描述画面。此功能需要同时开启画面理解，会把当前共享画面发送到配置的画面服务；不需要时可关闭主动描述或画面理解。";
            case "assistant_overlay":
                return "“授权左侧小圆点”用于申请置顶权限，以显示助手左侧小圆点。没有此权限时，连续语音仍可使用，也可以通过运行通知查看或操作辅助状态。授权系统设置由手机显示。";
            case "presentation_group":
                return "“呈现层实验（默认关闭）”包括“左右双耳线索（需双耳耳机）”“距离强度与节奏震动”和“两字方位提示”。不同手机、耳机和游戏画面会影响体验，开启或更改后请按页面提示重新开始辅助，并自行确认是否清楚。";
            case "presentation_spatial":
                return "开启后会尝试用立体声左右线索呈现方向，需使用支持立体声的双耳耳机。它不等同于完整 HRTF；单声道播放或外放可能听不出左右差异。";
            case "presentation_haptic":
                return "开启后会尝试用震动强度和节奏表达距离层级。此呈现仍需玩家验证，实际强弱和节奏感受受手机马达及系统设置影响，不能据此判断精确距离。";
            case "presentation_two_word":
                return "开启后会尝试用更短的两字方位语音提示，减少播报长度。方向仍按当前提醒规则解释；此呈现需要在实际游戏中确认是否容易听懂。";
            case "capture_screen":
                return "每次开始新的辅助会话，系统都会单独询问是否共享屏幕。请先阅读系统对共享范围的说明，再点击“授权截屏并开始”；运行通知中可以暂停或停止辅助。";
            case "capture_screen_grant":
                return "点击“授权截屏并开始”后，系统会单独询问是否共享屏幕。授权范围由系统弹窗说明；开始前请确认共享内容。";
            case "capture_notifications":
                return "“运行通知”用于显示辅助当前状态，并提供暂停、继续或停止等操作。Android 13 及以上需要单独允许通知；未允许时，部分设备也会阻止辅助正常启动。";
            case "capture_notifications_grant":
                return "点击“授权运行通知”可按系统版本请求通知权限或打开应用通知设置。";
            case "capture_overlay":
                return "“置顶视觉提示”组中的“允许使用视觉提示”控制视觉提示通道；打开后还要点击“授权置顶显示”并允许听野显示在其他应用上方。";
            case "capture_visual":
                return "此项控制视觉提示通道。开启视觉提示并启用小地图新头像标记时，需要置顶显示授权；只使用语音、提示音或触觉时，可以关闭此项。";
            case "capture_overlay_grant":
                return "点击“授权置顶显示”会打开系统置顶权限设置。授权后，听野才可以在其他应用上方显示视觉标记。";
            case "capture_battery":
                return "后台省电策略组提供“设置后台省电策略”和“系统省电优化设置”两个入口。建议允许后台运行；具体选项名称和效果由手机系统与厂商决定。";
            case "capture_battery_app":
                return "“设置后台省电策略”打开听野的应用详情页，可检查电池和后台运行限制。不同品牌的菜单名称不同，按手机系统提供的选项允许后台运行即可。";
            case "capture_battery_system":
                return "“系统省电优化设置”打开系统省电优化设置。允许后台运行可能有帮助，但标准省电状态无法代表所有厂商的后台限制；如仍中断，请查看手机厂商的应用管理设置。";
            case "haptic_rhythm":
                return "“保留原有节奏（默认）”使用各类提醒原有震动节奏；“短促节奏（保留单／双震）”会缩短每次震动和间隔，同时保留原有脉冲数量与顺序。";
            case "haptic_rhythm_original":
                return "保留各类提醒原有的震动节奏，不额外缩短震动和间隔。";
            case "haptic_rhythm_short":
                return "按原有的单震或双震数量与顺序缩短脉冲和间隔。";
            case "haptic_strength":
                return "“设备默认（默认）”使用设备默认振幅或原有脉冲时长；“较轻”和“较强”会按设备能力调整振幅，或缩短、延长脉冲时长。";
            case "haptic_strength_system":
                return "使用设备默认振幅或原有脉冲时长。";
            case "haptic_strength_light":
                return "请求较轻的振幅；不支持可调振幅时缩短脉冲。";
            case "haptic_strength_strong":
                return "请求较强的振幅；不支持可调振幅时延长脉冲。";
            case "haptic_preview":
                return "点击“试听当前触觉设置”会发出一次附近提醒的震动，帮助比较当前节奏和震感。辅助或实时对局运行时无法试听。";
            case "haptic_preview_button":
                return "“试听当前触觉设置”会使用当前节奏与震感档位发送一次触觉试听。实际感觉受设备振动能力和系统设置影响。";
            default:
                return "此说明只介绍当前功能，不会更改任何设置。返回后可以继续使用原来的设置页面。";
        }
    }
}
