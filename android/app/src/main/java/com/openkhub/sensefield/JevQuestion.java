package com.openkhub.sensefield;

import org.json.JSONException;
import org.json.JSONObject;

/** 一条判定问题的不可变描述（SystemOne 协议：choice / score / noul）。 */
final class JevQuestion {
    static final String TYPE_CHOICE = "choice";
    static final String TYPE_SCORE = "score";
    static final String TYPE_NOUL = "noul";

    final String id;
    final String type;
    final String instructions;
    /** choice: Map&lt;String,String&gt;（选项名→含义）；score: String[]（有序档位）；noul: 可空 Map。 */
    final Object criteria;

    JevQuestion(String id, String type, String instructions, Object criteria) {
        this.id = java.util.Objects.requireNonNull(id);
        this.type = java.util.Objects.requireNonNull(type);
        this.instructions = java.util.Objects.requireNonNull(instructions);
        this.criteria = criteria;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("instructions", instructions);
        if (criteria != null) {
            o.put("criteria", JSONObject.wrap(criteria));
        }
        return o;
    }
}
