package com.openkhub.sensefield;

/**
 * 三个可互换后端 + 唯一工厂（ADR-0001 D2）。
 * 铁律 2（Java 侧对齐）：endpoint 与 model 字符串只允许出现在本文件内部，
 * 业务代码、CueDispatcher/Harness、UI 中不得散落任何渠道地址。
 */
final class JevClients {
    private JevClients() {
    }

    /** 腾讯 EdgeOne Makers：现网默认渠道（国内、免自管 TypeSafe 账号）。 */
    static final class EdgeOneJevClient extends JevHttpClient {
        static final String ENDPOINT = "https://ai-gateway.edgeone.link/v1/systemone";
        static final String MODEL = "@makers/jev";

        EdgeOneJevClient(String apiKey) {
            super(ENDPOINT, MODEL, apiKey);
        }
    }

    /** OpenRouter 备选渠道（/api/v1/systemone 路由已实测存在）。 */
    static final class OpenRouterJevClient extends JevHttpClient {
        static final String ENDPOINT = "https://openrouter.ai/api/v1/systemone";
        static final String MODEL = "typesafe/jev-1.13";

        OpenRouterJevClient(String apiKey) {
            super(ENDPOINT, MODEL, apiKey);
        }
    }

    /** 本地/自托管兜底（baseUrl 注入；本机由 backend/adapter 的薄适配服务提供）。 */
    static final class LocalJevClient extends JevHttpClient {
        static final String DEFAULT_LOCAL_MODEL = "local-system-one";

        LocalJevClient(String baseUrl, String apiKey) {
            super(stripTrailingSlash(baseUrl) + "/v1/systemone",
                    DEFAULT_LOCAL_MODEL,
                    apiKey == null || apiKey.trim().isEmpty() ? "local" : apiKey);
        }
    }

    static JevClient create(String kind, String apiKey, String localBaseUrl) {
        if ("edgeone".equals(kind)) return new EdgeOneJevClient(apiKey);
        if ("openrouter".equals(kind)) return new OpenRouterJevClient(apiKey);
        if ("local".equals(kind)) {
            if (localBaseUrl == null || localBaseUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("local 渠道需要 baseUrl（判定自测页可配置）");
            }
            return new LocalJevClient(localBaseUrl, apiKey);
        }
        throw new IllegalArgumentException("未知判定渠道: " + kind + "（可选 edgeone | openrouter | local）");
    }

    private static String stripTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
