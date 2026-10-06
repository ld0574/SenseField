package android.graphics;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 桌面 JVM 替身。 */
public final class BitmapFactory {
    private BitmapFactory() {}

    public static Bitmap decodeFile(String path) {
        try {
            BufferedImage img = ImageIO.read(new File(path));
            if (img == null) return null;
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            return new Bitmap(rgb);
        } catch (Exception e) {
            return null;
        }
    }
}
