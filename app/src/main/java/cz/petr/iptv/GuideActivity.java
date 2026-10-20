package cz.petr.iptv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * TV guide as a timeline: one row per channel, three hours wide. Left/right move through programmes,
 * OK plays a running one or shows details (with a reminder button) for the others.
 */
public class GuideActivity extends Activity {
    private static final long WINDOW = 3L * 3600_000L;
    private static final long HALF_HOUR = 30L * 60_000L;

    private Repo repo;
    private List<Channel> list = new ArrayList<>();
    private RecyclerView rv;
    private TimeAxis axis;
    private long selTime;
    private long ws;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Reminders.checkDue(GuideActivity.this, key -> watchKey(key));
            redraw();
            h.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        repo = Session.repo;
        list = new ArrayList<>(Session.list);
        if (repo == null || list.isEmpty()) {
            finish();
            return;
        }
        selTime = System.currentTimeMillis();
        updateWindow();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1116);
        root.setPadding(dp(32), dp(20), dp(32), dp(16));

        TextView title = new TextView(this);
        title.setText("Přehled programu   ◀ ▶ pořady   OK přehrát / podrobnosti");
        title.setTextColor(0xFF9AA7B8);
        title.setTextSize(16);
        root.addView(title);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        View gap = new View(this);
        head.addView(gap, new LinearLayout.LayoutParams(dp(220), 1));
        axis = new TimeAxis(this);
        head.addView(axis, new LinearLayout.LayoutParams(0, dp(30), 1f));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(8);
        root.addView(head, hp);

        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        rv.setAdapter(new Adapter());
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        int start = Math.max(0, Math.min(Session.index, list.size() - 1));
        rv.scrollToPosition(start);
        rv.post(() -> {
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(start);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void updateWindow() {
        ws = selTime / HALF_HOUR * HALF_HOUR - HALF_HOUR;
        long minStart = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR - 3 * 3600_000L;
        if (ws < minStart) ws = minStart;
    }

    private void redraw() {
        axis.invalidate();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View v = rv.getChildAt(i);
            if (v instanceof LinearLayout) ((LinearLayout) v).getChildAt(2).invalidate();
        }
    }

    private Channel focusedChannel() {
        View f = getCurrentFocus();
        if (f == null) return null;
        View item = rv.findContainingItemView(f);
        if (item == null) return null;
        int p = rv.getChildAdapterPosition(item);
        return p >= 0 && p < list.size() ? list.get(p) : null;
    }

    private Prog at(Channel c, long t) {
        List<Prog> l = repo.programmes(c);
        if (l == null) return null;
        for (Prog p : l) if (p.start <= t && t < p.stop) return p;
        return null;
    }

    private void step(int dir) {
        Channel c = focusedChannel();
        if (c == null) return;
        List<Prog> l = repo.programmes(c);
        if (l == null || l.isEmpty()) {
            selTime += dir * HALF_HOUR;
        } else {
            Prog cur = at(c, selTime);
            if (dir > 0) {
                long from = cur != null ? cur.stop : selTime;
                Prog next = null;
                for (Prog p : l) if (p.start >= from) {
                    next = p;
                    break;
                }
                if (next != null) selTime = next.start + 1000;
            } else {
                long from = cur != null ? cur.start : selTime;
                Prog prev = null;
                for (Prog p : l) if (p.stop <= from) prev = p;
                if (prev != null) selTime = prev.start + 1000;
            }
        }
        updateWindow();
        redraw();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            int k = ev.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                step(-1);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                step(1);
                return true;
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    private void open(int pos) {
        Channel c = list.get(pos);
        Prog p = at(c, selTime);
        long now = System.currentTimeMillis();
        if (p == null || (p.start <= now && now < p.stop)) play(pos);
        else Reminders.showProg(this, repo, c, p);
    }

    private void play(int pos) {
        Session.list = new ArrayList<>(list);
        Session.index = pos;
        startActivity(new Intent(this, PlayerActivity.class));
    }

    private void watchKey(String key) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key().equals(key)) {
                play(i);
                return;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    // ---------- views ----------

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(GuideActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.row_bg);
            row.setFocusable(true);
            row.setClickable(true);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(64));
            lp.bottomMargin = dp(4);
            row.setLayoutParams(lp);

            ImageView logo = new ImageView(GuideActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(56), dp(44));
            ll.leftMargin = dp(6);
            row.addView(logo, ll);
            TextView name = new TextView(GuideActivity.this);
            name.setTextColor(Color.WHITE);
            name.setTextSize(18);
            name.setSingleLine(true);
            name.setPadding(dp(10), 0, dp(8), 0);
            row.addView(name, new LinearLayout.LayoutParams(dp(158), -2));
            ProgView pv = new ProgView(GuideActivity.this);
            row.addView(pv, new LinearLayout.LayoutParams(0, -1, 1f));

            final RecyclerView.ViewHolder vh = new RecyclerView.ViewHolder(row) {
            };
            row.setOnClickListener(v -> {
                int p = vh.getBindingAdapterPosition();
                if (p >= 0) open(p);
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder vh, int pos) {
            LinearLayout row = (LinearLayout) vh.itemView;
            Channel c = list.get(pos);
            Session.loadLogo((ImageView) row.getChildAt(0), c.logo);
            ((TextView) row.getChildAt(1)).setText(repo.name(c));
            ((ProgView) row.getChildAt(2)).channel = c;
            row.getChildAt(2).invalidate();
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    private class ProgView extends View {
        Channel channel;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        ProgView(Context c) {
            super(c);
            txt.setTextSize(dp(15));
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), hgt = getHeight();
            long now = System.currentTimeMillis();
            boolean focused = ((View) getParent()).isFocused();
            List<Prog> l = channel == null ? null : repo.programmes(channel);
            if (l == null || l.isEmpty()) {
                txt.setColor(0xFF6B788B);
                cv.drawText("Bez programu", dp(10), hgt / 2f + dp(5), txt);
            } else {
                for (Prog p : l) {
                    if (p.stop <= ws || p.start >= ws + WINDOW) continue;
                    float x1 = (float) (Math.max(p.start, ws) - ws) / WINDOW * w;
                    float x2 = (float) (Math.min(p.stop, ws + WINDOW) - ws) / WINDOW * w;
                    r.set(x1 + 2, 3, x2 - 2, hgt - 3);
                    boolean running = p.start <= now && now < p.stop;
                    boolean sel = focused && p.start <= selTime && selTime < p.stop;
                    fill.setColor(sel ? 0xFFFFC94D : running ? 0xFF2D3F5E : 0xFF1B2433);
                    cv.drawRoundRect(r, dp(6), dp(6), fill);
                    txt.setColor(sel ? 0xFF14181F : Color.WHITE);
                    cv.save();
                    cv.clipRect(r);
                    String t = (Reminders.has(getContext(), channel.key(), p.start) ? "⏰ " : "") + p.title;
                    cv.drawText(t, r.left + dp(8), hgt / 2f + dp(5), txt);
                    cv.restore();
                }
            }
            if (now >= ws && now < ws + WINDOW) {
                float x = (float) (now - ws) / WINDOW * w;
                fill.setColor(0xFFE5484D);
                cv.drawRect(x - 1, 0, x + 1, hgt, fill);
            }
        }
    }

    private class TimeAxis extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());

        TimeAxis(Context c) {
            super(c);
            p.setTextSize(dp(15));
            p.setColor(0xFF9AA7B8);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth();
            for (long t = ws; t < ws + WINDOW; t += HALF_HOUR) {
                float x = (float) (t - ws) / WINDOW * w;
                cv.drawText(f.format(new Date(t)), x + dp(4), getHeight() - dp(8), p);
                cv.drawRect(x, getHeight() - dp(4), x + 1, getHeight(), p);
            }
        }
    }
}
