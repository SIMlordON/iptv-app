package cz.petr.iptv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Channels, the user's choices (hidden, favourites, order, renames) and the guide. Everything except
 * loadAll() and the save helpers is meant to be used from the main thread.
 */
class Repo {
    final ExecutorService io = Executors.newSingleThreadExecutor();
    private final File dir;

    List<Channel> all = new ArrayList<>();
    /** Playlist order as downloaded, for "reset my order". */
    List<Channel> original = new ArrayList<>();
    final Set<String> hidden = new HashSet<>();
    final Set<String> favs = new HashSet<>();
    /** Channels whose stream (and backups) failed the last health check. */
    final Set<String> dead = new HashSet<>();
    /** Index (1-based into alts, 0 = main url) of the stream that last worked for a channel. */
    final Map<String, Integer> pick = new HashMap<>();
    final Map<String, String> names = new HashMap<>();
    private List<String> savedOrder = new ArrayList<>();
    /** The user's own channel lists (documentaries, films, sport...): name -> stream URLs. */
    final Map<String, Set<String>> mine = new LinkedHashMap<>();

    Map<String, String> epgMap = new HashMap<>();
    Map<String, List<Prog>> progs = new HashMap<>();
    long epgUpdated, playlistUpdated;

    Repo(Context ctx) {
        dir = ctx.getFilesDir();
    }

    // ---------- loading (background thread) ----------

