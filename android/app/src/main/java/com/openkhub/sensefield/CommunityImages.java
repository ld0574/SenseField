package com.openkhub.sensefield;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 社区配图的解码与异步加载。
 *
 * <p>刻意不引图片库：只需要「缩略显示」这一件事。一次最多读 8MB、按最长边采样，
 * 免得一张手机随手拍的 12MP 照片直接把内存吃满。
 */
final class CommunityImages {
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private CommunityImages() {
    }

    /** 按最长边缩到 maxPx 以内。读不动就返回 null，界面照常显示占位。 */
    static Bitmap decode(InputStream stream, int maxPx) throws IOException {
        byte[] raw = readAll(stream);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longest > 0 && longest / sample > maxPx * 2 && sample < 32) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
    }

    /**
     * 异步把网络图片放进 ImageView。
     *
     * <p>用 view 的 tag 记住这次要显示的地址，列表复用时旧图不会盖掉新图；
     * 加载失败就不设图，保留 contentDescription，读屏仍能知道这里本该有张图。
     */
    static void loadInto(final ImageView view, final String url, final int maxPx) {
        view.setTag(url);
        view.setImageDrawable(null);
        final Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(15000);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream in = connection.getInputStream()) {
                        bitmap = decode(in, maxPx);
                    }
                }
            } catch (IOException ignored) {
                // 取不到就让它空着，不给用户一个转圈的假象。
            } finally {
                if (connection != null) connection.disconnect();
            }
            final Bitmap loaded = bitmap;
            main.post(() -> {
                if (loaded == null || !url.equals(view.getTag())) return;
                view.setImageBitmap(loaded);
            });
        }, "sensefield-community-image");
        worker.setDaemon(true);
        worker.start();
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, count);
            if (buffer.size() > MAX_BYTES) throw new IOException("图片过大");
        }
        return buffer.toByteArray();
    }
}