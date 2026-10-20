package cz.petr.iptv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hands the visible channel list from the list screen to the player, and loads channel logos. */
final class Session {
    static Repo repo;
    static List<Channel> list = new ArrayList<>();
    static int index;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(300);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Session() {
    }

    static void loadLogo(final ImageView iv, final String url) {
        iv.setTag(url);
        iv.setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        Bitmap b = CACHE.get(url);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        POOL.execute(() -> {
            try (InputStream in = Net.open(url)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 3_000_000) out.write(buf, 0, n);
                byte[] data = out.toByteArray();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 160) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bmp == null) return;
                CACHE.put(url, bmp);
                MAIN.post(() -> {
                    if (url.equals(iv.getTag())) iv.setImageBitmap(bmp);
                });
            } catch (Throwable ignored) {
            }
        });
    }
}
