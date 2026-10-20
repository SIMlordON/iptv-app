package cz.petr.iptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private Repo repo;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private RecyclerView rv;
    private Adapter adapter;
    private TextView status, nowTitle, nowDesc;
    private final List<Channel> visible = new ArrayList<>();

    private LinearLayout side;
    private final List<View> topButtons = new ArrayList<>();
    private int lastPos;
    private long lastBack;
    private String mode = "all"; // all | fav | group | list
    private String group = null;
    private String query = "";
    private int moving = -1;
    private boolean loaded, busy, scrolledToLast, returning, checking;
    private final Runnable reminderTick = new Runnable() {
        @Override
        public void run() {
            Reminders.checkDue(MainActivity.this, key -> playKey(key));
            ui.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("iptv", Context.MODE_PRIVATE);
        repo = new Repo(this);
        Session.repo = repo;
        buildUi();
        status.setText("Načítám…");
        repo.io.execute(() -> {
            repo.loadAll();
            ui.post(() -> {
                loaded = true;
                applyFilter();
                handleIntent(getIntent());
                String url = activeUrl();
                if (url.isEmpty()) showLists();
                else if (url.startsWith("asset:") && appUpdated() != prefs.getLong("assetv", 0)) {
                    // the bundled list may have changed with the app update
                    prefs.edit().putLong("assetv", appUpdated()).apply();
                    refresh();
                } else if (repo.all.isEmpty() || !url.equals(prefs.getString("loaded", ""))
                        || System.currentTimeMillis() - repo.playlistUpdated > 12L * 3600_000L)
                    refresh();
                else if (System.currentTimeMillis() - prefs.getLong("checked", 0) > 7L * 24 * 3600_000L) checkChannels(true);
            });
        });
    }

    private long appUpdated() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (loaded) handleIntent(i);
    }

    /** `am start -n cz.petr.iptv/.MainActivity --es m3u URL --es epg URL` sets the addresses and reloads. */
    private void handleIntent(Intent i) {
        if (i == null) return;
        String pk = i.getStringExtra("play");
        if (pk != null) {
            i.removeExtra("play");
            playKey(pk);
        }
        String m = i.getStringExtra("m3u");
        String e = i.getStringExtra("epg");
        if (e != null) prefs.edit().putString("epg", e.trim()).apply();
        if (m != null && !m.trim().isEmpty()) {
            m = m.trim();
            List<String[]> l = lists();
            int at = -1;
            for (int k = 0; k < l.size(); k++) if (l.get(k)[1].equals(m)) at = k;
            if (at < 0) {
                l.add(new String[]{"Vlastní", m});
                at = l.size() - 1;
            }
            saveLists(l, at);
            i.removeExtra("m3u");
            refresh();
        }
    }

    // ---------- playlists ----------

    /** Merged, de-duplicated and stream-checked list shipped inside the app (see assets/verified.m3u). */
    private static final String VERIFIED = "asset:verified.m3u";
    /** Used when the playlist itself names no guide. Covers Czech, Slovak and many other channels. */
    private static final String DEFAULT_EPG = "https://epg.iptvx.one/epg.xml.gz";

    private static final String[][] DEFAULTS = {
            {"★ Vše ověřené (sloučeno, bez duplicit)", VERIFIED},
            {"Česko","https://iptv-org.github.io/iptv/countries/cz.m3u"},
            {"Slovensko", "https://iptv-org.github.io/iptv/countries/sk.m3u"},
            {"Čeština (jazyk)", "https://iptv-org.github.io/iptv/languages/ces.m3u"},
            {"Slovenština (jazyk)", "https://iptv-org.github.io/iptv/languages/slk.m3u"},
            {"Sport", "https://iptv-org.github.io/iptv/categories/sports.m3u"},
            {"Filmy", "https://iptv-org.github.io/iptv/categories/movies.m3u"},
            {"Dokumenty", "https://iptv-org.github.io/iptv/categories/documentary.m3u"},
            {"Zprávy", "https://iptv-org.github.io/iptv/categories/news.m3u"},
            {"Děti", "https://iptv-org.github.io/iptv/categories/kids.m3u"},
            {"Hudba", "https://iptv-org.github.io/iptv/categories/music.m3u"},
            {"Věda", "https://iptv-org.github.io/iptv/categories/science.m3u"},
            {"Auta", "https://iptv-org.github.io/iptv/categories/auto.m3u"},
            {"Španělsko (TDT Channels)", "https://www.tdtchannels.com/lists/tv.m3u8"},
            {"Španělsko", "https://iptv-org.github.io/iptv/countries/es.m3u"},
            {"Francie", "https://iptv-org.github.io/iptv/countries/fr.m3u"},
            {"Velká Británie", "https://iptv-org.github.io/iptv/countries/uk.m3u"},
            {"Německo", "https://iptv-org.github.io/iptv/countries/de.m3u"},
            {"Polsko", "https://iptv-org.github.io/iptv/countries/pl.m3u"},
            {"Free-TV (ověřené kanály)", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"},
            {"Roku (zdarma)", "https://www.apsattv.com/rok.m3u"},
            {"Svět – všechny kanály", "https://iptv-org.github.io/iptv/index.m3u"},
            {"Svět – podle jazyků", "https://iptv-org.github.io/iptv/index.language.m3u"},
            {"Svět – podle zemí", "https://iptv-org.github.io/iptv/index.country.m3u"},
    };
    private static final int DEFAULTS_VERSION = 3;

    /** Playlists are stored as JSON [{n:name,u:url}]; defaults are seeded on first run and new ones are appended after an update. */
    private List<String[]> lists() {
        List<String[]> out = new ArrayList<>();
        boolean seeded = false;
        try {
            String js = prefs.getString("lists", null);
            if (js != null) {
                JSONArray a = new JSONArray(js);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    out.add(new String[]{o.getString("n"), o.getString("u")});
                }
                seeded = true;
            }
        } catch (Exception ignored) {
        }
        if (!seeded) {
            String old = prefs.getString("m3u", "");
            if (!old.isEmpty()) out.add(new String[]{"Vlastní", old});
        }
        if (!seeded || prefs.getInt("defv", 0) < DEFAULTS_VERSION) {
            for (String[] d : DEFAULTS) {
                boolean has = false;
                for (String[] e : out) if (e[1].equals(d[1])) has = true;
                if (!has) out.add(d);
            }
            int active = prefs.getInt("active", 0);
            if (prefs.getInt("defv", 0) < 3) {
                // version 3 introduces the bundled verified list: make it the one in use
                for (int i = 0; i < out.size(); i++) if (out.get(i)[1].equals(VERIFIED)) active = i;
            }
            saveLists(out, active);
            prefs.edit().putInt("defv", DEFAULTS_VERSION).apply();
        }
        return out;
    }

    private void saveLists(List<String[]> l, int active) {
        try {
            JSONArray a = new JSONArray();
            for (String[] e : l) a.put(new JSONObject().put("n", e[0]).put("u", e[1]));
            prefs.edit().putString("lists", a.toString()).putInt("active", Math.max(0, Math.min(active, Math.max(0, l.size() - 1)))).apply();
        } catch (Exception ignored) {
        }
    }

    private int activeIndex() {
        return prefs.getInt("active", 0);
    }

    private String activeUrl() {
        List<String[]> l = lists();
        int i = activeIndex();
        return i >= 0 && i < l.size() ? l.get(i)[1] : "";
    }

    private String activeName() {
        List<String[]> l = lists();
        int i = activeIndex();
        return i >= 0 && i < l.size() ? l.get(i)[0] : "";
    }

    private void showLists() {
        final List<String[]> l = lists();
        final int act = activeIndex();
        String[] items = new String[l.size() + 2];
        for (int i = 0; i < l.size(); i++) items[i] = (i == act ? "✓ " : "") + l.get(i)[0];
        items[l.size()] = "＋ Přidat zdroj…";
        items[l.size() + 1] = "✎ Upravit nebo smazat zdroj…";
        new AlertDialog.Builder(this).setTitle("Zdroje kanálů (M3U)").setItems(items, (d, which) -> {
            if (which < l.size()) {
                if (which == act && !repo.all.isEmpty()) return;
                saveLists(l, which);
                mode = "all";
                query = "";
                refresh();
            } else if (which == l.size()) {
                editList(-1);
            } else {
                String[] names = new String[l.size()];
                for (int i = 0; i < names.length; i++) names[i] = l.get(i)[0];
                new AlertDialog.Builder(this).setTitle("Který zdroj?").setItems(names, (d2, w2) -> editList(w2)).show();
            }
        }).show();
    }

    /** index -1 adds a new playlist. */
    private void editList(final int index) {
        final List<String[]> l = lists();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        final EditText n = new EditText(this);
        n.setSingleLine(true);
        n.setHint("Název");
        final EditText u = new EditText(this);
        u.setSingleLine(true);
        u.setHint("Adresa M3U (https://…)");
        u.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (index >= 0) {
            n.setText(l.get(index)[0]);
            u.setText(l.get(index)[1]);
        }
        box.addView(n, new LinearLayout.LayoutParams(-1, -2));
        box.addView(u, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(index < 0 ? "Nový zdroj" : "Upravit zdroj").setView(box)
                .setPositiveButton("Uložit", (d, w) -> {
                    String url = u.getText().toString().trim();
                    if (url.isEmpty()) return;
                    String name = n.getText().toString().trim();
                    if (name.isEmpty()) name = "Seznam " + (l.size() + 1);
                    int act = activeIndex();
                    if (index < 0) {
                        l.add(new String[]{name, url});
                        saveLists(l, l.size() - 1);
                        refresh();
                    } else {
                        l.set(index, new String[]{name, url});
                        saveLists(l, act);
                        if (index == act) refresh();
                    }
                })
                .setNegativeButton("Zrušit", null);
        if (index >= 0 && l.size() > 1) {
            b.setNeutralButton("Smazat", (d, w) -> {
                int act = activeIndex();
                l.remove(index);
                int na = act > index ? act - 1 : act;
                saveLists(l, na);
                if (act == index) refresh();
            });
        }
        b.show();
    }

    // ---------- UI ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView tv(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = tv(18, Color.WHITE);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setBackgroundResource(R.drawable.btn_bg);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1116);
        root.setPadding(dp(48), dp(24), dp(48), dp(20));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tv(28, Color.WHITE);
        title.setText("IPTV");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title);
        status = tv(16, 0xFF9AA7B8);
        status.setPadding(dp(20), 0, dp(10), 0);
        status.setSingleLine(true);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        topButtons.add(addTop(top, "Přehled", v -> openGuide()));
        topButtons.add(addTop(top, "Hledat", v -> showSearch()));
        topButtons.add(addTop(top, "Zdroje", v -> showLists()));
        topButtons.add(addTop(top, "Nastavení", v -> showSettings()));
        root.addView(top);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setFillViewport(false);
        sideScroll.setVerticalScrollBarEnabled(false);
        side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        sideScroll.addView(side, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(250), -1);
        slp.rightMargin = dp(16);
        body.addView(sideScroll, slp);

        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        adapter = new Adapter();
        rv.setAdapter(adapter);
        body.addView(rv, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(14);
        blp.bottomMargin = dp(10);
        root.addView(body, blp);

        nowTitle = tv(20, Color.WHITE);
        nowTitle.setTypeface(Typeface.DEFAULT_BOLD);
        nowTitle.setSingleLine(true);
        nowDesc = tv(16, 0xFFB5C0CF);
        nowDesc.setMaxLines(3);
        nowDesc.setMinLines(3);
        root.addView(nowTitle);
        root.addView(nowDesc);
        setContentView(root);
        buildSide();
    }

    private TextView addTop(LinearLayout top, String label, View.OnClickListener l) {
        TextView t = button(label, l);
        top.addView(t);
        return t;
    }

    // ---------- left menu ----------

    private TextView sideItem(String label, boolean active, View.OnClickListener click, View.OnLongClickListener longClick) {
        TextView t = tv(20, active ? 0xFFFFC94D : Color.WHITE);
        t.setText((active ? "▶ " : "") + label);
        t.setSingleLine(true);
        t.setPadding(dp(16), dp(10), dp(12), dp(10));
        t.setBackgroundResource(R.drawable.row_bg);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        if (longClick != null) t.setOnLongClickListener(longClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView sideHeader(String label) {
        TextView t = tv(14, 0xFF7F8DA3);
        t.setText(label);
        t.setPadding(dp(16), dp(12), dp(8), dp(4));
        return t;
    }

    private void buildSide() {
        side.removeAllViews();
        int total = 0, favCount = 0;
        for (Channel c : repo.all) {
            if (repo.isOff(c)) continue;
            total++;
            if (repo.favs.contains(c.key())) favCount++;
        }
        side.addView(sideItem("Všechny kanály (" + total + ")", mode.equals("all"), v -> setMode("all", null), null));
        side.addView(sideItem("★ Oblíbené (" + favCount + ")", mode.equals("fav"), v -> setMode("fav", null), null));
        side.addView(sideHeader("MOJE SEZNAMY"));
        for (final String name : new ArrayList<>(repo.mine.keySet())) {
            side.addView(sideItem(name + " (" + repo.listCount(name) + ")", mode.equals("list") && name.equals(group),
                    v -> setMode("list", name), v -> {
                        manageList(name);
                        return true;
                    }));
        }
        side.addView(sideItem("＋ Nový seznam", false, v -> newList(null), null));
        side.addView(sideHeader("DALŠÍ"));
        side.addView(sideItem("Skupiny ze zdroje…", mode.equals("group"), v -> showGroups(), null));
    }

    private void setMode(String m, String g) {
        mode = m;
        group = g;
        query = "";
        applyFilter();
    }

    private void newList(final Channel addTo) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("Např. Dokumenty, Filmy, Sport");
        new AlertDialog.Builder(this).setTitle("Nový seznam").setView(pad(et))
                .setPositiveButton("Vytvořit", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!repo.addList(n)) {
                        Toast.makeText(this, "Seznam s tímto názvem už existuje nebo je název prázdný", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (addTo != null) repo.setInList(n, addTo.key(), true);
                    buildSide();
                    if (addTo != null) Toast.makeText(this, "Přidáno do „" + n + "“", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Zrušit", null).show();
    }

    private void manageList(final String name) {
        String[] items = {"Otevřít", "Přejmenovat", "Smazat seznam"};
        new AlertDialog.Builder(this).setTitle(name).setItems(items, (d, which) -> {
            if (which == 0) {
                setMode("list", name);
            } else if (which == 1) {
                final EditText et = new EditText(this);
                et.setSingleLine(true);
                et.setText(name);
                et.setSelectAllOnFocus(true);
                new AlertDialog.Builder(this).setTitle("Přejmenovat seznam").setView(pad(et))
                        .setPositiveButton("Uložit", (d2, w) -> {
                            if (!repo.renameList(name, et.getText().toString())) {
                                Toast.makeText(this, "Název je prázdný nebo už existuje", Toast.LENGTH_LONG).show();
                                return;
                            }
                            if (mode.equals("list") && name.equals(group)) group = et.getText().toString().trim();
                            buildSide();
                            updateStatus();
                        })
                        .setNegativeButton("Zrušit", null).show();
            } else {
                new AlertDialog.Builder(this).setTitle("Smazat seznam „" + name + "“?")
                        .setMessage("Kanály zůstanou ve zdroji, smaže se jen tento výběr.")
                        .setPositiveButton("Smazat", (d2, w) -> {
                            repo.deleteList(name);
                            if (mode.equals("list") && name.equals(group)) setMode("all", null);
                            else buildSide();
                        })
                        .setNegativeButton("Zrušit", null).show();
            }
        }).show();
    }

    private void chooseLists(final Channel c) {
        if (repo.mine.isEmpty()) {
            newList(c);
            return;
        }
        final List<String> names = new ArrayList<>(repo.mine.keySet());
        final boolean[] on = new boolean[names.size()];
        for (int i = 0; i < on.length; i++) on[i] = repo.inList(names.get(i), c.key());
        new AlertDialog.Builder(this).setTitle("Moje seznamy – " + repo.name(c))
                .setMultiChoiceItems(names.toArray(new String[0]), on, (d, i, checked) -> on[i] = checked)
                .setPositiveButton("Uložit", (d, w) -> {
                    for (int i = 0; i < on.length; i++) repo.setInList(names.get(i), c.key(), on[i]);
                    applyFilter();
                })
                .setNeutralButton("Nový seznam…", (d, w) -> newList(c))
                .setNegativeButton("Zrušit", null).show();
    }

    private void updateStatus() {
        String m;
        if (mode.equals("fav")) m = "★ Oblíbené";
        else if (mode.equals("group")) m = group;
        else if (mode.equals("list")) m = "▤ " + group;
        else m = "Všechny kanály";
        StringBuilder s = new StringBuilder(activeName()).append(" · ").append(m).append(" · ").append(visible.size()).append(" kanálů");
        if (!query.isEmpty()) s.append(" · hledání „").append(query).append("“");
        if (repo.epgUpdated > 0) s.append(" · program ").append(new SimpleDateFormat("d.M. HH:mm", Locale.getDefault()).format(new Date(repo.epgUpdated)));
        else s.append(" · bez programu (EPG)");
        status.setText(s);
    }

    private void applyFilter() {
        visible.clear();
        String q = query.toLowerCase();
        for (Channel c : repo.all) {
            if (repo.isOff(c)) continue;
            if (mode.equals("fav") && !repo.favs.contains(c.key())) continue;
            if (mode.equals("group") && !c.group.equals(group)) continue;
            if (mode.equals("list") && !repo.inList(group, c.key())) continue;
            if (!q.isEmpty() && !(repo.name(c) + " " + c.group + " " + c.tvgId).toLowerCase().contains(q)) continue;
            visible.add(c);
        }
        adapter.notifyDataSetChanged();
        updateStatus();
        buildSide();
        int pos = 0;
        if (!scrolledToLast) {
            scrolledToLast = true;
            String last = prefs.getString("last", "");
            for (int i = 0; i < visible.size(); i++) if (visible.get(i).key().equals(last)) pos = i;
        }
        focusPosition(pos);
    }

    private void focusPosition(final int pos) {
        if (visible.isEmpty()) return;
        final int p = Math.max(0, Math.min(pos, visible.size() - 1));
        rv.scrollToPosition(p);
        rv.post(() -> {
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(p);
            if (vh != null) vh.itemView.requestFocus();
            else rv.postDelayed(() -> {
                RecyclerView.ViewHolder v2 = rv.findViewHolderForAdapterPosition(p);
                if (v2 != null) v2.itemView.requestFocus();
            }, 100);
        });
    }

    private void onFocused(int pos) {
        if (pos < 0 || pos >= visible.size()) return;
        lastPos = pos;
        Channel c = visible.get(pos);
        long t = System.currentTimeMillis();
        Prog p = repo.now(c, t);
        Prog n = repo.next(c, t);
        nowTitle.setText(repo.name(c) + (p != null ? " – " + p.title + "  (" + Prog.hm(p.start) + "–" + Prog.hm(p.stop) + ")" : ""));
        StringBuilder d = new StringBuilder();
        if (p != null && !p.desc.isEmpty()) d.append(p.desc);
        if (n != null) d.append(d.length() > 0 ? "\n" : "").append("Poté ").append(Prog.hm(n.start)).append("  ").append(n.title);
        if (d.length() == 0) d.append(c.group);
        nowDesc.setText(d);
    }

    // ---------- list ----------

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            final TextView num, name, sub, star;
            final ImageView logo;

            VH(LinearLayout row, TextView num, ImageView logo, TextView name, TextView sub, TextView star) {
                super(row);
                this.num = num;
                this.logo = logo;
                this.name = name;
                this.sub = sub;
                this.star = star;
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(6), dp(16), dp(6));
            row.setBackgroundResource(R.drawable.row_bg);
            row.setFocusable(true);
            row.setClickable(true);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(68));
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);

            TextView num = tv(18, 0xFF8090A5);
            num.setGravity(Gravity.CENTER);
            row.addView(num, new LinearLayout.LayoutParams(dp(56), -2));
            ImageView logo = new ImageView(MainActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(72), dp(52));
            ll.rightMargin = dp(16);
            row.addView(logo, ll);
            LinearLayout col = new LinearLayout(MainActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = tv(22, Color.WHITE);
            name.setSingleLine(true);
            TextView sub = tv(15, 0xFFA7B3C4);
            sub.setSingleLine(true);
            col.addView(name);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView star = tv(24, 0xFFFFC94D);
            star.setText("★");
            row.addView(star);

            final VH vh = new VH(row, num, logo, name, sub, star);
            row.setOnClickListener(v -> {
                int p = vh.getBindingAdapterPosition();
                if (p >= 0) play(p);
            });
            row.setOnLongClickListener(v -> {
                int p = vh.getBindingAdapterPosition();
                if (p >= 0 && moving < 0) showMenu(p);
                return true;
            });
            row.setOnFocusChangeListener((v, has) -> {
                if (has) onFocused(vh.getBindingAdapterPosition());
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            Channel c = visible.get(pos);
            h.num.setText(String.valueOf(pos + 1));
            h.name.setText(repo.name(c));
            Prog p = repo.now(c, System.currentTimeMillis());
            h.sub.setText(p != null ? Prog.hm(p.start) + "–" + Prog.hm(p.stop) + "  " + p.title : c.group);
            h.star.setVisibility(repo.favs.contains(c.key()) ? View.VISIBLE : View.INVISIBLE);
            h.itemView.setSelected(pos == moving);
            Session.loadLogo(h.logo, c.logo);
        }

        @Override
        public int getItemCount() {
            return visible.size();
        }
    }

    private void play(int pos) {
        if (moving >= 0) return;
        Session.repo = repo;
        Session.list = new ArrayList<>(visible);
        Session.index = pos;
        returning = true;
        startActivity(new Intent(this, PlayerActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(reminderTick);
        ui.postDelayed(reminderTick, 3000);
        if (loaded) {
            // show current programmes again and go back to the channel that was playing
            adapter.notifyDataSetChanged();
            if (returning) {
                returning = false;
                String last = prefs.getString("last", "");
                for (int i = 0; i < visible.size(); i++) {
                    if (visible.get(i).key().equals(last)) {
                        focusPosition(i);
                        break;
                    }
                }
            }
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(reminderTick);
        super.onPause();
    }

    private void openGuide() {
        if (visible.isEmpty()) return;
        Session.repo = repo;
        Session.list = new ArrayList<>(visible);
        Session.index = Math.max(0, Math.min(lastPos, visible.size() - 1));
        startActivity(new Intent(this, GuideActivity.class));
    }

    /** Plays the channel with this key (from a reminder), in the full channel list. */
    private void playKey(String key) {
        List<Channel> l = new ArrayList<>();
        int at = -1;
        for (Channel c : repo.all) {
            if (repo.isOff(c) && !c.key().equals(key)) continue;
            if (c.key().equals(key)) at = l.size();
            l.add(c);
        }
        if (at < 0) return;
        Session.repo = repo;
        Session.list = l;
        Session.index = at;
        returning = true;
        startActivity(new Intent(this, PlayerActivity.class));
    }

    // ---------- reorder mode ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (moving >= 0 && ev.getAction() == KeyEvent.ACTION_DOWN) {
            switch (ev.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    move(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    move(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    endMove();
                    return true;
                default:
                    return true;
            }
        }
        if (moving >= 0) return true;
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            boolean inList = f != null && rv.findContainingViewHolder(f) != null;
            boolean inSide = f != null && f.getParent() == side;
            boolean inTop = f != null && topButtons.contains(f);
            int k = ev.getKeyCode();
            // hold UP anywhere in the list: jump straight to the top menu
            if (k == KeyEvent.KEYCODE_DPAD_UP && inList && ev.getRepeatCount() >= 1) {
                View first = topButtons.get(0);
                first.requestFocus();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP && inList && lastPos == 0 && ev.getRepeatCount() == 0) {
                topButtons.get(0).requestFocus();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && inList) {
                focusSide();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT && inSide) {
                focusPosition(lastPos);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && inTop) {
                focusPosition(lastPos);
                return true;
            }
        }
        if (ev.getAction() == KeyEvent.ACTION_DOWN && (ev.getKeyCode() == KeyEvent.KEYCODE_MENU || ev.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            View f = getCurrentFocus();
            if (f != null && rv.findContainingViewHolder(f) != null) {
                showMenu(rv.getChildAdapterPosition(rv.findContainingItemView(f)));
                return true;
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    private void focusSide() {
        for (int i = 0; i < side.getChildCount(); i++) {
            View v = side.getChildAt(i);
            if (v instanceof TextView && v.isFocusable() && ((TextView) v).getText().toString().startsWith("▶")) {
                v.requestFocus();
                return;
            }
        }
        for (int i = 0; i < side.getChildCount(); i++) {
            if (side.getChildAt(i).isFocusable()) {
                side.getChildAt(i).requestFocus();
                return;
            }
        }
    }

    /** First press leaves a filter, second press within 2 s closes the app. */
    @Override
    public void onBackPressed() {
        if (!mode.equals("all") || !query.isEmpty()) {
            setMode("all", null);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
            return;
        }
        lastBack = now;
        Toast.makeText(this, "Stiskni Zpět ještě jednou pro ukončení", Toast.LENGTH_SHORT).show();
    }

    private void startMove(int pos) {
        moving = pos;
        adapter.notifyItemChanged(pos);
        status.setText("Řazení: ↑ ↓ posune kanál, OK potvrdí");
    }

    private void move(int d) {
        int to = moving + d;
        if (to < 0 || to >= visible.size()) return;
        Channel a = visible.get(moving), b = visible.get(to);
        repo.swap(a, b);
        Collections.swap(visible, moving, to);
        int from = moving;
        moving = to;
        adapter.notifyItemChanged(from);
        adapter.notifyItemChanged(to);
        adapter.notifyItemMoved(from, to);
        focusPosition(to);
    }

    private void endMove() {
        int p = moving;
        moving = -1;
        adapter.notifyItemChanged(p);
        updateStatus();
        focusPosition(p);
    }

    // ---------- dialogs ----------

    private void showMenu(final int pos) {
        if (pos < 0 || pos >= visible.size()) return;
        final Channel c = visible.get(pos);
        boolean fav = repo.favs.contains(c.key());
        String[] items = {
                "Přehrát",
                fav ? "☆ Odebrat z oblíbených" : "★ Přidat do oblíbených",
                "▤ Moje seznamy…",
                "Přesunout (řadit)",
                "Přejmenovat",
                "Smazat ze seznamu",
                "Program kanálu"
        };
        new AlertDialog.Builder(this).setTitle(repo.name(c)).setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    play(pos);
                    break;
                case 1:
                    repo.toggleFav(c);
                    if (mode.equals("fav")) applyFilter();
                    else {
                        adapter.notifyItemChanged(pos);
                        buildSide();
                    }
                    break;
                case 2:
                    chooseLists(c);
                    break;
                case 3:
                    if (!query.isEmpty())
                        Toast.makeText(this, "Při hledání se řadit nedá, zruš hledání", Toast.LENGTH_LONG).show();
                    else startMove(pos);
                    break;
                case 4:
                    rename(c, pos);
                    break;
                case 5:
                    repo.hide(c);
                    visible.remove(pos);
                    adapter.notifyItemRemoved(pos);
                    updateStatus();
                    focusPosition(pos);
                    Toast.makeText(this, "Smazáno. Vrátit jde v Nastavení.", Toast.LENGTH_LONG).show();
                    break;
                default:
                    showGuide(c);
            }
        }).show();
    }

    private void rename(final Channel c, final int pos) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(repo.name(c));
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Přejmenovat kanál").setView(pad(et))
                .setPositiveButton("Uložit", (d, w) -> {
                    repo.rename(c, et.getText().toString());
                    adapter.notifyItemChanged(pos);
                })
                .setNegativeButton("Zrušit", null).show();
    }

    private void showGuide(Channel c) {
        final List<Prog> up = repo.upcoming(c, System.currentTimeMillis(), 60);
        if (up.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(repo.name(c)).setMessage("Pro tento kanál není k dispozici program.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] items = new String[up.size()];
        for (int i = 0; i < items.length; i++) items[i] = Prog.dayHm(up.get(i).start) + "   " + up.get(i).title;
        new AlertDialog.Builder(this).setTitle("Program – " + repo.name(c)).setItems(items, (d, which) ->
                Reminders.showProg(this, repo, c, up.get(which))).show();
    }

    private void showGroups() {
        final Map<String, Integer> gc = repo.groupCounts();
        final List<String> names = new ArrayList<>(gc.keySet());
        int total = 0;
        for (int n : gc.values()) total += n;
        int favCount = 0;
        for (Channel c : repo.all) if (repo.favs.contains(c.key()) && !repo.isOff(c)) favCount++;
        String[] items = new String[names.size() + 2];
        items[0] = "Všechny kanály (" + total + ")";
        items[1] = "★ Oblíbené (" + favCount + ")";
        for (int i = 0; i < names.size(); i++) items[i + 2] = names.get(i) + " (" + gc.get(names.get(i)) + ")";
        new AlertDialog.Builder(this).setTitle("Skupiny").setItems(items, (d, which) -> {
            if (which == 0) setMode("all", null);
            else if (which == 1) setMode("fav", null);
            else setMode("group", names.get(which - 2));
        }).show();
    }

    private void showSearch() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(query);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Hledat kanál").setView(pad(et))
                .setPositiveButton("Hledat", (d, w) -> {
                    query = et.getText().toString().trim();
                    applyFilter();
                })
                .setNeutralButton("Zrušit hledání", (d, w) -> {
                    query = "";
                    applyFilter();
                })
                .setNegativeButton("Zavřít", null).show();
    }

    private View pad(View v) {
        LinearLayout l = new LinearLayout(this);
        l.setPadding(dp(24), dp(8), dp(24), dp(8));
        l.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return l;
    }

    private void showSettings() {
        int hiddenCount = 0;
        for (Channel c : repo.all) if (repo.hidden.contains(c.key())) hiddenCount++;
        int deadCount = repo.dead.size();
        String[] items = {
                "Zdroje kanálů (přidat, upravit, smazat)…",
                "Adresa programu (EPG)…",
                "Obnovit seznam a program teď",
                "Vrátit smazané kanály (" + hiddenCount + ")",
                "Zrušit vlastní řazení",
                "Zkontrolovat funkčnost kanálů" + (checking ? " (běží…)" : ""),
                "Vrátit nefunkční kanály (" + deadCount + ")",
                "Připomínky pořadů (" + Reminders.all(this).size() + ")…",
                "Zálohovat nastavení",
                "Obnovit nastavení ze zálohy"
        };
        new AlertDialog.Builder(this).setTitle("Nastavení").setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    showLists();
                    break;
                case 1:
                    showAddress();
                    break;
                case 2:
                    refresh();
                    break;
                case 3:
                    repo.unhideAll();
                    applyFilter();
                    break;
                case 4:
                    repo.resetOrder();
                    applyFilter();
                    break;
                case 5:
                    checkChannels(false);
                    break;
                case 6:
                    repo.dead.clear();
                    repo.saveState();
                    applyFilter();
                    break;
                case 7:
                    showReminders();
                    break;
                case 8:
                    backup();
                    break;
                default:
                    restore();
            }
        }).show();
    }

    private void showReminders() {
        final List<Reminders.Item> l = Reminders.all(this);
        if (l.isEmpty()) {
            Toast.makeText(this, "Žádné připomínky. Přidáš je v programu kanálu nebo v Přehledu.", Toast.LENGTH_LONG).show();
            return;
        }
        Collections.sort(l, (a, b) -> Long.compare(a.start, b.start));
        String[] items = new String[l.size()];
        for (int i = 0; i < items.length; i++) items[i] = Prog.dayHm(l.get(i).start) + "  " + l.get(i).title + " (" + l.get(i).channel + ")";
        new AlertDialog.Builder(this).setTitle("Připomínky – OK smaže").setItems(items, (d, which) -> {
            Reminders.remove(this, l.get(which));
            Toast.makeText(this, "Připomenutí zrušeno", Toast.LENGTH_SHORT).show();
        }).show();
    }

    // ---------- health check ----------

    /** Tests all streams in the background and hides channels where neither the stream nor its backups answer. */
    private void checkChannels(final boolean quiet) {
        if (checking) {
            Toast.makeText(this, "Kontrola už běží", Toast.LENGTH_SHORT).show();
            return;
        }
        if (repo.all.isEmpty()) return;
        checking = true;
        final List<Channel> snapshot = new ArrayList<>();
        for (Channel c : repo.all) if (!repo.hidden.contains(c.key())) snapshot.add(c);
        if (!quiet) Toast.makeText(this, "Kontroluji " + snapshot.size() + " kanálů, může to chvíli trvat", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                final java.util.Set<String> dead = Health.findDead(snapshot, (done, total) ->
                        ui.post(() -> status.setText("Kontrola kanálů " + done + " / " + total)));
                ui.post(() -> {
                    repo.dead.clear();
                    repo.dead.addAll(dead);
                    repo.saveState();
                    prefs.edit().putLong("checked", System.currentTimeMillis()).apply();
                    applyFilter();
                    Toast.makeText(this, "Skryto " + dead.size() + " nefunkčních kanálů", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                ui.post(this::updateStatus);
            } finally {
                checking = false;
            }
        }).start();
    }

    // ---------- backup ----------

    private java.io.File backupFile() {
        java.io.File d = getExternalFilesDir(null);
        return new java.io.File(d != null ? d : getFilesDir(), "iptv-zaloha.json");
    }

    private void backup() {
        try {
            JSONObject o = new JSONObject();
            o.put("state", new JSONObject(repo.stateJson()));
            JSONObject pr = new JSONObject();
            for (String k : new String[]{"lists", "epg", "rem"}) pr.put(k, prefs.getString(k, ""));
            pr.put("active", prefs.getInt("active", 0));
            o.put("prefs", pr);
            java.io.File f = backupFile();
            java.nio.file.Files.write(f.toPath(), o.toString(2).getBytes("UTF-8"));
            new AlertDialog.Builder(this).setTitle("Záloha uložena").setMessage(f.getAbsolutePath()
                    + "\n\nZkopíruješ ji z TV např. příkazem adb pull. Pro obnovu ji vrať na stejné místo.")
                    .setPositiveButton("OK", null).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Zálohu se nepodařilo uložit: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restore() {
        final java.io.File f = backupFile();
        if (!f.exists()) {
            Toast.makeText(this, "Záloha nenalezena: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Obnovit ze zálohy?")
                .setMessage("Nahradí oblíbené, seznamy, řazení, zdroje a připomínky tím, co je v záloze.")
                .setPositiveButton("Obnovit", (d, w) -> repo.io.execute(() -> {
                    try {
                        JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                        JSONObject pr = o.optJSONObject("prefs");
                        final boolean ok = repo.restoreState(o.getJSONObject("state").toString());
                        ui.post(() -> {
                            if (!ok) {
                                Toast.makeText(this, "Záloha je poškozená", Toast.LENGTH_LONG).show();
                                return;
                            }
                            if (pr != null) {
                                SharedPreferences.Editor e = prefs.edit();
                                for (String k : new String[]{"lists", "epg", "rem"}) if (pr.has(k)) e.putString(k, pr.optString(k));
                                e.putInt("active", pr.optInt("active", 0));
                                e.apply();
                                Reminders.rescheduleAll(this);
                            }
                            applyFilter();
                            Toast.makeText(this, "Nastavení obnoveno", Toast.LENGTH_LONG).show();
                        });
                    } catch (Throwable t) {
                        ui.post(() -> Toast.makeText(this, "Obnova selhala: " + t.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }))
                .setNegativeButton("Zrušit", null).show();
    }

    private void showAddress() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(8), dp(24), dp(8));
        final EditText epg = new EditText(this);
        epg.setSingleLine(true);
        epg.setHint("Adresa EPG (XMLTV, nepovinné – jinak ze seznamu)");
        epg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        epg.setText(prefs.getString("epg", ""));
        l.addView(epg, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Program (EPG)").setView(l)
                .setPositiveButton("Uložit a načíst", (d, w) -> {
                    prefs.edit().putString("epg", epg.getText().toString().trim()).apply();
                    refresh();
                })
                .setNegativeButton("Zrušit", null).show();
    }

    // ---------- downloading ----------

    private void refresh() {
        final String m3u = activeUrl();
        if (m3u.isEmpty()) {
            showLists();
            return;
        }
        if (busy) {
            Toast.makeText(this, "Právě se stahuje jiný seznam, zkus to za chvíli", Toast.LENGTH_LONG).show();
            return;
        }
        busy = true;
        status.setText("Stahuji seznam kanálů…");
        final String epgPref = prefs.getString("epg", "");
        new Thread(() -> {
            try {
                M3uParser.Result r = M3uParser.parse(m3u.startsWith("asset:")
                        ? getAssets().open(m3u.substring(6)) : Net.open(m3u));
                if (r.channels.isEmpty()) throw new Exception("V seznamu nejsou žádné kanály");
                final List<Channel> channels = r.channels;
                // the guide set in Settings wins, then the playlist's own, then the default one
                final String epgUrl = !epgPref.isEmpty() ? epgPref : !r.epgUrl.isEmpty() ? r.epgUrl : DEFAULT_EPG;
                ui.post(() -> {
                    repo.setChannels(channels);
                    prefs.edit().putString("loaded", m3u).apply();
                    applyFilter();
                    status.setText(channels.size() + " kanálů načteno" + (epgUrl.isEmpty() ? "" : ", stahuji program…"));
                });
                if (!epgUrl.isEmpty()) {
                    try {
                        String tag = Net.tag(epgUrl);
                        String same = epgUrl + "|" + tag + "|" + m3u + "|m2"; // m2 = name matching version
                        if (!tag.isEmpty() && same.equals(prefs.getString("epgtag", "")) && !repo.progs.isEmpty()
                                && System.currentTimeMillis() - repo.epgUpdated < 6L * 3600_000L) {
                            ui.post(() -> {
                                updateStatus();
                                Toast.makeText(this, "Program je aktuální, nestahuji znovu", Toast.LENGTH_SHORT).show();
                            });
                            return;
                        }
                        EpgParser.Result e = EpgParser.parse(Net.open(epgUrl), channels, System.currentTimeMillis());
                        prefs.edit().putString("epgtag", same).apply();
                        ui.post(() -> {
                            repo.setEpg(e);
                            adapter.notifyDataSetChanged();
                            updateStatus();
                            View f = getCurrentFocus();
                            if (f != null && rv.findContainingItemView(f) != null) onFocused(rv.getChildAdapterPosition(rv.findContainingItemView(f)));
                            Toast.makeText(this, "Program přiřazen ke " + e.map.size() + " kanálům", Toast.LENGTH_LONG).show();
                        });
                    } catch (Throwable t) {
                        final String msg = String.valueOf(t.getMessage());
                        ui.post(() -> {
                            updateStatus();
                            Toast.makeText(this, "Program (EPG) se nepodařilo načíst: " + msg, Toast.LENGTH_LONG).show();
                        });
                    }
                } else {
                    ui.post(() -> Toast.makeText(this, "Tento seznam nemá program (EPG), jde zadat v Nastavení", Toast.LENGTH_LONG).show());
                }
            } catch (Throwable t) {
                final String msg = String.valueOf(t.getMessage());
                ui.post(() -> {
                    updateStatus();
                    Toast.makeText(this, "Načtení seznamu selhalo: " + msg, Toast.LENGTH_LONG).show();
                });
            } finally {
                busy = false;
            }
        }).start();
    }
}
