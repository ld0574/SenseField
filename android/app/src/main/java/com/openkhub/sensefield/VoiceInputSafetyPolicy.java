package com.openkhub.sensefield;

import android.media.AudioDeviceInfo;

/** Continuous-voice trial requires the app-owned route probe and exclusive microphone use. */
final class VoiceInputSafetyPolicy {
    static boolean verifiedHeadphoneRoute(int sdk, boolean probeOperational, int routedDeviceType) {
        return sdk >= 29 && probeOperational && approvedHeadphoneType(sdk, routedDeviceType);
    }
    static boolean approvedHeadphoneType(int sdk, int type) {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || (sdk >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }
    static boolean otherRecorder(int ownSessionId, int[] activeSessionIds) {
        for (int id : activeSessionIds) if (id != ownSessionId) return true;
        return false;
    }
}
