package kokomux.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kokomux.engine.Audio
import kokomux.engine.Kokoro
import kokomux.engine.withPause
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Where the app is: models to fetch, fetching, loading, ready, or stuck. */
sealed interface Stage {
    data object Missing : Stage
    data class Downloading(val label: String, val done: Long, val total: Long) : Stage
    data class Loading(val what: String) : Stage
    data object Ready : Stage
    data class Failed(val message: String) : Stage
}

/** One part made: how long misaki and Kokoro took, and how much speech it is. */
data class Made(val index: Int, val chars: Int, val sounds: Double, val kokoro: Double, val audio: Double) {
    val took get() = sounds + kokoro
}

/** A pronunciation check: how many parts, how many sound different, and which. */
data class Checked(val cases: Int, val seconds: Double, val differ: List<Triple<String, String, String>>)

/**
 * The spike's one screen: type, pick a voice, Play. Each part is tidied and
 * split by engine.py, turned into sounds by misaki, spoken by Kokoro and
 * appended to one ExoPlayer playlist, with the time each step took.
 */
class Spike(app: Application) : AndroidViewModel(app) {
    var stage by mutableStateOf<Stage>(Stage.Missing)
        private set
    var text by mutableStateOf(SAMPLE)
    var voice by mutableStateOf("af_heart")
    var voices by mutableStateOf(listOf("af_heart"))
        private set
    var playing by mutableStateOf(false)
        private set
    var loaded by mutableStateOf("")
        private set
    var checked by mutableStateOf<Checked?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    val made = mutableStateListOf<Made>()

    private val models = Models(File(app.filesDir, "models"))
    private val words = Text(app)
    private var kokoro: Kokoro? = null
    // Python and onnxruntime are driven from one thread, one part at a time.
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var job: Job? = null
    private var making = false
    private val parts = File(app.cacheDir, "parts")
    private val player = ExoPlayer.Builder(app).build().apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !making) playing = false
            }
        })
    }

    init {
        if (models.ready()) load()
    }

    /** Fetch the model and voices (about 354 MB), then load them. */
    fun download() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var shown = -1L
                    models.download { label, done, total ->
                        if (done - shown >= 1 shl 20 || done == total) {
                            shown = done
                            stage = Stage.Downloading(label, done, total)
                        }
                    }
                }
                load()
            } catch (e: Exception) {
                stage = Stage.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun load() {
        viewModelScope.launch(worker) {
            try {
                stage = Stage.Loading("Kokoro")
                val t0 = System.nanoTime()
                val k = Kokoro(models.model, models.voices)
                val kokoroSecs = seconds(t0)
                stage = Stage.Loading("misaki")
                val misakiSecs = words.warm("en-us") + words.warm("en-gb")
                kokoro = k
                voices = k.voices.names.filter { it.take(3) in setOf("af_", "am_", "bf_", "bm_") }
                loaded = "Kokoro loaded in ${fmt(kokoroSecs)} s, misaki and spaCy in ${fmt(misakiSecs)} s"
                stage = Stage.Ready
            } catch (e: Throwable) {
                stage = Stage.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Play the text, or stop. */
    fun playOrStop() {
        if (playing) return stop()
        val k = kokoro ?: return
        stop()
        made.clear()
        error = ""
        playing = true
        making = true
        val text = text
        val voice = voice
        val lang = if (voice.startsWith("b")) "en-gb" else "en-us"
        job = viewModelScope.launch(worker) {
            try {
                parts.deleteRecursively()
                parts.mkdirs()
                for ((i, part) in words.parts(text, lang).withIndex()) {
                    ensureActive()
                    val t0 = System.nanoTime()
                    val sounds = words.sounds(part.text, lang)
                    val t1 = System.nanoTime()
                    val speech = k.speak(sounds, voice)
                    val t2 = System.nanoTime()
                    val file = File(parts, "%04d.wav".format(i + 1))
                    Audio.writeWav(file, speech.withPause(part.pause))
                    val m = Made(i + 1, part.text.length, (t1 - t0) / 1e9, (t2 - t1) / 1e9, speech.seconds)
                    withContext(Dispatchers.Main) { queue(file, m) }
                }
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) error = e.message ?: e.toString()
            } finally {
                making = false
                withContext(Dispatchers.Main) { if (player.playbackState != Player.STATE_READY) playing = false }
            }
        }
    }

    private fun queue(file: File, m: Made) {
        made += m
        player.addMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        when {
            m.index == 1 -> { player.prepare(); player.play() }
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(m.index - 1, 0); player.play() }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        player.stop()
        player.clearMediaItems()
        playing = false
    }

    /** Run pronunciation.json's sentences through misaki here and compare with engine.py's sounds. */
    fun check() {
        if (checking) return
        checking = true
        viewModelScope.launch(worker) {
            try {
                val r = JSONObject(words.check())
                val differ = r.getJSONArray("differ")
                checked = Checked(r.getInt("cases"), r.getDouble("seconds"), (0 until differ.length()).map {
                    val d = differ.getJSONObject(it)
                    Triple("${d.getString("lang")}: ${d.getString("text")}", d.getString("want"), d.getString("got"))
                })
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                checking = false
            }
        }
    }

    override fun onCleared() {
        stop()
        player.release()
        kokoro?.close()
        worker.close()
    }

    companion object {
        // engine.py's BENCH_TEXT, so the numbers compare with kokoro --benchmark.
        const val SAMPLE = "The road left the village at first light and climbed into the hills, where the fields " +
            "gave way to heather. For three days she walked without meeting anyone, sleeping in the " +
            "lee of stone walls and waking to the sound of sheep."

        private fun seconds(since: Long) = (System.nanoTime() - since) / 1e9
        fun fmt(s: Double) = "%.2f".format(s)
    }
}
