package cz.petr.iptv;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

class Channel {
    String name = "", group = "Ostatní", logo = "", tvgId = "", tvgName = "", url = "";
    /** Backup stream URLs of the same channel (x-alt attribute), tried when the main one fails. */
    java.util.List<String> alts = new java.util.ArrayList<>();

    /** The stream URL is the stable identity of a channel (hidden, favourites and order refer to it). */
    String key() {
        return url;
    }
}

class Prog {
    long start, stop;
    String title = "", desc = "";

    static String hm(long t) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t));
    }

    static String dayHm(long t) {
        return new SimpleDateFormat("EEE d.M. HH:mm", Locale.getDefault()).format(new Date(t));
    }
}
