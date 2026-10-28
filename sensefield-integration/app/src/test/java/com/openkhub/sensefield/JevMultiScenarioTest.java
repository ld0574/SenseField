package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * P1 多场景测试矩阵（纯 JVM，不依赖真网）：
 * 用极简 ServerSocket 桩服务器伪造判定渠道，覆盖契约解析、错误分类、中文 state、阈值三档与白名单。
 * 注：Android 单元测试 classpath 无 com.sun.net.httpserver，故手写桩。
 * 真网端到端（OpenRouter / EdgeOne / 本地适配服务）由判定自测台在真机/模拟器上执行。
 */
public class JevMultiScenarioTest {

    /* ---------- 极简 HTTP 桩服务器 ---------- */

    private static final class StubServer implements AutoCloseable {
        final ServerSocket socket;
        volatile String cannedBody = "";
        volatile int cannedStatus = 200;
        volatile String lastRequest = "";
        private Thread worker;

        StubServer() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        }

        int port() {
            return socket.getLocalPort();
        }

        void start() {
            worker = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket s = socket.accept()) {
                        s.setSoTimeout(5000);
                        lastRequest = readFully(s.getInputStream());
                        byte[] body = cannedBody.getBytes(StandardCharsets.UTF_8);
                        OutputStream out = s.getOutputStream();
                        out.write(("HTTP/1.1 " + cannedStatus + " STUB\r\n"
                                + "Content-Type: application/json\r\n"
                                + "Content-Length: " + body.length + "\r\n"
                                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(body);
                        out.flush();
                    } catch (IOException e) {
                        return; // socket closed
                    }
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        /** 读完整请求：按头里的 Content-Length 精确读 body。 */
        private static String readFully(InputStream in) throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int contentLength = 0;
            while (true) {
                int b = in.read();
                if (b < 0) return header.toString("UTF-8");
                header.write(b);
                if (b == '\n' && header.toString("UTF-8").toLowerCase().contains("content-length:")) {
                    String line = header.toString("UTF-8");
                    int idx = line.lastIndexOf("Content-Length:");
                    if (idx < 0) idx = line.lastIndexOf("content-length:");
                    if (idx >= 0) {
                        try {
                            contentLength = Integer.parseInt(line.substring(idx + 15).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                // 头结束标记：连续 CRLF CRLF
                String h = header.toString("UTF-8");
                if (h.endsWith("\r\n\r\n")) break;
                if (h.length() > 65536) break;
            }
            byte[] body = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(body, off, contentLength - off);
                if (n < 0) break;
                off += n;
            }
            return header + "\n" + new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /* ---------- 场景工具 ---------- */

    private static Map<String, JevQuestion> threeQuestions() {
        Map<String, JevQuestion> q = new HashMap<>();
        q.put("next_action", new JevQuestion("next_action", JevQuestion.TYPE_CHOICE,
                "What next?", mapOf("announce_match", "Report a match", "wait", "Nothing changed")));
        q.put("danger_level", new JevQuestion("danger_level", JevQuestion.TYPE_SCORE,
                "How dangerous?", new String[]{"Safe", "Caution", "Danger"}));
        q.put("should_interrupt", new JevQuestion("should_interrupt", JevQuestion.TYPE_NOUL,
                "Speak now?", mapOf("true", "Speak now", "false", "Can wait")));
        return q;
    }

    private static Map<String, String> mapOf(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String happyBody() {
        return "{\"model\":\"test-jev\",\"answers\":{"
                + "\"next_action\":{\"type\":\"choice\",\"choice\":\"announce_match\","
                + "\"probabilities\":{\"announce_match\":0.91,\"wait\":0.09},\"confidence\":0.82},"
                + "\"danger_level\":{\"type\":\"score\",\"score\":0.24,"
                + "\"legend\":{\"0\":\"Safe\",\"1\":\"Caution\",\"2\":\"Danger\"},"
                + "\"probabilities\":{\"0\":0.77,\"1\":0.23,\"2\":0},\"confidence\":0.76},"
                + "\"should_interrupt\":{\"type\":\"noul\",\"noul\":0.17}},"
                + "\"usage\":{\"input_tokens\":474,\"output_tokens\":74}}";
    }

    private JevClient clientAt(String url) {
        return new JevClients.LocalJevClient(url, "test-key");
    }

    /* ---------- 场景 1：契约解析（三型答案 + usage） ---------- */

    @Test
    public void happyPathParsesAllThreeTypes() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.cannedStatus = 200;
            stub.cannedBody = happyBody();
            stub.start();
            JevResult r = clientAt("http://127.0.0.1:" + stub.port()).judge(threeQuestions(), threeQuestions());
            assertEquals("test-jev", r.model);
            assertEquals(474, r.inputTokens);
            assertEquals(74, r.outputTokens);
            assertEquals(3, r.answers.size());

            JevAnswer choice = r.answers.get("next_action");
            assertEquals(JevQuestion.TYPE_CHOICE, choice.type);
            assertEquals("announce_match", choice.choice);
            assertNotNull(choice.confidence);
            assertTrue(choice.confidence >= 0 && choice.confidence <= 1);
            double sum = choice.probabilities.values().stream().mapToDouble(Double::doubleValue).sum();
            assertTrue("probabilities 应归一，实际和=" + sum, Math.abs(sum - 1) < 0.02);

            JevAnswer score = r.answers.get("danger_level");
            assertEquals(JevQuestion.TYPE_SCORE, score.type);
            assertTrue(score.score >= 0 && score.score <= 2);
            assertEquals(0.76, score.confidence, 1e-9);

            JevAnswer noul = r.answers.get("should_interrupt");
            assertEquals(JevQuestion.TYPE_NOUL, noul.type);
            assertTrue(noul.noul >= 0 && noul.noul <= 1);
            assertEquals(null, noul.confidence);
            assertEquals(Math.abs(2 * 0.17 - 1), noul.surety(), 1e-9);
        }
    }

    /* ---------- 场景 2：错误分类矩阵 ---------- */

    @Test
    public void errorMatrixClassifiesAllKinds() {
        String[][] cases = {
                {"{\"error\":{\"message\":\"Missing API key\",\"code\":\"auth_missing\"}}", String.valueOf(401), "AUTH_MISSING"},
                {"{\"error\":{\"message\":\"bad key\",\"code\":\"auth_failed\"}}", String.valueOf(401), "AUTH_FAILED"},
                {"{\"error\":{\"message\":\"No cookie auth credentials found\",\"code\":401}}", String.valueOf(401), "AUTH"},
                {"{\"error\":{\"message\":\"expected record\",\"code\":400}}", String.valueOf(400), "VALIDATION"},
                {"{\"error\":{\"message\":\"criteria required\",\"code\":\"validation\"}}", String.valueOf(422), "VALIDATION"},
                {"{\"error\":{\"message\":\"rate limited\",\"code\":\"rate\"}}", String.valueOf(429), "RATE"},
                {"{\"error\":{\"message\":\"overloaded\",\"code\":\"overload\"}}", String.valueOf(529), "OVERLOAD"},
                {"{\"error\":{\"message\":\"boom\",\"code\":\"boom\"}}", String.valueOf(500), "HTTP"},
                {"NOT-JSON-AT-ALL", String.valueOf(200), "HTTP"},
        };
        for (String[] c : cases) {
            try (StubServer stub = new StubServer()) {
                stub.cannedStatus = Integer.parseInt(c[1]);
                stub.cannedBody = c[0];
                stub.start();
                clientAt("http://127.0.0.1:" + stub.port()).judge(threeQuestions(), threeQuestions());
                fail("期望抛出 " + c[2] + "，但请求成功了");
            } catch (JevException e) {
                assertEquals("状态码 " + c[1] + " 的 kind 分类错误", c[2], e.kind.name());
                assertTrue("错误必须保留网关原文", e.bodyText != null && !e.bodyText.isEmpty());
            } catch (Exception e) {
                fail("期望 JevException，实际 " + e);
            }
        }
    }

    /* ---------- 场景 3：网络失败（连接拒绝）→ NETWORK ---------- */

    @Test
    public void connectionRefusedIsNetworkKind() throws IOException {
        int closedPort;
        try (StubServer tmp = new StubServer()) {
            closedPort = tmp.port();
        }
        try {
            clientAt("http://127.0.0.1:" + closedPort).judge(threeQuestions(), threeQuestions());
            fail("期望 NETWORK 异常");
        } catch (JevException e) {
            assertEquals(JevException.Kind.NETWORK, e.kind);
        } catch (Exception e) {
            fail("期望 JevException，实际 " + e);
        }
    }

    /* ---------- 场景 4：中文 state 原样透传（无障碍树场景） ---------- */

    @Test
    public void chineseStateIsTransmittedVerbatim() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.cannedStatus = 200;
            stub.cannedBody = happyBody();
            stub.start();
            JSONObject axTree = new JSONObject()
                    .put("package", "com.example.match3game")
                    .put("nodes", new JSONArray()
                            .put(new JSONObject().put("id", "btn_start").put("text", "开始游戏").put("clickable", true))
                            .put(new JSONObject().put("id", "lbl_level").put("text", "第 12 关").put("clickable", false)));
            clientAt("http://127.0.0.1:" + stub.port()).judge(axTree, threeQuestions());
            String sent = stub.lastRequest;
            assertTrue("中文节点文本必须原样出现在请求体中", sent.contains("开始游戏"));
            assertTrue("中文关卡文本必须原样出现在请求体中", sent.contains("第 12 关"));
            assertTrue("请求体必须是 SystemOne 形状", sent.contains("\"questions\"") && sent.contains("\"state\""));
        }
    }

    /* ---------- 场景 5：阈值三档 + 白名单 + 绝不静默 ---------- */

    @Test
    public void thresholdTiersAndWhitelistAndNeverSilent() {
        JevThresholds t = JevThresholds.DEFAULT;

        assertEquals(JevThresholds.Decision.AUTO_EXECUTE, t.decide(answer(JevQuestion.TYPE_CHOICE, 0.95)));
        assertEquals(JevThresholds.Decision.CONFIRM, t.decide(answer(JevQuestion.TYPE_CHOICE, 0.70)));
        assertEquals(JevThresholds.Decision.UNCERTAIN, t.decide(answer(JevQuestion.TYPE_CHOICE, 0.30)));

        // noul 用 |2p−1|：p=0.97 → 把握度 0.94 → 自动档；p=0.55 → 0.10 → 没看清
        assertEquals(JevThresholds.Decision.AUTO_EXECUTE, t.decide(noul(0.97)));
        assertEquals(JevThresholds.Decision.UNCERTAIN, t.decide(noul(0.55)));

        // 白名单：Jev 说该按什么不算数，白名单说了算
        JevAnswer inList = new JevAnswer("next_action", JevQuestion.TYPE_CHOICE, "announce_match",
                0d, 0d, null, 0.99d);
        JevAnswer outList = new JevAnswer("next_action", JevQuestion.TYPE_CHOICE, "press_start",
                0d, 0d, null, 0.99d);
        assertTrue(JevThresholds.inWhitelist(inList, JevThresholds.defaultWhitelist()));
        assertTrue(!JevThresholds.inWhitelist(outList, JevThresholds.defaultWhitelist()));

        // 绝不静默：低置信的播报文案必须是「没看清」
        assertEquals(JevThresholds.UNCERTAIN_SPEECH, JevThresholds.speechFor(JevThresholds.Decision.UNCERTAIN, null));
        assertTrue(JevThresholds.UNCERTAIN_SPEECH.contains("没看清"));
    }

    private static JevAnswer answer(String type, double confidence) {
        return new JevAnswer("q", type, "announce_match", 0d, 0d, null, confidence);
    }

    private static JevAnswer noul(double p) {
        return new JevAnswer("q", JevQuestion.TYPE_NOUL, null, 0d, p, null, null);
    }
}
