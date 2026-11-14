package kokomux.app

import java.io.File

/**
 * Passages saved while listening, a line each in passages.tsv: when, the book
 * (its id and title), where (the chapter, and the part to go back to), and the
 * words.
 */
class Passages(private val file: File) {
    data class Passage(val saved: Long, val book: String, val title: String, val chapter: String, val part: Int, val text: String)

    @Synchronized
    fun all(): List<Passage> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 6) null else runCatching { Passage(f[0].toLong(), f[1], f[2], f[3], f[4].toInt(), f[5]) }.getOrNull()
    }.sortedByDescending { it.saved }

    @Synchronized
    fun save(p: Passage) {
        file.parentFile?.mkdirs()
        file.appendText(listOf(p.saved, p.book, clean(p.title), clean(p.chapter), p.part, clean(p.text)).joinToString("\t") + "\n")
    }

    @Synchronized
    fun remove(saved: Long) {
        val keep = all().filter { it.saved != saved }.sortedBy { it.saved }
        file.delete()
        keep.forEach(::save)
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()

    companion object {
        /**
         * The sentence just heard in [text]: the one the word at [at] (a character offset) is in;
         * the whole text when it's a sentence or less.
         */
        fun sentence(text: String, at: Int): String {
            // A sentence ends at its mark and closing quotes, where the next starts with a capital
            // (“Is that the sea?” she asked. — one sentence).
            val ends = Regex("[.!?…。！？]+[\"'”’)\\]]*\\s+(?=[\\p{Lu}“\"‘'\\d])").findAll(text).map { it.range.last + 1 }.toList()
            val start = ends.lastOrNull { it <= at } ?: 0
            val end = ends.firstOrNull { it > at } ?: text.length
            return text.substring(start, end).trim()
        }
    }
}
