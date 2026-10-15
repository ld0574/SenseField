package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small fixed UI templates, loaded once. No network, learned weights or user-template semantics. */
final class Match3VisualCatalog {
    static final int PATCH = 16;
    /** The obstacle admission line, shared by the label path and the diagnostic readout. */
    static final float ADMISSION_MAXIMUM = .12f;
    static final float ADMISSION_MARGIN = .025f;
    /** Upper bound on the reviewed cover supplement, raised 2026-10-12 by user ruling so a
     *  second reviewed rainbow-bird pose fits beside the first one. */
    static final int COVER_CATALOG_MAXIMUM_BYTES = 98304;
    static final class CellCache {
        int[] patch;
        int[] envelope;
        Match3Position.Cell result, animal;
        Match3Position.Cell inferred;
        Match3AnimalAppearance.Face face;
        Match3AnimalAppearance.Body body;
        boolean animalChecked;
        boolean inferredChecked;
        long inferredRevision;
        boolean iceChecked;
        int iceLayers;
        Match3UnknownElements.Observation reviewObservation;
        final Admission admission=new Admission();
    }
    /**
     * Read-only side channel of the admission line: the winner it chose and the two distances
     * that decided it, with the same lower-bound truncation the judgment itself applies.
     * A contender cut before scoring is only known to be worse than maximum+margin.
     */
    static final class Admission {
        String template;
        float error = Float.POSITIVE_INFINITY;
        float otherKind = Float.POSITIVE_INFINITY;
        void reset() { template = null; error = Float.POSITIVE_INFINITY; otherKind = Float.POSITIVE_INFINITY; }
        /** Non-finite numbers have no JSON representation, so an unseen reading is written as -1. */
        static float distance(float value) {
            return Float.isFinite(value) ? Math.round(value * 10000f) / 10000f : -1f;
        }
    }
    static final class Pattern {
        final String kind;
        final String rule;
        final Match3Position.Cell boundState;
        final Match3Goals.Mode goalMode;
        final String goalModeEvidence;
        final float aspect;
        final boolean neutralBackground;
        final int[] pixels;
        final boolean[] mask;
        final int supported;
        final String maskKey;
        final boolean[] coverEvidence;
        final boolean tileEnvelope;
        final int positiveWeight;
        private final int[] registeredCoverSupport=new int[9];
        final int registrationPixels;
        private final Match3PatchSums registrationSums;
        final int[] channelTotals=new int[3];
        final int[] blockTotals=new int[48];
        final Match3AnimalAppearance.Face identityShape;
        final Match3AnimalAppearance.Face goalShape;
        Pattern(JSONObject json) throws Exception {
            kind = json.getString("kind");
            rule = json.optString("rule", "unverified");
            boundState=json.has("rule_binding")?Match3StateBinding.parse(json.getJSONObject("rule_binding")):null;
            if(boundState!=null && (!kind.matches("bound_[0-9a-f]{20}") || !"reviewed_state".equals(rule)))
                throw new IllegalArgumentException("State binding in an unbound template");
            if(json.has("goal_binding")) {
                JSONObject binding=json.getJSONObject("goal_binding");
                goalMode=Match3Goals.Mode.valueOf(binding.getString("event_mode"));goalModeEvidence=binding.getString("evidence");
                if(!json.optBoolean("reviewed",false) || !"reviewed_goal_icon".equals(rule)
                        || !binding.getBoolean("reviewed") || goalModeEvidence.trim().isEmpty() || goalModeEvidence.length()>160
                        || !Match3Goals.supportsMode(Match3Goals.Kind.valueOf(kind),goalMode))
                    throw new IllegalArgumentException("Unreviewed or conflicting goal mode");
            } else { goalMode=null;goalModeEvidence=""; }
            if(json.has("rule_version") && json.getInt("rule_version")!=Match3Rules.VERSION)
                throw new IllegalArgumentException("Unsupported template rule version");
            aspect=(float)json.optDouble("aspect",0);
            neutralBackground=json.optBoolean("neutral_background",false);
            pixels = readPixels(json); mask = new boolean[pixels.length];
            JSONArray m = json.optJSONArray("mask"); String bits=json.optString("mask_bits", "");
            if(m!=null?m.length()!=pixels.length:!bits.matches("[01]{256}"))throw new IllegalArgumentException("Template mask");
            int count = 0;StringBuilder maskText=new StringBuilder(256);
            for (int i = 0; i < pixels.length; i++) {
                mask[i] = m!=null?m.getBoolean(i):bits.charAt(i)=='1';maskText.append(mask[i]?'1':'0');
                if (mask[i]) {
                    count++;int block=((i>>6)*4+((i&15)>>2))*3;
                    for(int k=0;k<3;k++) { int value=pixels[i]>>(16-8*k)&255;channelTotals[k]+=value;blockTotals[block+k]+=value; }
                }
            }
            // Masks come from a bounded, loaded-once catalog. Share equal keys
            // so the per-observation group lookup does not scan 256 bits per template.
            maskKey=maskText.toString().intern();
            if (count < 24) throw new IllegalArgumentException("Empty template");
            supported=count;
            String coverProof=json.optString("cover_evidence_mask_bits","");
            String effectProof=json.optString("effect_evidence_mask_bits","");
            tileEnvelope="tile_envelope".equals(json.optString("sampling","cell_inset"));
            positiveWeight=effectProof.isEmpty()?1:2;
            if(tileEnvelope!=!effectProof.isEmpty())throw new IllegalArgumentException("Effect sampling contract");
            if(!coverProof.isEmpty() && !effectProof.isEmpty())throw new IllegalArgumentException("Mixed positive evidence");
            String evidence=coverProof.isEmpty()?effectProof:coverProof;
            registrationPixels=json.optInt("max_registration_pixels",0);
            if(!evidence.isEmpty() || registrationPixels!=0) {
                boolean lock=boundState!=null && boundState.cover==Match3Position.Cover.LOCK && !coverProof.isEmpty();
                boolean line=boundState!=null && boundState.kind==Match3Position.Kind.ANIMAL
                        && boundState.cover==Match3Position.Cover.NONE && !effectProof.isEmpty()
                        && (boundState.effect==Match3Position.Effect.HORIZONTAL || boundState.effect==Match3Position.Effect.VERTICAL);
                if((!lock && !line)
                        || !evidence.matches("[01]{256}") || registrationPixels!=1 || supported!=256)
                    throw new IllegalArgumentException("Positive state evidence contract");
                coverEvidence=new boolean[256];int evidenceCount=0;
                for(int i=0;i<256;i++) {coverEvidence[i]=evidence.charAt(i)=='1';if(coverEvidence[i])evidenceCount++;}
                if(evidenceCount<24)throw new IllegalArgumentException("Sparse cover evidence");
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                    int countAtOffset=0;
                    for(int y=Math.max(0,-dy);y<Math.min(16,16-dy);y++)
                        for(int x=Math.max(0,-dx);x<Math.min(16,16-dx);x++)
                            if(coverEvidence[y*16+x])countAtOffset++;
                    registeredCoverSupport[(dy+1)*3+dx+1]=countAtOffset;
                }
            } else coverEvidence=null;
            registrationSums=coverEvidence==null?null:new Match3PatchSums(pixels);
            identityShape="iceflower".equals(kind) || "honey".equals(kind)
                    ?new Match3AnimalAppearance.Face('.',pixels):null;
            goalShape="reviewed_goal_icon".equals(rule) || "iceflower_goal_icon".equals(rule) || "user_confirmed_goal_icon".equals(rule)
                    ?new Match3AnimalAppearance.Face('.',goalShapePatch(pixels)):null;
        }
        float difference(int[] observed) {
            return difference(observed,Float.POSITIVE_INFINITY);
        }
        float difference(int[] observed,float ceiling) {
            return difference(observed,ceiling,null);
        }
        float difference(int[] observed,float ceiling,Match3PatchSums observedSums) {
            if(coverEvidence!=null) {
                if(Match3NativeMath.AVAILABLE && positiveWeight==1)return Match3NativeMath.coverDifference(pixels,observed,coverEvidence,ceiling);
                if(observedSums==null)observedSums=new Match3PatchSums(observed);
                float best=registeredCoverDifference(observed,observedSums,0,0,ceiling);
                // One patch pixel accommodates projection rounding and idle
                // pose. The animal and positive strands move together; neither
                // a matching face nor generic green pixels grants a cover.
                if(best>0)for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)
                    if(dx!=0 || dy!=0)best=Math.min(best,registeredCoverDifference(observed,observedSums,dx,dy,ceiling));
                return best;
            }
            long error = 0;
            double budget=(double)Math.nextUp(ceiling)*supported*765;
            for (int i = 0; i < pixels.length; i++) if (mask[i]) {
                int a = pixels[i], b = observed[i];
                error += Math.abs((a >> 16 & 255) - (b >> 16 & 255))
                        + Math.abs((a >> 8 & 255) - (b >> 8 & 255)) + Math.abs((a & 255) - (b & 255));
                if(error>budget)return Float.POSITIVE_INFINITY;
            }
            return error / (supported * 765f);
        }
        private float registeredCoverDifference(int[] observed,Match3PatchSums observedSums,int dx,int dy,float ceiling) {
            long whole=0,cover=0;
            int allCount=(16-Math.abs(dx))*(16-Math.abs(dy));
            int coverCount=registeredCoverSupport[(dy+1)*3+dx+1];
            if(coverCount<24)return Float.POSITIVE_INFINITY;
            double wholeBudget=(double)Math.nextUp(ceiling)*allCount*765;
            double coverBudget=(double)Math.nextUp(ceiling)*coverCount*765/positiveWeight;
            if(registrationSums.lowerBound(observedSums,dx,dy,wholeBudget)>wholeBudget)return Float.POSITIVE_INFINITY;
            for(int y=Math.max(0,-dy);y<Math.min(16,16-dy);y++)for(int x=Math.max(0,-dx);x<Math.min(16,16-dx);x++) {
                int i=y*16+x,a=pixels[i],b=observed[(y+dy)*16+x+dx];
                int delta=Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255));
                whole+=delta;if(coverEvidence[i])cover+=delta;
                // Positive partial sums are exact lower bounds. Rejected
                // offsets cannot win or change the declared ambiguity margin.
                if(whole>wholeBudget || cover>coverBudget)return Float.POSITIVE_INFINITY;
            }
            // The independently reviewed cover stencil remains sufficiently
            // supported even at the one-pixel registration boundary.
            float error=Math.max(whole/(allCount*765f),positiveWeight*cover/(coverCount*765f));
            return error<=ceiling?error:Float.POSITIVE_INFINITY;
        }
    }
    static final class Glyph {
        final int digit;
        final float aspect;
        final String bits;
        Glyph(JSONObject json) throws Exception {
            digit = json.getInt("digit"); aspect = (float) json.getDouble("aspect"); bits = json.getString("bits");
            if (digit < 0 || digit > 9 || !Float.isFinite(aspect) || aspect <= 0 || bits.length() != 160 || !bits.matches("[01]+"))
                throw new IllegalArgumentException("Invalid glyph");
        }
        float difference(String observed, float ratio) {
            int changed = 0;
            for (int i = 0; i < 160; i++) if (bits.charAt(i) != observed.charAt(i)) changed++;
            return changed / 160f + .08f * (float) Math.abs(Math.log(ratio / aspect));
        }
    }
    private static volatile Match3VisualCatalog loaded;
    final boolean available;
    final String id;
    String galleryId="", gallerySha256="", galleryStatus="absent";
    int galleryGoalsPendingReview;
    String coverCatalogId="", coverCatalogSha256="", coverCatalogStatus="absent";
    String hudGlyphCatalogId="", hudGlyphCatalogSha256="", hudGlyphCatalogStatus="absent";
    String boardFeedbackId="",boardFeedbackSha256="",boardFeedbackStatus="absent";
    private final boolean hasBoundGoalModes;
    final List<Pattern> steps, goals, cells, animals, largeObjects, iceSurfaces, iceBackgrounds, effectEnvelopes;
    final List<Match3AnimalAppearance.Face> animalFaces;
    final List<Match3AnimalAppearance.Body> animalBodies;
    final List<Character> bodyColors;
    private final Match3AnimalAppearance.BodyTable ordinaryBodies;
    List<Glyph> glyphs;
    final List<Glyph> checkmarks, levelSuffixes;
    /** One immutable buffer per bounded catalog; no native handles or per-cell
     * reference copies. All scores still compete by their original family. */
    private static final class PatternList extends java.util.AbstractList<Pattern> {
        private final List<Pattern> patterns;
        private final java.nio.ByteBuffer table;
        private final java.util.Map<CoverPatch,float[]> coverScores;
        /** Registered tables have the costly multi-offset comparisons. Reuse
         * their complete scores only for identical pixels and exact ceiling;
         * the owning catalog and all its patterns are immutable. */
        private static final class CoverPatch {
            final int[] pixels;
            final int ceilingBits, hash;
            CoverPatch(int[] pixels,float ceiling,boolean freeze) {
                this.pixels=freeze?pixels.clone():pixels;
                ceilingBits=Float.floatToIntBits(ceiling);
                hash=31*java.util.Arrays.hashCode(pixels)+ceilingBits;
            }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object other) {
                if(!(other instanceof CoverPatch))return false;
                CoverPatch patch=(CoverPatch)other;
                return ceilingBits==patch.ceilingBits && java.util.Arrays.equals(pixels,patch.pixels);
            }
        }
        PatternList(List<Pattern> source) {
            patterns=Collections.unmodifiableList(new ArrayList<>(source));
            boolean registered=false;
            for(Pattern pattern:source)registered|=pattern.coverEvidence!=null;
            coverScores=registered?new java.util.LinkedHashMap<CoverPatch,float[]>(128,.75f,true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<CoverPatch,float[]> entry) {
                    return size()>1024;
                }
            }:null;
            if(!Match3NativeMath.AVAILABLE || source.isEmpty()) { table=null;return; }
            java.nio.ByteBuffer packed=java.nio.ByteBuffer.allocateDirect(Math.multiplyExact(source.size(),7352)).order(java.nio.ByteOrder.nativeOrder());
            for(Pattern p:source) {
                // Mode 1 retains the cover contract; mode 2 applies the
                // stricter positive arrow score with the same offsets/record.
                packed.putInt(p.registrationPixels*p.positiveWeight).putInt(p.supported);
                for(int pixel:p.pixels)packed.putInt(pixel);
                for(boolean bit:p.mask)packed.put((byte)(bit?1:0));
                for(int i=0;i<256;i++)packed.put((byte)(p.coverEvidence!=null && p.coverEvidence[i]?1:0));
                packed.position(packed.position()+5808); // native RGB bounds, nine cover counts and pixel orders
            }
            packed.flip();table=Match3NativeMath.preparePatternTable(packed,size())?packed:null;
        }
        @Override public Pattern get(int index) { return patterns.get(index); }
        @Override public int size() { return patterns.size(); }
        float[] differences(int[] observed,float ceiling) {
            if(table==null)return null;
            if(coverScores==null)return Match3NativeMath.patternDifferences(observed,table,size(),ceiling);
            CoverPatch key=new CoverPatch(observed,ceiling,false);float[] cached;
            synchronized(coverScores) { cached=coverScores.get(key); }
            if(cached!=null)return cached;
            float[] result=Match3NativeMath.patternDifferences(observed,table,size(),ceiling);
            if(result!=null) {
                synchronized(coverScores) { coverScores.put(new CoverPatch(observed,ceiling,true),result.clone()); }
            }
            return result;
        }
    }
    private Match3VisualCatalog() {
        available = false; id = "unavailable";
        hasBoundGoalModes=false;
        steps = goals = cells = animals = largeObjects = iceSurfaces = iceBackgrounds = effectEnvelopes = Collections.emptyList(); glyphs = checkmarks = levelSuffixes = Collections.emptyList();
        animalFaces=Collections.emptyList();
        animalBodies=Collections.emptyList();
        bodyColors=Collections.emptyList();
        ordinaryBodies=new Match3AnimalAppearance.BodyTable(animalBodies,bodyColors);
    }
    private Match3VisualCatalog(JSONObject json) throws Exception {
        if (!"match3-fixed-ui-v1".equals(json.getString("format"))) throw new IllegalArgumentException("Catalog format");
        id = json.getString("id"); steps = patterns(json.getJSONArray("steps"));
        goals = patterns(json.getJSONArray("goals"));
        boolean bound=false;for(Pattern pattern:goals)if(pattern.goalMode!=null)bound=true;hasBoundGoalModes=bound;
        List<Pattern> large=new ArrayList<>();
        if(json.has("large_objects"))for(Pattern p:patterns(json.getJSONArray("large_objects")))
            if("cookie".equals(p.kind) && "identity_only_2x2".equals(p.rule))large.add(p);
        largeObjects=new PatternList(large);
        List<Pattern> surfaces=new ArrayList<>(),backgrounds=new ArrayList<>();
        if(json.has("ice_surfaces"))for(Pattern p:patterns(json.getJSONArray("ice_surfaces"))) {
            if("ice1".equals(p.kind) && "stationary_single_layer_ice".equals(p.rule)
                    || "ordinary0".equals(p.kind) && "ordinary_board_background".equals(p.rule)) {
                surfaces.add(p);
                if("ice1".equals(p.kind) && p.neutralBackground)backgrounds.add(p);
            }
        }
        iceSurfaces=new PatternList(surfaces);iceBackgrounds=Collections.unmodifiableList(backgrounds);
        List<Pattern> objects=new ArrayList<>(), faces=new ArrayList<>(),effects=new ArrayList<>();
        List<Match3AnimalAppearance.Face> descriptors=new ArrayList<>();
        for(Pattern pattern:patterns(json.getJSONArray("cells"))) {
            if(pattern.kind.startsWith("animal_") && pattern.kind.length()==8
                    && "ordinary_uncovered_animal".equals(pattern.rule)) {
                Match3AnimalAppearance.Face face=new Match3AnimalAppearance.Face(pattern.kind.charAt(7),pattern.pixels);
                if(face.detailed) { faces.add(pattern);descriptors.add(face); }
            } else if(pattern.tileEnvelope)effects.add(pattern);
            else if(!pattern.kind.startsWith("animal_"))objects.add(pattern);
        }
        cells=new PatternList(objects);animals=new PatternList(faces);
        // Arrows can sit outside the face inset. Both paths use the full tile
        // and the separately weighted positive stencil, at identical scores.
        effectEnvelopes=new PatternList(effects);
        animalFaces=new Match3AnimalAppearance.FaceList(descriptors);
        List<Match3AnimalAppearance.Body> bodies=new ArrayList<>();List<Character> colors=new ArrayList<>();
        JSONArray envelopes=json.getJSONArray("ordinary_envelopes");
        for(int i=0;i<envelopes.length();i++) {
            JSONObject item=envelopes.getJSONObject(i);
            if(!"ordinary_uncovered_animal".equals(item.optString("rule")))continue;
            char color=item.getString("color").charAt(0);if(!Match3Sampler.isMovable(color))throw new IllegalArgumentException("Animal envelope");
            int[] patch=readPixels(item);
            bodies.add(new Match3AnimalAppearance.Body(patch));colors.add(color);
        }
        animalBodies=Collections.unmodifiableList(bodies);bodyColors=Collections.unmodifiableList(colors);
        ordinaryBodies=new Match3AnimalAppearance.BodyTable(animalBodies,bodyColors);
        List<Glyph> parsed = new ArrayList<>(); JSONArray digits = json.getJSONArray("glyphs");
        for (int i = 0; i < digits.length(); i++) parsed.add(new Glyph(digits.getJSONObject(i)));
        glyphs = Collections.unmodifiableList(parsed); available = !steps.isEmpty() && !goals.isEmpty() && !glyphs.isEmpty();
        List<Glyph> marks = new ArrayList<>(); JSONArray checks = json.getJSONArray("checkmarks");
        for(int i=0;i<checks.length();i++)marks.add(new Glyph(checks.getJSONObject(i)));
        checkmarks=Collections.unmodifiableList(marks);
        List<Glyph> suffixes=new ArrayList<>();JSONArray suffix=json.getJSONArray("level_suffixes");
        for(int i=0;i<suffix.length();i++)suffixes.add(new Glyph(suffix.getJSONObject(i)));
        levelSuffixes=Collections.unmodifiableList(suffixes);
    }
    private static List<Pattern> patterns(JSONArray array) throws Exception {
        List<Pattern> patterns = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) patterns.add(new Pattern(array.getJSONObject(i)));
        return new PatternList(patterns);
    }
    static Match3VisualCatalog get(Context context) {
        Match3VisualCatalog catalog = loaded;
        if (catalog != null) return catalog;
        synchronized (Match3VisualCatalog.class) {
            if (loaded == null) try (java.io.InputStream input = context.getAssets().open("match3-fixed-ui-v1.json")) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) > 0) { if (output.size() + n > 327680) throw new IllegalArgumentException("Catalog size"); output.write(buffer,0,n); }
                byte[] fixedBytes=output.toByteArray();
                JSONObject json = new JSONObject(new String(fixedBytes, StandardCharsets.UTF_8));
                byte[] gallery=null;
                try(java.io.InputStream extra=context.getAssets().open("match3-gallery-v1.json")) {
                    java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();
                    int count;while((count=extra.read(buffer))>0) {
                        data.write(buffer,0,count);if(data.size()>1048576)break;
                    }
                    gallery=data.toByteArray();
                } catch(java.io.IOException absent) { }
                Match3RuleCatalog rules=Match3RuleCatalog.get(context);
                String galleryText=gallery==null?"":new String(gallery,StandardCharsets.UTF_8);
                if(galleryText.contains("\"rule_binding\"") || galleryText.contains("\"goal_binding\"")) {
                    JSONObject meta=new JSONObject(new String(gallery,StandardCharsets.UTF_8)).getJSONObject("metadata");
                    if(!rules.available || !rules.sha256.equals(meta.optString("rule_catalog_sha256"))) {
                        loaded=new Match3VisualCatalog(json);loaded.galleryStatus="rejected:RuleProvenance";return loaded;
                    }
                }
                List<byte[]> supplements=new ArrayList<>();
                for(String name:new String[]{"match3-vine-v1.json","match3-gray-v1.json"})
                    try(java.io.InputStream extra=context.getAssets().open(name)) {
                        java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();
                        int count;while((count=extra.read(buffer))>0) {
                            if(data.size()+count>COVER_CATALOG_MAXIMUM_BYTES)throw new IllegalArgumentException("Cover catalog size");
                            data.write(buffer,0,count);
                        }
                        supplements.add(data.toByteArray());
                    } catch(java.io.IOException absent) { }
                byte[] feedback=null;
                try(java.io.InputStream extra=context.getAssets().open("match3-board-feedback-v1.json")) {
                    java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();int count;
                    while((count=extra.read(buffer))>0) {
                        data.write(buffer,0,count);if(data.size()>65536)break;
                    }
                    feedback=data.toByteArray();
                } catch(java.io.IOException absent) { }
                Match3VisualCatalog candidate = withBoardFeedback(json,gallery,supplements,feedback,rules.sha256);
                byte[] hudGlyphs=null;
                try(java.io.InputStream extra=context.getAssets().open("match3-hud-glyphs-v1.json")) {
                    java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();
                    int count;while((count=extra.read(buffer))>0) {
                        data.write(buffer,0,count);if(data.size()>4096)break;
                    }
                    hudGlyphs=data.toByteArray();
                } catch(java.io.IOException absent) { }
                loaded=withHudGlyphSupplement(candidate,hudGlyphs,sha256(fixedBytes));
            } catch (Exception ignored) { loaded = new Match3VisualCatalog(); }
            return loaded;
        }
    }

    /** Reviewed OCR exemplars only: no game-state bindings, threshold changes or
     * count overrides. Validate the entire bounded sidecar before publishing it. */
    static Match3VisualCatalog withHudGlyphSupplement(Match3VisualCatalog catalog,byte[] bytes,String fixedSha) {
        if(bytes==null)return catalog;
        try {
            if(bytes.length>4096)throw new IllegalArgumentException("HUD glyph byte budget");
            JSONObject extra=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            JSONObject metadata=extra.getJSONObject("metadata");
            String id=extra.getString("id");
            if(!"match3-hud-glyphs-v1".equals(extra.getString("format")) || id.isEmpty() || id.length()>80
                    || !fixedSha.matches("[0-9a-f]{64}") || !fixedSha.equals(metadata.getString("fixed_catalog_sha256"))
                    || !"developer_visual_review".equals(metadata.getString("review_scope"))
                    || !"white_component_10x16_center".equals(metadata.getString("sampling"))
                    || !Boolean.FALSE.equals(metadata.get("independent")))
                throw new IllegalArgumentException("HUD glyph provenance");
            JSONArray glyphs=extra.getJSONArray("glyphs");
            if(glyphs.length()<1 || glyphs.length()>8)throw new IllegalArgumentException("HUD glyph entry budget");
            List<Glyph> admitted=new ArrayList<>(catalog.glyphs);
            for(int i=0;i<glyphs.length();i++) {
                JSONObject item=glyphs.getJSONObject(i);JSONArray box=item.getJSONArray("source_box");
                if(!Boolean.TRUE.equals(item.get("reviewed")) || !(item.get("digit") instanceof Integer)
                        || !item.getString("source_sha256").matches("[0-9a-f]{64}") || box.length()!=4)
                    throw new IllegalArgumentException("Unreviewed HUD glyph");
                for(int j=0;j<4;j++)if(!(box.get(j) instanceof Integer))throw new IllegalArgumentException("HUD glyph bounds");
                int l=box.getInt(0),t=box.getInt(1),r=box.getInt(2),b=box.getInt(3);
                Glyph glyph=new Glyph(item);
                if(l<0 || t<0 || r>432 || b>200 || r<=l || b-t<7 || r-l>(b-t)*.85f
                        || Math.abs(glyph.aspect-(r-l)/(float)(b-t))>.0001f)
                    throw new IllegalArgumentException("HUD glyph geometry");
                admitted.add(glyph);
            }
            String digest=sha256(bytes);
            catalog.glyphs=Collections.unmodifiableList(admitted);
            catalog.hudGlyphCatalogId=id;catalog.hudGlyphCatalogSha256=digest;catalog.hudGlyphCatalogStatus="loaded";
        } catch(Exception invalid) { catalog.hudGlyphCatalogStatus="rejected:"+invalid.getClass().getSimpleName(); }
        return catalog;
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex=new StringBuilder();
        for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return hex.toString();
    }

    /** Optional sidecar gallery of extra ordinary-animal body references, loaded
     *  separately so it is not bound by the fixed catalog's size cap. Appends to
     *  ordinary_envelopes before construction; any failure leaves the catalog
     *  unchanged. Identity/rule semantics are unchanged — only more plain-body
     *  exemplars, so a face-confirmed animal is less likely to abstain on swap. */
    static Match3VisualCatalog withGallery(JSONObject base, byte[] bytes) throws Exception {
        return mergeGallery(base,bytes).catalog;
    }
    private static final class CatalogMerge {
        final JSONObject json;
        final Match3VisualCatalog catalog;
        CatalogMerge(JSONObject json,Match3VisualCatalog catalog) { this.json=json;this.catalog=catalog; }
    }
    /** The original gallery stays byte-for-byte intact. A separately audited,
     * bounded supplement goes through the same typed admission and competition. */
    static Match3VisualCatalog withReviewedSupplement(JSONObject base,byte[] gallery,byte[] supplement,String rulesSha) throws Exception {
        return withReviewedSupplements(base,gallery,supplement==null?Collections.emptyList():Collections.singletonList(supplement),rulesSha);
    }
    /** Two fixed sidecars share the original total entry budgets. Admission is
     * atomic, and each sidecar retains its own 64KiB cap and rule provenance. */
    static Match3VisualCatalog withReviewedSupplements(JSONObject base,byte[] gallery,List<byte[]> supplements,String rulesSha) throws Exception {
        return reviewedMerge(base,gallery,supplements,rulesSha).catalog;
    }
    private static CatalogMerge reviewedMerge(JSONObject base,byte[] gallery,List<byte[]> supplements,String rulesSha) throws Exception {
        CatalogMerge original=mergeGallery(base,gallery);
        if(supplements.isEmpty())return original;
        try {
            if(supplements.size()>2)throw new IllegalArgumentException("Supplement count");
            CatalogMerge augmented=original;
            int cells=0,goals=0,envelopes=0;StringBuilder ids=new StringBuilder();
            java.io.ByteArrayOutputStream proof=new java.io.ByteArrayOutputStream();
            for(byte[] supplement:supplements) {
            if(supplement.length>COVER_CATALOG_MAXIMUM_BYTES)throw new IllegalArgumentException("Cover catalog size");
            JSONObject extra=new JSONObject(new String(supplement,StandardCharsets.UTF_8));
            JSONObject metadata=extra.getJSONObject("metadata");
            cells+=extra.optJSONArray("cells")==null?0:extra.getJSONArray("cells").length();
            goals+=extra.optJSONArray("goals")==null?0:extra.getJSONArray("goals").length();
            envelopes+=extra.optJSONArray("ordinary_envelopes")==null?0:extra.getJSONArray("ordinary_envelopes").length();
            if(cells>32 || goals>8 || envelopes>4)
                throw new IllegalArgumentException("Supplement entry budget");
            if(!rulesSha.matches("[0-9a-f]{64}") || !rulesSha.equals(metadata.getString("rule_catalog_sha256")))
                throw new IllegalArgumentException("Cover rule provenance");
            augmented=mergeGallery(augmented.json,supplement);
            if(!"loaded".equals(augmented.catalog.galleryStatus))throw new IllegalArgumentException("Cover admission");
            if(ids.length()>0)ids.append('+');ids.append(augmented.catalog.galleryId);
            proof.write(supplement);
            }
            Match3VisualCatalog result=augmented.catalog;
            result.coverCatalogId=ids.toString();
            result.coverCatalogSha256=sha256(proof.toByteArray());
            result.coverCatalogStatus="loaded";
            result.galleryId=original.catalog.galleryId;
            result.gallerySha256=original.catalog.gallerySha256;
            result.galleryStatus=original.catalog.galleryStatus;
            result.galleryGoalsPendingReview+=original.catalog.galleryGoalsPendingReview;
            return augmented;
        } catch(Exception invalid) {
            original.catalog.coverCatalogStatus="rejected:"+invalid.getClass().getSimpleName();
            return original;
        }
    }
    /** One fixed 64KiB board feedback sidecar: at most 24 reviewed foreground
     * exemplars and four ordinary envelopes. Separate from the existing cover
     * budget; every pattern still competes at the unchanged decision thresholds.
     * Invalid feedback leaves both earlier galleries fully intact. */
    static Match3VisualCatalog withBoardFeedback(JSONObject base,byte[] gallery,List<byte[]> supplements,
                                                 byte[] feedback,String rulesSha) throws Exception {
        CatalogMerge previous=reviewedMerge(base,gallery,supplements,rulesSha);
        if(feedback==null)return previous.catalog;
        try {
            if(feedback.length>65536)throw new IllegalArgumentException("Board feedback byte budget");
            JSONObject json=new JSONObject(new String(feedback,StandardCharsets.UTF_8));
            JSONObject metadata=json.getJSONObject("metadata");
            if(!rulesSha.matches("[0-9a-f]{64}") || !rulesSha.equals(metadata.getString("rule_catalog_sha256"))
                    || !"developer_visual_review".equals(metadata.getString("review_scope"))
                    || !Boolean.FALSE.equals(metadata.get("independent")))
                throw new IllegalArgumentException("Board feedback provenance");
            JSONArray cells=json.getJSONArray("cells");
            JSONArray bodies=json.optJSONArray("ordinary_envelopes");
            if(cells.length()<1 || cells.length()>24 || bodies!=null && bodies.length()>4)
                throw new IllegalArgumentException("Board feedback entry budget");
            for(String key:new String[]{"goals","large_objects","ice_surfaces"})
                if(json.has(key) && json.getJSONArray(key).length()>0)throw new IllegalArgumentException("Board feedback scope");
            JSONObject bindings=json.getJSONObject("state_bindings");
            for(int i=0;i<cells.length();i++) {
                JSONObject item=cells.getJSONObject(i);
                if(!item.getString("source_sha256").matches("[0-9a-f]{64}") || !Boolean.TRUE.equals(item.get("reviewed")))
                    throw new IllegalArgumentException("Board feedback source review");
                Match3Position.Cell state=Match3StateBinding.parse(bindings.getJSONObject(item.getString("rule_binding_ref")));
                if(state.kind!=Match3Position.Kind.BIRD && !(state.kind==Match3Position.Kind.SNOW && state.layers==-1)
                        && !(state.kind==Match3Position.Kind.UNKNOWN && state.cover==Match3Position.Cover.GRAY_FURBALL)
                        && !(state.kind==Match3Position.Kind.ANIMAL && Match3Action.line(state)))
                    throw new IllegalArgumentException("Board feedback state scope");
                if(state.kind==Match3Position.Kind.ANIMAL && !item.has("effect_evidence_mask_bits"))
                    throw new IllegalArgumentException("Missing positive direction evidence");
            }
            CatalogMerge merge=mergeGallery(previous.json,feedback);
            Match3VisualCatalog result=merge.catalog,old=previous.catalog;
            if(!"loaded".equals(result.galleryStatus))throw new IllegalArgumentException("Board feedback admission");
            result.boardFeedbackId=result.galleryId;result.boardFeedbackSha256=sha256(feedback);result.boardFeedbackStatus="loaded";
            result.galleryId=old.galleryId;result.gallerySha256=old.gallerySha256;result.galleryStatus=old.galleryStatus;
            result.galleryGoalsPendingReview=old.galleryGoalsPendingReview;
            result.coverCatalogId=old.coverCatalogId;result.coverCatalogSha256=old.coverCatalogSha256;result.coverCatalogStatus=old.coverCatalogStatus;
            return result;
        } catch(Exception invalid) {
            previous.catalog.boardFeedbackStatus="rejected:"+invalid.getClass().getSimpleName();
            return previous.catalog;
        }
    }
    private static CatalogMerge mergeGallery(JSONObject base, byte[] bytes) throws Exception {
        Match3VisualCatalog fallback=new Match3VisualCatalog(base);
        if(bytes==null)return new CatalogMerge(base,fallback);
        try {
            if(bytes.length>1048576)throw new IllegalArgumentException("Gallery size");
            JSONObject gallery=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(!"match3-gallery-v1".equals(gallery.getString("format")))throw new IllegalArgumentException("Gallery format");
            JSONObject bindings=gallery.optJSONObject("state_bindings");
            if(bindings!=null) {
                if(bindings.length()>64)throw new IllegalArgumentException("State binding budget");
                for(java.util.Iterator<String> names=bindings.keys();names.hasNext();) {
                    String name=names.next();if(!name.matches("bound_[0-9a-f]{20}"))throw new IllegalArgumentException("State binding identity");
                    Match3StateBinding.parse(bindings.getJSONObject(name));
                }
            }
            JSONObject merged=new JSONObject(base.toString());int total=0;
            int pendingGoals=0;
            java.util.Map<String,java.util.Set<String>> sources=new java.util.HashMap<>();
            java.util.Map<String,Match3Position.Cell> boundStates=new java.util.HashMap<>();
            for(String key:new String[]{"ordinary_envelopes","cells","goals","large_objects","ice_surfaces"}) {
                JSONArray extra=gallery.optJSONArray(key);if(extra==null)continue;
                total+=extra.length();if(total>3072)throw new IllegalArgumentException("Gallery entry budget");
                for(int i=0;i<extra.length();i++) {
                    JSONObject item=extra.getJSONObject(i);
                    if(item.has("rule_binding_ref")) {
                        String reference=item.getString("rule_binding_ref");
                        if(!"cells".equals(key) || bindings==null || item.has("rule_binding")
                                || !reference.equals(item.getString("kind")) || !bindings.has(reference)
                                || !"reviewed_state".equals(item.getString("rule")))
                            throw new IllegalArgumentException("Unresolved state binding");
                        item=new JSONObject(item.toString());item.remove("rule_binding_ref");
                        item.put("rule_binding",bindings.getJSONObject(reference));extra.put(i,item);
                    }
                    String rule=item.optString("rule","unverified"),kind=item.optString("kind","");
                    if("unverified".equals(rule) || rule.isEmpty())throw new IllegalArgumentException("Unreviewed gallery rule");
                    if(kind.startsWith("identity_") && (!item.optBoolean("reviewed",false)
                            || item.optInt("rule_version",0)!=1 || item.optString("source","").isEmpty()
                            || item.optString("family_id","").isEmpty()))
                        throw new IllegalArgumentException("Unreviewed typed identity");
                    if("cells".equals(key) && !validCellRule(kind,rule)) {
                        if(!"reviewed_state".equals(rule) || !kind.matches("bound_[0-9a-f]{20}")
                                || !Match3Rules.CATALOG_ID.equals(gallery.getJSONObject("metadata").optString("rule_catalog_id")))
                            throw new IllegalArgumentException("Unsupported cell rule");
                        Match3Position.Cell state=Match3StateBinding.parse(item.getJSONObject("rule_binding"));
                        Match3Position.Cell previous=boundStates.putIfAbsent(kind,state);
                        if(previous!=null && !previous.equals(state))throw new IllegalArgumentException("Conflicting state identity");
                    }
                    if("ordinary_envelopes".equals(key) && (!"ordinary_uncovered_animal".equals(rule)
                            || !item.optString("color","").matches("[ROYGBP]")))throw new IllegalArgumentException("Unsupported animal rule");
                    if("large_objects".equals(key) && (!"cookie".equals(kind) || !"identity_only_2x2".equals(rule)))
                        throw new IllegalArgumentException("Unsupported object rule");
                    if("ice_surfaces".equals(key) && (!"ice1".equals(kind) || !"stationary_single_layer_ice".equals(rule)
                            || !item.optBoolean("neutral_background",false)))throw new IllegalArgumentException("Unsupported ice rule");
                    if("goals".equals(key) && (!"reviewed_goal_icon".equals(rule) && !"user_confirmed_goal_icon".equals(rule) && !"iceflower_goal_icon".equals(rule)
                            || !validGoalKind(kind)))
                        throw new IllegalArgumentException("Unsupported goal rule");
                    if(item.has("goal_binding") && (!"goals".equals(key)
                            || !Match3Rules.CATALOG_ID.equals(gallery.getJSONObject("metadata").optString("rule_catalog_id"))))
                        throw new IllegalArgumentException("Unsupported goal mode provenance");
                    if(gallery.has("metadata")) {
                        if(!item.optBoolean("reviewed",false))throw new IllegalArgumentException("Unreviewed exemplar");
                        String family=item.getString("family_id"),source=item.getString("source");
                        sources.computeIfAbsent(family,k->new java.util.HashSet<>()).add(source);
                        if(sources.size()>64 || sources.get(family).size()>24)throw new IllegalArgumentException("Family budget");
                    }
                }
                if(!merged.has(key))merged.put(key,new JSONArray());
                if("goals".equals(key)) {
                    for(int i=0;i<extra.length();i++) {
                        JSONObject item=extra.getJSONObject(i);
                        // Legacy experiment icons lack independent review. They
                        // may confound an already verified task; retain the
                        // asset for review, but never grant a target rule here.
                        if(!item.optBoolean("reviewed",false)) { pendingGoals++;continue; }
                        merged.getJSONArray(key).put(item);
                    }
                } else appendAll(extra,merged.getJSONArray(key));
            }
            Match3VisualCatalog result=new Match3VisualCatalog(merged);
            result.galleryId=gallery.getString("id");
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex=new StringBuilder();for(byte value:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            result.gallerySha256=hex.toString();result.galleryStatus="loaded";result.galleryGoalsPendingReview=pendingGoals;return new CatalogMerge(merged,result);
        } catch(Exception invalid) {
            fallback.galleryStatus="rejected:"+invalid.getClass().getSimpleName();return new CatalogMerge(base,fallback);
        }
    }
    private static boolean validGoalKind(String kind) {
        try { return Match3Goals.Kind.valueOf(kind)!=Match3Goals.Kind.UNKNOWN; }
        catch(IllegalArgumentException invalid) { return false; }
    }

    private static boolean validCellRule(String kind,String rule) {
        if(kind.startsWith("identity_")) {
            if(!"reviewed_identity_only".equals(rule))return false;
            try {
                Match3Position.Kind typed=Match3Position.Kind.valueOf(kind.substring(9));
                return typed!=Match3Position.Kind.ANIMAL && !"unknown".equals(Match3Rules.ruleForKind(typed));
            } catch(IllegalArgumentException invalid) { return false; }
        }
        if(kind.startsWith("animal_"))return kind.matches("animal_[ROYGBP]") && "ordinary_uncovered_animal".equals(rule);
        switch(kind) {
            case "coin": return "coin".equals(rule);
            case "snow1": return "snow".equals(rule);
            case "iceflower": return "multistage_iceflower".equals(rule) || "user_confirmed_adjacent_clear".equals(rule);
            case "honey": return "single_clear_honey".equals(rule) || "user_confirmed_adjacent_clear".equals(rule);
            case "egg": return "egg".equals(rule);
            default: return false;
        }
    }

    private static int[] readPixels(JSONObject item) throws Exception {
        int[] pixels=new int[256];JSONArray array=item.optJSONArray("pixels");
        int representations=(item.has("pixels")?1:0)+(item.has("pixels_rgb")?1:0)+(item.has("pixels_base64")?1:0);
        if(representations!=1)throw new IllegalArgumentException("Conflicting pixel representations");
        if(array!=null) {
            if(array.length()!=256)throw new IllegalArgumentException("Pixel shape");
            for(int i=0;i<256;i++)pixels[i]=array.getInt(i);
        } else if(item.has("pixels_base64")) {
            String encoded=item.getString("pixels_base64");
            if(!encoded.matches("[A-Za-z0-9+/]{1024}"))throw new IllegalArgumentException("Packed RGB descriptor");
            byte[] rgb=java.util.Base64.getDecoder().decode(encoded);
            if(rgb.length!=768)throw new IllegalArgumentException("Packed RGB shape");
            for(int i=0;i<256;i++)pixels[i]=0xff000000|(rgb[i*3]&255)<<16|(rgb[i*3+1]&255)<<8|(rgb[i*3+2]&255);
        } else {
            String rgb=item.getString("pixels_rgb");
            if(!rgb.matches("[0-9a-f]{1536}"))throw new IllegalArgumentException("RGB descriptor");
            for(int i=0;i<256;i++)pixels[i]=0xff000000|Integer.parseInt(rgb.substring(i*6,i*6+6),16);
        }
        return pixels;
    }

    private static void appendAll(JSONArray extra, JSONArray base) throws org.json.JSONException {
        if (extra == null || base == null) return;
        for (int i = 0; i < extra.length(); i++) base.put(extra.getJSONObject(i));
    }
    static int[] patch(int[] pixels, int width, int left, int top, int right, int bottom) {
        int[] patch = new int[PATCH * PATCH];
        for (int y = 0; y < PATCH; y++) for (int x = 0; x < PATCH; x++) {
            int xx = left + Math.min(right-left-1, (2*x+1)*(right-left)/(PATCH*2));
            int yy = top + Math.min(bottom-top-1, (2*y+1)*(bottom-top)/(PATCH*2));
            patch[y*PATCH+x] = pixels[yy*width+xx];
        }
        return patch;
    }
    static int[] combineEnvelopes(int[] topLeft,int[] topRight,int[] bottomLeft,int[] bottomRight) {
        int[] patch=new int[256];
        for(int y=0;y<16;y++) {
            int[] left=y<8?topLeft:bottomLeft,right=y<8?topRight:bottomRight;
            int base=(2*(y%8)+1)*16,row=y*16;
            for(int x=0;x<8;x++) {int at=base+2*x+1;patch[row+x]=left[at];patch[row+x+8]=right[at];}
        }
        return patch;
    }
    static String recognize(List<Pattern> patterns, int[] patch, float maximum, float margin) {
        return recognize(patterns, patch, maximum, margin, null);
    }
    static String recognize(List<Pattern> patterns, int[] patch, float maximum, float margin, Admission out) {
        if (out != null) out.reset();
        String best = null; float error = Float.POSITIVE_INFINITY, second = Float.POSITIVE_INFINITY;
        java.util.Map<String,int[]> totals=new java.util.HashMap<>();
        Match3AnimalAppearance.Face goalShape=null;
        Match3PatchSums registrationSums=null;
        float[] nativeScores=patterns instanceof PatternList?((PatternList)patterns).differences(patch,maximum+margin):null;
        if(nativeScores!=null && nativeScores.length!=patterns.size())nativeScores=null;
        int patternIndex=0;
        for (int index=0,count=patterns.size();index<count;index++) {
            Pattern pattern=patterns.get(index);
            float candidate;
            if(nativeScores!=null)candidate=nativeScores[patternIndex++];
            else {
            int[] observed=totals.get(pattern.maskKey);
            if(observed==null) {
                observed=new int[51];
                for(int i=0;i<256;i++)if(pattern.mask[i]) {
                    int block=3+((i>>6)*4+((i&15)>>2))*3;
                    for(int k=0;k<3;k++) { int value=patch[i]>>(16-8*k)&255;observed[k]+=value;observed[block+k]+=value; }
                }
                totals.put(pattern.maskKey,observed);
            }
            long lower=0;for(int k=0;k<3;k++)lower+=Math.abs(observed[k]-pattern.channelTotals[k]);
            double budget=(double)Math.nextUp(maximum+margin)*pattern.supported*765;
            if(lower>budget && pattern.registrationPixels==0)continue;
            // Summed block means are another Manhattan lower bound. The mask
            // partitions every supported pixel exactly once; no contender is lost.
            lower=0;for(int k=0;k<48 && lower<=budget;k++)lower+=Math.abs(observed[k+3]-pattern.blockTotals[k]);
            if(lower>budget && pattern.registrationPixels==0)continue;
            // A partial sum above maximum+margin cannot win or make an
            // accepted winner ambiguous. Stop only those comparisons; retain
            // the exact scores and original thresholds for every contender.
            if((!Match3NativeMath.AVAILABLE || pattern.positiveWeight!=1) && pattern.registrationPixels!=0 && registrationSums==null)registrationSums=new Match3PatchSums(patch);
            candidate = pattern.difference(patch,maximum+margin,registrationSums);
            }
            if(pattern.goalShape!=null && candidate<=maximum+margin) {
                if(goalShape==null)goalShape=new Match3AnimalAppearance.Face('.',goalShapePatch(patch));
                candidate=Math.max(candidate,goalShape.difference(pattern.goalShape));
            }
            if (candidate < error) {
                if (!pattern.kind.equals(best)) second = error;
                best = pattern.kind; error = candidate;
            } else if (!pattern.kind.equals(best)) second = Math.min(second,candidate);
        }
        if (out != null) { out.template = best; out.error = error; out.otherKind = second; }
        return error <= maximum && second-error >= margin ? best : null;
    }
    Match3Goals.Target goalTarget(int slot,Match3Goals.Kind kind,int count,boolean complete,String visualId,int[] patch) {
        Match3Goals.Target observed=Match3Goals.Target.observed(slot,kind,count,complete,visualId);
        return hasBoundGoalModes?goalTarget(goals,patch,observed):observed;
    }
    static Match3Goals.Target goalTarget(List<Pattern> patterns,int[] patch,Match3Goals.Target observed) {
        Match3Goals.Mode best=null;String evidence="";
        float error=Float.POSITIVE_INFINITY,second=Float.POSITIVE_INFINITY;
        Match3AnimalAppearance.Face shape=null;
        for(Pattern pattern:patterns)if(pattern.kind.equals(observed.kind.name())) {
            float candidate=pattern.difference(patch,.165f);
            if(pattern.goalShape!=null && candidate<=.165f) {
                if(shape==null)shape=new Match3AnimalAppearance.Face('.',goalShapePatch(patch));
                candidate=Math.max(candidate,shape.difference(pattern.goalShape));
            }
            Match3Goals.Mode mode=pattern.goalMode==null?observed.mode:pattern.goalMode;
            if(candidate<error) {
                if(mode!=best)second=error;
                error=candidate;best=mode;evidence=pattern.goalMode==null?observed.modeEvidence:pattern.goalModeEvidence;
            } else if(mode!=best)second=Math.min(second,candidate);
        }
        if(error>.14f || second-error<.025f || best==null) {
            best=Match3Goals.Mode.UNKNOWN;evidence="goal_mode_visual_conflict_or_unobserved";
        }
        return new Match3Goals.Target(observed.slot,observed.kind,observed.remaining,observed.completed,
                observed.visualId,best,evidence);
    }
    /** Upper icon only: the changing counter cannot supply identity evidence. */
    private static int[] goalShapePatch(int[] pixels) {
        int[] out=new int[256];for(int y=0;y<16;y++)for(int x=0;x<16;x++)out[y*16+x]=pixels[(y/2)*16+x];
        return out;
    }
    int digit(String bits, float ratio) {
        float[] errors = new float[10]; java.util.Arrays.fill(errors, Float.POSITIVE_INFINITY);
        for (Glyph glyph : glyphs) errors[glyph.digit] = Math.min(errors[glyph.digit], glyph.difference(bits, ratio));
        int best = -1; float first = Float.POSITIVE_INFINITY, second = Float.POSITIVE_INFINITY;
        for (int i = 0; i < 10; i++) if (errors[i] < first) { second = first; first = errors[i]; best = i; }
        else second = Math.min(second,errors[i]);
        return first <= .22f && second-first >= .035f ? best : -1;
    }
    boolean checkmark(String bits,float ratio) {
        for(Glyph mark:checkmarks)if(mark.difference(bits,ratio)<=.18f)return true;
        return false;
    }
    boolean levelSuffix(String bits,float ratio) {
        for(Glyph suffix:levelSuffixes)if(suffix.difference(bits,ratio)<=.18f)return true;
        return false;
    }

    Match3Position.Cell obstacle(Bitmap frame, BoardGeometry geometry, int row, int col, int[] pixels,CellCache cache) {
        if (!available) return null;
        int left = (int) geometry.cellLeft(col), top = (int) geometry.cellTop(row);
        int width = (int) geometry.cellLeft(col+1)-left, height = (int) geometry.cellTop(row+1)-top;
        frame.getPixels(pixels,0,width,left,top,width,height);
        int ix = width/10, iy = height/10;
        int[] observed = patch(pixels,width,ix,iy,width-ix,height-iy);
        int[] envelope=patch(pixels,width,0,0,width,height);
        if(Match3NativeMath.samePixels(cache.patch,observed) && Match3NativeMath.samePixels(cache.envelope,envelope))return cache.result;
        cache.patch=observed;cache.envelope=envelope;cache.result=classifyObstacle(observed,cache.admission);
        if(cache.result==null)cache.result=classifyEffect(envelope);
        cache.animalChecked=false;cache.animal=null;cache.face=null;cache.body=null;
        cache.inferredChecked=false;cache.inferred=null;cache.iceChecked=false;
        return cache.result;
    }
    /** A positive animal face can survive a decorated lane or an idle outline; hue alone cannot. */
    Match3Position.Cell animal(CellCache cache) {
        if(!cache.animalChecked) {
            if(cache.patch!=null) {
                char color=Match3AnimalAppearance.recognize(face(cache),animalFaces,1);
                String exact=face(cache).detailed?recognize(animals,cache.patch,.12f,.025f):null;
                if(exact!=null) {
                    char ordinary=exact.charAt(7);
                    // An exact certified sprite admits expressions not represented by its centered face.
                    // Conflicting positive identities still abstain; hue is never an identity source.
                    if(Match3Sampler.isMovable(color) && color!=ordinary)color='.';
                    else color=ordinary;
                }
                if(Match3Sampler.isMovable(color)) {
                    int ice=iceLayers(cache);
                    boolean plain=ice!=1 && ordinaryBodies.matches(body(cache),color);
                    if(ice==1)for(int i=0;i<animalBodies.size();i++)if(bodyColors.get(i)==color && matchesIceBody(cache,animalBodies.get(i))) {plain=true;break;}
                    cache.animal=plain?ice==1?Match3Position.Cell.animalOnIce(color):Match3Position.Cell.animal(color)
                            :Match3Position.Cell.animalIdentity(color);
                }
            }
            cache.animalChecked=true;
        }
        return cache.animal;
    }
    int iceLayers(CellCache cache) {
        if(!cache.iceChecked) {
            cache.iceLayers=cache.envelope!=null && "ice1".equals(recognize(iceSurfaces,cache.envelope,
                    Match3AnimalAppearance.MAXIMUM,Match3AnimalAppearance.MARGIN))?1:0;
            cache.iceChecked=true;
        }
        return cache.iceLayers;
    }
    private boolean matchesIceBody(CellCache cache,Match3AnimalAppearance.Body ordinary) {
        for(Pattern background:iceBackgrounds)
            if(body(cache).differenceOnIce(ordinary,background.pixels)<=Match3AnimalAppearance.MAXIMUM)return true;
        return false;
    }
    static Match3AnimalAppearance.Body body(CellCache cache) {
        if(cache.body==null && cache.envelope!=null)cache.body=new Match3AnimalAppearance.Body(cache.envelope);
        if(cache.body==null)throw new IllegalArgumentException("Missing complete tile envelope");
        return cache.body;
    }
    Match3AnimalAppearance.Reference trustedReference(CellCache cache) {
        Match3Position.Cell animal=animal(cache);
        return animal!=null && animal.swappable && animal.iceLayers==0
                ?new Match3AnimalAppearance.Reference(face(cache,animal.color),body(cache)):null;
    }
    static Match3AnimalAppearance.Face face(CellCache cache) {
        if(cache.face==null)cache.face=new Match3AnimalAppearance.Face('.',cache.patch);
        return cache.face;
    }
    static Match3AnimalAppearance.Face face(CellCache cache,char color) {
        if(cache.face==null || cache.face.color!=color)cache.face=new Match3AnimalAppearance.Face(color,cache.patch);
        return cache.face;
    }
    /** Same typed pixel admission used by the bitmap sampler and frozen development patches. */
    Match3Position.Cell classifyObstacle(int[] observed) { return classifyObstacle(observed,null); }
    Match3Position.Cell classifyObstacle(int[] observed, Admission out) {
        String kind = recognize(cells,observed,ADMISSION_MAXIMUM,ADMISSION_MARGIN,out);
        if(kind!=null && kind.startsWith("identity_")) {
            Match3Position.Kind typed=Match3Position.Kind.valueOf(kind.substring(9));
            return Match3Position.Cell.observed(typed,Match3Rules.ruleForKind(typed),-1,-1)
                    .withIce(-1).withCover(Match3Position.Cover.UNKNOWN,-1)
                    .withAccess(Match3Position.Gate.UNKNOWN,false);
        }
        if(kind!=null && kind.startsWith("bound_")) {
            Match3Position.Cell state=null;
            for(Pattern p:cells)if(kind.equals(p.kind)) {
                if(p.boundState==null || state!=null && !state.equals(p.boundState))return null;
                state=p.boundState;
            }
            return state;
        }
        if(("iceflower".equals(kind) || "honey".equals(kind)) && !obstacleIdentity(cells,kind,observed))return null;
        if ("snow1".equals(kind)) return Match3Position.Cell.obstacle(Match3Position.Kind.SNOW,1);
        if ("coin".equals(kind)) return Match3Position.Cell.obstacle(Match3Position.Kind.COIN,1);
        if ("iceflower".equals(kind)) return Match3Position.Cell.obstacle(Match3Position.Kind.ICEFLOWER,1);
        if ("honey".equals(kind)) return Match3Position.Cell.obstacle(Match3Position.Kind.HONEY,1);
        if ("unknown_surface".equals(kind)) return Match3Position.Cell.obstacle(Match3Position.Kind.SURFACE,-1);
        if ("egg".equals(kind)) {
            int green=0;
            for(int y=10;y<PATCH;y++)for(int x=3;x<13;x++) {
                int c=observed[y*PATCH+x],r=c>>16&255,g=c>>8&255,b=c&255;
                if(g>80 && g-r>20 && g-b>20)green++;
            }
            // These supported egg/nest stencils have a visible green base. A
            // similarly brown animal or an opaque cookie is not an egg rule.
            if(green>=6)return Match3Position.Cell.obstacle(Match3Position.Kind.EGG,-1);
        }
        return null;
    }
    Match3Position.Cell classifyEffect(int[] envelope) {
        String kind=recognize(effectEnvelopes,envelope,.12f,.025f);
        Match3Position.Cell state=null;
        if(kind!=null)for(Pattern p:effectEnvelopes)if(kind.equals(p.kind)) {
            if(p.boundState==null || state!=null && !state.equals(p.boundState))return null;
            state=p.boundState;
        }
        return state;
    }
    /** A cyan field or animal must not acquire an obstacle identity from colour proximity alone. */
    static boolean obstacleIdentity(List<Pattern> patterns,String kind,int[] observed) {
        Match3AnimalAppearance.Face shape=new Match3AnimalAppearance.Face('.',observed);
        if(!shape.detailed)return false;
        for(Pattern pattern:patterns)if(kind.equals(pattern.kind) && pattern.identityShape!=null
                && pattern.difference(observed,.145f)<=.12f && shape.difference(pattern.identityShape)<=.12f)return true;
        return false;
    }
}
