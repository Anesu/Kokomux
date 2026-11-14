package kokomux.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's own browser, out of sight, for the pages a site shows only once a
 * browser has passed its check (Cloudflare's "Just a moment…"): it opens the
 * page as Chrome on the phone would, waits for the check to pass, and hands
 * back the page. The text side calls [fetch] from its thread (Python, through
 * Chaquopy) when a site asks for the check.
 */
object Browser {
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * The page at [url] once its check has passed (posting [form], url-encoded, if there is one);
     * null if it hasn't in 34 seconds. Blocks; never call it on the main thread.
     */
    @JvmStatic
    fun fetch(url: String, form: String?): String? {
        val done = CountDownLatch(1)
        var html: String? = null
        var view: WebView? = null
        main.post { view = open(url, form) { html = it; done.countDown() } }
        done.await(34, TimeUnit.SECONDS)
        main.post { view?.destroy() }
        return html
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(url: String, form: String?, got: (String) -> Unit): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        var finished = false
        fun look() {
            if (finished) return
            evaluateJavascript("(function(){return [document.title, document.documentElement.outerHTML]})()") { value ->
                val page = runCatching { JSONArray(value) }.getOrNull()
                val title = page?.optString(0).orEmpty()
                if (page != null && !title.startsWith("Just a moment") && page.optString(1).length > 500) {
                    finished = true
                    got(page.getString(1))
                } else if (!finished) {
                    main.postDelayed(::look, 987)
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, address: String) = look()
        }
        if (form != null) postUrl(url, form.toByteArray()) else loadUrl(url)
    }
}
