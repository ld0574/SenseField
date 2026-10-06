package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import javax.imageio.ImageIO;

/** 桌面 JVM 替身：用 BufferedImage 模拟 Android Bitmap，供离线精度基准使用。 */
public final class Bitmap {
    public enum CompressFormat { PNG, JPEG }

    public final BufferedImage img;

    public Bitmap(BufferedImage img) { this.img = img; }

    public int getWidth() { return img.getWidth(); }

    public int getHeight() { return img.getHeight(); }

    public int getPixel(int x, int y) { return img.getRGB(x, y) | 0xFF000000; }

    /** 与 Android 同名方法一致：第二参 offset（colors 首写索引）、第三参 stride（跨行增量）。
     *  colors[offset + row*stride + col] = 像素(sx+col, sy+row)，行优先。
     *  参数非法时与真机 checkPixelsAccess 同款抛数组越界（offset<0 / stride<0 / 末像素越界）。 */
    public void getPixels(int[] colors, int offset, int stride, int sx, int sy, int w, int h) {
        if (offset < 0) {
            throw new ArrayIndexOutOfBoundsException("offset is negative");
        }
        if (stride < 0) {
            throw new ArrayIndexOutOfBoundsException("stride is negative");
        }
        int lastPixelIdx = offset + (h - 1) * stride + w - 1;
        if (lastPixelIdx >= colors.length) {
            throw new ArrayIndexOutOfBoundsException(
                    "last pixel out of bounds: " + lastPixelIdx + " length: " + colors.length);
        }
        for (int y = sy; y < sy + h; y++) {
            int idx = offset + (y - sy) * stride;
            for (int x = sx; x < sx + w; x++) {
                colors[idx++] = img.getRGB(Math.min(x, img.getWidth() - 1),
                        Math.min(y, img.getHeight() - 1)) | 0xFF000000;
            }
        }
    }

    /** filter=true 走逐级折半盒式缩放，贴近 Skia 大比例缩小时用 mipmap 的行为。 */
    public static Bitmap createScaledBitmap(Bitmap src, int w, int h, boolean filter) {
        BufferedImage out = filter ? scaleArea(src.img, w, h) : scaleNearest(src.img, w, h);
        return new Bitmap(out);
    }

    public static Bitmap createBitmap(Bitmap src, int x, int y, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src.img, -x, -y, null);
        g.dispose();
        return new Bitmap(out);
    }

    public boolean compress(CompressFormat format, int quality, OutputStream os) {
        try {
            ImageIO.write(img, "png", os);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static BufferedImage scaleNearest(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage scaleArea(BufferedImage src, int w, int h) {
        int cw = src.getWidth(), ch = src.getHeight();
        BufferedImage cur = src;
        while (cw / 2 > w && ch / 2 > h) {
            int nw = Math.max(w, cw / 2), nh = Math.max(h, ch / 2);
            BufferedImage next = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cur, 0, 0, nw, nh, null);
            g.dispose();
            cur = next;
            cw = nw;
            ch = nh;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(cur, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
