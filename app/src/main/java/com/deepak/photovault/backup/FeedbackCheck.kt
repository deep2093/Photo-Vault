package com.deepak.photovault.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepak.photovault.AndroidDrivePlatform
import com.deepak.photovault.MainActivity
import com.deepak.photovault.R
import com.deepak.photovault.core.household.FeedbackBox
import com.deepak.photovault.core.household.FeedbackKind
import com.deepak.photovault.core.household.HouseholdStore
import com.deepak.photovault.core.household.Membership
import java.util.concurrent.TimeUnit

/**
 * Every 3 hours the owner's phone looks for new feedback on the family drive and says so in a notification
 * (25 Oct). It only works while the phone can reach the drive; otherwise it quietly tries again next time.
 */
class FeedbackCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!com.deepak.photovault.NotifyPrefs.on(applicationContext, com.deepak.photovault.NotifyPrefs.FEEDBACK)) return Result.success()
        val platform = AndroidDrivePlatform(applicationContext)
        val connection = platform.loadConnection() ?: return Result.success()
        // Away from home: don't even try (10 Nov).
        if (!platform.onSameNetwork(connection.host.trim())) return Result.success()
        val unseen = runCatching {
            platform.openDetached(connection).use { fs ->
                val joined = HouseholdStore(fs) { System.currentTimeMillis() }
                    .membership(platform.deviceId, platform.rememberedUserId()) as? Membership.Joined ?: return@use emptyList()
                if (!joined.isOwner) return@use emptyList()
                val seen = maxOf(platform.feedbackSeenAt(), platform.feedbackNotifiedAt())
                FeedbackBox(fs).inbox(joined.household.members.map { it.userId }).map { it.note }.filter { it.createdAtMillis > seen }
            }
        }.getOrElse { return Result.success() }
        if (unseen.isEmpty()) return Result.success()
        FeedbackNotifications.show(applicationContext, unseen.size, unseen.count { it.kind == FeedbackKind.BUG })
        platform.setFeedbackNotifiedAt(unseen.maxOf { it.createdAtMillis })
        return Result.success()
    }
}

object FeedbackCheck {
    private const val NAME = "feedback_check"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<FeedbackCheckWorker>(3, TimeUnit.HOURS)
            // 10 Nov: Wi-Fi only, and (below) only on the home network, where the drive is.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        // UPDATE so phones that already had the old "any network" job get the Wi-Fi-only one.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

private object FeedbackNotifications {
    private const val CHANNEL = "feedback_received"
    private const val ID = 4107

    @android.annotation.SuppressLint("MissingPermission")
    fun show(context: Context, count: Int, bugs: Int) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(CHANNEL, "Feedback received", NotificationManager.IMPORTANCE_DEFAULT))
        val title = if (bugs > 0) "New bug report" + (if (bugs > 1) "s" else "") else "New feedback"
        val text = if (count == 1) "Someone in the family sent a note. Open Settings > Send feedback to read it." else "$count new notes from the family. Open Settings > Send feedback to read them."
        // Its own number (8 Nov): with 0 it shared one tap action with the backup notification.
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.deepak.photovault.NotificationIcon.of(context))
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(ID, n)
    }
}
