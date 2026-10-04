package com.openkhub.sensefield;

import android.media.AudioDeviceInfo;

/** Continuous-voice trial requires the app-owned route probe and exclusive microphone use. */
final class VoiceInputSafetyPolicy {
    enum InputState {
        READY("ready", "语音输入可用"),
        PAUSED("paused", "助手语音已暂停"),
        CLIENT_SILENCED("client_silenced", "系统已静音麦克风，助手暂停输入"),
        OTHER_RECORDING("other_recording", "存在其他录音会话或录音状态未知，助手暂停输入"),
        ROUTE_UNVERIFIED("route_unverified", "请戴耳机并确认游戏声音也从耳机播放；当前路由不支持连续语音"),
        UNAVAILABLE("unavailable", "语音输入暂不可用，预警继续运行");

        final String auditKey;
        final String status;
        InputState(String auditKey, String status) { this.auditKey = auditKey; this.status = status; }
    }

    static final int FLAG_PAUSED = 1;
    static final int FLAG_CLIENT_SILENCED = 1 << 1;
    static final int FLAG_OTHER_RECORDER = 1 << 2;
    static final int FLAG_UNVERIFIED_ROUTE = 1 << 3;
    static final int FLAG_UNAVAILABLE = 1 << 4;

    static InputState inputState(boolean paused, boolean clientSilenced,
                                 boolean otherRecorder, boolean headphoneRoute) {
        if (paused) return InputState.PAUSED;
        if (clientSilenced) return InputState.CLIENT_SILENCED;
        if (otherRecorder) return InputState.OTHER_RECORDING;
        if (!headphoneRoute) return InputState.ROUTE_UNVERIFIED;
        return InputState.READY;
    }

    static int safetyFlags(boolean paused, boolean clientSilenced,
                           boolean otherRecorder, boolean headphoneRoute) {
        return (paused ? FLAG_PAUSED : 0)
                | (clientSilenced ? FLAG_CLIENT_SILENCED : 0)
                | (otherRecorder ? FLAG_OTHER_RECORDER : 0)
                | (!headphoneRoute ? FLAG_UNVERIFIED_ROUTE : 0);
    }

    /** Every gate transition, including recovery to READY, starts a fresh generation. */
    static boolean requiresSessionReset(int previousFlags, int nextFlags) {
        return nextFlags != previousFlags;
    }

    static boolean verifiedHeadphoneRoute(int sdk, boolean probeOperational, int routedDeviceType) {
        return sdk >= 29 && probeOperational && approvedHeadphoneType(sdk, routedDeviceType);
    }
    static boolean approvedHeadphoneType(int sdk, int type) {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                // A2DP is the ordinary Bluetooth media route. Android does
                // not distinguish A2DP earbuds from A2DP speakers by type, so
                // the separate audio consent must require in-ear headphones.
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || (sdk >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }
    static boolean otherRecorder(int ownSessionId, int[] activeSessionIds) {
        for (int id : activeSessionIds) if (id != ownSessionId) return true;
        return false;
    }
}
