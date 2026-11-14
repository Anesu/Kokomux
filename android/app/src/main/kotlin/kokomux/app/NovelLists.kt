package kokomux.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kokomux.app.ui.Found
import kokomux.app.ui.TocEntry
import kokomux.engine.Book
import kokomux.engine.Books
import kokomux.engine.plain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Web novels' lists, for the screens: a novel's chapters (the one playing's,
 * or a found one's, to pick where to start) and the novels found by name.
 * Part of the AppModel, which gives it where to say what went wrong ([failed]).
 */
class NovelLists(private val engine: Engine, private val scope: CoroutineScope, private val failed: (String) -> Unit) {
    /** A novel's chapters (empty until asked for), and whether they're being fetched. */
    var toc by mutableStateOf(emptyList<TocEntry>())
        private set
    var tocLoading by mutableStateOf(false)
        private set
    /** Novels found by name, and whether a search is going. */
    var found by mutableStateOf(emptyList<Found>())
        private set
    var searching by mutableStateOf(false)
        private set

    /** The chapters of the novel [b], which is playing at part [at]. */
    fun loadToc(b: Book, at: Int) {
        val c = b.chapters.lastOrNull()?.takeIf { b.novel != null } ?: return
        tocLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val got = engine.novels.chapter(c.url)?.first ?: return@launch
                val playing = b.chapterOf(at.coerceAtLeast(1))?.number ?: c.number
                toc = engine.novels.toc(got).map { (n, label, url) -> TocEntry(n, label, url, n < playing, false) }
            } catch (e: Exception) {
                failed(e.plain())
            } finally {
                tocLoading = false
            }
        }
    }

    /** Novels whose name has these words in it. */
    fun search(query: String) {
        if (query.isBlank()) return
        searching = true
        found = emptyList()
        scope.launch(Dispatchers.IO) {
            try {
                found = engine.exclusive { engine.text.search(query) }.map { (title, url, latest) -> Found(title, url, latest) }
                if (found.isEmpty()) failed("No novel called that")
            } catch (e: Exception) {
                failed(e.plain())
            } finally {
                searching = false
            }
        }
    }

    /** A found novel's chapters, to pick where to start. */
    fun novelToc(found: Found) {
        tocLoading = true
        toc = emptyList()
        scope.launch(Dispatchers.IO) {
            try {
                val heard = engine.bookmarks.get(Books.id(found.url))?.number ?: 0
                val list = engine.exclusive { engine.text.toc(found.url, "") }
                toc = list.map { (n, label, url) -> TocEntry(n, label, url, n < heard, n == heard) }
                if (toc.isEmpty()) failed("Couldn't find that novel's chapters")
            } catch (e: Exception) {
                failed(e.plain())
            } finally {
                tocLoading = false
            }
        }
    }
}
