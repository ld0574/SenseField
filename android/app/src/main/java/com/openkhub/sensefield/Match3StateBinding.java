package com.openkhub.sensefield;

import org.json.JSONObject;

/** Explicit reviewed template state; absent fields remain unknown. Never infer charge or topology from a name. */
final class Match3StateBinding {
    static Match3Position.Cell parse(JSONObject binding) throws Exception {
        if(!Boolean.TRUE.equals(binding.opt("reviewed")) || binding.getString("evidence").trim().isEmpty()
                || binding.getString("evidence").length()>160 || integer(binding,"rule_version",-1)!=Match3Rules.VERSION)
            throw new IllegalArgumentException("Unreviewed state binding");
        JSONObject state=binding.getJSONObject("state");
        if(state.has("object_id") || state.has("topology"))throw new IllegalArgumentException("Frame-specific evidence in a family");
        Match3Position.Kind kind=Match3Position.Kind.valueOf(state.getString("kind"));
        String color=state.optString("color","");Match3Position.Cell cell;
        if(kind==Match3Position.Kind.ANIMAL) {
            if(color.length()!=1 || Match3Goals.Kind.animal(color.charAt(0))==Match3Goals.Kind.UNKNOWN)
                throw new IllegalArgumentException("Unconfirmed animal color");
            int layers=integer(state,"layers",-1);if(layers!=0 && layers!=-1)throw new IllegalArgumentException("Animal layers");
            cell=Match3Position.Cell.animal(color.charAt(0)).withLayers(layers)
                    .withEffect(Match3Position.Effect.valueOf(state.optString("effect","UNKNOWN")));
        } else {
            if(!color.isEmpty() || !"NONE".equals(state.optString("effect","NONE")))throw new IllegalArgumentException("Nonanimal effect/color");
            cell=Match3Position.Cell.observed(kind,Match3Rules.ruleForKind(kind),integer(state,"layers",-1),-1);
        }
        if(!cell.ruleId.equals(binding.getString("rule_id")))throw new IllegalArgumentException("Identity/rule mismatch");
        int ice=integer(state,"floor_ice",-1);
        if(ice>1 || ice>0 && !"stationary_single_layer_ice".equals(state.optString("floor_rule_id")))
            throw new IllegalArgumentException("Unreviewed floor variant");
        Match3Position.Cover cover=Match3Position.Cover.valueOf(state.optString("cover",kind==Match3Position.Kind.ANIMAL?"UNKNOWN":"NONE"));
        String gateRule=state.optString("gate_rule_id","");
        if(!gateRule.isEmpty() && !"stone_gate".equals(gateRule))throw new IllegalArgumentException("Unknown gate rule");
        return cell.withIce(ice).withCover(cover,integer(state,"cover_layers",cover==Match3Position.Cover.NONE?0:-1))
                .withAccess(Match3Position.Gate.valueOf(state.optString("gate","UNKNOWN")),"stone_gate".equals(state.optString("gate_rule_id","")))
                .withEnergy(integer(state,"charge",-1),integer(state,"capacity",-1)).withDirection(integer(state,"direction",-1));
    }
    private static int integer(JSONObject value,String key,int missing) throws Exception {
        if(!value.has(key))return missing;Object raw=value.get(key);
        if(!(raw instanceof Integer) && !(raw instanceof Long))throw new IllegalArgumentException("Noninteger state "+key);
        long number=((Number)raw).longValue();if(number<Integer.MIN_VALUE || number>Integer.MAX_VALUE)
            throw new IllegalArgumentException("State integer range");return (int)number;
    }
    private Match3StateBinding() { }
}
