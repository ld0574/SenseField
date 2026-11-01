package com.openkhub.sensefield;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

/** 判定结果：一次请求内所有答案＋用量＋耗时。 */
final class JevResult {
    final String model;
    final Map<String, JevAnswer> answers;
    final int inputTokens;
    final int outputTokens;
    final long elapsedMs;
    final String bodyText;

    JevResult(String model, Map<String, JevAnswer> answers, int inputTokens, int outputTokens,
              long elapsedMs, String bodyText) {
        this.model = model;
        this.answers = answers;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.elapsedMs = elapsedMs;
        this.bodyText = bodyText;
    }
}

/** JudgmentClient 接口：L2 判断层唯一入口。实现类只允许在 JevClients 中选择。 */
interface JevClient {
    /**
     * 同步判定：一帧 state + 一组原子问题，单次 POST 并行评估。
     * state 可传 String、JSONObject、JSONArray 或 Map。
     * 调用方负责放到工作线程（本方法做网络 IO）。
     */
    JevResult judge(Object state, Map<String, JevQuestion> questions) throws JevException;
}

/** HTTP 版公共实现：请求构造、30s 超时、错误分类、响应解析。 */
abstract class JevHttpClient implements JevClient {
    private static final int TIMEOUT_MS = 30000;

    private final String endpoint;
    private final String model;
    private final String apiKey;

    JevHttpClient(String endpoint, String model, String apiKey) {
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public JevResult judge(Object state, Map<String, JevQuestion> questions) throws JevException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new JevException(JevException.Kind.AUTH_MISSING, 0, "", "缺少 apiKey");
        }
        long started = System.currentTimeMillis();
        String payload;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("state", wrapState(state));
            JSONObject qs = new JSONObject();
            for (Map.Entry<String, JevQuestion> e : questions.entrySet()) {
                qs.put(e.getKey(), e.getValue().toJson());
            }
            body.put("questions", qs);
            payload = body.toString();
        } catch (JSONException e) {
            throw new JevException(JevException.Kind.VALIDATION, 0, "", "请求体构造失败: " + e.getMessage());
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            String text = readAll(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new JevException(JevException.classify(status, text), status, text, null);
            }
            try {
                return parseResult(text, System.currentTimeMillis() - started);
            } catch (JSONException e) {
                // 解析失败也必须保留网关原文（绝不美化改写、绝不静默）
                throw new JevException(JevException.Kind.HTTP, status, text, "响应解析失败: " + e.getMessage());
            }
        } catch (JevException e) {
            throw e;
        } catch (IOException e) {
            throw new JevException(JevException.Kind.NETWORK, 0, "", e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private static Object wrapState(Object state) throws JSONException {
        if (state instanceof String || state instanceof JSONObject || state instanceof JSONArray) {
            return state;
        }
        if (state instanceof Map) {
            return new JSONObject((Map<String, Object>) state);
        }
        return new JSONObject(String.valueOf(state));
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (Scanner scanner = new Scanner(in, "UTF-8")) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private static JevResult parseResult(String text, long elapsedMs) throws JSONException {
        JSONObject root = new JSONObject(text);
        JSONObject answersJson = root.getJSONObject("answers");
        Map<String, JevAnswer> answers = new LinkedHashMap<>();
        java.util.Iterator<String> keys = answersJson.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            answers.put(id, JevAnswer.parse(id, answersJson.getJSONObject(id)));
        }
        JSONObject usage = root.optJSONObject("usage");
        return new JevResult(
                root.optString("model", ""),
                answers,
                usage == null ? 0 : usage.optInt("input_tokens", 0),
                usage == null ? 0 : usage.optInt("output_tokens", 0),
                elapsedMs, text);
    }
}
