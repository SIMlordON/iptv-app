package cz.petr.iptv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks whether channel streams still answer, so dead ones can be hidden. */
final class Health {
    interface Progress {
        void update(int done, int total);
    }

    private Health() {
    }

    private static byte[] get(String url, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", Net.UA);
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while (out.size() < max && (n = in.read(buf, 0, Math.min(buf.length, max - out.size()))) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static boolean alive(String url) {
        try {
            byte[] d = get(url, 4096);
            if (d == null || d.length == 0 || d[0] == '<') return false;
            String s = new String(d, "UTF-8");
            if (s.startsWith("#EXTM3U")) {
                for (String ln : s.split("\\r?\\n")) {
                    ln = ln.trim();
                    if (ln.isEmpty() || ln.startsWith("#")) continue;
                    byte[] d2 = get(new URL(new URL(url), ln).toString(), 2000);
                    return d2 != null && d2.length > 0 && d2[0] != '<';
                }
                return false;
            }
            return d.length >= 1000; // raw TS / progressive stream
        } catch (Throwable t) {
            return false;
        }
    }

    /** Returns the keys of channels where neither the main stream nor any backup works. Blocks until done. */
    static Set<String> findDead(List<Channel> channels, final Progress progress) throws InterruptedException {
        final Set<String> dead = Collections.synchronizedSet(new HashSet<String>());
        final AtomicInteger done = new AtomicInteger();
        final int total = channels.size();
        ExecutorService ex = Executors.newFixedThreadPool(24);
        for (final Channel c : new ArrayList<>(channels)) {
            ex.execute(() -> {
                boolean ok = alive(c.url);
                for (int i = 0; !ok && i < c.alts.size(); i++) ok = alive(c.alts.get(i));
                if (!ok) dead.add(c.key());
                int d = done.incrementAndGet();
                if (progress != null && d % 20 == 0) progress.update(d, total);
            });
        }
        ex.shutdown();
        ex.awaitTermination(20, TimeUnit.MINUTES);
        return dead;
    }
}
