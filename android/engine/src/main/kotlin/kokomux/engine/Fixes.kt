package kokomux.engine

import java.io.File

/**
 * Your own pronunciations: a word or name, and how to say it, spelt as it
 * sounds ("Xiao Yan" said as "Shao Yen"). They change only what the voice is
 * given: the book's text stays as it's written, and the word's own letters
 * light up while its respelling is spoken. A word is matched whole, in any
 * mix of capitals.
 */
class Fixes(pairs: List<Pair<String, String>>) {
    private val sayAs = pairs.map { it.first.trim() to it.second.trim() }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        .associate { it.first.lowercase() to it.second }
    private val words = if (sayAs.isEmpty()) null else Regex(
        "(?<![\\p{L}\\p{N}])(?:" + sayAs.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])",
        setOf(RegexOption.IGNORE_CASE),
    )

    val isEmpty get() = sayAs.isEmpty()

    /** A stretch of the spoken text (bytes [from] until [to]) that stands for a word of the book's (bytes [wasFrom] until [wasTo]). */
    internal class Swap(val from: Int, val to: Int, val wasFrom: Int, val wasTo: Int)

    /** A part as it's spoken ([text]), and where its respellings are, to find the book's words again. */
    class Spoken(val text: String, internal val swaps: List<Swap>)

    /** [text] as the voice is given it. */
    fun spoken(text: String): Spoken {
        val words = words ?: return Spoken(text, emptyList())
        val out = StringBuilder()
        val swaps = ArrayList<Swap>()
        var at = 0                    // how far through [text], in characters
        var was = 0                   // the same in bytes, and how far through the spoken text
        var now = 0
        for (m in words.findAll(text)) {
            val say = sayAs[m.value.lowercase()] ?: continue
            val before = text.substring(at, m.range.first)
            out.append(before).append(say)
            val skipped = before.toByteArray().size
            val wordBytes = m.value.toByteArray().size
            val sayBytes = say.toByteArray().size
            swaps += Swap(now + skipped, now + skipped + sayBytes, was + skipped, was + skipped + wordBytes)
            was += skipped + wordBytes
            now += skipped + sayBytes
            at = m.range.last + 1
        }
        return Spoken(out.append(text.substring(at)).toString(), swaps)
    }

    /**
     * Word timings made for the spoken text ([rows], as words.player_rows writes them: a line
     * about confidence, then "start end a b" per word, a and b bytes), as timings for the book's
     * text: a word said by its respelling is lit from the respelling's first word to its last.
     */
    fun rows(rows: String, spoken: Spoken): String {
        if (spoken.swaps.isEmpty()) return rows
        val out = ArrayList<String>()
        var last: List<String>? = null            // the row before, if it's a word's: start, end, a, b
        for (line in rows.lineSequence()) {
            val f = line.split('\t')
            val a = f.getOrNull(2)?.toIntOrNull()
            val b = f.getOrNull(3)?.toIntOrNull()
            if (f.size != 4 || a == null || b == null) {
                if (line.isNotEmpty()) out += line
                last = null
                continue
            }
            val row = listOf(f[0], f[1], "${start(a, spoken)}", "${end(b, spoken)}")
            val before = last
            if (before != null && before[2] == row[2] && before[3] == row[3]) {
                last = listOf(before[0], row[1], row[2], row[3])       // the same word, still being said
                out[out.size - 1] = last.joinToString("\t")
            } else {
                last = row
                out += row.joinToString("\t")
            }
        }
        return out.joinToString("") { "$it\n" }
    }

    /** Where a word starting at byte [p] of the spoken text starts in the book's. */
    private fun start(p: Int, spoken: Spoken): Int {
        var shift = 0
        for (s in spoken.swaps) {
            if (p < s.from) break
            if (p < s.to) return s.wasFrom
            shift = s.wasTo - s.to
        }
        return p + shift
    }

    /** Where a word ending at byte [p] of the spoken text ends in the book's. */
    private fun end(p: Int, spoken: Spoken): Int {
        var shift = 0
        for (s in spoken.swaps) {
            if (p <= s.from) break
            if (p <= s.to) return s.wasTo
            shift = s.wasTo - s.to
        }
        return p + shift
    }
}

/** A voice saying parts with [fixes]: given their respellings, its word timings put back on the book's text. */
class FixedSpeaker(private val voice: Speaker, private val fixes: Fixes) : Speaker {
    override fun say(text: String): Said {
        val spoken = fixes.spoken(text)
        val said = voice.say(spoken.text)
        return Said(said.samples, said.rate, fixes.rows(said.rows, spoken), said.prepSecs, said.voiceSecs)
    }

    override fun spoken(text: String) = fixes.spoken(text).text
}

/** The pronunciations kept: a line each, the word and how to say it, tab-separated, in the order they were added. */
class FixFile(private val file: File) {
    @Synchronized
    fun all(): List<Pair<String, String>> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 2 || f[0].isBlank() || f[1].isBlank()) null else f[0] to f[1]
    }

    /** Keep [word] said as [sayAs], in place of how it was said before. */
    @Synchronized
    fun set(word: String, sayAs: String) = write(all().filter { !it.first.equals(clean(word), ignoreCase = true) } + (clean(word) to clean(sayAs)))

    @Synchronized
    fun remove(word: String) = write(all().filter { !it.first.equals(word, ignoreCase = true) })

    private fun write(pairs: List<Pair<String, String>>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(pairs.joinToString("") { "${it.first}\t${it.second}\n" })
        if (!tmp.renameTo(file)) error("Couldn't save the pronunciations")
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim().replace(Regex(" +"), " ")
}