    void loadAll() {
        try {
            String s = read("channels.json");
            if (s != null) {
                JSONObject o = new JSONObject(s);
                playlistUpdated = o.optLong("updated");
                JSONArray a = o.getJSONArray("ch");
                List<Channel> l = new ArrayList<>(a.length());
                for (int i = 0; i < a.length(); i++) {
                    JSONArray r = a.getJSONArray(i);
                    Channel c = new Channel();
                    c.name = r.getString(0);
                    c.group = r.getString(1);
                    c.logo = r.getString(2);
                    c.tvgId = r.getString(3);
                    c.tvgName = r.getString(4);
                    c.url = r.getString(5);
                    if (r.length() > 6 && !r.getString(6).isEmpty()) {
                        for (String u : r.getString(6).split(" ")) c.alts.add(u);
                    }
                    l.add(c);
                }
                all = l;
            }
        } catch (Exception ignored) {
        }
        try {
            String s = read("state.json");
            if (s != null) {
                JSONObject o = new JSONObject(s);
                fill(hidden, o.optJSONArray("hidden"));
                fill(favs, o.optJSONArray("favs"));
                fill(dead, o.optJSONArray("dead"));
                JSONObject pk = o.optJSONObject("pick");
                if (pk != null) {
                    java.util.Iterator<String> pi = pk.keys();
                    while (pi.hasNext()) {
                        String k = pi.next();
                        pick.put(k, pk.getInt(k));
                    }
                }
                JSONArray ord = o.optJSONArray("order");
                if (ord != null) {
                    for (int i = 0; i < ord.length(); i++) savedOrder.add(ord.getString(i));
                }
                JSONObject ml = o.optJSONObject("mine");
                if (ml != null) {
                    java.util.Iterator<String> mi = ml.keys();
                    while (mi.hasNext()) {
                        String k = mi.next();
                        Set<String> set = new HashSet<>();
                        fill(set, ml.getJSONArray(k));
                        mine.put(k, set);
                    }
                }
                JSONObject n = o.optJSONObject("names");
                if (n != null) {
                    java.util.Iterator<String> it = n.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        names.put(k, n.getString(k));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        original = new ArrayList<>(all);
        sortByOrder();
        try {
            String s = read("epg.json");
            if (s != null) {
                JSONObject o = new JSONObject(s);
                epgUpdated = o.optLong("updated");
                JSONObject m = o.getJSONObject("map");
                Map<String, String> map = new HashMap<>();
                java.util.Iterator<String> it = m.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    map.put(k, m.getString(k));
                }
                JSONObject pr = o.getJSONObject("prog");
                Map<String, List<Prog>> pm = new HashMap<>();
                it = pr.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray a = pr.getJSONArray(k);
                    List<Prog> l = new ArrayList<>(a.length());
                    for (int i = 0; i < a.length(); i++) {
                        JSONArray r = a.getJSONArray(i);
                        Prog p = new Prog();
                        p.start = r.getLong(0);
                        p.stop = r.getLong(1);
                        p.title = r.getString(2);
                        p.desc = r.getString(3);
                        l.add(p);
                    }
                    pm.put(k, l);
                }
                epgMap = map;
                progs = pm;
            }
        } catch (Exception ignored) {
        }
    }

    private static void fill(Set<String> set, JSONArray a) throws Exception {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
    }

    private String read(String f) throws Exception {
        File file = new File(dir, f);
        if (!file.exists()) return null;
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void write(String f, String content) {
        try {
            File tmp = new File(dir, f + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            File dst = new File(dir, f);
            if (!tmp.renameTo(dst)) {
                dst.delete();
                tmp.renameTo(dst);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- playlist ----------

    private void sortByOrder() {
        final Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < savedOrder.size(); i++) idx.put(savedOrder.get(i), i);
        final int big = savedOrder.size() + 1;
        // stable sort: channels unknown to the saved order keep playlist order at the end
        Collections.sort(all, (a, b) -> {
            Integer x = idx.get(a.key());
            Integer y = idx.get(b.key());
            return Integer.compare(x == null ? big : x, y == null ? big : y);
        });
    }

    void setChannels(List<Channel> list) {
        // drop duplicate stream URLs, they share one identity
        LinkedHashMap<String, Channel> uniq = new LinkedHashMap<>();
        for (Channel c : list) if (!uniq.containsKey(c.key())) uniq.put(c.key(), c);
        all = new ArrayList<>(uniq.values());
        original = new ArrayList<>(all);
        playlistUpdated = System.currentTimeMillis();
        sortByOrder();
        saveChannels();
        saveState();
    }

    void saveChannels() {
        try {
            JSONArray a = new JSONArray();
            for (Channel c : original) {
                JSONArray r = new JSONArray();
                r.put(c.name).put(c.group).put(c.logo).put(c.tvgId).put(c.tvgName).put(c.url).put(String.join(" ", c.alts));
                a.put(r);
            }
            JSONObject o = new JSONObject();
            o.put("updated", playlistUpdated);
            o.put("ch", a);
            final String s = o.toString();
            io.execute(() -> write("channels.json", s));
        } catch (Exception ignored) {
        }
    }

    void saveState() {
        final String s = stateJson();
        if (s != null) io.execute(() -> write("state.json", s));
    }

    /** The user's choices as JSON (also the format of the backup file). */
    String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("hidden", new JSONArray(hidden));
            o.put("favs", new JSONArray(favs));
            o.put("dead", new JSONArray(dead));
            o.put("pick", new JSONObject(pick));
            JSONArray ord = new JSONArray();
            savedOrder = new ArrayList<>(all.size());
            for (Channel c : all) {
                ord.put(c.key());
                savedOrder.add(c.key());
            }
            o.put("order", ord);
            o.put("names", new JSONObject(names));
            JSONObject ml = new JSONObject();
            for (Map.Entry<String, Set<String>> e : mine.entrySet()) ml.put(e.getKey(), new JSONArray(e.getValue()));
            o.put("mine", ml);
            return o.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Replaces the user's choices with a backup (call from a background thread, then reload the UI). */
    boolean restoreState(String json) {
        try {
            new JSONObject(json);
        } catch (Exception e) {
            return false;
        }
        write("state.json", json);
        hidden.clear();
        favs.clear();
        dead.clear();
        pick.clear();
        names.clear();
        mine.clear();
        savedOrder = new ArrayList<>();
        List<Channel> keep = original;
        loadStateOnly();
        all = new ArrayList<>(keep);
        sortByOrder();
        return true;
    }

    private void loadStateOnly() {
        List<Channel> a = all;
        List<Channel> o = original;
        loadAll();
        all = a;
        original = o;
    }

    /** Hidden by the user or failed the health check. */
    boolean isOff(Channel c) {
        return hidden.contains(c.key()) || dead.contains(c.key());
    }

    /** Stream URLs to try for a channel, the one that worked last first. */
    List<String> streams(Channel c) {
        List<String> l = new ArrayList<>();
        l.add(c.url);
        l.addAll(c.alts);
        Integer p = pick.get(c.key());
        if (p != null && p > 0 && p < l.size()) l.add(0, l.remove((int) p));
        return l;
    }

    void resetOrder() {
        savedOrder = new ArrayList<>();
        all = new ArrayList<>(original);
        saveState();
    }

    // ---------- user choices ----------

    String name(Channel c) {
        String n = names.get(c.key());
        return n != null && !n.isEmpty() ? n : c.name;
    }

    boolean toggleFav(Channel c) {
        boolean on = favs.add(c.key());
        if (!on) favs.remove(c.key());
        saveState();
        return on;
    }

    void rename(Channel c, String n) {
        if (n == null || n.trim().isEmpty() || n.trim().equals(c.name)) names.remove(c.key());
        else names.put(c.key(), n.trim());
        saveState();
    }

    void hide(Channel c) {
        hidden.add(c.key());
        saveState();
    }

    void unhideAll() {
        hidden.clear();
        saveState();
    }

    /** Swaps two channels in the master order. */
    void swap(Channel a, Channel b) {
        int i = all.indexOf(a);
        int j = all.indexOf(b);
        if (i < 0 || j < 0) return;
        Collections.swap(all, i, j);
        saveState();
    }

    // ---------- my lists ----------

    boolean addList(String name) {
        name = name.trim();
        if (name.isEmpty() || mine.containsKey(name)) return false;
        mine.put(name, new HashSet<String>());
        saveState();
        return true;
    }

    boolean renameList(String old, String name) {
        name = name.trim();
        if (name.isEmpty() || !mine.containsKey(old)) return false;
        if (name.equals(old)) return true;
        if (mine.containsKey(name)) return false;
        LinkedHashMap<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : mine.entrySet()) copy.put(e.getKey().equals(old) ? name : e.getKey(), e.getValue());
        mine.clear();
        mine.putAll(copy);
        saveState();
        return true;
    }

    void deleteList(String name) {
        mine.remove(name);
        saveState();
    }

    boolean inList(String list, String key) {
        Set<String> s = mine.get(list);
        return s != null && s.contains(key);
    }

    void setInList(String list, String key, boolean on) {
        Set<String> s = mine.get(list);
        if (s == null) return;
        if (on) s.add(key);
        else s.remove(key);
        saveState();
    }

    int listCount(String list) {
        int n = 0;
        for (Channel c : all) if (!isOff(c) && inList(list, c.key())) n++;
        return n;
    }

    Map<String, Integer> groupCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Channel c : all) {
            if (isOff(c)) continue;
            Integer n = m.get(c.group);
            m.put(c.group, n == null ? 1 : n + 1);
        }
        return m;
    }

    // ---------- guide ----------

    void setEpg(EpgParser.Result r) {
        epgMap = r.map;
        progs = r.progs;
        epgUpdated = System.currentTimeMillis();
        try {
            JSONObject o = new JSONObject();
            o.put("updated", epgUpdated);
            o.put("map", new JSONObject(epgMap));
            JSONObject pr = new JSONObject();
            for (Map.Entry<String, List<Prog>> e : progs.entrySet()) {
                JSONArray a = new JSONArray();
                for (Prog p : e.getValue()) {
                    JSONArray row = new JSONArray();
                    row.put(p.start).put(p.stop).put(p.title).put(p.desc);
                    a.put(row);
                }
                pr.put(e.getKey(), a);
            }
            o.put("prog", pr);
            final String s = o.toString();
            io.execute(() -> write("epg.json", s));
        } catch (Exception ignored) {
        }
    }

    private List<Prog> list(Channel c) {
        String id = epgMap.get(c.key());
        return id == null ? null : progs.get(id);
    }

    /** All known programmes of a channel sorted by start, or null when the guide has none. */
    List<Prog> programmes(Channel c) {
        return list(c);
    }

    Prog now(Channel c, long t) {
        List<Prog> l = list(c);
        if (l == null) return null;
        for (Prog p : l) if (p.start <= t && t < p.stop) return p;
        return null;
    }

    Prog next(Channel c, long t) {
        List<Prog> l = list(c);
        if (l == null) return null;
        for (Prog p : l) if (p.start > t) return p;
        return null;
    }

    /** Programmes that are running now or start later, at most max of them. */
    List<Prog> upcoming(Channel c, long t, int max) {
        List<Prog> out = new ArrayList<>();
        List<Prog> l = list(c);
        if (l == null) return out;
        for (Prog p : l) {
            if (p.stop > t) {
                out.add(p);
                if (out.size() >= max) break;
            }
        }
        return out;
    }
}
