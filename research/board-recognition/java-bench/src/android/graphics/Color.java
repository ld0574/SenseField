package android.graphics;

/** 桌面 JVM 替身：Android Color 的取通道与 RGB→HSV。hsv 约定 h∈[0,360)、s/v∈[0,1]。 */
public final class Color {
    private Color() {}

    public static int alpha(int p) { return (p >>> 24) & 0xFF; }

    public static int red(int p) { return (p >> 16) & 0xFF; }

    public static int green(int p) { return (p >> 8) & 0xFF; }

    public static int blue(int p) { return p & 0xFF; }

    public static int rgb(int r, int g, int b) {
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static void colorToHSV(int color, float[] hsv) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int delta = max - min;
        hsv[2] = max / 255f;
        hsv[1] = max == 0 ? 0f : delta / (float) max;
        float h;
        if (delta == 0) {
            h = 0f;
        } else if (max == r) {
            h = 60f * ((g - b) / (float) delta);
        } else if (max == g) {
            h = 60f * (2f + (b - r) / (float) delta);
        } else {
            h = 60f * (4f + (r - g) / (float) delta);
        }
        if (h < 0) h += 360f;
        hsv[0] = h;
    }
}
