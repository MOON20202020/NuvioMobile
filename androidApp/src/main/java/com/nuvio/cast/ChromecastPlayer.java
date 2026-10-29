package com.nuvio.cast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal Google Cast (CASTV2) sender. Launches the Default Media Receiver on the
 * Chromecast / Google TV and controls playback. No Google Play Services needed.
 */
public final class ChromecastPlayer implements RemotePlayer {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String NS_CONN = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECV = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    private static final String APP_ID = "CC1AD845"; // Default Media Receiver
    private static final String SENDER = "sender-nuvio";
    private static final String RECEIVER = "receiver-0";

    private final CastTarget target;
    private SSLSocket socket;
    private OutputStream out;
    private Thread reader;
    private Thread heartbeat;
    private volatile boolean closed;
    private int requestId = 1;

    private volatile String transportId;
    private volatile String sessionId;
    private volatile int mediaSessionId = -1;
    private volatile double volume = -1;
    private volatile int state = IDLE;
    private volatile long positionMs;
    private volatile long durationMs;
    private volatile long positionStamp;
    private volatile String error;
    private volatile boolean loadFailed;

    public ChromecastPlayer(CastTarget target) {
        this.target = target;
    }

    private synchronized void connect() throws Exception {
        if (socket != null) return;
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(target.host, target.port), 6000);
        s.setSoTimeout(0);
        s.startHandshake();
        socket = s;
        out = s.getOutputStream();
        send(NS_CONN, RECEIVER, new JSONObject().put("type", "CONNECT"));
        final DataInputStream in = new DataInputStream(s.getInputStream());
        reader = new Thread(new Runnable() {
            public void run() {
                try {
                    while (!closed) {
                        int len = in.readInt();
                        if (len <= 0 || len > 4_000_000) break;
                        byte[] buf = new byte[len];
                        in.readFully(buf);
                        onMessage(buf);
                    }
                } catch (Exception e) {
                    if (!closed) { error = "انقطع الاتصال بالجهاز"; state = ERROR; }
                }
            }
        }, "cast-reader");
        reader.setDaemon(true);
        reader.start();
        heartbeat = new Thread(new Runnable() {
            public void run() {
                try {
                    while (!closed) {
                        Thread.sleep(5000);
                        send(NS_HEART, RECEIVER, new JSONObject().put("type", "PING"));
                    }
                } catch (Exception ignored) {}
            }
        }, "cast-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    // ---------------------------------------------------------------- RemotePlayer

    @Override
    public void load(Media media) throws Exception {
        connect();
        state = LOADING;
        loadFailed = false;
        error = null;
        send(NS_RECV, RECEIVER, new JSONObject().put("type", "LAUNCH").put("appId", APP_ID).put("requestId", nextId()));
        long deadline = System.currentTimeMillis() + 20000;
        while (transportId == null) {
            if (System.currentTimeMillis() > deadline) throw new IOException("الجهاز لم يستجب");
            Thread.sleep(150);
        }
        send(NS_CONN, transportId, new JSONObject().put("type", "CONNECT"));

        JSONObject meta = new JSONObject().put("metadataType", 0).put("title", media.title == null ? "Nuvio" : media.title);
        JSONObject m = new JSONObject()
                .put("contentId", media.url)
                .put("contentUrl", media.url)
                .put("streamType", "BUFFERED")
                .put("contentType", media.mime)
                .put("metadata", meta);
        JSONObject load = new JSONObject()
                .put("type", "LOAD")
                .put("requestId", nextId())
                .put("sessionId", sessionId)
                .put("autoplay", true)
                .put("currentTime", media.startMs / 1000.0);
        if (media.subtitleUrl != null) {
            JSONObject track = new JSONObject()
                    .put("trackId", 1)
                    .put("type", "TEXT")
                    .put("trackContentId", media.subtitleUrl)
                    .put("trackContentType", "text/vtt")
                    .put("subtype", "SUBTITLES")
                    .put("name", media.subtitleName == null ? "Subtitles" : media.subtitleName);
            if (media.subtitleLang != null) track.put("language", media.subtitleLang);
            m.put("tracks", new JSONArray().put(track));
            m.put("textTrackStyle", new JSONObject()
                    .put("backgroundColor", "#00000000")
                    .put("edgeType", "OUTLINE")
                    .put("edgeColor", "#000000FF")
                    .put("foregroundColor", "#FFFFFFFF")
                    .put("fontScale", 1.1));
            load.put("activeTrackIds", new JSONArray().put(1));
        }
        load.put("media", m);
        mediaSessionId = -1;
        send(NS_MEDIA, transportId, load);
        deadline = System.currentTimeMillis() + 30000;
        while (mediaSessionId < 0 && !loadFailed) {
            if (System.currentTimeMillis() > deadline) throw new IOException("انتهت مهلة تحميل الفيلم على التلفاز");
            Thread.sleep(150);
        }
        if (loadFailed) throw new IOException(error == null ? "التلفاز لم يستطع تشغيل هذا الرابط" : error);
    }

    @Override public void play() throws Exception { mediaCmd("PLAY", null); }
    @Override public void pause() throws Exception { mediaCmd("PAUSE", null); }

    @Override
    public void seek(long ms) throws Exception {
        mediaCmd("SEEK", new JSONObject().put("currentTime", Math.max(0, ms) / 1000.0));
        positionMs = Math.max(0, ms);
        positionStamp = System.currentTimeMillis();
    }

    @Override
    public void stop() throws Exception {
        if (sessionId != null) {
            send(NS_RECV, RECEIVER, new JSONObject().put("type", "STOP").put("sessionId", sessionId).put("requestId", nextId()));
        }
        state = IDLE;
    }

    @Override
    public void changeVolume(int direction) throws Exception {
        double v = volume < 0 ? 0.5 : volume;
        v = Math.max(0, Math.min(1, v + direction * 0.05));
        volume = v;
        send(NS_RECV, RECEIVER, new JSONObject().put("type", "SET_VOLUME").put("requestId", nextId())
                .put("volume", new JSONObject().put("level", v)));
    }

    @Override
    public void refresh() throws Exception {
        if (transportId == null || mediaSessionId < 0) return;
        send(NS_MEDIA, transportId, new JSONObject().put("type", "GET_STATUS").put("requestId", nextId())
                .put("mediaSessionId", mediaSessionId));
    }

    @Override
    public long positionMs() {
        long p = positionMs;
        if (state == PLAYING && positionStamp > 0) p += System.currentTimeMillis() - positionStamp;
        if (durationMs > 0) p = Math.min(p, durationMs);
        return p;
    }

    @Override public long durationMs() { return durationMs; }
    @Override public int state() { return state; }
    @Override public String error() { return error; }

    @Override
    public void release() {
        closed = true;
        try {
            if (transportId != null) send(NS_CONN, transportId, new JSONObject().put("type", "CLOSE"));
        } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- protocol

    private synchronized int nextId() { return ++requestId; }

    private void mediaCmd(String type, JSONObject extra) throws Exception {
        if (transportId == null || mediaSessionId < 0) return;
        JSONObject o = extra == null ? new JSONObject() : extra;
        o.put("type", type).put("requestId", nextId()).put("mediaSessionId", mediaSessionId);
        send(NS_MEDIA, transportId, o);
    }

    private void onMessage(byte[] buf) throws Exception {
        String ns = null, src = null, payload = null;
        int i = 0;
        while (i < buf.length) {
            long key = 0; int shift = 0; int b;
            do { b = buf[i++] & 0xff; key |= (long) (b & 0x7f) << shift; shift += 7; } while ((b & 0x80) != 0);
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (wire == 0) {
                do { b = buf[i++] & 0xff; } while ((b & 0x80) != 0);
            } else if (wire == 2) {
                int len = 0; shift = 0;
                do { b = buf[i++] & 0xff; len |= (b & 0x7f) << shift; shift += 7; } while ((b & 0x80) != 0);
                String v = new String(buf, i, len, UTF8);
                i += len;
                if (field == 2) src = v;
                else if (field == 4) ns = v;
                else if (field == 6) payload = v;
            } else {
                return; // unexpected
            }
        }
        if (payload == null || ns == null) return;
        JSONObject msg = new JSONObject(payload);
        String type = msg.optString("type");
        if (NS_HEART.equals(ns)) {
            if ("PING".equals(type)) send(NS_HEART, src == null ? RECEIVER : src, new JSONObject().put("type", "PONG"));
        } else if (NS_RECV.equals(ns) && "RECEIVER_STATUS".equals(type)) {
            JSONObject st = msg.optJSONObject("status");
            if (st == null) return;
            JSONObject vol = st.optJSONObject("volume");
            if (vol != null && vol.has("level")) volume = vol.optDouble("level", volume);
            JSONArray apps = st.optJSONArray("applications");
            boolean found = false;
            if (apps != null) {
                for (int k = 0; k < apps.length(); k++) {
                    JSONObject a = apps.getJSONObject(k);
                    if (APP_ID.equals(a.optString("appId"))) {
                        sessionId = a.optString("sessionId");
                        transportId = a.optString("transportId");
                        found = true;
                    }
                }
            }
            if (!found && transportId != null && mediaSessionId >= 0) {
                state = ENDED; // receiver app was closed on the TV
            }
        } else if (NS_MEDIA.equals(ns)) {
            if ("MEDIA_STATUS".equals(type)) {
                JSONArray arr = msg.optJSONArray("status");
                if (arr == null || arr.length() == 0) return;
                JSONObject st = arr.getJSONObject(0);
                if (st.has("mediaSessionId")) mediaSessionId = st.getInt("mediaSessionId");
                if (st.has("currentTime")) {
                    positionMs = (long) (st.getDouble("currentTime") * 1000);
                    positionStamp = System.currentTimeMillis();
                }
                JSONObject media = st.optJSONObject("media");
                if (media != null && media.has("duration")) {
                    double d = media.optDouble("duration", 0);
                    if (d > 0) durationMs = (long) (d * 1000);
                }
                JSONObject vol = st.optJSONObject("volume");
                if (vol != null && vol.has("level")) volume = vol.optDouble("level", volume);
                String ps = st.optString("playerState");
                if ("PLAYING".equals(ps)) state = PLAYING;
                else if ("PAUSED".equals(ps)) state = PAUSED;
                else if ("BUFFERING".equals(ps)) state = LOADING;
                else if ("IDLE".equals(ps)) {
                    String reason = st.optString("idleReason");
                    if ("FINISHED".equals(reason)) state = ENDED;
                    else if ("ERROR".equals(reason)) { state = ERROR; error = "حدث خطأ أثناء التشغيل على التلفاز"; loadFailed = true; }
                    else if ("CANCELLED".equals(reason) || "INTERRUPTED".equals(reason)) state = IDLE;
                }
            } else if ("LOAD_FAILED".equals(type) || "LOAD_CANCELLED".equals(type) || "INVALID_REQUEST".equals(type)) {
                error = "التلفاز لم يستطع تشغيل هذا الرابط";
                loadFailed = true;
                state = ERROR;
            }
        }
    }

    private void send(String ns, String dest, JSONObject json) throws IOException {
        byte[] payload = json.toString().getBytes(UTF8);
        ByteArrayOutputStream b = new ByteArrayOutputStream(payload.length + 128);
        writeVarintField(b, 1, 0);          // protocol_version CASTV2_1_0
        writeString(b, 2, SENDER);
        writeString(b, 3, dest);
        writeString(b, 4, ns);
        writeVarintField(b, 5, 0);          // payload_type STRING
        writeBytes(b, 6, payload);
        byte[] msg = b.toByteArray();
        synchronized (this) {
            if (out == null) throw new IOException("not connected");
            out.write(new byte[]{(byte) (msg.length >>> 24), (byte) (msg.length >>> 16), (byte) (msg.length >>> 8), (byte) msg.length});
            out.write(msg);
            out.flush();
        }
    }

    private static void writeVarint(ByteArrayOutputStream b, long v) {
        while ((v & ~0x7FL) != 0) { b.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        b.write((int) v);
    }

    private static void writeVarintField(ByteArrayOutputStream b, int field, long v) {
        writeVarint(b, (field << 3));
        writeVarint(b, v);
    }

    private static void writeString(ByteArrayOutputStream b, int field, String s) {
        writeBytes(b, field, s.getBytes(UTF8));
    }

    private static void writeBytes(ByteArrayOutputStream b, int field, byte[] data) {
        writeVarint(b, (field << 3) | 2);
        writeVarint(b, data.length);
        b.write(data, 0, data.length);
    }
}
