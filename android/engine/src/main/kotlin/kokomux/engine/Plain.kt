package kokomux.engine

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What went wrong, in a few plain words to show: a message written for the
 * listener is kept as it is (a [ReadError]'s, a download's), a failure that
 * has a common cause is named by it, and anything else says only its kind,
 * never a stack of class names or a paragraph.
 */
fun Throwable.plain(): String = when (this) {
    is ReadError -> message.orEmpty()
    is UnknownHostException, is ConnectException, is SocketTimeoutException, is SSLException -> "No connection"
    is OutOfMemoryError -> "The phone ran out of memory"
    is FileNotFoundException -> "That file isn't there any more"
    is IOException -> written() ?: "Couldn't read or save a file"
    else -> written() ?: "Something went wrong (${javaClass.simpleName})"
}

/** The message, if it reads as one written to be shown: a line, not too long, with no class name leading it. */
private fun Throwable.written(): String? = message?.trim()?.takeIf {
    it.isNotEmpty() && it.length <= 120 && '\n' !in it && !Regex("^[a-z]+(\\.[A-Za-z_$]+)+").containsMatchIn(it)
}
