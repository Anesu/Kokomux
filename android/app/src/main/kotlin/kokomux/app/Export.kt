package kokomux.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kokomux.engine.Book
import kokomux.engine.Books
import kokomux.engine.Maker
import kokomux.engine.VoiceId

/**
 * Saving some of a book as an audiobook: its parts from one to another, made
 * if they aren't kept yet (in the voice and speed it's heard in), then one
 * after the other as AAC in an .m4a file where you chose. Runs on its own,
 * with a notification saying how far it's got; [progress] says so to the
 * screen too.
 */
class Export(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    /** What's to be saved: the book (as it's loaded now), its request, the parts, and where. */
    class Job(val book: Book, val request: Request, val lang: String, val from: Int, val to: Int, val uri: Uri)

    override suspend fun doWork(): Result {
        val job = pending ?: return Result.failure()
        pending = null
        val engine = applicationContext.engine
        val name = job.book.novel?.title ?: job.book.title
        val total = job.to - job.from + 1
        setForeground(info(name, 0, total))
        return try {
            val madeAt = if (VoiceId.makesAtSpeed(job.request.voice)) job.request.speed else 1f
            val cache = Maker.cacheDir(engine.cache, job.request.id, job.request.voice, madeAt, job.lang)
            val speaker = engine.speaker(job.request.voice, job.request.speed, job.lang)
            val wavs = ArrayList<File>()
            Maker(job.book, cache, speaker, ahead = Double.MAX_VALUE).run(job.from, { 0 }, { isStopped }, job.to) { made ->
                wavs += made.wav
                progress = wavs.size to total
                setForegroundAsync(info(name, wavs.size, total))
            }
            if (isStopped) return Result.failure()
            applicationContext.contentResolver.openFileDescriptor(job.uri, "rw")!!.use { fd -> encode(wavs, fd.fileDescriptor) }
            Listening.note = "Saved: $name"
            Result.success()
        } catch (e: Throwable) {
            Listening.error = "Couldn't save the audiobook (${e.message})"
            Result.failure()
        } finally {
            progress = null
        }
    }

    private fun info(name: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Saving audiobooks", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Saving $name").setContentText("$done of $total parts").setProgress(total, done, false)
            .setOngoing(true).setOnlyAlertOnce(true).build()
        return ForegroundInfo(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
    }

    companion object {
        private const val CHANNEL = "export"
        private const val NOTIFICATION = 21
        /** The bit rate: speech in AAC is clear at 64 kb/s mono. */
        private const val BITRATE = 64_000

        @Volatile private var pending: Job? = null
        /** How far the export going now has got: (parts made, of); null when none is going. */
        var progress by mutableStateOf<Pair<Int, Int>?>(null)

        fun start(context: Context, job: Job) {
            pending = job
            progress = 0 to (job.to - job.from + 1)
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<Export>()
                .setInputData(workDataOf("book" to job.request.id)).build())
        }

        /** "The Long Road - Chapter 2.m4a": a file name for the export. */
        fun fileName(title: String, what: String) =
            (listOf(title, what).filter { it.isNotBlank() }.joinToString(" - ") + ".m4a").replace(Regex("[\\\\/:*?\"<>|]"), " ")

        /** The WAV files (16-bit mono, one rate), one after the other, as AAC in an MPEG-4 file. */
        fun encode(wavs: List<File>, out: java.io.FileDescriptor) {
            if (wavs.isEmpty()) return
            val rate = RandomAccessFile(wavs[0], "r").use { f ->
                val head = ByteArray(44).also { f.readFully(it) }
                ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            val info = MediaCodec.BufferInfo()
            var samples = 0L
            val chunks = wavs.asSequence().flatMap { w ->
                val bytes = w.readBytes()
                (44 until bytes.size step (1 shl 15)).asSequence().map { bytes.copyOfRange(it, minOf(bytes.size, it + (1 shl 15))) }
            }.iterator()
            var inputDone = false
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            if (!chunks.hasNext()) {
                                codec.queueInputBuffer(i, 0, 0, samples * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val c = chunks.next()
                                buf.clear()
                                buf.put(c, 0, minOf(c.size, buf.remaining()))
                                codec.queueInputBuffer(i, 0, minOf(c.size, buf.capacity()), samples * 1_000_000 / rate, 0)
                                samples += c.size / 2
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
                if (track >= 0) muxer.stop()
                muxer.release()
            }
        }
    }
}
