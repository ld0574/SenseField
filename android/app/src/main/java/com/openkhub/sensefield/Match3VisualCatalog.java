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
        Match3Position.Cell result, animal;
        boolean animalChecked;
    }
    static final class Pattern {
        final String kind;
        final int[] pixels;
        final boolean[] mask;
        final int supported;
        Pattern(JSONObject json) throws Exception {
            kind = json.getString("kind");
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
    final List<Glyph> glyphs, checkmarks, levelSuffixes;
    private Match3VisualCatalog() {
        available = false; id = "unavailable";
        steps = goals = cells = animals = Collections.emptyList(); glyphs = checkmarks = levelSuffixes = Collections.emptyList();
    }
    private Match3VisualCatalog(JSONObject json) throws Exception {
        if (!"match3-fixed-ui-v1".equals(json.getString("format"))) throw new IllegalArgumentException("Catalog format");
        id = json.getString("id"); steps = patterns(json.getJSONArray("steps"));
        goals = patterns(json.getJSONArray("goals"));
        List<Pattern> objects=new ArrayList<>(), faces=new ArrayList<>();
        for(Pattern pattern:patterns(json.getJSONArray("cells")))
            (pattern.kind.startsWith("animal_")?faces:objects).add(pattern);
        cells=Collections.unmodifiableList(objects);animals=Collections.unmodifiableList(faces);
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
                while ((n = input.read(buffer)) > 0) { if (output.size() + n > 262144) throw new IllegalArgumentException("Catalog size"); output.write(buffer,0,n); }
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
        if(java.util.Arrays.equals(cache.patch,observed))return cache.result;
        cache.patch=observed;cache.result=classifyObstacle(observed);cache.animalChecked=false;cache.animal=null;
        return cache.result;
    }
    /** A positive animal face can survive a decorated lane or an idle outline; hue alone cannot. */
    Match3Position.Cell animal(CellCache cache) {
        if(!cache.animalChecked) {
            String kind=cache.patch==null?null:recognize(animals,cache.patch,.12f,.025f);
            if(kind!=null && kind.length()==8 && Match3Sampler.isMovable(kind.charAt(7)))
                cache.animal=Match3Position.Cell.animal(kind.charAt(7));
            cache.animalChecked=true;
        }
        return cache.animal;
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
