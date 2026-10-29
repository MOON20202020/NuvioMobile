package com.nuvio.cast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UPnP / DLNA media renderer control (Samsung, LG, Sony, Philips, Hisense, TCL and others). */
public final class DlnaPlayer implements RemotePlayer {
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";

    private final CastTarget target;
    private volatile int state = IDLE;
    private volatile long positionMs;
    private volatile long durationMs;
    private volatile long stamp;
    private volatile String error;
    private volatile boolean sawPlaying;
    private int volume = -1;

    public DlnaPlayer(CastTarget target) {
        this.target = target;
    }

    @Override
    public void load(Media media) throws Exception {
        state = LOADING;
        error = null;
        try { soap(target.avTransportUrl, AVT, "Stop", "<InstanceID>0</InstanceID>"); } catch (Exception ignored) {}
        String mime = media.mime == null || media.mime.endsWith("/*") ? "video/mp4" : media.mime;
        if (mime.contains("mpegURL") || mime.contains("mpegurl")) mime = "application/vnd.apple.mpegurl";
        String protocol = "http-get:*:" + mime + ":DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
        StringBuilder didl = new StringBuilder();
        didl.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            .append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
            .append("xmlns:sec=\"http://www.sec.co.kr/\" xmlns:pv=\"http://www.pv.com/pvns/\">")
            .append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            .append("<dc:title>").append(esc(media.title == null ? "Nuvio" : media.title)).append("</dc:title>")
            .append("<upnp:class>object.item.videoItem.movie</upnp:class>");
        if (media.subtitleUrl != null) {
            didl.append("<sec:CaptionInfoEx sec:type=\"srt\">").append(esc(media.subtitleUrl)).append("</sec:CaptionInfoEx>")
                .append("<sec:CaptionInfo sec:type=\"srt\">").append(esc(media.subtitleUrl)).append("</sec:CaptionInfo>");
        }
        didl.append("<res protocolInfo=\"").append(esc(protocol)).append("\"");
        if (media.subtitleUrl != null) {
            didl.append(" pv:subtitleFileUri=\"").append(esc(media.subtitleUrl)).append("\" pv:subtitleFileType=\"srt\"");
        }
        didl.append(">").append(esc(media.url)).append("</res>");
        if (media.subtitleUrl != null) {
            didl.append("<res protocolInfo=\"http-get:*:text/srt:*\">").append(esc(media.subtitleUrl)).append("</res>");
        }
        didl.append("</item></DIDL-Lite>");

        soap(target.avTransportUrl, AVT, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>" + esc(media.url) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + esc(didl.toString()) + "</CurrentURIMetaData>");
        soap(target.avTransportUrl, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
        state = PLAYING;
        if (media.startMs > 5000) {
            // Most TVs refuse to seek until playback actually started.
            for (int i = 0; i < 20; i++) {
                Thread.sleep(1000);
                try {
                    refresh();
                    if (durationMs > 0 && sawPlaying) break;
                } catch (Exception ignored) {}
            }
            try { seek(media.startMs); } catch (Exception ignored) {}
        }
    }

    @Override public void play() throws Exception {
        soap(target.avTransportUrl, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
        state = PLAYING;
    }

    @Override public void pause() throws Exception {
        soap(target.avTransportUrl, AVT, "Pause", "<InstanceID>0</InstanceID>");
        state = PAUSED;
    }

    @Override public void seek(long ms) throws Exception {
        soap(target.avTransportUrl, AVT, "Seek",
                "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" + fmt(Math.max(0, ms)) + "</Target>");
        positionMs = Math.max(0, ms);
        stamp = System.currentTimeMillis();
    }

    @Override public void stop() throws Exception {
        soap(target.avTransportUrl, AVT, "Stop", "<InstanceID>0</InstanceID>");
        state = IDLE;
    }

    @Override public void changeVolume(int direction) throws Exception {
        if (target.renderingUrl == null) return;
        if (volume < 0) {
            String r = soap(target.renderingUrl, RC, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>");
            String v = tag(r, "CurrentVolume");
            volume = v == null ? 20 : Integer.parseInt(v.trim());
        }
        volume = Math.max(0, Math.min(100, volume + direction * 2));
        soap(target.renderingUrl, RC, "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>" + volume + "</DesiredVolume>");
    }

    @Override public void refresh() throws Exception {
        String pos = soap(target.avTransportUrl, AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>");
        long rel = parse(tag(pos, "RelTime"));
        long dur = parse(tag(pos, "TrackDuration"));
        if (rel >= 0) { positionMs = rel; stamp = System.currentTimeMillis(); }
        if (dur > 0) durationMs = dur;
        String tr = soap(target.avTransportUrl, AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>");
        String st = tag(tr, "CurrentTransportState");
        if (st != null) {
            if (st.equals("PLAYING")) { state = PLAYING; sawPlaying = true; }
            else if (st.startsWith("PAUSED")) state = PAUSED;
            else if (st.equals("TRANSITIONING")) state = LOADING;
            else if (st.equals("STOPPED") || st.equals("NO_MEDIA_PRESENT")) {
                if (sawPlaying) {
                    state = durationMs > 0 && positionMs > durationMs - 60000 ? ENDED : IDLE;
                }
            }
        }
    }

    @Override public long positionMs() {
        long p = positionMs;
        if (state == PLAYING && stamp > 0) p += Math.min(1500, System.currentTimeMillis() - stamp);
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }

    @Override public long durationMs() { return durationMs; }
    @Override public int state() { return state; }
    @Override public String error() { return error; }
    @Override public void release() {}

    // ------------------------------------------------------------------ helpers

    static String soap(String url, String service, String action, String args) throws Exception {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + service + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + service + "#" + action + "\"");
        byte[] data = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(data.length);
        OutputStream o = c.getOutputStream();
        o.write(data);
        o.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = in == null ? "" : readAll(in);
        c.disconnect();
        if (code >= 400) {
            String desc = tag(resp, "errorDescription");
            throw new Exception("التلفاز رفض الأمر (" + action + (desc != null ? ": " + desc : "") + ")");
        }
        return resp;
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    static String tag(String xml, String name) {
        if (xml == null) return null;
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + "(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static long parse(String t) {
        if (t == null) return -1;
        t = t.trim();
        if (t.isEmpty() || t.startsWith("NOT")) return -1;
        try {
            String[] p = t.split(":");
            double total = 0;
            for (String part : p) total = total * 60 + Double.parseDouble(part);
            return (long) (total * 1000);
        } catch (Exception e) {
            return -1;
        }
    }
}
