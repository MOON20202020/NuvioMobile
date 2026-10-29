package com.nuvio.cast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Cast to TV" screen. Nuvio opens it as an external player (ACTION_VIEW); it finds
 * TVs on the Wi-Fi, sends the movie there and works as a remote control.
 */
public class CastActivity extends Activity implements Discovery.Listener {
    private static final int BG = 0xFF0D0D12, CARD = 0xFF1A1A23, CARD_ACTIVE = 0xFF262635,
            TEXT = 0xFFF2F2F7, MUTED = 0xFF9A9AAE, ACCENT = 0xFF4F8CFF, DANGER = 0xFFFF5A5F;

    // The session outlives the screen so the TV keeps playing and the remote can be reopened.
    private static RemotePlayer sPlayer;
    private static CastTarget sTarget;
    private static LocalServer sServer;
    private static String sTitle;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, CastTarget> devices = new LinkedHashMap<String, CastTarget>();
    private Discovery discovery;

    // Request
    private Uri videoUri;
    private String mime;
    private String title;
    private long startMs;
    private final Map<String, String> headers = new LinkedHashMap<String, String>();
    private final List<String> subUrls = new ArrayList<String>();
    private final List<String> subNames = new ArrayList<String>();
    private int subIndex = 0; // -1 = off
    private boolean guessedMime;

