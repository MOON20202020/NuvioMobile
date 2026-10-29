package com.nuvio.app.features.cast

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Live HLS stream built on the phone from any video Nuvio can play (MKV, MP4, torrents...).
 *
 * Video and audio are re-packaged into fragmented-MP4 segments (audio converted to AAC when the TV
 * can't play it) and subtitles - including the ones embedded in MKV files - are published as a
 * WebVTT track that grows while the video is read. Chromecast / Google TV fetch the playlists
 * continuously, so subtitles that are only discovered while streaming still reach the TV.
 */
class CastHlsSession(
    context: Context,
    private val url: String,
    private val headers: Map<String, String>,
    /** Position in the source where this stream starts; stream time 0 == this position. */
    @JvmField val startMs: Long,
    private val audioOrdinal: Int,
    /** Index among the source's text tracks to publish, or -1 for none. */
    private val subtitleOrdinal: Int,
    @JvmField val subtitleName: String,
    @JvmField val subtitleLanguage: String?,
) {
    private val appContext = context.applicationContext
    @JvmField val id: String = java.lang.Long.toString(System.nanoTime(), 36)
    private val dir = File(appContext.cacheDir, "cast-hls/$id").apply { mkdirs() }

    private val video = Rendition("v", "video/mp4")
    private val audio = Rendition("a", "audio/mp4")
    private val cues = CopyOnWriteArrayList<Cue>()
    private val lock = Object()

    @Volatile private var stopped = false
    @Volatile private var finished = false
    @Volatile private var lastRequestedVideo = -1
    @Volatile var error: String? = null
        private set
    @Volatile private var videoCodecs: String? = null
    @Volatile private var audioCodecs: String? = null
    @Volatile private var hasAudio = false
    @Volatile private var hasSubtitles = false

    /** Moves subtitles later (positive) or earlier (negative), in milliseconds. */
    @Volatile var subtitleDelayMs: Long = 0

    /** True when a subtitle track (embedded or added via [addCue]) is published. */
    val publishesSubtitles: Boolean get() = hasSubtitles

    private class Cue(val startUs: Long, val endUs: Long, val text: String)

    /** Adds an already time-shifted cue (from an external subtitle file). */
    fun addCue(startMs: Long, endMs: Long, text: String) {
        if (endMs <= 0 || text.isBlank()) return
        cues += Cue(maxOf(0, startMs) * 1000, endMs * 1000, text)
        hasSubtitles = true
    }

    fun start() {
        if (subtitleOrdinal >= 0) hasSubtitles = true
        thread(name = "cast-hls-$id", isDaemon = true) { pump() }
    }

    fun stop() {
        stopped = true
        synchronized(lock) { lock.notifyAll() }
        thread(isDaemon = true) {
            Thread.sleep(3000)
            dir.deleteRecursively()
        }
    }

    /** Blocks until the first video segment exists (or failure); returns false on failure. */
    fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (video.segments.size < 2 && !finished && error == null && !stopped) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(minOf(left, 500))
            }
        }
        return video.segments.isNotEmpty() && error == null
    }

    /** Stream time range (ms) currently available to the TV, or null. */
    fun availableRangeMs(): LongArray? {
        val segs = video.segments.toList()
        if (segs.isEmpty()) return null
        return longArrayOf(segs.first().startUs / 1000, (segs.last().startUs + segs.last().durationUs) / 1000)
    }

    // ------------------------------------------------------------------ HTTP side

    /** Returns (contentType, body) for a request path inside this session, or null. */
    fun serve(name: String): Pair<String, Any>? {
        return when {
            name == "master.m3u8" -> MPEGURL to masterPlaylist().toByteArray()
            name == "v.m3u8" -> MPEGURL to mediaPlaylist(video).toByteArray()
            name == "a.m3u8" -> MPEGURL to mediaPlaylist(audio).toByteArray()
            name == "s.m3u8" -> MPEGURL to subtitlePlaylist().toByteArray()
            name == "v_init.mp4" -> video.mime to (video.init ?: return null)
            name == "a_init.mp4" -> audio.mime to (audio.init ?: return null)
            name.startsWith("v_") && name.endsWith(".m4s") -> {
                val n = name.substring(2, name.length - 4).toIntOrNull() ?: return null
                if (n > lastRequestedVideo) {
                    lastRequestedVideo = n
                    synchronized(lock) { lock.notifyAll() }
                }
                video.mime to (video.file(n) ?: return null)
            }
            name.startsWith("a_") && name.endsWith(".m4s") -> {
                val n = name.substring(2, name.length - 4).toIntOrNull() ?: return null
                audio.mime to (audio.file(n) ?: return null)
            }
            name.startsWith("s_") && name.endsWith(".vtt") -> {
                val n = name.substring(2, name.length - 4).toIntOrNull() ?: return null
                "text/vtt; charset=utf-8" to subtitleSegment(n).toByteArray()
            }
            else -> null
        }
    }

    private fun masterPlaylist(): String {
        // Wait briefly so codec strings are known.
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + 20000
            while (video.init == null && error == null && !stopped && System.currentTimeMillis() < deadline) lock.wait(300)
        }
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        if (hasAudio) {
            sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"a.m3u8\"\n")
        }
        if (hasSubtitles) {
            sb.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"sub\",NAME=\"")
                .append(subtitleName.replace("\"", "'"))
                .append("\",")
            if (!subtitleLanguage.isNullOrBlank()) sb.append("LANGUAGE=\"").append(subtitleLanguage).append("\",")
            sb.append("DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,URI=\"s.m3u8\"\n")
        }
        val codecs = listOfNotNull(videoCodecs, if (hasAudio) audioCodecs else null).joinToString(",")
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=8000000")
        if (codecs.isNotEmpty()) sb.append(",CODECS=\"").append(codecs).append('"')
        if (hasAudio) sb.append(",AUDIO=\"aud\"")
        if (hasSubtitles) sb.append(",SUBTITLES=\"sub\"")
        sb.append("\nv.m3u8\n")
        return sb.toString()
    }

    private fun header(first: Int, map: String?): StringBuilder {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:").append(TARGET_DURATION_S)
            .append("\n#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n')
        sb.append("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES\n")
        if (map != null) sb.append("#EXT-X-MAP:URI=\"").append(map).append("\"\n")
        return sb
    }

    private fun mediaPlaylist(r: Rendition): String {
        val segs = r.segments.toList()
        val first = segs.firstOrNull()?.index ?: 0
        val sb = header(first, "${r.prefix}_init.mp4")
        for (s in segs) {
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.durationUs / 1e6)).append(",\n")
                .append(r.prefix).append('_').append(s.index).append(".m4s\n")
        }
        if (finished) sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    private fun subtitlePlaylist(): String {
        val segs = video.segments.toList()
        val first = segs.firstOrNull()?.index ?: 0
        val sb = header(first, null)
        for (s in segs) {
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.durationUs / 1e6)).append(",\n")
                .append("s_").append(s.index).append(".vtt\n")
        }
        if (finished) sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    private fun subtitleSegment(n: Int): String {
        val seg = video.segments.firstOrNull { it.index == n }
        val sb = StringBuilder("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000\n\n")
        if (seg != null) {
            val from = seg.startUs
            val to = seg.startUs + seg.durationUs
            val shift = subtitleDelayMs * 1000
            for (c in cues) {
                val cs = c.startUs + shift
                val ce = c.endUs + shift
                if (ce <= 0 || ce <= from || cs >= to) continue
                sb.append(vttTime(maxOf(0, cs))).append(" --> ").append(vttTime(ce)).append('\n')
                    .append(c.text.replace("&", "&amp;").replace("<", "&lt;").replace("-->", "→")).append("\n\n")
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ producer

    private fun pump() {
        val extractor = MediaExtractorCompat(appContext)
        var vMux: FragmentedMp4Muxer? = null
        var aMux: FragmentedMp4Muxer? = null
        var transcoder: CastRemuxer.AudioTranscoder? = null
        try {
            extractor.setDataSource(appContext, Uri.parse(url), headers)
            var videoTrack = -1
            var audioTrack = -1
            var textTrack = -1
            var audioSeen = 0
            var textSeen = 0
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && videoTrack < 0 -> videoTrack = i
                    mime.startsWith("audio/") -> {
                        if (audioSeen == audioOrdinal || audioTrack < 0) audioTrack = i
                        audioSeen++
                    }
                    isText(mime) -> {
                        if (textSeen == subtitleOrdinal) textTrack = i
                        textSeen++
                    }
                }
            }
            require(videoTrack >= 0) { "no video track" }
            extractor.selectTrack(videoTrack)
            if (audioTrack >= 0) extractor.selectTrack(audioTrack)
            if (textTrack >= 0) extractor.selectTrack(textTrack)
            if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)

            val vFormat = MediaFormatUtil.createFormatFromMediaFormat(extractor.getTrackFormat(videoTrack))
            videoCodecs = vFormat.codecs?.takeIf { it.isNotBlank() } ?: fallbackVideoCodecs(vFormat.sampleMimeType)
            val vm = FragmentedMp4Muxer.Builder(Segmenter(video))
                .setFragmentDurationMs(FRAGMENT_MS).setSampleCopyingEnabled(true).build()
            vMux = vm
            val vId = vm.addTrack(vFormat)

            var aId = -1
            if (audioTrack >= 0) {
                val af = extractor.getTrackFormat(audioTrack)
                val mime = af.getString(MediaFormat.KEY_MIME)!!
                val outFormat: Format = if (mime in FragmentedMp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES && mime != MimeTypes.AUDIO_RAW) {
                    MediaFormatUtil.createFormatFromMediaFormat(af)
                } else {
                    CastRemuxer.AudioTranscoder(af).also { transcoder = it }.outputFormat
                }
                audioCodecs = when (outFormat.sampleMimeType) {
                    MimeTypes.AUDIO_AAC -> outFormat.codecs?.takeIf { it.startsWith("mp4a") } ?: "mp4a.40.2"
                    MimeTypes.AUDIO_OPUS -> "opus"
                    MimeTypes.AUDIO_VORBIS -> "vorbis"
                    else -> outFormat.codecs
                }
                val am = FragmentedMp4Muxer.Builder(Segmenter(audio))
                    .setFragmentDurationMs(FRAGMENT_MS).setSampleCopyingEnabled(true).build()
                aMux = am
                aId = am.addTrack(outFormat)
                hasAudio = true
            }

            val decoder = CueDecoder()
            var buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            var baseUs = Long.MIN_VALUE
            var sawKey = false
            while (!stopped) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val size = extractor.sampleSize
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((size + 65536).toInt())
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                val timeUs = extractor.sampleTime
                val key = (extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                if (read > 0) {
                    if (track == videoTrack && !sawKey) {
                        if (!key) { extractor.advance(); continue }
                        sawKey = true
                    }
                    if (sawKey) {
                        if (baseUs == Long.MIN_VALUE) baseUs = timeUs
                        val pts = maxOf(0L, timeUs - baseUs)
                        buffer.position(0).limit(read)
                        when (track) {
                            videoTrack -> {
                                vm.writeSampleData(vId, buffer, BufferInfo(pts, read, if (key) C.BUFFER_FLAG_KEY_FRAME else 0))
                                throttle()
                            }
                            audioTrack -> {
                                val am = aMux!!
                                val tc = transcoder
                                if (tc == null) {
                                    am.writeSampleData(aId, buffer, BufferInfo(pts, read, C.BUFFER_FLAG_KEY_FRAME))
                                } else {
                                    tc.feed(buffer, read, pts) { data, len, outPts ->
                                        am.writeSampleData(aId, data, BufferInfo(outPts, len, C.BUFFER_FLAG_KEY_FRAME))
                                    }
                                }
                            }
                            textTrack -> {
                                val bytes = ByteArray(read)
                                buffer.get(bytes)
                                runCatching {
                                    val cw = decoder.decode(timeUs, bytes, 0, read)
                                    val text = cw.cues.mapNotNull { it.text?.toString()?.trim() }
                                        .filter { it.isNotEmpty() }.joinToString("\n")
                                    if (text.isNotEmpty()) {
                                        val s = cw.startTimeUs - baseUs
                                        val e = if (cw.durationUs != C.TIME_UNSET && cw.durationUs > 0) s + cw.durationUs else s + 3_000_000
                                        if (e > 0) cues += Cue(maxOf(0, s), e, text)
                                    }
                                }
                            }
                        }
                    }
                }
                if (!extractor.advance()) break
            }
        } catch (t: Throwable) {
            if (!stopped) {
                Log.w(TAG, "hls pump stopped", t)
                error = t.message ?: t.javaClass.simpleName
            }
        } finally {
            runCatching { transcoder?.release() }
            runCatching { vMux?.close() }
            runCatching { aMux?.close() }
            runCatching { extractor.release() }
            finished = true
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** Keeps the phone at most [AHEAD_SEGMENTS] segments ahead of what the TV has requested. */
    private fun throttle() {
        synchronized(lock) {
            while (!stopped && video.segments.isNotEmpty() &&
                video.segments.last().index - lastRequestedVideo > AHEAD_SEGMENTS
            ) {
                lock.wait(500)
            }
        }
    }

    // ------------------------------------------------------------------ segments

    private class Segment(val index: Int, val startUs: Long, val durationUs: Long, val file: File)

    private inner class Rendition(val prefix: String, val mime: String) {
        @Volatile var init: ByteArray? = null
        @Volatile var timescale: Long = 1000
        val segments = CopyOnWriteArrayList<Segment>()
        private var next = 0

        fun file(n: Int): File? = segments.firstOrNull { it.index == n }?.file?.takeIf { it.exists() }

        fun add(startTicks: Long, durationTicks: Long, bytes: ByteArray) {
            val f = File(dir, "${prefix}_$next.m4s")
            f.writeBytes(bytes)
            val seg = Segment(next++, startTicks * 1_000_000 / timescale, durationTicks * 1_000_000 / timescale, f)
            segments += seg
            while (segments.size > WINDOW_SEGMENTS) {
                val old = segments.removeAt(0)
                old.file.delete()
            }
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** Splits the muxer's output into the init segment and one media segment per moof+mdat. */
    private inner class Segmenter(private val r: Rendition) : OutputStream() {
        private var buf = ByteArray(1 shl 20)
        private var len = 0
        private val initBuf = ByteArrayOutputStream()
        private var moof: ByteArray? = null

        override fun write(b: Int) {
            ensure(1)
            buf[len++] = b.toByte()
            drain()
        }

        override fun write(b: ByteArray, off: Int, n: Int) {
            ensure(n)
            System.arraycopy(b, off, buf, len, n)
            len += n
            drain()
        }

        private fun ensure(extra: Int) {
            if (len + extra <= buf.size) return
            var cap = buf.size
            while (cap < len + extra) cap *= 2
            buf = buf.copyOf(cap)
        }

        private fun drain() {
            while (len >= 8) {
                var size = u32(buf, 0)
                var header = 8
                if (size == 1L) {
                    if (len < 16) return
                    size = u64(buf, 8)
                    header = 16
                }
                if (size < header) { len = 0; return } // corrupt; drop
                if (len < size) return
                val n = size.toInt()
                val box = buf.copyOfRange(0, n)
                val type = String(box, 4, 4, Charsets.US_ASCII)
                System.arraycopy(buf, n, buf, 0, len - n)
                len -= n
                onBox(type, box)
            }
        }

        private fun onBox(type: String, box: ByteArray) {
            when (type) {
                "ftyp" -> initBuf.write(box)
                "moov" -> {
                    initBuf.write(box)
                    r.timescale = findTimescale(box)
                    r.init = initBuf.toByteArray()
                    synchronized(lock) { lock.notifyAll() }
                }
                "moof" -> moof = box
                "mdat" -> {
                    val m = moof ?: return
                    moof = null
                    val (start, dur) = moofTiming(m)
                    val out = ByteArray(m.size + box.size)
                    System.arraycopy(m, 0, out, 0, m.size)
                    System.arraycopy(box, 0, out, m.size, box.size)
                    r.add(start, dur, out)
                }
                else -> Unit
            }
        }
    }

    companion object {
        private const val TAG = "CastHls"
        private const val MPEGURL = "application/vnd.apple.mpegurl"
        private const val FRAGMENT_MS = 4000L
        private const val TARGET_DURATION_S = 12
        private const val AHEAD_SEGMENTS = 15
        private const val WINDOW_SEGMENTS = 60

        @JvmStatic
        fun isText(mime: String): Boolean =
            mime == MimeTypes.APPLICATION_MEDIA3_CUES || mime.startsWith("text/") ||
                mime == MimeTypes.APPLICATION_SUBRIP || mime == MimeTypes.APPLICATION_TTML ||
                mime == MimeTypes.APPLICATION_TX3G

        private fun fallbackVideoCodecs(mime: String?): String? = when (mime) {
            MimeTypes.VIDEO_H264 -> "avc1.640028"
            MimeTypes.VIDEO_H265 -> "hvc1.1.6.L120.90"
            MimeTypes.VIDEO_AV1 -> "av01.0.08M.08"
            MimeTypes.VIDEO_VP9 -> "vp09.00.40.08"
            else -> null
        }

        private fun vttTime(us: Long): String {
            val ms = us / 1000
            return String.format(Locale.US, "%02d:%02d:%02d.%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000)
        }

        private fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
                ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

        private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

        /** Finds the first child box of [type] inside [b] between [from] and [to]; returns its offset. */
        private fun child(b: ByteArray, from: Int, to: Int, type: String): Int {
            var p = from
            while (p + 8 <= to) {
                var size = u32(b, p)
                if (size == 1L) size = u64(b, p + 8)
                if (size < 8) return -1
                if (String(b, p + 4, 4, Charsets.US_ASCII) == type) return p
                p += size.toInt()
            }
            return -1
        }

        private fun boxEnd(b: ByteArray, p: Int): Int = p + u32(b, p).toInt()

        /** moov > trak > mdia > mdhd timescale. */
        internal fun findTimescale(moov: ByteArray): Long {
            val trak = child(moov, 8, moov.size, "trak"); if (trak < 0) return 1000
            val mdia = child(moov, trak + 8, boxEnd(moov, trak), "mdia"); if (mdia < 0) return 1000
            val mdhd = child(moov, mdia + 8, boxEnd(moov, mdia), "mdhd"); if (mdhd < 0) return 1000
            val version = moov[mdhd + 8].toInt()
            val off = mdhd + 12 + if (version == 1) 16 else 8
            return u32(moov, off).takeIf { it > 0 } ?: 1000
        }

        /** (baseMediaDecodeTime, total sample duration) of the first track fragment. */
        internal fun moofTiming(moof: ByteArray): Pair<Long, Long> {
            val traf = child(moof, 8, moof.size, "traf"); if (traf < 0) return 0L to 0L
            val trafEnd = boxEnd(moof, traf)
            var defaultDuration = 0L
            val tfhd = child(moof, traf + 8, trafEnd, "tfhd")
            if (tfhd >= 0) {
                val flags = u32(moof, tfhd + 8) and 0xffffff
                var p = tfhd + 16 // after version/flags + track_ID
                if (flags and 0x01L != 0L) p += 8
                if (flags and 0x02L != 0L) p += 4
                if (flags and 0x08L != 0L) defaultDuration = u32(moof, p)
            }
            var base = 0L
            val tfdt = child(moof, traf + 8, trafEnd, "tfdt")
            if (tfdt >= 0) base = if (moof[tfdt + 8].toInt() == 1) u64(moof, tfdt + 12) else u32(moof, tfdt + 12)
            var total = 0L
            var p = traf + 8
            while (true) {
                val trun = child(moof, p, trafEnd, "trun")
                if (trun < 0) break
                val flags = u32(moof, trun + 8) and 0xffffff
                val count = u32(moof, trun + 12).toInt()
                var q = trun + 16
                if (flags and 0x01L != 0L) q += 4
                if (flags and 0x04L != 0L) q += 4
                for (i in 0 until count) {
                    if (flags and 0x100L != 0L) { total += u32(moof, q); q += 4 } else total += defaultDuration
                    if (flags and 0x200L != 0L) q += 4
                    if (flags and 0x400L != 0L) q += 4
                    if (flags and 0x800L != 0L) q += 4
                }
                p = boxEnd(moof, trun)
            }
            return base to total
        }
    }
}
