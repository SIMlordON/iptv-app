package cz.petr.iptv;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

final class Net {
    static final String UA = "Mozilla/5.0 (Linux; Android 14; Smart TV) IPTVKanaly/1.0";

    private Net() {
    }

    /** ETag or Last-Modified of a URL (HEAD request), empty when the server gives none. */
    static String tag(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url.trim()).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            String t = c.getResponseCode() == 200 ? c.getHeaderField("ETag") : null;
            if (t == null && c.getResponseCode() == 200) t = c.getHeaderField("Last-Modified");
            c.disconnect();
            return t == null ? "" : t;
        } catch (Exception e) {
            return "";
        }
    }

    /** Opens a URL, follows redirects (also http to https) and transparently unzips gzip content. */
    static InputStream open(String url) throws IOException {
        String u = url.trim();
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) break;
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            BufferedInputStream in = new BufferedInputStream(c.getInputStream(), 65536);
            in.mark(4);
            int b1 = in.read();
            int b2 = in.read();
            in.reset();
            if (b1 == 0x1f && b2 == 0x8b) return new GZIPInputStream(in, 65536);
            return in;
        }
        throw new IOException("Příliš mnoho přesměrování");
    }
}
