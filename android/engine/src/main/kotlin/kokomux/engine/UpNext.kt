package kokomux.engine

import java.io.File

/**
 * What's waiting to be heard, in order, kept as the Termux queue keeps it:
 * a line each of id, kind ("url", "file" or "text"), source (the address, or
 * the file in the app's storage) and title, tab-separated.
 */
class UpNext(private val file: File) {
    data class Item(val id: String, val kind: String, val source: String, val title: String)

    @Synchronized
    fun all(): List<Item> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 4 || f[0].isEmpty()) null else Item(f[0], f[1], f[2], f[3])
    }

    /** Add [item] at the end; false if it's waiting already. */
    @Synchronized
    fun add(item: Item): Boolean {
        if (all().any { it.id == item.id }) return false
        file.parentFile?.mkdirs()
        file.appendText(listOf(item.id, item.kind, item.source, clean(item.title)).joinToString("\t") + "\n")
        return true
    }

    /** Take the one called [id] out; the item, if it was waiting. */
    @Synchronized
    fun remove(id: String): Item? {
        val items = all()
        val gone = items.firstOrNull { it.id == id } ?: return null
        val tmp = File(file.path + ".tmp")
        tmp.writeText(items.filter { it.id != id }.joinToString("") { listOf(it.id, it.kind, it.source, it.title).joinToString("\t") + "\n" })
        if (!tmp.renameTo(file)) error("Couldn't save Up next")
        return gone
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()
}
