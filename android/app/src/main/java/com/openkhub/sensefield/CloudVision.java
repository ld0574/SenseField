package com.openkhub.sensefield;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 云端视觉识别兜底（阶段三·修复 3）：颜色采样不稳时，把棋盘裁剪图交云端
 * 多模态模型（OpenRouter：GLM-4.5V / Qwen3-VL 等）读取棋盘矩阵。
 * 默认模型 z-ai/glm-4.5v，可用 setModel 更换。Key 复用判定层 OpenRouter Key。
 */
final class CloudVision {
    private static final String TAG = "CloudVision";
    private static volatile String model = "z-ai/glm-4.5v";

    private CloudVision() {
    }

    static void setModel(String m) {
        if (m != null && !m.trim().isEmpty()) model = m.trim();
    }

    /** 把棋盘矩阵读成颜色字母矩阵；失败返回 null。必须在后台线程调用。
     *  真机对拍（REAL_VIDEO_FINDINGS.md）：本地采样在真实截图上 49/49 全对，
     *  云端只作为本地不确定时的兜底——因此行列数强制锁定为采样给出的 rows×cols，
     *  不许模型自改（自改行列会让覆盖结果整盘错位）；失败后冷却 60s 防限速连环打。 */
    private static volatile long cooldownUntil = 0;

    /** 采集线程调用：把棋盘裁剪图编成 base64 JPEG。像素工作必须留在持帧线程——
     *  上一帧会被 onImageAvailable 回收，跨线程用 Bitmap 编码等于拿被回收的像素，
     *  子线程未捕获异常直接崩整个 App。网络线程只收这串字节。 */
    static String encodeForUpload(Bitmap boardCrop) {
        return toBase64Jpeg(scaleForUpload(boardCrop));
    }

