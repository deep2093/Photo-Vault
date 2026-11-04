package com.deepak.photovault.faces

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Scans for faces only while the phone is charging (People, 7A). A few hundred photos a turn, then it queues the next. */
class FaceScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!FaceLibrary.enabled(applicationContext)) return Result.success()
        val more = try {
            FaceLibrary.scan(applicationContext, limit = PER_TURN)
        } finally {
            if (isStopped) FaceLibrary.stop()
        }
        if (more && !isStopped) schedule(applicationContext, replace = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "people-scan"
        private const val PER_TURN = 300

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
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
