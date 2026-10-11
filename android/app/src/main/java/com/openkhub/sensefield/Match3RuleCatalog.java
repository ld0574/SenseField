package com.openkhub.sensefield;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Loaded-once bounded provenance catalog. JSON cannot add executable rules to the native registry. */
final class Match3RuleCatalog {
    private static volatile Match3RuleCatalog loaded;
    final boolean available;
    final String sha256;
    private Match3RuleCatalog(boolean available,String sha) { this.available=available;sha256=sha; }
    static Match3RuleCatalog parse(byte[] bytes) throws Exception {
        if(bytes.length>131072)throw new IllegalArgumentException("Rule catalog size");
        JSONObject json=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(!"match3-rule-catalog-v1".equals(json.getString("format")) || !Match3Rules.CATALOG_ID.equals(json.getString("id"))
                || json.getInt("version")!=Match3Rules.VERSION
                || !json.getJSONObject("source").getString("sha256").matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Rule catalog provenance");
        JSONArray rules=json.getJSONArray("rules");Set<String> ids=new HashSet<>();
        for(int i=0;i<rules.length();i++) {
            JSONObject rule=rules.getJSONObject(i);String id=rule.getString("rule_id");
            if(!Match3Rules.IDS.contains(id) || !ids.add(id) || rule.getInt("version")!=Match3Rules.VERSION
                    || !rule.getString("reference_evidence").matches("documented|confirmed|partial|unverified|legacy_reviewed")
                    || !rule.getString("parameter_completeness").matches("complete|partial|missing")
                    || !rule.getString("engine_status").matches("direct_effect|partial_effect|direct_opportunity|state_only|pending")
                    || !"pending".equals(rule.getString("visual_status")) || !"pending".equals(rule.getString("live_validation")))
                throw new IllegalArgumentException("Unreviewed rule metadata");
            rule.getJSONObject("parameters");rule.getJSONArray("required_evidence");rule.getJSONArray("source_entries");
        }
        if(!ids.equals(Match3Rules.IDS))throw new IllegalArgumentException("Incomplete rule registry");
        StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return new Match3RuleCatalog(true,sha.toString());
    }
    static Match3RuleCatalog get(Context context) {
        if(loaded!=null)return loaded;
        synchronized(Match3RuleCatalog.class) {
            if(loaded==null)try(InputStream input=context.getAssets().open("match3-rules-v1.json")) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
                while((count=input.read(buffer))>0) { if(out.size()+count>131072)throw new IllegalArgumentException("Rule catalog size");out.write(buffer,0,count); }
                loaded=parse(out.toByteArray());
            }catch(Exception invalid) { loaded=new Match3RuleCatalog(false,""); }
            return loaded;
        }
    }
}
