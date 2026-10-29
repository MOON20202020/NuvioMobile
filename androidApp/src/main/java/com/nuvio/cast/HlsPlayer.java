package com.nuvio.cast;

import com.nuvio.app.features.cast.CastHlsSession;

import java.util.List;

/**
 * Plays a video on Chromecast / Google TV through a live HLS stream built on the phone.
 * Used when subtitles embedded in the file must reach the TV. Stream time 0 equals
 * {@code offsetMs} in the movie; long seeks restart the stream at the new position.
 */
final class HlsPlayer implements RemotePlayer {
    private final RemotePlayer inner;
    private final LocalServer server;
    private final String source;
    private final long durationMs;
    private final int audioOrdinal;
    private final int subtitleOrdinal;
    private final String subtitleName;
    private final String subtitleLang;
    private final List<LocalServer.Cue> externalCues;
    private Media media;
    private volatile CastHlsSession session;
    private volatile long offsetMs;

    HlsPlayer(RemotePlayer inner, LocalServer server, String source, long durationMs, int audioOrdinal,
              int subtitleOrdinal, String subtitleName, String subtitleLang, List<LocalServer.Cue> externalCues) {
        this.inner = inner;
        this.server = server;
        this.source = source;
        this.durationMs = durationMs;
        this.audioOrdinal = audioOrdinal;
        this.subtitleOrdinal = subtitleOrdinal;
        this.subtitleName = subtitleName == null ? "Subtitles" : subtitleName;
        this.subtitleLang = subtitleLang;
        this.externalCues = externalCues;
    }

    @Override
    public void load(Media m) throws Exception {
        media = m;
        startAt(Math.max(0, m.startMs));
    }

    private void startAt(long startMs) throws Exception {
        if (durationMs > 0) startMs = Math.min(startMs, Math.max(0, durationMs - 10000));
        CastHlsSession s = server.startHls(source, startMs, audioOrdinal, subtitleOrdinal, subtitleName, subtitleLang);
        if (externalCues != null) {
            for (LocalServer.Cue c : externalCues) s.addCue(c.start - startMs, c.end - startMs, c.text);
        }
        s.start();
        session = s;
        offsetMs = startMs;
        if (!s.awaitReady(90000)) {
            String err = s.getError();
            throw new Exception(err != null ? "تعذر تجهيز الفيلم: " + err : "الفيلم يتأخر في التحميل، حاول مرة أخرى");
        }
        Media c = new Media();
        c.url = server.hlsUrl(s);
        c.mime = "application/x-mpegurl";
        c.hls = true;
        c.hlsSubtitles = s.getPublishesSubtitles();
        c.title = media.title;
        c.startMs = 0;
        inner.load(c);
    }

    @Override public void play() throws Exception { inner.play(); }
    @Override public void pause() throws Exception { inner.pause(); }

    @Override
    public void seek(long positionMs) throws Exception {
        long target = Math.max(0, positionMs);
        if (durationMs > 0) target = Math.min(target, durationMs - 5000);
        CastHlsSession s = session;
        long[] range = s == null ? null : s.availableRangeMs();
        long rel = target - offsetMs;
        if (range != null && rel >= range[0] && rel <= range[1] - 2000) {
            inner.seek(rel); // already on the TV: instant seek
        } else {
            startAt(target);
        }
    }

    @Override public void stop() throws Exception { server.stopHls(); inner.stop(); }
    @Override public void changeVolume(int direction) throws Exception { inner.changeVolume(direction); }
    @Override public void refresh() throws Exception { inner.refresh(); }

    @Override
    public long positionMs() {
        long p = offsetMs + inner.positionMs();
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }

    @Override public long durationMs() { return durationMs > 0 ? durationMs : inner.durationMs(); }

    @Override
    public int state() {
        int s = inner.state();
        if (s == ENDED && durationMs > 0 && positionMs() < durationMs - 60000) return IDLE;
        return s;
    }

    @Override public String error() { return inner.error(); }
    @Override public void release() { inner.release(); }
}
