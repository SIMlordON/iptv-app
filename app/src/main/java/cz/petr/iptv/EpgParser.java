package cz.petr.iptv;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Streaming XMLTV reader. Keeps only programmes of channels that exist in the playlist and only the
 * window from a few hours back to three days ahead, so a huge guide file never fills the TV's memory.
 */
final class EpgParser {
    static final class Result {
        /** playlist channel key (stream url) -> XMLTV channel id */
        final Map<String, String> map = new HashMap<>();
        /** XMLTV channel id -> programmes sorted by start */
        final Map<String, List<Prog>> progs = new HashMap<>();
    }

    private EpgParser() {
    }

    static String norm(String s) {
        if (s == null) return "";
        // "CNN Prima News (1080p)" and "Nova Sport 1 [CZ]" match the plain names in the guide
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replaceAll("\\(.*?\\)|\\[.*?\\]", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    static Result parse(InputStream in, List<Channel> channels, long now) throws Exception {
        long from = now - 3L * 3600_000L;
        long to = now + 3L * 24 * 3600_000L;

        Map<String, List<Channel>> byTvgId = new HashMap<>();
        Map<String, List<Channel>> byName = new HashMap<>();
        for (Channel c : channels) {
            if (!c.tvgId.isEmpty()) byTvgId.computeIfAbsent(c.tvgId.toLowerCase(), k -> new ArrayList<>()).add(c);
            String n1 = norm(c.name);
            if (!n1.isEmpty()) byName.computeIfAbsent(n1, k -> new ArrayList<>()).add(c);
            String n2 = norm(c.tvgName);
            if (!n2.isEmpty() && !n2.equals(n1)) byName.computeIfAbsent(n2, k -> new ArrayList<>()).add(c);
        }

        Map<String, List<String>> xmlNames = new HashMap<>(); // xmltv id -> display names
        Set<String> wanted = new HashSet<>();
        boolean resolved = false;
        Result res = new Result();

        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(in, null);
        String curChannel = null;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if (tag.equals("channel")) {
                    curChannel = p.getAttributeValue(null, "id");
                    if (curChannel != null) xmlNames.put(curChannel, new ArrayList<>());
                } else if (tag.equals("display-name") && curChannel != null) {
                    String t = p.nextText();
                    List<String> l = xmlNames.get(curChannel);
                    if (l != null) l.add(t);
                } else if (tag.equals("programme")) {
                    if (!resolved) {
                        resolved = true;
                        resolve(xmlNames, byTvgId, byName, wanted, res.map);
                    }
                    String id = p.getAttributeValue(null, "channel");
                    long start = parseTime(p.getAttributeValue(null, "start"));
                    long stop = parseTime(p.getAttributeValue(null, "stop"));
                    boolean keep = id != null && wanted.contains(id) && stop > from && start < to;
                    String title = "", desc = "";
                    int depth = p.getDepth();
                    while (true) {
                        ev = p.next();
                        if (ev == XmlPullParser.END_TAG && p.getDepth() == depth) break;
                        if (ev == XmlPullParser.END_DOCUMENT) break;
                        if (ev == XmlPullParser.START_TAG) {
                            String t = p.getName();
                            if (t.equals("title") && title.isEmpty()) title = p.nextText();
                            else if (t.equals("desc") && desc.isEmpty()) desc = p.nextText();
                        }
                    }
                    if (keep) {
                        Prog pr = new Prog();
                        pr.start = start;
                        pr.stop = stop;
                        pr.title = title.trim();
                        pr.desc = desc.length() > 400 ? desc.substring(0, 400).trim() + "…" : desc.trim();
                        res.progs.computeIfAbsent(id, k -> new ArrayList<>()).add(pr);
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.getName().equals("channel")) {
                curChannel = null;
            }
            ev = p.next();
        }
        if (!resolved) resolve(xmlNames, byTvgId, byName, wanted, res.map);
        for (List<Prog> l : res.progs.values()) {
            Collections.sort(l, new Comparator<Prog>() {
                @Override
                public int compare(Prog a, Prog b) {
                    return Long.compare(a.start, b.start);
                }
            });
        }
        return res;
    }

    private static void resolve(Map<String, List<String>> xmlNames, Map<String, List<Channel>> byTvgId,
                                Map<String, List<Channel>> byName, Set<String> wanted, Map<String, String> map) {
        // 1) playlist channels with a tvg-id that exists in the guide
        for (String xid : xmlNames.keySet()) {
            List<Channel> l = byTvgId.get(xid.toLowerCase());
            if (l != null) {
                wanted.add(xid);
                for (Channel c : l) map.put(c.key(), xid);
            }
        }
        // tvg-ids that are not declared as <channel> still match programmes directly
        for (Map.Entry<String, List<Channel>> e : byTvgId.entrySet()) {
            boolean declared = false;
            for (String xid : xmlNames.keySet()) {
                if (xid.equalsIgnoreCase(e.getKey())) {
                    declared = true;
                    break;
                }
            }
            if (!declared && xmlNames.isEmpty()) {
                wanted.add(e.getKey());
                for (Channel c : e.getValue()) map.put(c.key(), e.getKey());
            }
        }
        // 2) the rest by (normalised) channel name
        for (Map.Entry<String, List<String>> e : xmlNames.entrySet()) {
            for (String dn : e.getValue()) {
                List<Channel> l = byName.get(norm(dn));
                if (l == null) continue;
                boolean any = false;
                for (Channel c : l) {
                    if (!map.containsKey(c.key())) {
                        map.put(c.key(), e.getKey());
                        any = true;
                    }
                }
                if (any) wanted.add(e.getKey());
            }
        }
    }

    /** Parses an XMLTV timestamp such as 20261020183000 +0200 (no zone means UTC). */
    static long parseTime(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.length() < 12) return 0;
        try {
            int y = Integer.parseInt(s.substring(0, 4));
            int mo = Integer.parseInt(s.substring(4, 6));
            int d = Integer.parseInt(s.substring(6, 8));
            int h = Integer.parseInt(s.substring(8, 10));
            int mi = Integer.parseInt(s.substring(10, 12));
            int sec = s.length() >= 14 ? Integer.parseInt(s.substring(12, 14)) : 0;
            long offset = 0;
            String rest = s.length() > 14 ? s.substring(14).trim() : "";
            if (rest.length() >= 5 && (rest.charAt(0) == '+' || rest.charAt(0) == '-')) {
                int oh = Integer.parseInt(rest.substring(1, 3));
                int om = Integer.parseInt(rest.substring(3, 5));
                offset = (oh * 60L + om) * 60_000L;
                if (rest.charAt(0) == '-') offset = -offset;
            }
            GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(y, mo - 1, d, h, mi, sec);
            return c.getTimeInMillis() - offset;
        } catch (Exception e) {
            return 0;
        }
    }
}
