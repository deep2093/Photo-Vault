package com.deepak.photovault.faces

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ForegroundInfo
import androidx.work.OutOfQuotaPolicy
import com.deepak.photovault.MainActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    override suspend fun getForegroundInfo(): ForegroundInfo = info(applicationContext, null)

    override suspend fun doWork(): Result {
        if (!FaceLibrary.enabled(applicationContext)) return Result.success()
        if (inputData.getBoolean(MANUAL, false)) return manual()
        val daily = inputData.getBoolean(DAILY, false)
        val more = try {
            FaceLibrary.scan(applicationContext, limit = if (daily) DAILY_LIMIT else PER_TURN)
        } finally {
            if (isStopped) FaceLibrary.stop()
        }
        // Warm phone (6 Nov): tried again at the next idle time (idle jobs can't set their own back-off).
        if (FaceLibrary.stoppedWarm && !daily) return Result.retry()
        // The daily look only takes the newest photos; anything bigger waits for the charger.
        if (more && !isStopped) schedule(applicationContext, replace = !daily)
        return Result.success()
    }

    /**
     * The Scan button (6 Nov): runs with a notification ("Looking for faces: 1,200 of 3,253", Pause), which Android lets
     * keep going with the screen off. A background scan that is running stops first and this one takes over.
     */
    private suspend fun manual(): Result = coroutineScope {
        runCatching { setForeground(info(applicationContext, null)) }
        FaceLibrary.takeOver()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        var shown = -1
        val updates = launch {
            FaceLibrary.progress.collect { p ->
                if (p == null || !p.running) return@collect
                // A new line every 1% or so, not every photo.
                val step = if (p.total > 0) p.done * 100 / p.total else 0
                if (step == shown) return@collect
                shown = step
                runCatching { nm?.notify(ID, build(applicationContext, p)) }
            }
        }
        try {
            FaceLibrary.scan(applicationContext, fromButton = true)
        } finally {
            if (isStopped) FaceLibrary.stop()
            updates.cancel()
        }
        Result.success()
    }

    companion object {
        private const val NAME = "people-scan"
        private const val MANUAL_NAME = "people-scan-now"
        private const val MANUAL = "manual"
        private const val CHANNEL = "people_scan"
        private const val ID = 62

        /** Scan now (6 Nov): runs straight away, shown as a notification. Tapping again queues one more turn. */
        fun scanNow(context: Context) {
            if (!FaceLibrary.enabled(context)) return
            val request = OneTimeWorkRequestBuilder<FaceScanWorker>()
                .setInputData(androidx.work.workDataOf(MANUAL to true))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        private fun build(context: Context, p: com.deepak.photovault.ui.shell.ScanProgress?): android.app.Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "People: looking for faces", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                context,
                7,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PEOPLE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val pause = PendingIntent.getBroadcast(
                context,
                8,
                Intent(context, FaceScanPauseReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = if (p != null && p.total > 0) "%,d of %,d photos".format(p.done, p.total) else "Getting ready"
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(com.deepak.photovault.NotificationIcon.of(context))
                .setContentTitle("Looking for faces")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setProgress(p?.total ?: 0, p?.done ?: 0, p == null || p.total == 0)
                .setContentIntent(open)
                .addAction(0, "Pause", pause)
                .build()
        }

        private fun info(context: Context, p: com.deepak.photovault.ui.shell.ScanProgress?): ForegroundInfo {
            val n = build(context, p)
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(ID, n)
        }
        private const val PER_TURN = 300
        private const val DAILY_NAME = "people-scan-daily"
        private const val DAILY = "daily"

        /** Once a day, even without the charger (6 Nov): new photos only, a small number. */
        private const val DAILY_LIMIT = 60

        fun schedule(context: Context, replace: Boolean = false) {
            if (!FaceLibrary.enabled(context)) return
            // Charging, and the phone not in use (6 Nov): it mostly runs overnight.
            val request = OneTimeWorkRequestBuilder<FaceScanWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).setRequiresDeviceIdle(true).build())
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
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(DAILY_NAME)
        }
    }
}

/** "Pause" on the scan notification (6 Nov): stops the scan; what it found so far is kept. */
class FaceScanPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FaceLibrary.stop()
    }
}
