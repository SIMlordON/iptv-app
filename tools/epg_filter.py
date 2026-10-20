#!/usr/bin/env python3
"""Shrinks a huge XMLTV guide to the channels of a playlist and to the next few days.

    python tools/epg_filter.py app/src/main/assets/verified.m3u docs/epg.xml.gz [source-url]

The source defaults to https://epg.iptvx.one/epg.xml.gz (about 96 MB). The result is a few MB, so the
app can download it quickly: publish it (GitHub Pages or the raw file URL) and enter that address
under Settings > Program (EPG). Channels are matched like the app does: by tvg-id, then by the
normalised channel name.
"""
import gzip
import re
import sys
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

PLAYLIST = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/verified.m3u"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/epg.xml.gz"
SOURCE = sys.argv[3] if len(sys.argv) > 3 else "https://epg.iptvx.one/epg.xml.gz"
HOURS_BACK, DAYS_AHEAD = 3, 3


def norm(s):
    s = unicodedata.normalize("NFD", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    s = re.sub(r"\(.*?\)|\[.*?\]", "", s)
    return re.sub(r"[^a-z0-9]", "", s)


def playlist_keys(path):
    ids, names = set(), set()
    attr = re.compile(r'([A-Za-z0-9_\-]+)="([^"]*)"')
    for line in open(path, encoding="utf8"):
        if not line.startswith("#EXTINF"):
            continue
        a = {k.lower(): v for k, v in attr.findall(line)}
        if a.get("tvg-id"):
            ids.add(a["tvg-id"].lower())
        tail = line[line.rfind('"') + 1:]
        name = tail.split(",", 1)[-1].strip()
        if name:
            names.add(norm(name))
        if a.get("tvg-name"):
            names.add(norm(a["tvg-name"]))
    names.discard("")
    return ids, names


def parse_time(s):
    s = (s or "").strip()
    if len(s) < 12:
        return None
    dt = datetime.strptime(s[:14].ljust(14, "0"), "%Y%m%d%H%M%S")
    rest = s[14:].strip()
    off = timedelta()
    if len(rest) >= 5 and rest[0] in "+-":
        off = timedelta(hours=int(rest[1:3]), minutes=int(rest[3:5]))
        if rest[0] == "-":
            off = -off
    return (dt - off).replace(tzinfo=timezone.utc)


def main():
    ids, names = playlist_keys(PLAYLIST)
    now = datetime.now(timezone.utc)
    lo, hi = now - timedelta(hours=HOURS_BACK), now + timedelta(days=DAYS_AHEAD)
    req = urllib.request.Request(SOURCE, headers={"User-Agent": "Mozilla/5.0 epg_filter"})
    kept = set()
    channels, programmes = [], []
    with urllib.request.urlopen(req, timeout=300) as resp:
        raw = gzip.GzipFile(fileobj=resp) if SOURCE.endswith(".gz") else resp
        for _, el in ET.iterparse(raw, events=("end",)):
            if el.tag == "channel":
                cid = el.get("id", "")
                dn = [d.text or "" for d in el.findall("display-name")]
                if cid.lower() in ids or any(norm(d) in names for d in dn):
                    kept.add(cid)
                    channels.append(ET.tostring(el, encoding="unicode"))
                el.clear()
            elif el.tag == "programme":
                if el.get("channel") in kept:
                    start, stop = parse_time(el.get("start")), parse_time(el.get("stop"))
                    if start and stop and stop > lo and start < hi:
                        programmes.append(ET.tostring(el, encoding="unicode"))
                el.clear()
    with gzip.open(OUT, "wt", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<tv generator-info-name="epg_filter">\n')
        f.write("\n".join(channels) + "\n" + "\n".join(programmes) + "\n</tv>\n")
    print(f"{len(channels)} channels, {len(programmes)} programmes -> {OUT}")


if __name__ == "__main__":
    main()
