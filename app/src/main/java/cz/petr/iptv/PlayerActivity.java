package cz.petr.iptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/** Full screen playback with channel zapping and an info banner (now/next from the guide). */
public class PlayerActivity extends Activity {
    private Repo repo;
    private List<Channel> list = new ArrayList<>();
    private int idx;
    private ExoPlayer player;
    private PlayerView view;
    private DefaultHttpDataSource.Factory dsFactory;
    private LinearLayout banner;
    private TextView bChannel, bNow, bNext, bError;
    private ProgressBar bBar, spinner;
    private boolean triedHls;
    /** Streams of the current channel (main + backups) and the one being tried. */
    private List<String> cands = new ArrayList<>();
    private int candIdx;
    private int prevIdx = -1;
    private int resize;
    private final StringBuilder digits = new StringBuilder();
    private TextView bDigits;
    private final Runnable jumpDigits = () -> {
        if (digits.length() == 0) return;
        int n = Integer.parseInt(digits.toString());
        digits.setLength(0);
        bDigits.setVisibility(View.GONE);
        if (n >= 1 && n <= list.size()) play(n - 1);
        else Toast.makeText(this, "Kanál " + n + " neexistuje", Toast.LENGTH_SHORT).show();
    };
    private final Runnable reminderTick = new Runnable() {
        @Override
        public void run() {
            Reminders.checkDue(PlayerActivity.this, key -> {
                for (int i = 0; i < list.size(); i++) if (list.get(i).key().equals(key)) play(i);
            });
            h.postDelayed(this, 20000);
        }
    };
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (banner.getVisibility() == View.VISIBLE) fillBanner();
            h.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        repo = Session.repo;
        list = new ArrayList<>(Session.list);
        idx = Session.index;
        if (repo == null || list.isEmpty()) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setKeepContentOnPlayerReset(false);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));

        bError = text(26, Color.WHITE);
        bError.setGravity(Gravity.CENTER);
        bError.setBackgroundColor(0xCC000000);
        bError.setPadding(dp(32), dp(20), dp(32), dp(20));
        bError.setVisibility(View.GONE);
        root.addView(bError, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        bDigits = text(64, Color.WHITE);
        bDigits.setTypeface(Typeface.DEFAULT_BOLD);
        bDigits.setBackgroundColor(0xAA000000);
        bDigits.setPadding(dp(28), dp(12), dp(28), dp(12));
        bDigits.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        dlp.setMargins(0, dp(40), dp(56), 0);
        root.addView(bDigits, dlp);

        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(56), dp(24), dp(56), dp(28));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xE6000000});
        banner.setBackground(g);
        bChannel = text(34, Color.WHITE);
        bChannel.setTypeface(Typeface.DEFAULT_BOLD);
        bNow = text(24, Color.WHITE);
        bBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bBar.setMax(1000);
        bNext = text(20, 0xFFB5C0CF);
        banner.addView(bChannel);
        banner.addView(bNow);
        banner.addView(bBar, new LinearLayout.LayoutParams(-1, dp(6)));
        banner.addView(bNext);
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        dsFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(Net.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory))
                .build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                spinner.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) {
                    bError.setVisibility(View.GONE);
                    rememberWorking();
                }
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                String u = cands.get(candIdx);
                if (!triedHls && !u.toLowerCase().contains(".m3u8")) {
                    // a stream URL without extension may still be HLS
                    triedHls = true;
                    player.setMediaSource(new HlsMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(u)));
                    player.prepare();
                    player.setPlayWhenReady(true);
                    return;
                }
                if (candIdx + 1 < cands.size()) {
                    // this stream is dead, try the next backup of the channel
                    candIdx++;
                    startCandidate();
                    return;
                }
                spinner.setVisibility(View.GONE);
                bError.setText("Kanál se nepodařilo přehrát\n" + e.getErrorCodeName() + "\nNahoru/dolů přepne kanál");
                bError.setVisibility(View.VISIBLE);
            }
        });
        play(idx);
        h.postDelayed(tick, 20000);
        h.postDelayed(reminderTick, 5000);
    }

    private void startCandidate() {
        triedHls = false;
        player.setMediaItem(MediaItem.fromUri(cands.get(candIdx)));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /** Remembers which of the channel's streams works, so it is tried first next time. */
    private void rememberWorking() {
        Channel c = list.get(idx);
        String u = cands.get(candIdx);
        int orig = u.equals(c.url) ? 0 : 1 + c.alts.indexOf(u);
        Integer old = repo.pick.get(c.key());
        if ((old == null ? 0 : old) == orig) return;
        if (orig == 0) repo.pick.remove(c.key());
        else repo.pick.put(c.key(), orig);
        repo.saveState();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        return t;
    }

    private void play(int i) {
        int ni = (i % list.size() + list.size()) % list.size();
        if (ni != idx) prevIdx = idx;
        idx = ni;
        Channel c = list.get(idx);
        cands = repo.streams(c);
        candIdx = 0;
        bError.setVisibility(View.GONE);
        getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().putString("last", c.key()).apply();
        startCandidate();
        showBanner();
    }

    private void showBanner() {
        fillBanner();
        banner.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideBanner);
        h.postDelayed(hideBanner, 5000);
    }

    private void fillBanner() {
        Channel c = list.get(idx);
        long t = System.currentTimeMillis();
        bChannel.setText((idx + 1) + "   " + repo.name(c));
        Prog p = repo.now(c, t);
        Prog n = repo.next(c, t);
        if (p != null) {
            bNow.setText(Prog.hm(p.start) + "–" + Prog.hm(p.stop) + "   " + p.title);
            bBar.setProgress((int) (1000L * (t - p.start) / Math.max(1, p.stop - p.start)));
            bBar.setVisibility(View.VISIBLE);
        } else {
            bNow.setText(c.group);
            bBar.setVisibility(View.INVISIBLE);
        }
        bNext.setText(n != null ? "Poté " + Prog.hm(n.start) + "   " + n.title : "");
    }

    private void showGuide() {
        final Channel c = list.get(idx);
        final List<Prog> up = repo.upcoming(c, System.currentTimeMillis(), 40);
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

    private void showOptions() {
        String[] items = {"Program kanálu", "Zvuková stopa…", "Titulky…", "Kvalita obrazu…", "Poměr stran: " + resizeName(),
                "Předchozí kanál", "Další zdroj streamu (" + (candIdx + 1) + "/" + cands.size() + ")"};
        new AlertDialog.Builder(this).setTitle("Možnosti").setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    showGuide();
                    break;
                case 1:
                    chooseTrack(C.TRACK_TYPE_AUDIO, "Zvuková stopa", false);
                    break;
                case 2:
                    chooseTrack(C.TRACK_TYPE_TEXT, "Titulky", true);
                    break;
                case 3:
                    chooseTrack(C.TRACK_TYPE_VIDEO, "Kvalita obrazu", true);
                    break;
                case 4:
                    resize = (resize + 1) % 3;
                    view.setResizeMode(resize == 0 ? AspectRatioFrameLayout.RESIZE_MODE_FIT
                            : resize == 1 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FILL);
                    Toast.makeText(this, "Poměr stran: " + resizeName(), Toast.LENGTH_SHORT).show();
                    break;
                case 5:
                    if (prevIdx >= 0) play(prevIdx);
                    break;
                default:
                    if (cands.size() > 1) {
                        candIdx = (candIdx + 1) % cands.size();
                        startCandidate();
                    }
            }
        }).show();
    }

    private String resizeName() {
        return resize == 0 ? "celý obraz" : resize == 1 ? "vyplnit (ořez)" : "roztáhnout";
    }

    /** Lists the tracks of one type; optional ones can also be switched off (video: back to automatic). */
    private void chooseTrack(final int type, String title, final boolean optional) {
        final List<Tracks.Group> groups = new ArrayList<>();
        final List<Integer> indexes = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        int selected = -1;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                String l = f.label != null ? f.label : f.language != null ? f.language : "stopa " + (labels.size() + 1);
                if (type == C.TRACK_TYPE_VIDEO && f.height > 0) l = f.height + "p" + (f.bitrate > 0 ? "  " + f.bitrate / 1000 + " kb/s" : "");
                if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) l += "  (" + f.channelCount + " kan.)";
                if (g.isTrackSelected(i)) selected = labels.size() + (optional ? 1 : 0);
                groups.add(g);
                indexes.add(i);
                labels.add(l);
            }
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, type == C.TRACK_TYPE_TEXT ? "Tento stream nemá titulky" : "Žádné další stopy", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> items = new ArrayList<>();
        if (optional) items.add(type == C.TRACK_TYPE_TEXT ? "Vypnuto" : "Automaticky");
        items.addAll(labels);
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(items.toArray(new String[0]), selected < 0 ? 0 : selected, (d, which) -> {
            TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
            if (optional && which == 0) {
                if (type == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(type, true);
                else b.clearOverridesOfType(type);
            } else {
                int k = which - (optional ? 1 : 0);
                b.setTrackTypeDisabled(type, false);
                b.setOverrideForType(new TrackSelectionOverride(groups.get(k).getMediaTrackGroup(), indexes.get(k)));
            }
            player.setTrackSelectionParameters(b.build());
            d.dismiss();
        }).setNegativeButton("Zavřít", null).show();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                play(idx + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                play(idx - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                ev.startTracking();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (prevIdx >= 0) play(prevIdx);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_INFO:
                showGuide();
                return true;
            case KeyEvent.KEYCODE_MENU:
                showOptions();
                return true;
            default:
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    if (digits.length() < 4) digits.append(code - KeyEvent.KEYCODE_0);
                    bDigits.setText(digits);
                    bDigits.setVisibility(View.VISIBLE);
                    h.removeCallbacks(jumpDigits);
                    h.postDelayed(jumpDigits, 1500);
                    return true;
                }
                return super.onKeyDown(code, ev);
        }
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            showOptions();
            return true;
        }
        return super.onKeyLongPress(code, ev);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && ev.isTracking() && !ev.isCanceled()) {
            showBanner();
            return true;
        }
        return super.onKeyUp(code, ev);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (player != null) player.setPlayWhenReady(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
