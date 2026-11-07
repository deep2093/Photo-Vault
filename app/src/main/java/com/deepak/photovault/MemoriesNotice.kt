package com.deepak.photovault

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepak.photovault.core.media.MediaStoreLibrary
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.ui.shell.Memories
import kotlinx.datetime.TimeZone
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * On this day (7C-1, 7 Nov): once a morning, a quiet notification when there are photos from today's date in
 * earlier years. Off in Settings > Appearance. Only one a day, and nothing is sent anywhere: it all runs on the phone.
 */
class MemoriesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val appearance = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        if (!appearance.getBoolean("memories_notice", true)) return Result.success()
        val zone = TimeZone.currentSystemDefault()
        val today = Timeline.localDate(System.currentTimeMillis(), zone)
        val prefs = app.getSharedPreferences("memories", Context.MODE_PRIVATE)
        if (prefs.getString("noticed", null) == today.toString()) return Result.success()
        // Too early in the day (the job can run a little before 9): try again next time.
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 8) return Result.success()
        val items = runCatching { MediaStoreLibrary(app).loadAll() }.getOrElse { return Result.success() }
        val memory = Memories.forNotice(Memories.find(items, today, zone)) ?: run {
            prefs.edit().putString("noticed", today.toString()).apply()
            return Result.success()
        }
        if (MemoriesNotice.show(app, memory.title, memory.year, memory.ids.size)) {
            prefs.edit().putString("noticed", today.toString()).apply()
        }
        return Result.success()
    }
}

object MemoriesNotice {
    private const val CHANNEL = "memories"
    private const val ID = 71
    private const val NAME = "memories_daily"

    /** Checks every few hours; the worker itself makes sure it's only once a day, after 8 am. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<MemoriesWorker>(4, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun show(context: Context, title: String, year: Int, count: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "On this day", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            context,
            7,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_MEMORIES, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(NotificationIcon.of(context))
            .setContentTitle(title.replaceFirstChar { it.uppercase() })
            .setContentText("$count ${if (count == 1) "photo" else "photos"} from $year. Tap to look back.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(ID, n)
        return true
    }
}
