package com.nuvio.cast;

/** A device found on the network that can receive a video. */
public final class CastTarget {
    public static final int CHROMECAST = 1;
    public static final int DLNA = 2;

    public final int kind;
    public final String id;
    public final String name;
    // Chromecast
    public final String host;
    public final int port;
    // DLNA
    public final String avTransportUrl;
    public final String renderingUrl;

    private CastTarget(int kind, String id, String name, String host, int port, String av, String rc) {
        this.kind = kind;
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.avTransportUrl = av;
        this.renderingUrl = rc;
    }

    public static CastTarget chromecast(String id, String name, String host, int port) {
        return new CastTarget(CHROMECAST, "cc:" + id, name, host, port, null, null);
    }

    public static CastTarget dlna(String id, String name, String av, String rc) {
        return new CastTarget(DLNA, "dlna:" + id, name, null, 0, av, rc);
    }
}
