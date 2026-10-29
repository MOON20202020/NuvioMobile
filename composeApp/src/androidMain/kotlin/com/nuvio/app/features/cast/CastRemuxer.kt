package com.nuvio.app.features.cast

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-packages a video (typically MKV) into fragmented MP4 while it streams, so Chromecast /
 * Google TV can play it with sound. Video is copied untouched; audio is copied when MP4 allows it
 * (AAC, Opus, Vorbis) and otherwise converted to AAC on the phone (like VLC does).
 */
object CastRemuxer {
    private const val TAG = "CastRemuxer"

    class Probe(
        @JvmField val videoMime: String?,
        @JvmField val audioMime: String?,
        @JvmField val durationMs: Long,
        @JvmField val audioCopy: Boolean,
        @JvmField val audioConvert: Boolean,
        @JvmField val audioNames: Array<String>,
        /** Subtitle tracks embedded in the file (MKV), in track order. */
        @JvmField val textNames: Array<String> = emptyArray(),
        @JvmField val textLanguages: Array<String?> = emptyArray(),
    ) {
        /** True when the stream can be served as MP4 with working sound. */
        val canRemux: Boolean get() = videoMime != null && videoMime in FragmentedMp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES &&
            (audioMime == null || audioCopy || audioConvert)
    }

