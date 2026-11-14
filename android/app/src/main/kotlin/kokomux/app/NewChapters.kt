package kokomux.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kokomux.engine.Books

/**
 * Once a day, on a network, each web novel on the shelf is checked for new
 * chapters; those out are fetched and kept (so they can be heard offline), and
 * a notification says how many, per novel. Tapping it opens the app.
 */
class NewChapters(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = applicationContext.engine
        for (mark in engine.bookmarks.all().filter { it.kind == "novel" }) {
            val c = runCatching { engine.novels.chapter(mark.source)?.first }.getOrNull() ?: continue
            val new = runCatching { engine.novels.check(c) }.getOrDefault(0)
            if (new > 0) tell(mark.title, mark.novel, new)
        }
        return Result.success()
    }

    private fun tell(title: String, novelUrl: String, new: Int) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "New chapters", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(Books.id(novelUrl).hashCode(), NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title)
            .setContentText(if (new == 1) "A new chapter is out" else "$new new chapters are out")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    companion object {
        const val CHANNEL = "new-chapters"

        /** Check daily from now on (once is enough: it's kept across restarts). */
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<NewChapters>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("new-chapters", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
