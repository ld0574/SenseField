package com.openkhub.sensefield;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** 单条类型化判定答案（SystemOne 响应契约，见 specs/jev-api-spec.md §5）。 */
final class JevAnswer {
    final String id;
    final String type;
    /** choice 型：选中的选项名。 */
    final String choice;
    /** score 型：概率加权分值，可落在两档之间。 */
    final double score;
    /** noul 型：为 yes 的概率 0..1（该类型没有 confidence，把握度用 |2p−1|）。 */
    final double noul;
    /** choice/score 型：完整概率分布（和为 1）。 */
    final Map<String, Double> probabilities;
    /** choice/score 型：0..1；noul 型为 null。 */
    final Double confidence;

    JevAnswer(String id, String type, String choice, double score, double noul,
              Map<String, Double> probabilities, Double confidence) {
        this.id = id;
        this.type = type;
        this.choice = choice;
        this.score = score;
        this.noul = noul;
        this.probabilities = probabilities;
        this.confidence = confidence;
    }

    /** 把握度：choice/score 用 confidence，noul 用 |2p−1|。 */
    double surety() {
        if (JevQuestion.TYPE_NOUL.equals(type)) {
            return Math.abs(2 * noul - 1);
        }
        return confidence == null ? 0d : confidence;
    }

    static JevAnswer parse(String id, JSONObject o) throws JSONException {
        String type = o.getString("type");
        switch (type) {
            case JevQuestion.TYPE_CHOICE: {
                String choice = o.getString("choice");
                Map<String, Double> probs = readProbabilities(o);
                Double conf = o.has("confidence") ? o.getDouble("confidence") : null;
                return new JevAnswer(id, type, choice, 0d, 0d, probs, conf);
            }
            case JevQuestion.TYPE_SCORE: {
                double score = o.getDouble("score");
                Map<String, Double> probs = readProbabilities(o);
                Double conf = o.has("confidence") ? o.getDouble("confidence") : null;
                return new JevAnswer(id, type, null, score, 0d, probs, conf);
            }
            case JevQuestion.TYPE_NOUL: {
                double noul = o.getDouble("noul");
                return new JevAnswer(id, type, null, 0d, noul, null, null);
            }
            default:
                throw new JSONException("未知答案类型: " + type + "（" + id + "）");
        }
    }

    private static Map<String, Double> readProbabilities(JSONObject o) throws JSONException {
        if (!o.has("probabilities")) {
            throw new JSONException("答案缺 probabilities");
        }
        JSONObject p = o.getJSONObject("probabilities");
        Map<String, Double> out = new HashMap<>();
        Iterator<String> keys = p.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            out.put(k, p.getDouble(k));
        }
        return out;
    }
}
