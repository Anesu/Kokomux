package kokomux.app

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kokomux.app.ui.Sleep
import kokomux.engine.Book
import kokomux.engine.Maker

/** What to listen to: a book file in the app's storage, an address, or a typed text's file. */
data class Request(val id: String, val kind: String, val source: String, val name: String, val voice: String,
                   val speed: Float = 1f) {
    fun toBundle() = Bundle().apply {
        putString("id", id); putString("kind", kind); putString("source", source); putString("name", name)
        putString("voice", voice); putFloat("speed", speed)
    }

    companion object {
        fun from(b: Bundle) = Request(b.getString("id")!!, b.getString("kind")!!, b.getString("source")!!,
            b.getString("name").orEmpty(), b.getString("voice") ?: "af_heart", b.getFloat("speed", 1f))
    }
}

/**
 * What's being listened to, shared by the listening service (which writes it)
 * and the screen (which shows it): the book, the parts made so far with how
 * long each took, the part playing, and what went wrong.
 */
object Listening {
    /** The book being listened to, from which part, in which voice. */
    class Now(val request: Request, val book: Book, val first: Int, val lang: String)

    var now by mutableStateOf<Now?>(null)
    /** Opening a book or page (reading it, loading Kokoro). */
    var opening by mutableStateOf(false)
    /** Parts are still to be made. */
    var making by mutableStateOf(false)
    /** An engine getting ready for its first listen on this phone, in words to show ("" when it isn't). */
    var preparing by mutableStateOf("")
    /** The part playing (counting from 1), 0 before it starts. */
    var at by mutableStateOf(0)
    var error by mutableStateOf("")
    /** Something to say that isn't wrong ("Up to date: Chapter 8 is the latest"), shown for a moment. */
    var note by mutableStateOf("")
    val made = mutableStateListOf<Maker.Made>()
    /** How Kokoro ran for [made] (kokoro --benchmark's words). */
    var madeWith by mutableStateOf("")
    /** The sleep timer, if it's set. */
    var sleep by mutableStateOf<Sleep?>(null)
    /** Seconds of speech per letter at 1×, from the parts made this listen: for the time left. */
    var perLetter by mutableStateOf(0.0)
    private var letters = 0L
    private var seconds = 0.0

    /** A part made: [letters] long, taking [secondsAtOne] to say at 1×. */
    fun paced(letters: Int, secondsAtOne: Double) {
        this.letters += letters
        seconds += secondsAtOne
        if (this.letters > 0) perLetter = seconds / this.letters
    }

    /** A new listen (or voice): the pace is measured again. */
    fun unpaced() {
        letters = 0
        seconds = 0.0
        perLetter = 0.0
    }
}
