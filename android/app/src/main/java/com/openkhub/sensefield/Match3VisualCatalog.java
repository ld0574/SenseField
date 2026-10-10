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
    }
    static final class Pattern {
        final String kind;
        final String rule;
        final int[] pixels;
        final boolean[] mask;
        final int supported;
        Pattern(JSONObject json) throws Exception {
            kind = json.getString("kind");
            rule = json.optString("rule", "unverified");
            JSONArray p = json.getJSONArray("pixels"), m = json.getJSONArray("mask");
            if (p.length() != PATCH * PATCH || m.length() != p.length()) throw new IllegalArgumentException("Template shape");
            pixels = new int[p.length()]; mask = new boolean[p.length()];
            int count = 0;
            for (int i = 0; i < p.length(); i++) { pixels[i] = p.getInt(i); mask[i] = m.getBoolean(i); if (mask[i]) count++; }
            if (count < 24) throw new IllegalArgumentException("Empty template");
            supported=count;
        }
        float difference(int[] observed) {
            return difference(observed,Float.POSITIVE_INFINITY);
        }
        float difference(int[] observed,float ceiling) {
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
    }
    static final class Glyph {
        final int digit;
        final float aspect;
        final String bits;
        Glyph(JSONObject json) throws Exception {
            digit = json.getInt("digit"); aspect = (float) json.getDouble("aspect"); bits = json.getString("bits");
            if (digit < 0 || digit > 9 || aspect <= 0 || bits.length() != 160 || !bits.matches("[01]+"))
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
    final List<Pattern> steps, goals, cells, animals;
    final List<Match3AnimalAppearance.Face> animalFaces;
    final List<Match3AnimalAppearance.Body> animalBodies;
    final List<Character> bodyColors;
    final List<Glyph> glyphs, checkmarks, levelSuffixes;
    private Match3VisualCatalog() {
        available = false; id = "unavailable";
        steps = goals = cells = animals = Collections.emptyList(); glyphs = checkmarks = levelSuffixes = Collections.emptyList();
        animalFaces=Collections.emptyList();
        animalBodies=Collections.emptyList();
        bodyColors=Collections.emptyList();
    }
    private Match3VisualCatalog(JSONObject json) throws Exception {
        if (!"match3-fixed-ui-v1".equals(json.getString("format"))) throw new IllegalArgumentException("Catalog format");
        id = json.getString("id"); steps = patterns(json.getJSONArray("steps"));
        goals = patterns(json.getJSONArray("goals"));
        List<Pattern> objects=new ArrayList<>(), faces=new ArrayList<>();
        List<Match3AnimalAppearance.Face> descriptors=new ArrayList<>();
        for(Pattern pattern:patterns(json.getJSONArray("cells"))) {
            if(pattern.kind.startsWith("animal_") && pattern.kind.length()==8
                    && "ordinary_uncovered_animal".equals(pattern.rule)) {
                Match3AnimalAppearance.Face face=new Match3AnimalAppearance.Face(pattern.kind.charAt(7),pattern.pixels);
                if(face.detailed) { faces.add(pattern);descriptors.add(face); }
            } else if(!pattern.kind.startsWith("animal_"))objects.add(pattern);
        }
        cells=Collections.unmodifiableList(objects);animals=Collections.unmodifiableList(faces);
        animalFaces=Collections.unmodifiableList(descriptors);
        List<Match3AnimalAppearance.Body> bodies=new ArrayList<>();List<Character> colors=new ArrayList<>();
        JSONArray envelopes=json.getJSONArray("ordinary_envelopes");
        for(int i=0;i<envelopes.length();i++) {
            JSONObject item=envelopes.getJSONObject(i);
            if(!"ordinary_uncovered_animal".equals(item.optString("rule")))continue;
            char color=item.getString("color").charAt(0);if(!Match3Sampler.isMovable(color))throw new IllegalArgumentException("Animal envelope");
            JSONArray values=item.getJSONArray("pixels");if(values.length()!=256)throw new IllegalArgumentException("Envelope shape");
            int[] patch=new int[256];for(int p=0;p<256;p++)patch[p]=values.getInt(p);
            bodies.add(new Match3AnimalAppearance.Body(patch));colors.add(color);
        }
        animalBodies=Collections.unmodifiableList(bodies);bodyColors=Collections.unmodifiableList(colors);
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
        return Collections.unmodifiableList(patterns);
    }
    static Match3VisualCatalog get(Context context) {
        Match3VisualCatalog catalog = loaded;
        if (catalog != null) return catalog;
        synchronized (Match3VisualCatalog.class) {
            if (loaded == null) try (java.io.InputStream input = context.getAssets().open("match3-fixed-ui-v1.json")) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) > 0) { if (output.size() + n > 327680) throw new IllegalArgumentException("Catalog size"); output.write(buffer,0,n); }
                loaded = new Match3VisualCatalog(new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8)));
            } catch (Exception ignored) { loaded = new Match3VisualCatalog(); }
            return loaded;
        }
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
    static String recognize(List<Pattern> patterns, int[] patch, float maximum, float margin) {
        String best = null; float error = Float.POSITIVE_INFINITY, second = Float.POSITIVE_INFINITY;
        for (Pattern pattern : patterns) {
            // A partial sum above maximum+margin cannot win or make an
            // accepted winner ambiguous. Stop only those comparisons; retain
            // the exact scores and original thresholds for every contender.
            float candidate = pattern.difference(patch,maximum+margin);
            if (candidate < error) {
                if (!pattern.kind.equals(best)) second = error;
                best = pattern.kind; error = candidate;
            } else if (!pattern.kind.equals(best)) second = Math.min(second,candidate);
        }
        return error <= maximum && second-error >= margin ? best : null;
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
        if(java.util.Arrays.equals(cache.patch,observed) && java.util.Arrays.equals(cache.envelope,envelope))return cache.result;
        cache.patch=observed;cache.envelope=envelope;cache.result=classifyObstacle(observed);cache.animalChecked=false;cache.animal=null;cache.face=null;cache.body=null;
        cache.inferredChecked=false;cache.inferred=null;
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
                    boolean plain=false;
                    for(int i=0;i<animalBodies.size();i++)if(bodyColors.get(i)==color
                            && body(cache).difference(animalBodies.get(i))<=Match3AnimalAppearance.MAXIMUM) {
                        plain=true;break;
                    }
                    cache.animal=plain?Match3Position.Cell.animal(color):Match3Position.Cell.animalIdentity(color);
                }
            }
            cache.animalChecked=true;
        }
        return cache.animal;
    }
    static Match3AnimalAppearance.Body body(CellCache cache) {
        if(cache.body==null && cache.envelope!=null)cache.body=new Match3AnimalAppearance.Body(cache.envelope);
        if(cache.body==null)throw new IllegalArgumentException("Missing complete tile envelope");
        return cache.body;
    }
    Match3AnimalAppearance.Reference trustedReference(CellCache cache) {
        Match3Position.Cell animal=animal(cache);
        return animal!=null && animal.swappable
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
    private Match3Position.Cell classifyObstacle(int[] observed) {
        String kind = recognize(cells,observed,.12f,.025f);
        if ("snow1".equals(kind)) return Match3Position.Cell.obstacle(Match3Position.Kind.SNOW,1);
        if ("coin".equals(kind)) return Match3Position.Cell.obstacle(Match3Position.Kind.COIN,1);
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
}
