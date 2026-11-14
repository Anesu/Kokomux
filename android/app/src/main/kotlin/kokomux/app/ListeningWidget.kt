package kokomux.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionStartActivity as startWith
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kokomux.app.ui.Tokens
import kokomux.app.ui.timeLeftLabel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The home-screen widget: what's playing (its title, its chapter and the time
 * left) and play or pause; with nothing playing, the book heard last, and play
 * carries on with it. Tap the rest to open the app. In the app's colours,
 * dark or light with the phone.
 */
class ListeningWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val now = Listening.now
        val mark = if (now == null) context.engine.bookmarks.all().firstOrNull() else null
        val title = now?.book?.novel?.title ?: now?.book?.title ?: mark?.title ?: "kokomux"
        val at = Listening.at
        val line = when {
            now != null -> listOfNotNull(now.book.chapterOf(at.coerceAtLeast(1))?.title?.takeIf { now.book.chapters.size > 1 || now.book.novel != null },
                Listening.perLetter.takeIf { it > 0 && at > 0 }?.let {
                    val letters = now.book.parts.drop(at - 1).sumOf { p -> p.text.length }
                    timeLeftLabel(letters * it / now.request.speed)
                }).joinToString(" · ")
            mark != null -> mark.chapter.ifEmpty { "${(mark.progress * 100).toInt()}% through" }
            else -> "Nothing on the shelf yet"
        }
        val playing = now != null && playingNow
        provideContent {
            val bg = ColorProvider(day = Tokens.LIGHT.surf, night = Tokens.DARK.surf)
            val fg = ColorProvider(day = Tokens.LIGHT.fg, night = Tokens.DARK.fg)
            val quiet = ColorProvider(day = Tokens.LIGHT.quietOn(Tokens.LIGHT.surf), night = Tokens.DARK.quietOn(Tokens.DARK.surf))
            val accent = ColorProvider(day = Tokens.LIGHT.accent, night = Tokens.DARK.accent)
            Row(
                GlanceModifier.fillMaxSize().background(bg).cornerRadius(21.dp).padding(13.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The book's tile: its first letter on the accent (Cover's, simply).
                Box(GlanceModifier.size(34.dp, 55.dp).background(accent).cornerRadius(5.dp), contentAlignment = Alignment.Center) {
                    Text(title.take(1).uppercase(), style = TextStyle(color = ColorProvider(Color.White, Color(0xFF16151D)),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold))
                }
                Spacer(GlanceModifier.width(13.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(title, maxLines = 1, style = TextStyle(color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                    if (line.isNotEmpty()) Text(line, maxLines = 1, style = TextStyle(color = quiet, fontSize = 13.sp))
                }
                if (now != null || mark != null) {
                    Spacer(GlanceModifier.width(8.dp))
                    Box(
                        GlanceModifier.size(55.dp).background(accent).cornerRadius(28.dp).clickable(
                            if (now != null) actionRunCallback<PlayPause>()
                            else startWith(Intent(context, MainActivity::class.java).putExtra(MainActivity.RESUME, mark!!.id)),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(ImageProvider(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = if (playing) "Pause" else "Play", modifier = GlanceModifier.size(28.dp),
                            colorFilter = androidx.glance.ColorFilter.tint(ColorProvider(day = Tokens.LIGHT.bg, night = Tokens.DARK.bg)))
                    }
                }
            }
        }
    }

    /** Play or pause, through the listening service's session. */
    class PlayPause : ActionCallback {
        override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
            val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, ListenService::class.java))).buildAsync()
            val got = CompletableDeferred<MediaController>()
            future.addListener({ got.complete(future.get()) }, ContextCompat.getMainExecutor(context))
            val c = got.await()
            if (c.isPlaying) c.pause() else c.play()
            c.release()
        }
    }

    companion object {
        /** Whether the player is playing (the service says, as it changes). */
        @Volatile var playingNow = false

        /** Show what's playing now. */
        fun refresh(context: Context) {
            MainScope().launch { runCatching { ListeningWidget().updateAll(context) } }
        }
    }
}

/** The widget's receiver, as Android asks for one. */
class ListeningWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ListeningWidget()
}
