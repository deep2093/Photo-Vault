package com.deepak.photovault.faces

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Scans for faces while the phone is charging (People, 7A): a few hundred photos a turn, then it queues the next. Also
 * once a day without the charger (6 Nov), for a few new photos.
 */
class FaceScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!FaceLibrary.enabled(applicationContext)) return Result.success()
        val daily = inputData.getBoolean(DAILY, false)
        val more = try {
            FaceLibrary.scan(applicationContext, limit = if (daily) DAILY_LIMIT else PER_TURN)
        } finally {
            if (isStopped) FaceLibrary.stop()
        }
        // The daily look only takes the newest photos; anything bigger waits for the charger.
        if (more && !isStopped) schedule(applicationContext, replace = !daily)
        return Result.success()
    }

    companion object {
        private const val NAME = "people-scan"
        private const val PER_TURN = 300
        private const val DAILY_NAME = "people-scan-daily"
        private const val DAILY = "daily"

        /** Once a day, even without the charger (6 Nov): new photos only, a small number. */
        private const val DAILY_LIMIT = 60

        fun schedule(context: Context, replace: Boolean = false) {
            if (!FaceLibrary.enabled(context)) return
            val request = OneTimeWorkRequestBuilder<FaceScanWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                if (replace) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
            val daily = androidx.work.PeriodicWorkRequestBuilder<FaceScanWorker>(1, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInputData(androidx.work.workDataOf(DAILY to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY_NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP, daily)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(DAILY_NAME)
        }
    }
}
