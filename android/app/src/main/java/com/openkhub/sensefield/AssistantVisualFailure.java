package com.openkhub.sensefield;

/** Distinguish server model access from phone-to-gateway authentication, including old gateways. */
final class AssistantVisualFailure {
    private AssistantVisualFailure() { }

    static String code(int httpStatus, String source, int upstreamStatus, String fallback) {
        if ("provider".equals(source)) {
            if (upstreamStatus == 401) return "vision_provider_auth_failed";
            if (upstreamStatus == 403) return "vision_provider_access_denied";
        }
        if (httpStatus == 401) return "gateway_auth_failed";
        if (httpStatus == 403) return "gateway_access_denied";
        if (httpStatus == 504) return "timeout";
        return fallback;
    }

    static boolean requiresServiceRepair(String code) {
        return "vision_provider_auth_failed".equals(code)
                || "vision_provider_access_denied".equals(code)
                || "gateway_auth_failed".equals(code) || "gateway_access_denied".equals(code);
    }

    static String message(String code) {
        if ("vision_provider_auth_failed".equals(code))
            return "画面模型服务拒绝了访问，请检查服务端账户余额和接入配置；本地预警继续运行。";
        if ("vision_provider_access_denied".equals(code))
            return "画面模型服务拒绝了访问，请检查服务端账户与模型权限；本地预警继续运行。";
        if ("gateway_auth_failed".equals(code) || "gateway_access_denied".equals(code))
            return "画面服务拒绝了连接，请检查助手的服务设置；本地预警正常。";
        if ("timeout".equals(code) || "vision_timeout".equals(code)) return "画面读取超时，请重试。";
        if ("provider_busy".equals(code) || "1305".equals(code))
            return "画面模型繁忙，请稍后重试；本地预警正常。";
        if ("public_quota_exceeded".equals(code))
            return "体验额度已用完或请求过快，请稍后再试；本地预警继续。";
        if ("rate_limited".equals(code)) return "画面服务繁忙，请稍后重试。";
        return "画面理解暂时不可用，请稍后再试。";
    }
}
