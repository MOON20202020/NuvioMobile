package com.nuvio.cast;

/**
 * Wraps a Chromecast player that receives a live re-packaged MP4 stream from the phone.
 * The stream always starts at 0, so this keeps the real start offset and implements
 * seeking by restarting the stream at the new position.
 */
final class RemuxPlayer implements RemotePlayer {
    private final RemotePlayer inner;
    private final LocalServer server;
    private final String source;
    private final long durationMs;
    private final int audioOrdinal;
    private Media media;
    private volatile long offsetMs;

    RemuxPlayer(RemotePlayer inner, LocalServer server, String source, long durationMs, int audioOrdinal) {
        this.inner = inner;
        this.server = server;
        this.source = source;
        this.durationMs = durationMs;
        this.audioOrdinal = audioOrdinal;
    }

    @Override
    public void load(Media m) throws Exception {
        media = m;
        offsetMs = Math.max(0, m.startMs);
        Media copy = copyAt(offsetMs);
        inner.load(copy);
    }

    private Media copyAt(long startMs) {
        Media c = new Media();
        c.url = server.remuxUrl(source, startMs, audioOrdinal);
        c.mime = "video/mp4";
        c.title = media.title;
        c.startMs = 0;
        c.subtitleUrl = media.subtitleUrl;
        c.subtitleName = media.subtitleName;
        c.subtitleLang = media.subtitleLang;
        return c;
    }

    @Override public void play() throws Exception { inner.play(); }
    @Override public void pause() throws Exception { inner.pause(); }

    @Override
    public void seek(long positionMs) throws Exception {
        long target = Math.max(0, positionMs);
        if (durationMs > 0) target = Math.min(target, durationMs - 5000);
        offsetMs = target;
        inner.load(copyAt(target));
    }

    @Override public void stop() throws Exception { server.stopRemux(); inner.stop(); }
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
        // A live re-packaged stream may report "finished" early if the phone stopped sending.
        if (s == ENDED && durationMs > 0 && positionMs() < durationMs - 60000) return IDLE;
        return s;
    }

    @Override public String error() { return inner.error(); }
    @Override public void release() { inner.release(); }
}