    @JvmStatic
    fun probe(context: Context, url: String, headers: Map<String, String>): Probe? {
        val extractor = MediaExtractorCompat(context)
        return try {
            extractor.setDataSource(context, Uri.parse(url), headers)
            var videoMime: String? = null
            var audioMime: String? = null
            var durationUs = 0L
            val names = ArrayList<String>()
            val textNames = ArrayList<String>()
            val textLangs = ArrayList<String?>()
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/") && videoMime == null) videoMime = mime
                if (CastHlsSession.isText(mime)) {
                    val lang = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null
                    val clean = lang?.takeIf { it.isNotBlank() && it != "und" }
                    textLangs += clean
                    textNames += languageName(clean) ?: "ترجمة ${textNames.size + 1}"
                }
                if (mime.startsWith("audio/")) {
                    if (audioMime == null) audioMime = mime
                    val lang = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null
                    names += listOfNotNull(lang?.takeIf { it != "und" }, shortCodec(mime)).joinToString(" · ")
                }
            }
            val copy = audioMime != null && audioMime in FragmentedMp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES &&
                audioMime != MimeTypes.AUDIO_RAW
            val convert = audioMime != null && !copy && hasDecoder(audioMime)
            Probe(videoMime, audioMime, durationUs / 1000, copy, convert, names.toTypedArray(),
                textNames.toTypedArray(), textLangs.toTypedArray())
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed", t)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Writes fragmented MP4 to [out] starting at [startMs]. Timestamps are rebased so the output
     * starts at 0. Blocks until the source ends, [cancelled] is set or the client disconnects.
     */
    @JvmStatic
    fun remux(
        context: Context,
        url: String,
        headers: Map<String, String>,
        startMs: Long,
        audioOrdinal: Int,
        out: OutputStream,
        cancelled: AtomicBoolean,
    ) {
        val extractor = MediaExtractorCompat(context)
        var muxer: FragmentedMp4Muxer? = null
        var transcoder: AudioTranscoder? = null
        try {
            extractor.setDataSource(context, Uri.parse(url), headers)
            var videoTrack = -1
            var audioTrack = -1
            var audioSeen = 0
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                if (mime.startsWith("audio/")) {
                    if (audioSeen == audioOrdinal || audioTrack < 0) audioTrack = i
                    audioSeen++
                }
            }
            require(videoTrack >= 0) { "no video track" }
            extractor.selectTrack(videoTrack)
            if (audioTrack >= 0) extractor.selectTrack(audioTrack)
            if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)

            val mux = FragmentedMp4Muxer.Builder(out)
                .setFragmentDurationMs(2000)
                .setSampleCopyingEnabled(true)
                .build()
            muxer = mux
            val videoId = mux.addTrack(MediaFormatUtil.createFormatFromMediaFormat(extractor.getTrackFormat(videoTrack)))
            var audioId = -1
            if (audioTrack >= 0) {
                val af = extractor.getTrackFormat(audioTrack)
                val mime = af.getString(MediaFormat.KEY_MIME)!!
                if (mime in FragmentedMp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES && mime != MimeTypes.AUDIO_RAW) {
                    audioId = mux.addTrack(MediaFormatUtil.createFormatFromMediaFormat(af))
                } else {
                    val t = AudioTranscoder(af)
                    transcoder = t
                    audioId = mux.addTrack(t.outputFormat)
                }
            }

            var buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            var baseUs = Long.MIN_VALUE
            var sawVideoKey = false
            while (!cancelled.get()) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val size = extractor.sampleSize
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((size + 65536).toInt())
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                val timeUs = extractor.sampleTime
                val keyFrame = (extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                if (read > 0) {
                    if (track == videoTrack && !sawVideoKey) {
                        if (!keyFrame) { extractor.advance(); continue }
                        sawVideoKey = true
                    }
                    if (sawVideoKey) {
                        if (baseUs == Long.MIN_VALUE) baseUs = timeUs
                        val pts = maxOf(0L, timeUs - baseUs)
                        buffer.position(0).limit(read)
                        if (track == videoTrack) {
                            mux.writeSampleData(videoId, buffer, BufferInfo(pts, read, if (keyFrame) C.BUFFER_FLAG_KEY_FRAME else 0))
                        } else if (track == audioTrack && audioId >= 0) {
                            val tc = transcoder
                            if (tc == null) {
                                mux.writeSampleData(audioId, buffer, BufferInfo(pts, read, C.BUFFER_FLAG_KEY_FRAME))
                            } else {
                                tc.feed(buffer, read, pts) { data, len, outPts ->
                                    mux.writeSampleData(audioId, data, BufferInfo(outPts, len, C.BUFFER_FLAG_KEY_FRAME))
                                }
                            }
                        }
                    }
                }
                if (!extractor.advance()) break
            }
        } catch (t: Throwable) {
            if (!cancelled.get()) Log.w(TAG, "remux stopped", t)
        } finally {
            runCatching { transcoder?.release() }
            runCatching { muxer?.close() }
            runCatching { extractor.release() }
            runCatching { out.flush() }
        }
    }

    @JvmStatic
    fun languageName(code: String?): String? {
        if (code.isNullOrBlank()) return null
        val c = code.lowercase(java.util.Locale.US)
        return when {
            c.startsWith("ar") -> "عربي"
            c.startsWith("en") -> "English"
            c.startsWith("fr") -> "Français"
            c.startsWith("es") || c == "spa" -> "Español"
            c.startsWith("de") || c == "ger" -> "Deutsch"
            c.startsWith("it") -> "Italiano"
            c.startsWith("pt") || c == "por" -> "Português"
            c.startsWith("tr") -> "Türkçe"
            c.startsWith("ru") -> "Русский"
            c.startsWith("ja") || c == "jpn" -> "日本語"
            else -> runCatching { java.util.Locale.forLanguageTag(c).getDisplayLanguage(java.util.Locale.forLanguageTag("ar")) }
                .getOrNull()?.takeIf { it.isNotBlank() && it.lowercase() != c } ?: code
        }
    }

    private fun shortCodec(mime: String): String = when (mime) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "Dolby AC3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby EAC3"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        else -> mime.substringAfter('/')
    }

    private fun hasDecoder(mime: String): Boolean =
        runCatching { MediaCodec.createDecoderByType(mime).release(); true }.getOrDefault(false)

    /** Decodes any audio the phone can decode and re-encodes it as stereo AAC-LC. */
    internal class AudioTranscoder(input: MediaFormat) {
        private val decoder: MediaCodec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
        private val encoder: MediaCodec
        private val sampleRate: Int = input.getInteger(MediaFormat.KEY_SAMPLE_RATE).let { if (it in 8000..48000) it else 48000 }
        private var inChannels: Int = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private val info = MediaCodec.BufferInfo()
        private var encodedFrames = 0L
        val outputFormat: Format

        init {
            decoder.configure(input, null, null, 0)
            decoder.start()
            val enc = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
            enc.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            enc.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            enc.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(enc, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            outputFormat = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(sampleRate)
                .setChannelCount(2)
                .setAverageBitrate(192_000)
                .setInitializationData(listOf(audioSpecificConfig(sampleRate, 2)))
                .build()
        }

        fun feed(data: ByteBuffer, size: Int, ptsUs: Long, emit: (ByteBuffer, Int, Long) -> Unit) {
            var fed = false
            var guard = 0
            while (!fed && guard++ < 200) {
                val inIdx = decoder.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val b = decoder.getInputBuffer(inIdx)!!
                    b.clear()
                    data.position(0).limit(size)
                    b.put(data)
                    decoder.queueInputBuffer(inIdx, 0, size, ptsUs, 0)
                    fed = true
                }
                drainDecoder(emit)
            }
        }

        private fun drainDecoder(emit: (ByteBuffer, Int, Long) -> Unit) {
            while (true) {
                val idx = decoder.dequeueOutputBuffer(info, 0)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    inChannels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    continue
                }
                if (idx < 0) return
                val pcm = decoder.getOutputBuffer(idx)!!
                pcm.position(info.offset).limit(info.offset + info.size)
                val stereo = toStereo(pcm.slice().order(ByteOrder.nativeOrder()), info.size)
                decoder.releaseOutputBuffer(idx, false)
                pushPcm(stereo, emit)
            }
        }

        private fun pushPcm(pcm: ByteArray, emit: (ByteBuffer, Int, Long) -> Unit) {
            var offset = 0
            while (offset < pcm.size) {
                val inIdx = encoder.dequeueInputBuffer(10_000)
                if (inIdx < 0) { drainEncoder(emit); continue }
                val b = encoder.getInputBuffer(inIdx)!!
                b.clear()
                val n = minOf(b.remaining() - (b.remaining() % 4), pcm.size - offset)
                b.put(pcm, offset, n)
                val pts = encodedFrames * 1_000_000L / sampleRate
                encoder.queueInputBuffer(inIdx, 0, n, pts, 0)
                encodedFrames += n / 4
                offset += n
                drainEncoder(emit)
            }
        }

        private fun drainEncoder(emit: (ByteBuffer, Int, Long) -> Unit) {
            while (true) {
                val idx = encoder.dequeueOutputBuffer(info, 0)
                if (idx < 0) return
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size == 0) {
                    encoder.releaseOutputBuffer(idx, false)
                    continue
                }
                val b = encoder.getOutputBuffer(idx)!!
                b.position(info.offset).limit(info.offset + info.size)
                val copy = ByteBuffer.allocateDirect(info.size)
                copy.put(b)
                copy.flip()
                emit(copy, info.size, info.presentationTimeUs)
                encoder.releaseOutputBuffer(idx, false)
            }
        }

        /** 16-bit PCM, any channel count -> interleaved stereo (simple downmix). */
        private fun toStereo(src: ByteBuffer, size: Int): ByteArray {
            val ch = inChannels.coerceAtLeast(1)
            val frames = size / (2 * ch)
            val out = ByteArray(frames * 4)
            val shorts = src.asShortBuffer()
            for (f in 0 until frames) {
                val base = f * ch
                val l: Int
                val r: Int
                when (ch) {
                    1 -> { l = shorts.get(base).toInt(); r = l }
                    2 -> { l = shorts.get(base).toInt(); r = shorts.get(base + 1).toInt() }
                    else -> {
                        val fl = shorts.get(base).toInt()
                        val fr = shorts.get(base + 1).toInt()
                        val c = shorts.get(base + 2).toInt()
                        val bl = if (ch > 4) shorts.get(base + 4).toInt() else 0
                        val br = if (ch > 5) shorts.get(base + 5).toInt() else 0
                        l = (fl * 0.5 + c * 0.35 + bl * 0.3).toInt()
                        r = (fr * 0.5 + c * 0.35 + br * 0.3).toInt()
                    }
                }
                val lc = l.coerceIn(-32768, 32767)
                val rc = r.coerceIn(-32768, 32767)
                out[f * 4] = lc.toByte(); out[f * 4 + 1] = (lc shr 8).toByte()
                out[f * 4 + 2] = rc.toByte(); out[f * 4 + 3] = (rc shr 8).toByte()
            }
            return out
        }

        fun release() {
            runCatching { decoder.stop() }; runCatching { decoder.release() }
            runCatching { encoder.stop() }; runCatching { encoder.release() }
        }

        private fun audioSpecificConfig(rate: Int, channels: Int): ByteArray {
            val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            val idx = rates.indexOf(rate).let { if (it < 0) 3 else it }
            val v = (2 shl 11) or (idx shl 7) or (channels shl 3)
            return byteArrayOf((v shr 8).toByte(), v.toByte())
        }
    }
}
