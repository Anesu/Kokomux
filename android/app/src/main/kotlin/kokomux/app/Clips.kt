package kokomux.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import java.nio.ByteOrder
import kokomux.engine.Audio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Clips to clone a voice from, as Qwen takes them: 24 kHz mono, the silence
 * at either end trimmed, at most [MAX] seconds. Recorded from the microphone
 * (as for speech recognition: no noise suppression or gain riding) or decoded
 * from an audio file.
 */
object Clips {
    const val RATE = 24000
    const val MAX = 15.0
    private const val MIC = 48000

    /** Recording from the microphone until [stop]; [level] hears (seconds so far, loudness 0..1) as it goes. */
    class Recorder(private val level: (Double, Float) -> Unit) {
        @Volatile private var going = true
        private val chunks = ArrayList<ShortArray>()
        private var thread: Thread? = null

        @SuppressLint("MissingPermission")         // asked for by the screen before it records
        fun start() {
            val size = max(AudioRecord.getMinBufferSize(MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), MIC / 5)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MIC, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size * 2)
            thread = Thread({
                rec.startRecording()
                var samples = 0
                try {
                    while (going && samples < (MAX + 1) * MIC) {
                        val buf = ShortArray(MIC / 20)
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        synchronized(chunks) { chunks += buf.copyOf(n) }
                        samples += n
                        var sum = 0.0
                        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                        level(samples.toDouble() / MIC, min(1f, (sqrt(sum / n) / 6000).toFloat()))
                    }
                } finally {
                    rec.stop()
                    rec.release()
                }
            }, "kokomux-recording").apply { start() }
        }

        /** Stop, and the clip. */
        fun stop(): FloatArray {
            going = false
            thread?.join(2000)
            val all = synchronized(chunks) { chunks.toList() }
            val pcm = FloatArray(all.sumOf { it.size })
            var at = 0
            for (c in all) for (s in c) pcm[at++] = s / 32768f
            return tidy(resample(pcm, MIC, RATE))
        }
    }

    /** An audio file (any the phone can play), decoded. */
    fun decode(context: Context, uri: Uri): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("That file has no sound in it")
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = ArrayList<FloatArray>()
        var total = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (total < (MAX + 5) * rate) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = info.size / 2 / channels
                    val mono = FloatArray(frames) { f ->
                        var s = 0f
                        for (c in 0 until channels) s += b.get(f * channels + c)
                        s / channels / 32768f
                    }
                    out += mono
                    total += frames
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            ex.release()
        }
        return tidy(resample(Audio.join(out), rate, RATE))
    }

    /** The silence at either end trimmed, no longer than [MAX], and up to a speaking level. */
    private fun tidy(pcm: FloatArray): FloatArray {
        val (head, tail) = Audio.soundSpan(pcm)
        val clip = pcm.copyOfRange(head, min(tail, head + (MAX * RATE).toInt()))
        val peak = clip.maxOfOrNull { abs(it) } ?: 0f
        if (peak > 0f && peak < 0.5f) for (i in clip.indices) clip[i] *= 0.5f / peak
        return clip
    }

    /** From [from] Hz to [to] Hz, with a windowed-sinc filter (so nothing above the new rate's half folds back). */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val ratio = to.toDouble() / from
        val cutoff = min(1.0, ratio) * 0.95
        val half = 16
        val n = (x.size * ratio).toInt()
        val y = FloatArray(n)
        for (i in 0 until n) {
            val t = i / ratio
            val c = t.toInt()
            var sum = 0.0
            var wsum = 0.0
            for (k in c - half + 1..c + half) {
                if (k < 0 || k >= x.size) continue
                val d = t - k
                val sinc = if (d == 0.0) 1.0 else sin(PI * cutoff * d) / (PI * cutoff * d)
                val w = 0.5 + 0.5 * cos(PI * d / half)
                sum += x[k] * sinc * w
                wsum += sinc * w
            }
            y[i] = if (wsum != 0.0) (sum / wsum).toFloat() else 0f
        }
        return y
    }
}
