package com.nuvio.cast;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds Chromecast devices (mDNS) and DLNA TVs (SSDP) on the local network. */
public final class Discovery {
    public interface Listener {
        void onFound(CastTarget target);
    }

    private final NsdManager nsd;
    private final Listener listener;
    private NsdManager.DiscoveryListener nsdListener;
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<NsdServiceInfo>();
    private boolean resolving;
    private volatile boolean running;
    private final Set<String> seenLocations = new HashSet<String>();

    public Discovery(Context ctx, Listener listener) {
        this.nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public void start() {
        stop();
        running = true;
        synchronized (seenLocations) { seenLocations.clear(); }
        startChromecast();
        Thread t = new Thread(new Runnable() {
            public void run() { ssdpSearch(); }
        }, "ssdp");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        if (nsdListener != null) {
            try { nsd.stopServiceDiscovery(nsdListener); } catch (Exception ignored) {}
            nsdListener = null;
        }
        synchronized (resolveQueue) { resolveQueue.clear(); resolving = false; }
    }

    // ------------------------------------------------------------------ Chromecast

    private void startChromecast() {
        if (nsd == null) return;
        nsdListener = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String s, int e) {}
            public void onStopDiscoveryFailed(String s, int e) {}
            public void onDiscoveryStarted(String s) {}
            public void onDiscoveryStopped(String s) {}
            public void onServiceLost(NsdServiceInfo info) {}
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (resolveQueue) { resolveQueue.add(info); }
                resolveNext();
            }
        };
        try {
            nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception ignored) {}
    }

    private void resolveNext() {
        final NsdServiceInfo next;
        synchronized (resolveQueue) {
            if (resolving || resolveQueue.isEmpty() || !running) return;
            next = resolveQueue.poll();
            resolving = true;
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo info, int err) { done(); }
                public void onServiceResolved(NsdServiceInfo info) {
                    try {
                        String name = info.getServiceName();
                        String id = name;
                        Map<String, byte[]> attrs = info.getAttributes();
                        if (attrs != null) {
                            byte[] fn = attrs.get("fn");
                            if (fn != null) name = new String(fn, "UTF-8");
                            byte[] uid = attrs.get("id");
                            if (uid != null) id = new String(uid, "UTF-8");
                            byte[] md = attrs.get("md");
                            // Skip audio-only groups / speakers.
                            if (md != null && new String(md, "UTF-8").toLowerCase().contains("audio")) { done(); return; }
                        }
                        InetAddress host = info.getHost();
                        if (host != null && running) {
                            listener.onFound(CastTarget.chromecast(id, name, host.getHostAddress(), info.getPort()));
                        }
                    } catch (Exception ignored) {}
                    done();
                }
                private void done() {
                    synchronized (resolveQueue) { resolving = false; }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            synchronized (resolveQueue) { resolving = false; }
        }
    }

    // ------------------------------------------------------------------ DLNA

    private void ssdpSearch() {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setSoTimeout(800);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            String[] targets = {"urn:schemas-upnp-org:service:AVTransport:1", "urn:schemas-upnp-org:device:MediaRenderer:1"};
            long end = System.currentTimeMillis() + 9000;
            int round = 0;
            byte[] buf = new byte[4096];
            while (running && System.currentTimeMillis() < end) {
                if (round < 3) {
                    for (String st : targets) {
                        String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: "
                                + st + "\r\nUSER-AGENT: Android UPnP/1.0 NuvioCast/1.0\r\n\r\n";
                        byte[] d = msg.getBytes("UTF-8");
                        sock.send(new DatagramPacket(d, d.length, group, 1900));
                    }
                    round++;
                }
                long roundEnd = System.currentTimeMillis() + 1500;
                while (running && System.currentTimeMillis() < roundEnd) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        sock.receive(p);
                        String resp = new String(p.getData(), 0, p.getLength(), "UTF-8");
                        Matcher m = Pattern.compile("(?im)^location:\\s*(\\S+)").matcher(resp);
                        if (m.find()) {
                            final String loc = m.group(1).trim();
                            boolean fresh;
                            synchronized (seenLocations) { fresh = seenLocations.add(loc); }
                            if (fresh) {
                                Thread t = new Thread(new Runnable() {
                                    public void run() { describe(loc); }
                                });
                                t.setDaemon(true);
                                t.start();
                            }
                        }
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) sock.close();
        }
    }

    private void describe(String location) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(location).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            String xml = DlnaPlayer.readAll(c.getInputStream());
            c.disconnect();
            String name = DlnaPlayer.tag(xml, "friendlyName");
            String udn = DlnaPlayer.tag(xml, "UDN");
            String base = DlnaPlayer.tag(xml, "URLBase");
            URL baseUrl = new URL(base != null && base.trim().length() > 0 ? base.trim() : location);
            String av = null, rc = null;
            Matcher m = Pattern.compile("<(?:\\w+:)?service>([\\s\\S]*?)</(?:\\w+:)?service>").matcher(xml);
            while (m.find()) {
                String svc = m.group(1);
                String type = DlnaPlayer.tag(svc, "serviceType");
                String ctrl = DlnaPlayer.tag(svc, "controlURL");
                if (type == null || ctrl == null) continue;
                String abs = new URL(baseUrl, ctrl.trim()).toString();
                if (type.contains(":AVTransport:")) av = abs;
                else if (type.contains(":RenderingControl:")) rc = abs;
            }
            if (av == null || !running) return;
            if (name == null) name = new URL(location).getHost();
            name = name.replace("&amp;", "&").trim();
            listener.onFound(CastTarget.dlna(udn != null ? udn.trim() : location, name, av, rc));
        } catch (Exception ignored) {
        }
    }
}
