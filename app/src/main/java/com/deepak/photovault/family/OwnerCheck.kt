package com.deepak.photovault.family

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
import com.deepak.photovault.core.household.HouseholdStore
import com.deepak.photovault.core.household.Membership
import com.deepak.photovault.core.household.SpaceRequests
import java.util.concurrent.TimeUnit

/**
 * The owner's phone looks at the drive about once an hour (24 Oct) for people asking for more space or phones
 * asking to join, and shows a notification (Android adds the dot on the app icon). Only on Wi-Fi, only when the
 * phone is on the home network, only on the owner's phone; anywhere else it does nothing at all. A couple of
 * seconds each time.
 */
class OwnerCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val platform = AndroidDrivePlatform(app)
        val connection = platform.loadConnection() ?: return Result.success()
        if (connection.username.isNotBlank() && connection.password.isEmpty()) return Result.success()
        // Away from home: don't even try.
        if (!platform.onSameNetwork(connection.host.trim())) return Result.success()
        val fs = runCatching { platform.openDetached(connection) }.getOrNull() ?: return Result.success()
        fs.use {
            val store = HouseholdStore(fs) { System.currentTimeMillis() }
            val joined = runCatching { store.membership(platform.deviceId, platform.rememberedUserId()) }.getOrNull() as? Membership.Joined
                ?: return Result.success()
            if (!joined.isOwner) return Result.success()
            val asks = runCatching { SpaceRequests(fs) { System.currentTimeMillis() }.open(joined.household) }.getOrDefault(emptyList())
            val phones = runCatching { store.pendingRequests() }.getOrDefault(emptyList())
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val told = prefs.getStringSet(KEY_TOLD, emptySet()).orEmpty()
            val items = asks.map { "space:${it.userId}:${it.requestedAtMillis}" to "${it.name} asks for ${it.wantGb} GB of space" } +
                phones.map { "join:${it.deviceId}:${it.requestedAtMillis}" to "${it.newName ?: joined.household.member(it.existingUserId ?: "")?.name ?: "A phone"} wants to join the family" }
            val fresh = items.filter { it.first !in told }
            if (fresh.isNotEmpty()) notify(app, fresh.map { it.second })
            // Keep only what is still open, so a later request notifies again.
            prefs.edit().putStringSet(KEY_TOLD, items.map { it.first }.toSet()).apply()
        }
        return Result.success()
    }

    private fun notify(context: Context, lines: List<String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Family requests", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_FAMILY, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (lines.size == 1) lines.first() else "${lines.size} family requests")
            .setContentText(if (lines.size == 1) "Open Family to answer." else lines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setNumber(lines.size)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(ID, n)
    }

    companion object {
        private const val PREFS = "family"
        private const val KEY_TOLD = "requests_told"
        private const val CHANNEL = "family_requests"
        private const val ID = 51
        private const val UNIQUE = "owner-check"

        /** Hourly, Wi-Fi only (unmetered), battery not low. Runs on every phone but acts only on the owner's. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OwnerCheckWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
