package kokomux.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kokomux.app.ui.Clip
import kokomux.app.ui.EngineFiles
import kokomux.app.ui.Prefs
import kokomux.app.ui.Voices
import kokomux.engine.Audio
import kokomux.engine.Books
import kokomux.engine.VoiceId
import kokomux.engine.plain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The voices, for the screens: every voice there is, a voice's sample, each
 * engine's files (fetching them, letting them go), and a new voice made from
 * a clip, recorded or picked. Part of the AppModel, which gives it the
 * settings ([prefs]), the book's player to pause while something else is
 * heard ([book]), what to do with a voice just made ([chosen]) and where to
 * say what went wrong ([failed]).
 */
class VoiceStudio(
    private val app: Application,
    private val scope: CoroutineScope,
    private val prefs: () -> Prefs,
    private val book: () -> Player?,
    private val chosen: (String) -> Unit,
    private val failed: (String) -> Unit,
) {
    private val engine = app.engine

    /** Every voice there is: Kokoro's, Ming's, and those cloned with Qwen. */
    var voices by mutableStateOf(emptyList<String>())
        private set
    /** The voice whose sample is playing. */
    var hearing by mutableStateOf("")
        private set
    /** What each engine has on the phone, by its id. */
    var engineFiles by mutableStateOf(emptyMap<String, EngineFiles>())
        private set
    /** Recording a clip: how long so far, and how loud it is now; null when not. */
    var recording by mutableStateOf<Pair<Double, Float>?>(null)
        private set
    /** The clip recorded or picked. */
    var clip by mutableStateOf<Clip?>(null)
        private set
    /** A new voice is being made from the clip. */
    var cloning by mutableStateOf(false)
        private set

    private var clipSamples: FloatArray? = null
    private var recorder: Clips.Recorder? = null
    private var preview: ExoPlayer? = null

    init {
        refreshEngines()
    }

    /** The voices, read again (blocking: Kokoro's come with its model). */
    fun loadVoices() {
        val kokoro = engine.kokoro?.voices?.names ?: voices.filter { VoiceId.engine(it) == VoiceId.KOKORO }
        voices = kokoro + Voices.ming + engine.cloned().map { it.voice }
    }

    fun refreshVoices() {
        scope.launch(Dispatchers.IO) { loadVoices() }
    }

    /** A voice saying a line in its own language, the book paused meanwhile (tui.sh's voice_sample). */
    fun hear(voice: String) = say(voice, Voices.sample(voice))

    /** [voice] saying [text], the book paused meanwhile. */
    fun say(voice: String, text: String) {
        if (hearing.isNotEmpty()) return
        hearing = voice
        val speed = prefs().speed
        val file = File(app.cacheDir, "sample.wav")
        scope.launch(Dispatchers.IO) {
            try {
                val said = engine.speaker(voice, speed, Books.langOf(voice)).say(text)
                Audio.writeWav(file, said.samples, said.rate)
                withContext(Dispatchers.Main) {
                    val resume = book()?.isPlaying == true
                    book()?.pause()
                    val p = previewPlayer()
                    p.clearMediaItems()
                    p.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state != Player.STATE_ENDED) return
                            p.removeListener(this)
                            hearing = ""
                            if (resume) book()?.play()
                        }
                    })
                    p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    p.setPlaybackSpeed(if (VoiceId.makesAtSpeed(voice)) 1f else speed)
                    p.prepare()
                    p.play()
                }
            } catch (e: Throwable) {
                hearing = ""
                failed(e.plain())
            }
        }
    }

    /** The player for a sample or a clip (not the book's). */
    private fun previewPlayer() = preview ?: ExoPlayer.Builder(app).build().also { preview = it }

    // ------------------------------------------------------------ engines

    private fun pack(id: String) = when (id) {
        VoiceId.MING -> engine.mingFiles
        VoiceId.QWEN -> engine.qwenFiles
        else -> engine.kokoroFiles
    }

    /** What each engine has on the phone. */
    private fun refreshEngines() {
        scope.launch(Dispatchers.IO) {
            engineFiles = Voices.engines.associate { (id, title) ->
                val p = pack(id)
                id to (engineFiles[id]?.takeIf { it.downloading } ?: EngineFiles(id, title, p.total,
                    if (p.ready()) p.total else p.size().coerceAtMost(p.total - 1)))
            }
        }
    }

    fun fetchEngine(id: String) {
        if (engineFiles[id]?.downloading == true) return
        val p = pack(id)
        engineFiles = engineFiles + (id to EngineFiles(id, p.title, p.total, done = 0))
        scope.launch(Dispatchers.IO) {
            var shown = -1L
            try {
                p.download { done, total ->
                    if (done - shown >= 4 shl 20 || done == total) {
                        shown = done
                        engineFiles = engineFiles + (id to EngineFiles(id, p.title, total, done = done))
                    }
                }
                engineFiles = engineFiles + (id to EngineFiles(id, p.title, p.total, p.total))
            } catch (e: Exception) {
                engineFiles = engineFiles + (id to EngineFiles(id, p.title, p.total, p.size(), error = e.plain()))
            }
        }
    }

    /** Let an engine's files go (Kokoro's stay). */
    fun removeEngine(id: String) {
        if (id == VoiceId.KOKORO) return
        scope.launch(Dispatchers.IO) {
            engine.unload(id)
            pack(id).remove()
            refreshEngines()
        }
    }

    // ------------------------------------------------------------ a new voice

    fun record() {
        if (recorder != null) return
        clip = null
        clipSamples = null
        recording = 0.0 to 0f
        recorder = Clips.Recorder { secs, level -> scope.launch { if (recorder != null) recording = secs to level } }
            .also { it.start() }
    }

    fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        scope.launch(Dispatchers.IO) {
            val pcm = r.stop()
            recording = null
            useClip(pcm)
        }
    }

    fun pickClip(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                useClip(Clips.decode(app, uri))
            } catch (e: Exception) {
                failed("Couldn't read that clip (${e.plain()})")
            }
        }
    }

    private fun useClip(pcm: FloatArray) {
        if (pcm.size < Clips.RATE) {
            failed("That clip is too short: a few seconds of speaking is right")
            return
        }
        clipSamples = pcm
        clip = Clip(pcm.size.toDouble() / Clips.RATE)
        Audio.writeWav(File(app.cacheDir, "clip.wav"), pcm, Clips.RATE)
    }

    /** Play the clip back, or stop it. */
    fun playClip() {
        val c = clip ?: return
        val p = previewPlayer()
        if (c.playing) {
            p.stop()
            clip = c.copy(playing = false)
            return
        }
        book()?.pause()
        p.clearMediaItems()
        p.setPlaybackSpeed(1f)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED && state != Player.STATE_IDLE) return
                p.removeListener(this)
                clip = clip?.copy(playing = false)
            }
        })
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(app.cacheDir, "clip.wav"))))
        p.prepare()
        p.play()
        clip = c.copy(playing = true)
    }

    fun dropClip() {
        recorder?.stop()
        recorder = null
        recording = null
        clip = null
        clipSamples = null
    }

    /** A voice made from the clip, its [words] (blank if not known), [name] and language; it's then [chosen]. */
    fun makeVoice(words: String, name: String, lang: String) {
        val pcm = clipSamples ?: return
        if (cloning) return
        cloning = true
        scope.launch(Dispatchers.IO) {
            try {
                val voice = engine.clone(pcm, words, name, lang)
                withContext(Dispatchers.Main) {
                    dropClip()
                    chosen(voice)
                }
            } catch (e: Throwable) {
                failed(e.plain())
            } finally {
                cloning = false
            }
        }
    }

    fun forgetVoice(voice: String) {
        scope.launch(Dispatchers.IO) {
            engine.forget(voice)
            loadVoices()
        }
    }

    /** The model is done with: its player goes. */
    fun release() {
        preview?.release()
    }
}