    static char[][] readBoard(String imageB64, String apiKey, String model, int rows, int cols) {
        if (imageB64 == null || android.os.SystemClock.elapsedRealtime() < cooldownUntil) return null;
        try {
            String prompt = "这是三消游戏棋盘截图。输出 JSON：{\"rows\":" + rows + ",\"cols\":" + cols
                    + ",\"grid\":[[...]]}，grid 必须是恰好 " + rows + " 行 " + cols
                    + " 列的二维数组，每个元素是棋子颜色名，限定：红狐狸/小鸡/青蛙/河马/棕熊/紫猫/未知。"
                    + "按从上到下、从左到右顺序。只输出 JSON，禁止任何其他文字。";
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray messages = new JSONArray();
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            JSONArray content = new JSONArray();
            JSONObject imgUrl = new JSONObject();
            imgUrl.put("type", "image_url");
            imgUrl.put("image_url", new JSONObject().put("url",
                    "data:image/jpeg;base64," + imageB64));
            content.put(imgUrl);
            content.put(new JSONObject().put("type", "text").put("text", prompt));
            msg.put("content", content);
            messages.put(msg);
            body.put("messages", messages);

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://openrouter.ai/api/v1/chat/completions").openConnection();
            conn.setRequestMethod("POST");
            /* 预算 4s 连／12s 读：兜底失败的代价是「这一盘没上云端」，
             * 而 45 秒读超时在采集线程上曾是「全程静默」的元凶（bugreport 2026-10-07）。
             * 现在虽然挪到了独立线程，仍要给请求本身一个上界，免得在途标记被长期占住。 */
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String text = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code < 200 || code >= 300) {
                Log.w(TAG, "HTTP " + code + ": " + safe(text));
                cooldownUntil = android.os.SystemClock.elapsedRealtime() + 60_000L;
                return null;
            }
            String reply = new JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            char[][] matrix = parseMatrix(reply);
            Log.i(TAG, "云端识别 " + (matrix == null ? "解析失败" : matrix.length + "x" + matrix[0].length));
            if (matrix == null) {
                cooldownUntil = android.os.SystemClock.elapsedRealtime() + 60_000L;
            } else {
                cooldownUntil = 0;
            }
            return matrix;
        } catch (Exception e) {
            Log.w(TAG, "云端识别失败: " + e.getMessage());
            cooldownUntil = android.os.SystemClock.elapsedRealtime() + 60_000L;
            return null;
        }
    }

    /** 自托管模型服务（腾讯云）：POST <base>/read_board，body {image:b64jpeg, rows, cols}，
     *  回包 {rows, cols, grid:[[颜色名...]]}（颜色名与 OpenRouter 路径同一套，走 letterOf）。
     *  自家服务器无配额无限速，配置了 match3_cloud_url 时优先走它；失败返回 null 回退。 */
    static char[][] readBoardFromServer(String imageB64, String baseUrl, int rows, int cols) {
        if (imageB64 == null || baseUrl == null || baseUrl.trim().isEmpty()) return null;
        try {
            String base = baseUrl.trim();
            JSONObject body = new JSONObject();
            body.put("image", imageB64);
            body.put("rows", rows);
            body.put("cols", cols);
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    base + (base.endsWith("/") ? "" : "/") + "read_board").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String text = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code < 200 || code >= 300) {
                Log.w(TAG, "自托管识别 HTTP " + code + ": " + safe(text));
                return null;
            }
            JSONObject o = new JSONObject(text);
            JSONArray grid = o.optJSONArray("grid");
            int rr = o.optInt("rows", 0), cc = o.optInt("cols", 0);
            if (grid == null || rr != rows || cc != cols) {
                Log.w(TAG, "自托管回包行列不符: " + rr + "x" + cc);
                return null;
            }
            char[][] out = new char[rr][cc];
            for (int r = 0; r < rr; r++) {
                JSONArray row = grid.optJSONArray(r);
                for (int c = 0; c < cc; c++) {
                    out[r][c] = row == null ? '.' : letterOf(row.optString(c, "未知"));
                }
            }
            Log.i(TAG, "自托管识别 " + rr + "x" + cc + " 成功");
            return out;
        } catch (Exception e) {
            Log.w(TAG, "自托管识别失败: " + e.getMessage());
            return null;
        }
    }

    /** 从模型回复中提取棋盘矩阵（容忍 ```json 围栏与行列数自适应）。 */
    static char[][] parseMatrix(String content) {
        try {
            String json = content;
            int s = json.indexOf('{');
            int e = json.lastIndexOf('}');
            if (s < 0 || e <= s) return null;
            json = json.substring(s, e + 1);
            JSONObject o = new JSONObject(json);
            int rows = o.optInt("rows", 0);
            int cols = o.optInt("cols", 0);
            JSONArray grid = o.optJSONArray("grid");
            if (grid == null || rows <= 0 || cols <= 0 || rows > 12 || cols > 12) return null;
            char[][] out = new char[rows][cols];
            for (int r = 0; r < rows; r++) {
                JSONArray row = grid.optJSONArray(r);
                for (int c = 0; c < cols; c++) {
                    String name = row == null ? "未知" : row.optString(c, "未知");
                    out[r][c] = letterOf(name);
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 棋子颜色名 → 矩阵字母。 */
    static char letterOf(String name) {
        if (name == null) return '.';
        if (name.contains("狐") || name.contains("红")) return 'R';
        if (name.contains("鸡") || name.contains("黄")) return 'Y';
        if (name.contains("蛙") || name.contains("绿")) return 'G';
        if (name.contains("马") || name.contains("蓝")) return 'B';
        if (name.contains("熊") || name.contains("棕")) return 'O';
        if (name.contains("紫") || name.contains("猫")) return 'P';
        return '.';
    }

    private static Bitmap scaleForUpload(Bitmap src) {
        int max = 896;
        if (src.getWidth() <= max && src.getHeight() <= max) return src;
        float scale = Math.min((float) max / src.getWidth(), (float) max / src.getHeight());
        return Bitmap.createScaledBitmap(src,
                Math.round(src.getWidth() * scale), Math.round(src.getHeight() * scale), true);
    }

    private static String toBase64Jpeg(Bitmap bitmap) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bos);
        return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static String readStream(InputStream in) {
        if (in == null) return "";
        try (Scanner scanner = new Scanner(in, "UTF-8")) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.substring(0, Math.min(200, s.length()));
    }
}
