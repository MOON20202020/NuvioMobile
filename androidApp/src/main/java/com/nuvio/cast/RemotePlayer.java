package com.nuvio.cast;

/** Common controls for a video playing on a TV. All methods block; call them off the UI thread. */
public interface RemotePlayer {
    int IDLE = 0, LOADING = 1, PLAYING = 2, PAUSED = 3, ENDED = 4, ERROR = 5;

    void load(Media media) throws Exception;
    void play() throws Exception;
    void pause() throws Exception;
    void seek(long positionMs) throws Exception;
    void stop() throws Exception;
    void changeVolume(int direction) throws Exception;
    /** Refresh position/state from the device. */
    void refresh() throws Exception;
    long positionMs();
    long durationMs();
    int state();
    String error();
    /** Disconnect from the device without stopping playback. */
    void release();

    final class Media {
        public String url;
        public String mime;
        public String title;
        public long startMs;
        public String subtitleUrl;      // WebVTT (Chromecast) or SRT (DLNA)
        public String subtitleSource;   // original subtitle link (re-served with a time shift)
        public boolean hls;             // fMP4 HLS stream built on the phone
        public boolean hlsSubtitles;    // the HLS stream carries a subtitle track to switch on
        public String subtitleName;
        public String subtitleLang;
    }
}