    // Views
    private LinearLayout deviceList;
    private TextView emptyHint, statusText, timeText, durText, playBtn, nowPlaying, subtitleBtn;
    private ProgressBar searching;
    private LinearLayout pickerSection, remoteSection;
    private SeekBar seekBar;
    private boolean dragging;
    private boolean finishedReported;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        }
        readIntent(getIntent());
        buildUi();
        discovery = new Discovery(this, this);
        if (sPlayer != null && (videoUri == null || TextUtils.equals(title, sTitle))) {
            showRemote(); // reopened while already casting this title
        } else {
            showPicker();
        }
        ui.post(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pickerSection.getVisibility() == View.VISIBLE) startSearch();
    }

    @Override
    protected void onPause() {
        super.onPause();
        discovery.stop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        io.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finishWithResult(false);
    }

    private void readIntent(Intent in) {
        videoUri = in.getData();
        mime = in.getType();
        title = in.getStringExtra("title");
        if (title == null) title = in.getStringExtra(Intent.EXTRA_TITLE);
        if (title == null && videoUri != null) title = videoUri.getLastPathSegment();
        startMs = in.getIntExtra("position", 0);
        String[] h = in.getStringArrayExtra("headers");
        if (h != null) {
            for (String line : h) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
            }
        }
        Parcelable[] subs = in.getParcelableArrayExtra("subs");
        String[] names = in.getStringArrayExtra("subs.name");
        if (subs == null) {
            subs = in.getParcelableArrayExtra("subtitle_uri");
            names = in.getStringArrayExtra("subtitle_name");
        }
        if (subs != null) {
            for (int i = 0; i < subs.length; i++) {
                if (!(subs[i] instanceof Uri)) continue;
                subUrls.add(subs[i].toString());
                subNames.add(names != null && i < names.length ? names[i] : "ترجمة " + (i + 1));
            }
        }
        if (subUrls.isEmpty()) subIndex = -1;
        if (mime == null || mime.equals("video/*")) {
            mime = guessMime(videoUri);
            guessedMime = true;
        }
    }

    private static String guessMime(Uri uri) {
        String p = uri == null || uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.US);
        if (p.endsWith(".m3u8")) return "application/x-mpegURL";
        if (p.endsWith(".mpd")) return "application/dash+xml";
        if (p.endsWith(".mkv")) return "video/x-matroska";
        if (p.endsWith(".webm")) return "video/webm";
        return "video/mp4";
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView button(String label, int bg, int fg) {
        TextView b = text(label, 16, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(bg, 14));
        b.setPadding(dp(12), dp(14), dp(12), dp(14));
        b.setClickable(true);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(margin), dp(margin), dp(margin), dp(margin));
        return p;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("📺  بث إلى التلفاز", 26, TEXT, true));
        TextView sub = text(title == null ? "" : title, 15, MUTED, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(sub);

        // ---- picker
        pickerSection = new LinearLayout(this);
        pickerSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(pickerSection);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("الأجهزة المتاحة", 17, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        searching = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        head.addView(searching, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView refresh = text("  بحث مجدداً", 14, ACCENT, true);
        refresh.setPadding(dp(8), dp(8), dp(8), dp(8));
        refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startSearch(); } });
        head.addView(refresh);
        pickerSection.addView(head);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        deviceList.setPadding(0, dp(8), 0, 0);
        pickerSection.addView(deviceList);

        emptyHint = text("جارٍ البحث عن أجهزة…\n\nتأكد أن الهاتف والتلفاز متصلان بنفس شبكة الواي فاي، وأن التلفاز يدعم Chromecast أو DLNA (مثل Samsung و LG و Sony و Google TV).", 14, MUTED, false);
        emptyHint.setPadding(dp(4), dp(16), dp(4), dp(16));
        emptyHint.setLineSpacing(0, 1.3f);
        pickerSection.addView(emptyHint);

        if (subUrls.isEmpty()) {
            TextView noSubs = text("💬  لا توجد ترجمة جاهزة للبث.\nالترجمة المدمجة داخل ملف الفيلم لا تصل للتلفاز. من المشغّل اضغط زر الترجمة (CC) واختر ترجمة من الإضافات، ثم اضغط زر البث مرة أخرى.", 13, MUTED, false);
            noSubs.setPadding(dp(4), dp(14), dp(4), 0);
            noSubs.setLineSpacing(0, 1.25f);
            pickerSection.addView(noSubs);
        }
        if (!subUrls.isEmpty()) {
            subtitleBtn = button("", CARD, TEXT);
            subtitleBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { chooseSubtitle(); } });
            pickerSection.addView(subtitleBtn, lp(-1, -2, 0, 0));
            ((LinearLayout.LayoutParams) subtitleBtn.getLayoutParams()).topMargin = dp(12);
            updateSubtitleLabel();
        }

        // ---- remote
        remoteSection = new LinearLayout(this);
        remoteSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(remoteSection);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 20));
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        remoteSection.addView(card);
        nowPlaying = text("", 17, TEXT, true);
        card.addView(nowPlaying);
        statusText = text("", 14, MUTED, false);
        statusText.setPadding(0, dp(6), 0, dp(10));
        card.addView(statusText);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && sPlayer != null && sPlayer.durationMs() > 0) timeText.setText(fmt(sPlayer.durationMs() * p / 1000));
            }
            public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                final RemotePlayer p = sPlayer;
                if (p == null || p.durationMs() <= 0) return;
                final long target = p.durationMs() * s.getProgress() / 1000;
                run(new Task() { public void go() throws Exception { p.seek(target); } });
            }
        });
        card.addView(seekBar);
        LinearLayout times = new LinearLayout(this);
        times.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        timeText = text("0:00", 13, MUTED, false);
        durText = text("--:--", 13, MUTED, false);
        durText.setGravity(Gravity.END);
        times.addView(timeText, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(durText, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(times);

        LinearLayout row1 = new LinearLayout(this);
        row1.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row1.setPadding(0, dp(14), 0, 0);
        TextView back = button("⟲ 10", CARD_ACTIVE, TEXT);
        playBtn = button("⏸", ACCENT, Color.WHITE);
        playBtn.setTextSize(22);
        TextView fwd = button("30 ⟳", CARD_ACTIVE, TEXT);
        row1.addView(back, lp(0, -2, 1, 4));
        row1.addView(playBtn, lp(0, -2, 1.3f, 4));
        row1.addView(fwd, lp(0, -2, 1, 4));
        card.addView(row1);
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { skip(-10000); } });
        fwd.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { skip(30000); } });
        playBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { togglePlay(); } });

        LinearLayout row2 = new LinearLayout(this);
        row2.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView volDown = button("🔉  −", CARD_ACTIVE, TEXT);
        TextView volUp = button("🔊  +", CARD_ACTIVE, TEXT);
        row2.addView(volDown, lp(0, -2, 1, 4));
        row2.addView(volUp, lp(0, -2, 1, 4));
        card.addView(row2);
        volDown.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { volume(-1); } });
        volUp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { volume(1); } });

        TextView stop = button("⏹  إيقاف البث", DANGER, Color.WHITE);
        remoteSection.addView(stop, lp(-1, -2, 0, 0));
        ((LinearLayout.LayoutParams) stop.getLayoutParams()).topMargin = dp(16);
        stop.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stopCasting(); } });

        TextView other = button("اختيار جهاز آخر", CARD, TEXT);
        remoteSection.addView(other, lp(-1, -2, 0, 0));
        ((LinearLayout.LayoutParams) other.getLayoutParams()).topMargin = dp(10);
        other.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showPicker(); } });

        TextView close = button("رجوع إلى Nuvio (البث يستمر)", BG, MUTED);
        remoteSection.addView(close, lp(-1, -2, 0, 0));
        close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finishWithResult(false); } });

        setContentView(scroll);
    }

    private void updateSubtitleLabel() {
        if (subtitleBtn == null) return;
        subtitleBtn.setText("💬  الترجمة: " + (subIndex < 0 ? "بدون" : subNames.get(subIndex)));
    }

    private void chooseSubtitle() {
        final String[] items = new String[subNames.size() + 1];
        items[0] = "بدون ترجمة";
        for (int i = 0; i < subNames.size(); i++) items[i + 1] = subNames.get(i);
        new AlertDialog.Builder(this)
                .setTitle("اختر الترجمة")
                .setSingleChoiceItems(items, subIndex + 1, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        subIndex = which - 1;
                        updateSubtitleLabel();
                        d.dismiss();
                    }
                }).show();
    }

    private void showPicker() {
        pickerSection.setVisibility(View.VISIBLE);
        remoteSection.setVisibility(View.GONE);
        if (videoUri == null) emptyHint.setText("افتح فيلماً من Nuvio ثم اختر «بث إلى التلفاز».");
        startSearch();
    }

    private void showRemote() {
        discovery.stop();
        pickerSection.setVisibility(View.GONE);
        remoteSection.setVisibility(View.VISIBLE);
        nowPlaying.setText("يعرض الآن على: " + (sTarget == null ? "" : sTarget.name));
        updateRemote();
    }

    private void startSearch() {
        if (videoUri == null) return;
        devices.clear();
        deviceList.removeAllViews();
        emptyHint.setVisibility(View.VISIBLE);
        searching.setVisibility(View.VISIBLE);
        discovery.start();
        ui.postDelayed(new Runnable() {
            public void run() {
                searching.setVisibility(View.INVISIBLE);
                if (devices.isEmpty()) emptyHint.setText("لم يتم العثور على أي جهاز.\n\nتأكد أن الهاتف والتلفاز على نفس شبكة الواي فاي وأن التلفاز يعمل، ثم اضغط «بحث مجدداً».");
            }
        }, 12000);
    }

    @Override
    public void onFound(final CastTarget t) {
        ui.post(new Runnable() {
            public void run() {
                if (devices.containsKey(t.id) || isFinishing()) return;
                devices.put(t.id, t);
                emptyHint.setVisibility(View.GONE);
                LinearLayout item = new LinearLayout(CastActivity.this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setBackground(round(CARD, 16));
                item.setPadding(dp(16), dp(14), dp(16), dp(14));
                item.addView(text((t.kind == CastTarget.CHROMECAST ? "📡  " : "📺  ") + t.name, 16, TEXT, true));
                TextView kind = text(t.kind == CastTarget.CHROMECAST ? "Chromecast / Google TV" : "تلفاز ذكي (DLNA)", 13, MUTED, false);
                kind.setPadding(0, dp(4), 0, 0);
                item.addView(kind);
                item.setClickable(true);
                item.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { castTo(t); } });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                p.topMargin = dp(8);
                deviceList.addView(item, p);
            }
        });
    }

    // ------------------------------------------------------------------ casting

    private interface Task { void go() throws Exception; }

    private void run(final Task t) {
        io.execute(new Runnable() {
            public void run() {
                try { t.go(); } catch (final Exception e) {
                    ui.post(new Runnable() { public void run() { setStatus(msg(e), true); } });
                }
            }
        });
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? "تعذر الاتصال بالجهاز" : m;
    }

    private void castTo(final CastTarget target) {
        final RemotePlayer old = sPlayer;
        final RemotePlayer player = target.kind == CastTarget.CHROMECAST ? new ChromecastPlayer(target) : new DlnaPlayer(target);
        sPlayer = player;
        sTarget = target;
        sTitle = title;
        finishedReported = false;
        showRemote();
        setStatus("جارٍ الاتصال بـ " + target.name + "…", false);
        final long resumeAt = startMs;
        io.execute(new Runnable() {
            public void run() {
                if (old != null) old.release();
                try {
                    if (sServer == null) sServer = new LocalServer(getApplicationContext());
                    final boolean chromecast = target.kind == CastTarget.CHROMECAST;
                    RemotePlayer.Media m = new RemotePlayer.Media();
                    m.title = title;
                    m.mime = mime;
                    m.startMs = resumeAt;
                    String scheme = videoUri.getScheme() == null ? "" : videoUri.getScheme().toLowerCase(Locale.US);
                    String host = videoUri.getHost() == null ? "" : videoUri.getHost().toLowerCase(Locale.US);
                    boolean needsPhone = scheme.equals("content") || scheme.equals("file");
                    boolean local = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
                    boolean viaProxy = local || !headers.isEmpty();
                    if (scheme.startsWith("http") && guessedMime) {
                        String probed = probeMime(videoUri.toString(), headers);
                        if (probed != null) { mime = probed; m.mime = probed; guessedMime = false; }
                    }
                    if (needsPhone || viaProxy || subIndex >= 0) {
                        sServer.start();
                        sServer.setHeaders(headers);
                    }
                    // Chromecast / Google TV play MKV video but drop its audio: re-package to MP4 on the phone.
                    RemotePlayer active = player;
                    if (chromecast && needsInspection(mime, guessedMime)) {
                        ui.post(new Runnable() { public void run() { setStatus("جارٍ تجهيز الفيلم للتلفاز…", false); } });
                        com.nuvio.app.features.cast.CastRemuxer.Probe probe =
                                com.nuvio.app.features.cast.CastRemuxer.probe(getApplicationContext(), videoUri.toString(), headers);
                        if (probe != null && probe.getCanRemux()) {
                            sServer.start();
                            sServer.setHeaders(headers);
                            active = new RemuxPlayer(player, sServer, videoUri.toString(), probe.durationMs, 0);
                            sPlayer = active;
                            if (probe.audioConvert) {
                                ui.post(new Runnable() { public void run() { setStatus("جارٍ تحويل الصوت للتلفاز…", false); } });
                            }
                        }
                    }
                    if (active != player) {
                        m.url = videoUri.toString();
                        if (subIndex >= 0) {
                            m.subtitleSource = subUrls.get(subIndex);
                            m.subtitleUrl = sServer.subtitleUrl(subUrls.get(subIndex), true);
                            m.subtitleName = subNames.get(subIndex);
                            m.subtitleLang = guessLang(subNames.get(subIndex));
                        }
                        active.load(m);
                        ui.post(new Runnable() { public void run() { setStatus("يعمل الآن ✓", false); } });
                        return;
                    }
                    if (needsPhone) {
                        String ext = mime.contains("matroska") ? "mkv" : mime.contains("webm") ? "webm" : "mp4";
                        m.url = sServer.contentUrl(videoUri, ext);
                    } else if (viaProxy) {
                        m.url = sServer.proxyUrl(videoUri.toString());
                    } else {
                        m.url = videoUri.toString();
                    }
                    if (subIndex >= 0) {
                        m.subtitleUrl = sServer.subtitleUrl(subUrls.get(subIndex), chromecast);
                        m.subtitleName = subNames.get(subIndex);
                        m.subtitleLang = guessLang(subNames.get(subIndex));
                    }
                    ui.post(new Runnable() { public void run() { setStatus("جارٍ تشغيل الفيلم على التلفاز…", false); } });
                    try {
                        player.load(m);
                    } catch (Exception first) {
                        // Retry through the phone (adds CORS headers that Chromecast needs for some servers).
                        if (needsPhone || viaProxy || !videoUri.toString().startsWith("http")) throw first;
                        sServer.start();
                        sServer.setHeaders(headers);
                        m.url = sServer.proxyUrl(videoUri.toString());
                        player.load(m);
                    }
                    ui.post(new Runnable() { public void run() { setStatus("يعمل الآن ✓", false); } });
                } catch (final Exception e) {
                    ui.post(new Runnable() { public void run() { setStatus(msg(e), true); } });
                }
            }
        });
    }

    /** Containers the Cast receiver can't play with sound, or that we couldn't identify. */
    private static boolean needsInspection(String mime, boolean guessed) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.US);
        if (m.contains("mpegurl") || m.contains("dash")) return false;
        if (m.contains("matroska") || m.contains("x-msvideo") || m.contains("avi")) return true;
        return guessed;
    }

    /** Asks the server what the video really is (torrent links have no file extension). */
    private static String probeMime(String url, Map<String, String> headers) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            c.setRequestProperty("Range", "bytes=0-0");
            c.getResponseCode();
            String t = c.getContentType();
            String disp = c.getHeaderField("Content-Disposition");
            c.disconnect();
            String name = (disp == null ? "" : disp).toLowerCase(Locale.US);
            if (name.contains(".mkv")) return "video/x-matroska";
            if (name.contains(".mp4") || name.contains(".m4v")) return "video/mp4";
            if (name.contains(".webm")) return "video/webm";
            if (t == null) return null;
            t = t.split(";")[0].trim().toLowerCase(Locale.US);
            if (t.startsWith("video/") || t.contains("mpegurl") || t.contains("dash")) return t;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String guessLang(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.contains("arab") || n.contains("عرب") || n.startsWith("ar")) return "ar";
        if (n.contains("eng") || n.startsWith("en")) return "en";
        if (n.contains("fr")) return "fr";
        return null;
    }

    private void togglePlay() {
        final RemotePlayer p = sPlayer;
        if (p == null) return;
        final boolean playing = p.state() == RemotePlayer.PLAYING;
        playBtn.setText(playing ? "▶" : "⏸");
        run(new Task() { public void go() throws Exception { if (playing) p.pause(); else p.play(); } });
    }

    private void skip(final long delta) {
        final RemotePlayer p = sPlayer;
        if (p == null) return;
        final long target = Math.max(0, p.positionMs() + delta);
        run(new Task() { public void go() throws Exception { p.seek(target); } });
    }

    private void volume(final int dir) {
        final RemotePlayer p = sPlayer;
        if (p == null) return;
        run(new Task() { public void go() throws Exception { p.changeVolume(dir); } });
    }

    private void stopCasting() {
        final RemotePlayer p = sPlayer;
        if (p == null) { finishWithResult(false); return; }
        startMs = p.positionMs();
        io.execute(new Runnable() {
            public void run() {
                try { p.stop(); } catch (Exception ignored) {}
                p.release();
                if (sServer != null) { sServer.stop(); sServer = null; }
                ui.post(new Runnable() { public void run() { finishWithResult(false); } });
            }
        });
        lastPosition = p.positionMs();
        lastDuration = p.durationMs();
        sPlayer = null;
        sTarget = null;
    }

    private long lastPosition, lastDuration;

    private void setStatus(String s, boolean error) {
        statusText.setText(s);
        statusText.setTextColor(error ? DANGER : MUTED);
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            updateRemote();
            final RemotePlayer p = sPlayer;
            if (p != null && remoteSection.getVisibility() == View.VISIBLE) {
                io.execute(new Runnable() { public void run() { try { p.refresh(); } catch (Exception ignored) {} } });
            }
            ui.postDelayed(this, 1000);
        }
    };

    private void updateRemote() {
        RemotePlayer p = sPlayer;
        if (p == null || remoteSection == null) return;
        long pos = p.positionMs(), dur = p.durationMs();
        lastPosition = pos;
        lastDuration = dur;
        if (!dragging) {
            timeText.setText(fmt(pos));
            if (dur > 0) seekBar.setProgress((int) (pos * 1000 / dur));
        }
        durText.setText(dur > 0 ? fmt(dur) : "--:--");
        int st = p.state();
        playBtn.setText(st == RemotePlayer.PLAYING || st == RemotePlayer.LOADING ? "⏸" : "▶");
        if (st == RemotePlayer.PLAYING) setStatus("يعمل الآن ✓", false);
        else if (st == RemotePlayer.PAUSED) setStatus("متوقف مؤقتاً", false);
        else if (st == RemotePlayer.ERROR && p.error() != null) setStatus(p.error(), true);
        else if (st == RemotePlayer.ENDED && !finishedReported) {
            finishedReported = true;
            setStatus("انتهى الفيلم", false);
        }
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** Hands the watch position back to Nuvio (same extras as MX Player / VLC). */
    private void finishWithResult(boolean unused) {
        RemotePlayer p = sPlayer;
        long pos = p != null ? p.positionMs() : lastPosition;
        long dur = p != null ? p.durationMs() : lastDuration;
        boolean ended = (p != null && p.state() == RemotePlayer.ENDED) || (dur > 0 && pos >= dur - 30000);
        Intent r = new Intent();
        if (pos > 0) {
            r.putExtra("position", (int) Math.min(Integer.MAX_VALUE, pos));
            r.putExtra("extra_position", pos);
        }
        if (dur > 0) {
            r.putExtra("duration", (int) Math.min(Integer.MAX_VALUE, dur));
            r.putExtra("extra_duration", dur);
        }
        r.putExtra("end_by", ended ? "playback_completion" : "user");
        setResult(RESULT_OK, r);
        finish();
    }
}
