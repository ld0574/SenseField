package com.openkhub.sensefield;

/** 判定层统一错误。kind 分类与 backend/src/protocol.js 对齐。 */
final class JevException extends Exception {
    enum Kind {
        NETWORK,        // 断网 / 超时 / 连接失败 → Harness 应走本地兜底或出声提示
        AUTH_MISSING,   // 401 + auth_missing（未带 key）
        AUTH_FAILED,    // 401 + auth_failed（key 错误）
        AUTH,           // 其他 401（渠道自有文案）
        VALIDATION,     // 400 / 422 请求体不合规
        RATE,           // 429 限流 → 指数退避
        OVERLOAD,       // 529 过载 → 指数退避
        HTTP            // 其他非 2xx
    }

    final Kind kind;
    final int status;
    /** 网关原文，永远原样保留，不许美化改写。 */
    final String bodyText;

    JevException(Kind kind, int status, String bodyText, String detail) {
        super("[" + kind + "] HTTP " + status + (detail == null ? "" : " " + detail));
        this.kind = kind;
        this.status = status;
        this.bodyText = bodyText == null ? "" : bodyText;
    }

    static Kind classify(int status, String bodyText) {
        String code = null;
        try {
            org.json.JSONObject err = new org.json.JSONObject(bodyText).optJSONObject("error");
            code = err == null ? null : err.optString("code", null);
        } catch (Exception ignored) {
            // 非 JSON 原文也照常分类
        }
        if (status == 401) {
            if ("auth_missing".equals(code)) return Kind.AUTH_MISSING;
            if ("auth_failed".equals(code)) return Kind.AUTH_FAILED;
            return Kind.AUTH;
        }
        if (status == 400 || status == 422) return Kind.VALIDATION;
        if (status == 429) return Kind.RATE;
        if (status == 529) return Kind.OVERLOAD;
        return Kind.HTTP;
    }
}
