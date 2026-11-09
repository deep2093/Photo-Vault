package com.deepak.photovault.living

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepak.photovault.core.media.MediaStoreLibrary
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.ui.shell.Memories
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import java.util.concurrent.TimeUnit

/**
 * Living photos made ahead (7D-3, 9 Nov): only while the phone is charging (and the battery isn't low), the covers
 * of today's and tomorrow's Memories are made into living photos, so their cards already move when opened. Nothing
 * leaves the phone.
 */
class LivingPrepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val zone = TimeZone.currentSystemDefault()
        val today = Timeline.localDate(System.currentTimeMillis(), zone)
        val items = runCatching { MediaStoreLibrary(app).loadAll() }.getOrElse { return Result.success() }
        val byId = items.associateBy { it.id }
        val covers = LinkedHashSet<Long>()
        for (day in listOf(today, today.plus(1, DateTimeUnit.DAY))) {
            val found = Memories.find(items, day, zone)
            val month = Memories.thisMonth(items, day, zone, shown = found.flatMap { it.ids }.toHashSet())
            (found + month).mapNotNullTo(covers) { m -> m.ids.firstOrNull() }
        }
        val living = AndroidLiving(app)
        for (id in covers.take(MOST)) {
            if (isStopped) break
            val item = byId[id] ?: continue
            if (item.isVideo) continue
            runCatching { living.make(item) }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "living_prep"
        private const val MOST = 16

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LivingPrepWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
