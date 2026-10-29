package com.nuvio.cast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses SRT / WebVTT / ASS-SSA subtitles into cues and writes WebVTT or SRT. */
final class SubtitleCues {
    private static final Pattern TIME = Pattern.compile(
            "((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})");

    private SubtitleCues() {}

    static List<LocalServer.Cue> parse(String text) {
        if (text.contains("[Events]") && text.contains("Dialogue:")) return parseAss(text);
        List<LocalServer.Cue> cues = new ArrayList<LocalServer.Cue>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TIME.matcher(lines[i]);
            if (!m.find()) continue;
            LocalServer.Cue c = new LocalServer.Cue();
            c.start = time(m.group(1));
            c.end = time(m.group(2));
            StringBuilder sb = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && lines[j].trim().length() > 0 && !TIME.matcher(lines[j]).find()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[j].trim());
                j++;
            }
            // A bare cue number right before the next timing line belongs to the next cue.
            c.text = sb.toString().replaceAll("\\n\\d+$", "").trim();
            if (c.text.length() > 0 && c.end > c.start) cues.add(c);
            i = j - 1;
        }
        sort(cues);
        return cues;
    }

    private static void sort(List<LocalServer.Cue> cues) {
        java.util.Collections.sort(cues, new java.util.Comparator<LocalServer.Cue>() {
            public int compare(LocalServer.Cue a, LocalServer.Cue b) { return Long.compare(a.start, b.start); }
        });
    }

    private static List<LocalServer.Cue> parseAss(String text) {
        List<LocalServer.Cue> cues = new ArrayList<LocalServer.Cue>();
        int startIdx = 1, endIdx = 2, textIdx = 9, fields = 10;
        boolean inEvents = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("[")) { inEvents = line.equalsIgnoreCase("[Events]"); continue; }
            if (!inEvents) continue;
            if (line.startsWith("Format:")) {
                String[] f = line.substring(7).split(",");
                fields = f.length;
                for (int k = 0; k < f.length; k++) {
                    String n = f[k].trim().toLowerCase(Locale.US);
                    if (n.equals("start")) startIdx = k;
                    else if (n.equals("end")) endIdx = k;
                    else if (n.equals("text")) textIdx = k;
                }
            } else if (line.startsWith("Dialogue:")) {
                String[] p = line.substring(9).split(",", fields);
                if (p.length < fields) continue;
                LocalServer.Cue c = new LocalServer.Cue();
                c.start = time(p[startIdx].trim());
                c.end = time(p[endIdx].trim());
                String t = p[textIdx]
                        .replaceAll("\\{[^}]*\\}", "")
                        .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                        .trim();
                c.text = t;
                if (t.length() > 0 && c.end > c.start) cues.add(c);
            }
        }
        sort(cues);
        return cues;
    }

    static String toVtt(List<LocalServer.Cue> cues, long shiftMs) {
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        for (LocalServer.Cue c : cues) {
            long s = c.start - shiftMs, e = c.end - shiftMs;
            if (e <= 0) continue;
            sb.append(fmt(Math.max(0, s), '.')).append(" --> ").append(fmt(e, '.')).append('\n')
              .append(escape(c.text)).append("\n\n");
        }
        return sb.toString();
    }

    static String toSrt(List<LocalServer.Cue> cues, long shiftMs) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (LocalServer.Cue c : cues) {
            long s = c.start - shiftMs, e = c.end - shiftMs;
            if (e <= 0) continue;
            sb.append(n++).append('\n')
              .append(fmt(Math.max(0, s), ',')).append(" --> ").append(fmt(e, ',')).append('\n')
              .append(c.text).append("\n\n");
        }
        return sb.toString();
    }

    /** Keeps simple <i>/<b>/<u> styling, drops other tags and fixes characters VTT treats specially. */
    private static String escape(String t) {
        String s = t.replaceAll("<(?!/?[ibu]>)[^>]*>", "");
        s = s.replace("&", "&amp;");
        s = s.replace("-->", "→");
        return s;
    }

    static long time(String t) {
        t = t.replace(',', '.');
        String[] parts = t.split(":");
        double total = 0;
        for (String p : parts) total = total * 60 + Double.parseDouble(p);
        return Math.round(total * 1000);
    }

    private static String fmt(long ms, char sep) {
        long h = ms / 3600000, m = (ms / 60000) % 60, s = (ms / 1000) % 60, f = ms % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", h, m, s, sep, f);
    }
}
