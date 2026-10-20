package cz.petr.iptv;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Reminders for programmes: stored in preferences, fired by an alarm (notification) and by the open app. */
final class Reminders {
    static final class Item {
        String key = "", channel = "", title = "";
        long start;
    }

    /** The reminder fires this long before the programme starts. */
    static final long LEAD = 60_000L;

    private Reminders() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
    }

    static List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("rem", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.key = o.getString("k");
                it.channel = o.getString("c");
                it.title = o.getString("t");
                it.start = o.getLong("s");
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Item> l) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : l) a.put(new JSONObject().put("k", it.key).put("c", it.channel).put("t", it.title).put("s", it.start));
            prefs(c).edit().putString("rem", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static boolean has(Context c, String key, long start) {
        for (Item it : all(c)) if (it.key.equals(key) && it.start == start) return true;
        return false;
    }

    static void add(Context c, Channel ch, String name, Prog p) {
        List<Item> l = all(c);
        Item it = new Item();
        it.key = ch.key();
        it.channel = name;
        it.title = p.title;
        it.start = p.start;
        l.add(it);
        save(c, l);
        schedule(c, it);
    }

    static void remove(Context c, Item item) {
        List<Item> l = all(c);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).key.equals(item.key) && l.get(i).start == item.start) {
                l.remove(i);
                break;
            }
        }
        save(c, l);
        cancel(c, item);
    }

    private static PendingIntent pending(Context c, Item it) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction("cz.petr.iptv.REMIND");
        i.putExtra("k", it.key).putExtra("c", it.channel).putExtra("t", it.title).putExtra("s", it.start);
        int id = (it.key + it.start).hashCode();
        return PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c, Item it) {
        long at = it.start - LEAD;
        if (at < System.currentTimeMillis()) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, it));
    }

    private static void cancel(Context c, Item it) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, it));
    }

    /** After a reboot the alarms are gone: schedule the ones still in the future. */
    static void rescheduleAll(Context c) {
        long now = System.currentTimeMillis();
        List<Item> keep = new ArrayList<>();
        for (Item it : all(c)) {
            if (it.start + 60_000L < now) continue;
            keep.add(it);
            schedule(c, it);
        }
        save(c, keep);
    }

    /** Reminders that are due now (or whose programme started less than five minutes ago); removes them from the store. */
    static List<Item> takeDue(Context c) {
        long now = System.currentTimeMillis();
        List<Item> all = all(c);
        List<Item> due = new ArrayList<>();
        List<Item> keep = new ArrayList<>();
        for (Item it : all) {
            if (it.start - LEAD <= now && now < it.start + 5 * 60_000L) due.add(it);
            else if (it.start + 5 * 60_000L > now) keep.add(it);
        }
        if (keep.size() != all.size()) {
            save(c, keep);
            for (Item it : due) cancel(c, it);
        }
        return due;
    }

    /** Programme details, with a button to be reminded of a future programme. */
    static void showProg(final Activity a, final Repo repo, final Channel ch, final Prog p) {
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(p.title)
                .setMessage(Prog.dayHm(p.start) + "–" + Prog.hm(p.stop) + "\n\n" + (p.desc.isEmpty() ? "Bez popisu." : p.desc))
                .setNegativeButton("Zavřít", null);
        if (p.start > System.currentTimeMillis()) {
            if (has(a, ch.key(), p.start)) {
                b.setPositiveButton("Zrušit připomenutí", (d, w) -> {
                    for (Item it : all(a)) if (it.key.equals(ch.key()) && it.start == p.start) remove(a, it);
                    Toast.makeText(a, "Připomenutí zrušeno", Toast.LENGTH_SHORT).show();
                });
            } else {
                b.setPositiveButton("Připomenout", (d, w) -> {
                    add(a, ch, repo.name(ch), p);
                    Toast.makeText(a, "Připomenu minutu před začátkem", Toast.LENGTH_SHORT).show();
                });
            }
        }
        b.show();
    }

    /** Dialog shown by an open activity when a reminder is due; onWatch gets the channel key. */
    static void checkDue(final Activity a, final Consumer<String> onWatch) {
        for (final Item it : takeDue(a)) {
            new AlertDialog.Builder(a).setTitle("Za chvíli začíná")
                    .setMessage(it.title + "\n" + it.channel + "  " + Prog.hm(it.start))
                    .setPositiveButton("Přepnout", (d, w) -> onWatch.accept(it.key))
                    .setNegativeButton("Později", null).show();
        }
    }
}
