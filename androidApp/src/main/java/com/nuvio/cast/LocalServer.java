package com.nuvio.cast;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tiny HTTP server on the phone. It lets the TV:
 *  - load subtitles (converted SRT -> WebVTT for Chromecast, with CORS),
 *  - play streams that need custom headers or live on the phone itself (localhost / downloaded files).
 */
public final class LocalServer {
    private final ContentResolver resolver;
    private ServerSocket server;
    private String host;
    private final Map<String, String> headers = new LinkedHashMap<String, String>();
    private final List<String> subtitles = Collections.synchronizedList(new ArrayList<String>());
    private volatile Uri localContent;
    private volatile boolean running;

    private final android.content.Context context;
    private volatile String remuxSource;
    private volatile int remuxAudio;
    private java.util.concurrent.atomic.AtomicBoolean remuxCancel;

    public LocalServer(android.content.Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    /**
     * URL on the phone that serves {@code source} re-packaged as MP4 starting at {@code startMs}
     * (fixes MKV playing without sound on Chromecast / Google TV).
     */
    public String remuxUrl(String source, long startMs, int audioOrdinal) {
        remuxSource = source;
        remuxAudio = audioOrdinal;
        return base() + "/remux/" + startMs + "/" + System.nanoTime() + ".mp4";
    }

    public synchronized void start() throws Exception {
        if (server != null) return;
        host = System.getProperty("nuviocast.host");
        if (host == null) host = findLanAddress();
        if (host == null) throw new Exception("الهاتف غير متصل بشبكة واي فاي");
        server = new ServerSocket(0);
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        Thread h = new Thread(new Runnable() { public void run() { handle(s); } }, "cast-http");
                        h.setDaemon(true);
                        h.start();
                    } catch (Exception e) {
                        if (!running) return;
                    }
                }
            }
        }, "cast-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        stopRemux();
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }

    public void setHeaders(Map<String, String> h) {
        synchronized (headers) { headers.clear(); headers.putAll(h); }
    }

    private String base() {
        return "http://" + host + ":" + server.getLocalPort();
    }

    /** URL on the phone that proxies {@code original} (keeping its path, so relative HLS segments still work). */
    public String proxyUrl(String original) throws Exception {
        java.net.URI u = new java.net.URI(original);
        String origin = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        String enc = Base64.encodeToString(origin.getBytes("UTF-8"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        return base() + "/p/" + enc + path + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
    }

    /** URL on the phone serving a content:// or file:// video. */
    public String contentUrl(Uri uri, String ext) {
        localContent = uri;
        return base() + "/local/video." + ext;
    }

    /** Registers a subtitle and returns its URL ({@code vtt} for Chromecast, otherwise SRT). */
    public String subtitleUrl(String original, boolean vtt) {
        return subtitleUrl(original, vtt, 0);
    }

    /** Subtitle URL whose cue times are moved {@code shiftMs} earlier (for streams that restart at 0). */
    public String subtitleUrl(String original, boolean vtt, long shiftMs) {
        int idx;
        synchronized (subtitles) {
            idx = subtitles.indexOf(original);
            if (idx < 0) { subtitles.add(original); idx = subtitles.size() - 1; }
        }
        return base() + "/sub/" + idx + "_" + Math.max(0, shiftMs) + (vtt ? ".vtt" : ".srt");
    }

    // ------------------------------------------------------------------ request handling

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) { s.close(); return; }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { s.close(); return; }
            String method = parts[0];
            String path = parts[1];
            Map<String, String> req = new LinkedHashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0) req.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
            }
            if (method.equals("OPTIONS")) {
                writeHead(out, 200, "OK", "text/plain", 0, null);
            } else if (path.startsWith("/sub/")) {
                serveSubtitle(out, path);
            } else if (path.startsWith("/p/")) {
                serveProxy(out, method, path, req);
            } else if (path.startsWith("/remux/")) {
                serveRemux(out, method, path);
            } else if (path.startsWith("/local/")) {
                serveLocal(out, method, req);
            } else {
                writeHead(out, 404, "Not Found", "text/plain", 0, null);
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void serveSubtitle(OutputStream out, String path) throws Exception {
        String name = path.substring(5);
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        boolean vtt = name.endsWith(".vtt");
        String stem = name.substring(0, name.indexOf('.'));
        long shiftMs = 0;
        int us = stem.indexOf('_');
        if (us >= 0) { shiftMs = Long.parseLong(stem.substring(us + 1)); stem = stem.substring(0, us); }
        String src = subtitles.get(Integer.parseInt(stem));
        byte[] raw;
        if (src.startsWith("http")) {
            HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NuvioCast");
            c.setRequestProperty("Accept-Encoding", "identity");
            InputStream is = c.getInputStream();
            raw = readBytes(is);
            c.disconnect();
        } else {
            raw = readBytes(resolver.openInputStream(Uri.parse(src)));
        }
        if (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) {
            raw = readBytes(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(raw)));
        }
        String text = decode(raw);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        List<Cue> cues = SubtitleCues.parse(text);
        String body = vtt ? SubtitleCues.toVtt(cues, shiftMs) : SubtitleCues.toSrt(cues, shiftMs);
        byte[] data = body.getBytes("UTF-8");
        writeHead(out, 200, "OK", vtt ? "text/vtt; charset=utf-8" : "application/x-subrip; charset=utf-8", data.length, null);
        out.write(data);
    }

    static final class Cue {
        long start, end;
        String text;
    }

    private void serveProxy(OutputStream out, String method, String path, Map<String, String> req) throws Exception {
        String rest = path.substring(3);
        int slash = rest.indexOf('/');
        String enc = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "/" : rest.substring(slash);
        String origin = new String(Base64.decode(enc, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(origin + tail).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method.equals("HEAD") ? "HEAD" : "GET");
        synchronized (headers) {
            for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        }
        if (req.containsKey("range")) c.setRequestProperty("Range", req.get("range"));
        int code = c.getResponseCode();
        Map<String, String> extra = new LinkedHashMap<String, String>();
        String cr = c.getHeaderField("Content-Range");
        if (cr != null) extra.put("Content-Range", cr);
        String type = c.getContentType();
        long len = c.getContentLengthLong();
        InputStream body = code >= 400 ? c.getErrorStream() : c.getInputStream();
        writeHead(out, code, code < 300 ? "OK" : "Error", type == null ? "application/octet-stream" : type, len, extra);
        if (!method.equals("HEAD") && body != null) copy(body, out, -1);
        if (body != null) body.close();
        c.disconnect();
    }

    private void serveRemux(OutputStream out, String method, String path) throws Exception {
        String[] parts = path.split("/");
        long startMs = Long.parseLong(parts[2]);
        String src = remuxSource;
        if (src == null) { writeHead(out, 404, "Not Found", "text/plain", 0, null); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nAccept-Ranges: none\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\n");
        sb.append("Cache-Control: no-cache\r\nConnection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if (method.equals("HEAD")) return;
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        synchronized (this) {
            if (remuxCancel != null) remuxCancel.set(true); // only one TV stream at a time
            remuxCancel = cancel;
        }
        Map<String, String> h;
        synchronized (headers) { h = new LinkedHashMap<String, String>(headers); }
        com.nuvio.app.features.cast.CastRemuxer.remux(context, src, h, startMs, remuxAudio,
                new java.io.BufferedOutputStream(out, 256 * 1024), cancel);
    }

    public void stopRemux() {
        synchronized (this) {
            if (remuxCancel != null) remuxCancel.set(true);
        }
    }

    private void serveLocal(OutputStream out, String method, Map<String, String> req) throws Exception {
        Uri uri = localContent;
        AssetFileDescriptor afd;
        if ("file".equals(uri.getScheme())) {
            afd = null;
        } else {
            afd = resolver.openAssetFileDescriptor(uri, "r");
        }
        FileInputStream fis;
        long total;
        if (afd != null) {
            fis = afd.createInputStream();
            total = afd.getLength();
            if (total < 0) total = fis.getChannel().size();
        } else {
            java.io.File f = new java.io.File(uri.getPath());
            fis = new FileInputStream(f);
            total = f.length();
        }
        long start = 0, end = total - 1;
        int code = 200;
        Map<String, String> extra = new LinkedHashMap<String, String>();
        String range = req.get("range");
        if (range != null && range.startsWith("bytes=")) {
            String[] r = range.substring(6).split("-", -1);
            if (r[0].length() > 0) start = Long.parseLong(r[0].trim());
            if (r.length > 1 && r[1].trim().length() > 0) end = Long.parseLong(r[1].trim());
            if (r[0].length() == 0 && r.length > 1) { start = total - Long.parseLong(r[1].trim()); end = total - 1; }
            end = Math.min(end, total - 1);
            code = 206;
            extra.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
        }
        String path = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment().toLowerCase(Locale.US);
        String type = path.endsWith(".mkv") ? "video/x-matroska" : path.endsWith(".webm") ? "video/webm" : "video/mp4";
        writeHead(out, code, code == 206 ? "Partial Content" : "OK", type, end - start + 1, extra);
        if (!method.equals("HEAD")) {
            fis.getChannel().position(fis.getChannel().position() + start);
            copy(fis, out, end - start + 1);
        }
        fis.close();
        if (afd != null) afd.close();
    }

    private static void writeHead(OutputStream out, int code, String msg, String type, long len, Map<String, String> extra) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Headers: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range\r\n");
        sb.append("transferMode.dlna.org: Streaming\r\n");
        sb.append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
    }

    private static void copy(InputStream in, OutputStream out, long limit) throws Exception {
        byte[] buf = new byte[64 * 1024];
        long left = limit;
        while (limit < 0 || left > 0) {
            int want = limit < 0 ? buf.length : (int) Math.min(buf.length, left);
            int n = in.read(buf, 0, want);
            if (n < 0) break;
            out.write(buf, 0, n);
            if (limit >= 0) left -= n;
        }
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    /** UTF-8 if valid, otherwise Windows-1256 (common for Arabic subtitles). */
    private static String decode(byte[] raw) {
        try {
            CharBuffer cb = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw));
            return cb.toString();
        } catch (CharacterCodingException e) {
            try { return new String(raw, "windows-1256"); } catch (Exception e2) { return new String(raw); }
        }
    }

    private static String vttToSrt(String vtt) {
        String[] blocks = vtt.split("\n\n+");
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (String b : blocks) {
            int arrow = b.indexOf("-->");
            if (arrow < 0) continue;
            String[] lines = b.split("\n");
            int ti = 0;
            while (ti < lines.length && !lines[ti].contains("-->")) ti++;
            String[] times = lines[ti].split("-->");
            sb.append(n++).append('\n')
              .append(fixTime(times[0].trim())).append(" --> ").append(fixTime(times[1].trim().split(" ")[0])).append('\n');
            for (int i = ti + 1; i < lines.length; i++) sb.append(lines[i]).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String fixTime(String t) {
        if (t.split(":").length == 2) t = "00:" + t;
        return t.replace('.', ',');
    }

    private static String findLanAddress() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String name = ni.getName().toLowerCase(Locale.US);
                    if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}
