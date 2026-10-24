package com.deepak.photovault.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.deepak.photovault.MainActivity
import com.deepak.photovault.R
import com.deepak.photovault.core.backup.BackupSettings
import com.deepak.photovault.core.backup.BackupTrigger
import com.deepak.photovault.core.backup.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The background job that backs up (or restores). Android's WorkManager starts it at the scheduled time once its
 * conditions are met (Wi-Fi, and charging if chosen), so a phone not on charge at 2 am backs up as soon as it is
 * charging on Wi-Fi. It survives the app being closed and the phone restarting.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = BackupNotifications.foreground(applicationContext, null)

    override suspend fun doWork(): Result {
        val graph = BackupGraph.get(applicationContext)
        // Keeps the work alive for long backups and shows its progress. Android 12+ may refuse this when the app is
        // in the background; the backup then still runs, and carries on next time if Android stops it.
        runCatching { setForeground(getForegroundInfo()) }
        if (inputData.getBoolean(KEY_RESTORE, false)) {
            val only = if (inputData.getBoolean(KEY_SELECTED, false)) RestoreSelection.take(applicationContext) else null
            graph.runner.restoreMissing(only) { done, total -> BackupNotifications.restoring(applicationContext, done, total) }
            BackupNotifications.clearProgress(applicationContext)
            return Result.success()
        }
        val trigger = runCatching { BackupTrigger.valueOf(inputData.getString(KEY_TRIGGER).orEmpty()) }.getOrDefault(BackupTrigger.SCHEDULED)
        val run = graph.runner.backUp(trigger) { p -> BackupNotifications.progress(applicationContext, p.done, p.total) }
        BackupNotifications.clearProgress(applicationContext)
        if (run != null) {
            when (RetryPolicy.after(run)) {
                RetryPolicy.Action.RETRY_IN_AN_HOUR -> BackupScheduler.retryInAnHour(applicationContext, graph.settings.load())
                RetryPolicy.Action.NOTIFY -> BackupNotifications.failed(applicationContext, run.failureMessage ?: "Backup didn't finish.")
                RetryPolicy.Action.NOTHING -> Unit
            }
        }
        // The next scheduled time, counted from now (a backup that waited for a charger doesn't shift the week).
        if (trigger != BackupTrigger.MANUAL) BackupScheduler.scheduleNext(applicationContext, graph.settings.load(), exceptId = id)
        return Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        const val KEY_RESTORE = "restore"
        const val KEY_SELECTED = "selected"
    }
}

/** Puts backups in WorkManager: the next scheduled one, the one-hour retry, "Back up now" and restore. */
object BackupScheduler {
    private const val TAG_ALL = "backup"
    private const val TAG_SCHEDULED = "backup-scheduled"
    private const val UNIQUE_RETRY = "backup-retry"
    private const val UNIQUE_NOW = "backup-now"
    private const val UNIQUE_RESTORE = "restore-now"

    /** Wi-Fi unless mobile data is allowed; a charger for scheduled backups when chosen. */
    private fun constraints(settings: BackupSettings, scheduled: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (settings.mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresCharging(scheduled && settings.chargingOnly)
        .build()

    /** After a change in Backup settings: replaces the waiting scheduled backup with one at the new time. */
    suspend fun reschedule(context: Context, settings: BackupSettings) = scheduleNext(context, settings, exceptId = null)

    /** At app start: makes sure a scheduled backup is waiting (they are kept across restarts by Android). */
    suspend fun ensureScheduled(context: Context, settings: BackupSettings) = withContext(Dispatchers.IO) {
        if (!settings.schedule.enabled) return@withContext
        val waiting = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_SCHEDULED).first()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
        if (!waiting) scheduleNext(context, settings, exceptId = null)
    }

    suspend fun scheduleNext(context: Context, settings: BackupSettings, exceptId: java.util.UUID?) = withContext(Dispatchers.IO) {
        val wm = WorkManager.getInstance(context)
        // Cancel waiting ones only, never a backup that is running right now.
        wm.getWorkInfosByTagFlow(TAG_SCHEDULED).first()
            .filter { it.id != exceptId && it.state == WorkInfo.State.ENQUEUED }
            .forEach { wm.cancelWorkById(it.id) }
        if (!settings.schedule.enabled) return@withContext
        val now = System.currentTimeMillis()
        val at = settings.schedule.nextRun(now, TimeZone.currentSystemDefault())
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(settings, scheduled = true))
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to BackupTrigger.SCHEDULED.name))
            .addTag(TAG_ALL)
            .addTag(TAG_SCHEDULED)
            .build()
        wm.enqueue(request)
    }

    fun retryInAnHour(context: Context, settings: BackupSettings) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(RetryPolicy.RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(settings, scheduled = true))
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to BackupTrigger.RETRY.name))
            .addTag(TAG_ALL)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_RETRY, ExistingWorkPolicy.REPLACE, request)
    }

    /** "Back up now": no charger needed; waits for Wi-Fi unless mobile data is allowed. */
    fun backUpNow(context: Context, settings: BackupSettings) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(constraints(settings, scheduled = false))
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to BackupTrigger.MANUAL.name))
            .addTag(TAG_ALL)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun restore(context: Context, selected: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(BackupWorker.KEY_RESTORE to true, BackupWorker.KEY_SELECTED to selected))
            .addTag(TAG_ALL)
            .build()
        // A chosen set replaces a waiting one; a full restore keeps the one already running.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_RESTORE, if (selected) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * The backups chosen in Settings > Storage to bring back, kept in a small file because a long list doesn't fit
 * in WorkManager's input. Read once by the restore job.
 */
object RestoreSelection {
    private fun file(context: Context) = java.io.File(context.applicationContext.filesDir, "backup/restore-selection.txt")

    fun save(context: Context, paths: List<String>) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(paths.joinToString("\n"))
    }

    fun take(context: Context): Set<String> {
        val f = file(context)
        if (!f.exists()) return emptySet()
        return f.readLines().filter { it.isNotBlank() }.toSet().also { f.delete() }
    }
}

/** Backup notifications: a quiet progress one while running, and one when a scheduled backup fails twice. */
object BackupNotifications {
    private const val CHANNEL_PROGRESS = "backup_progress"
    private const val CHANNEL_PROBLEMS = "backup_problems"
    private const val ID_PROGRESS = 41
    private const val ID_FAILED = 42

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Backup progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROBLEMS, "Backup problems", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_BACKUP, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(context: Context, title: String, done: Int?, total: Int?) =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (done != null && total != null && total > 0) "$done of $total" else null)
            .setProgress(total ?: 0, done ?: 0, total == null || total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()

    fun foreground(context: Context, total: Int?): ForegroundInfo {
        val n = progressNotification(context, "Backing up", null, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID_PROGRESS, n)
        }
    }

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    fun progress(context: Context, done: Int, total: Int) {
        if (allowed(context)) NotificationManagerCompat.from(context).notify(ID_PROGRESS, progressNotification(context, "Backing up", done, total))
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun restoring(context: Context, done: Int, total: Int) {
        if (allowed(context)) NotificationManagerCompat.from(context).notify(ID_PROGRESS, progressNotification(context, "Restoring photos", done, total))
    }

    fun clearProgress(context: Context) = NotificationManagerCompat.from(context).cancel(ID_PROGRESS)

    /** Says why, in plain words (decided 24 Oct). */
    @android.annotation.SuppressLint("MissingPermission")
    fun failed(context: Context, why: String) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Backup didn't finish")
            .setContentText(why)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$why\n\nIt was tried twice, an hour apart. It will try again at the next scheduled time."))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_FAILED, n)
    }
}
