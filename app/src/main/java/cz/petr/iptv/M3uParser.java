package cz.petr.iptv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class M3uParser {
    static final class Result {
        final List<Channel> channels = new ArrayList<>();
        String epgUrl = "";
    }

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_\\-]+)=\"([^\"]*)\"");

    private M3uParser() {
    }

    static Result parse(InputStream in) throws IOException {
        Result r = new Result();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"), 65536);
        Channel cur = null;
        String extGroup = null;
        String line;
        boolean first = true;
        while ((line = br.readLine()) != null) {
            if (first) {
                first = false;
                if (line.startsWith("﻿")) line = line.substring(1);
            }
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTM3U")) {
                Matcher m = ATTR.matcher(line);
                while (m.find()) {
                    String k = m.group(1).toLowerCase();
                    if (k.equals("url-tvg") || k.equals("x-tvg-url") || k.equals("tvg-url")) {
                        String v = m.group(2).trim();
                        int comma = v.indexOf(',');
                        if (comma > 0) v = v.substring(0, comma).trim();
                        if (r.epgUrl.isEmpty()) r.epgUrl = v;
                    }
                }
            } else if (line.startsWith("#EXTINF")) {
                cur = new Channel();
                Matcher m = ATTR.matcher(line);
                while (m.find()) {
                    String k = m.group(1).toLowerCase();
                    String v = m.group(2).trim();
                    switch (k) {
                        case "tvg-id": cur.tvgId = v; break;
                        case "tvg-name": cur.tvgName = v; break;
                        case "tvg-logo": cur.logo = v; break;
                        case "x-alt":
                            for (String a : v.split("\s+")) if (a.startsWith("http")) cur.alts.add(a);
                            break;
                        case "group-title": if (!v.isEmpty()) cur.group = v; break;
                        default: break;
                    }
                }
                int q = line.lastIndexOf('"');
                int comma = line.indexOf(',', Math.max(q, 0));
                if (comma >= 0) cur.name = line.substring(comma + 1).trim();
                if (cur.name.isEmpty()) cur.name = cur.tvgName;
                extGroup = null;
            } else if (line.startsWith("#EXTGRP:")) {
                extGroup = line.substring(8).trim();
            } else if (line.startsWith("#")) {
                // other directives (#EXTVLCOPT, #KODIPROP, ...) are ignored
            } else {
                if (cur == null) {
                    cur = new Channel();
                    cur.name = line;
                }
                if (extGroup != null && !extGroup.isEmpty() && cur.group.equals("Ostatní")) cur.group = extGroup;
                if (cur.name.isEmpty()) cur.name = line;
                cur.url = line;
                r.channels.add(cur);
                cur = null;
                extGroup = null;
            }
        }
        return r;
    }
}
