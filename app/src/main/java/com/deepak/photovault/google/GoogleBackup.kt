package com.deepak.photovault.google

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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepak.photovault.MainActivity
import com.deepak.photovault.backup.AndroidMediaReader
import com.deepak.photovault.backup.BackupRunner
import com.deepak.photovault.backup.BackupSettingsStore
import com.deepak.photovault.backup.FileBackupIndex
import com.deepak.photovault.core.backup.BackupCatalog
import com.deepak.photovault.core.backup.BackupEngine
import com.deepak.photovault.core.backup.BackupPrivacy
import com.deepak.photovault.core.backup.BackupRecord
import com.deepak.photovault.core.backup.BackupSelection
import com.deepak.photovault.core.backup.BackupTrigger
import com.deepak.photovault.core.backup.FailureReason
import com.deepak.photovault.core.media.MediaStoreLibrary
import com.deepak.photovault.ui.shell.GoogleStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Backing up to Google Drive (Phase 10, 13 Nov). Runs on its own (not in the family drive's time slot: it can't
 * crowd the home drive), every 12 hours when the phone allows, and when asked.
 *
 * Accounts fill in order: when the first is nearly full, the rest go to the next one. When all are full the person is
 * told by a notification and, when they open the app, a popup.
 */
object GoogleBackup {
    /** Each phone's Google folder is "users/me" inside Pixallery: one person per Google account, whichever family. */
    const val USER = "me"

    /** Prefix of a Google file's path in the timeline: drive:gdrive/<account>/<path>. */
    const val PATH_PREFIX = "gdrive/"

    val status = MutableStateFlow(GoogleStatus())
    private val running = Mutex()

    fun index(context: Context, email: String) = FileBackupIndex(context, "google-${GoogleAccounts.slug(email)}.jsonl")

    /** What is backed up to any Google account, by key: for "backed up" marks and Free up space. */
    suspend fun keys(context: Context): Set<String> =
        GoogleAccounts.list(context).flatMap { index(context, it).keys() }.toSet()

    /**
     * Everything Pixallery saved to Google, from each account's own lists, with paths made unique per account for the
     * timeline ("gdrive/<account>/users/me/...").
     */
    suspend fun records(context: Context): List<BackupRecord> {
        val vault = privateVault(context)
        return GoogleAccounts.list(context).flatMap { email ->
            runCatching {
                BackupCatalog(GoogleAccounts.fs(context, email), USER, vault).records()
                    .map { it.copy(drivePath = PATH_PREFIX + GoogleAccounts.slug(email) + "/" + it.drivePath) }
            }.getOrDefault(emptyList())
        }
    }

    /** Which account and which path a timeline path points at, or null when it isn't a Google one. */
    fun split(context: Context, path: String): Pair<String, String>? {
        if (!path.startsWith(PATH_PREFIX)) return null
        val rest = path.removePrefix(PATH_PREFIX)
        val slug = rest.substringBefore('/')
        val email = GoogleAccounts.list(context).firstOrNull { GoogleAccounts.slug(it) == slug } ?: return null
        return email to rest.substringAfter('/')
    }

    /** Reads a Google file for the timeline (pictures, videos), decrypting a Private one on the way. */
    suspend fun read(context: Context, path: String, sink: (ByteArray, Int) -> Unit): Boolean {
        val (email, inner) = split(context, path) ?: return false
        val fs = GoogleAccounts.fs(context, email)
        return try {
            val kind = when {
                inner.endsWith(".pxo") -> com.deepak.photovault.core.backup.ObjectKind.MEDIA
                inner.endsWith(".pxt") -> com.deepak.photovault.core.backup.ObjectKind.THUMB
                else -> null
            }
            if (kind == null) {
                fs.readStream(inner, sink)
            } else {
                val vault = privateVault(context) ?: return false
                vault.decrypt(kind, com.deepak.photovault.core.backup.PrivatePaths.idOf(inner), { cipher -> fs.readStream(inner, cipher) }, sink)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** This person's private key, if it is on the phone (the family drive's: one key per person). */
    private fun privateVault(context: Context): com.deepak.photovault.core.backup.PrivateVault? {
        val platform = com.deepak.photovault.AndroidDrivePlatform(context)
        val house = platform.rememberedHousehold() ?: return null
        val me = platform.rememberedUserId() ?: return null
        return com.deepak.photovault.PrivateKeys.vault(context, house.householdId, me)
    }

    /** Reads each account's space from Google (shared with Gmail and Google Photos). */
    suspend fun refreshSpace(context: Context) {
        for (email in GoogleAccounts.list(context)) {
            runCatching {
                val s = GoogleAccounts.fs(context, email).space()
                if (s.totalBytes != Long.MAX_VALUE) GoogleAccounts.setSpace(context, email, s.totalBytes - s.freeBytes, s.totalBytes)
                GoogleAccounts.setFull(context, email, s.freeBytes < FULL_MARGIN)
            }.onFailure { if (it is GoogleSignInNeeded) GoogleAccounts.setNeedsSignIn(context, email, true) }
        }
    }

    /** One backup run to Google; does nothing when another is going or Google isn't chosen. */
    suspend fun run(context: Context, trigger: BackupTrigger, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        if (!running.tryLock()) return
        try {
            val settings = BackupSettingsStore(context).load()
            if (!settings.destination.usesGoogle) return
            val accounts = GoogleAccounts.list(context)
            if (accounts.isEmpty()) return
            if (settings.privacy == BackupPrivacy.UNSET) {
                status.value = status.value.copy(message = FailureReason.PRIVACY_NOT_CHOSEN.plain)
                return
            }
            val vault = if (settings.privacy == BackupPrivacy.PRIVATE) {
                privateVault(context) ?: run {
                    status.value = status.value.copy(message = FailureReason.NO_PRIVATE_KEY.plain)
                    return
                }
            } else {
                null
            }
            val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == true
            val all = MediaStoreLibrary(context).loadAll()
            val items = BackupSelection.eligible(all, settings, onMobileData = metered)
            val done = keys(context).toMutableSet()
            var todo = items.filter { BackupSelection.key(it) !in done }
            status.value = GoogleStatus(running = true, total = todo.size)
            var message: String? = null
            var uploadedTotal = 0
            for (email in accounts) {
                if (todo.isEmpty()) break
                if (GoogleAccounts.needsSignIn(context, email)) continue
                val fs = GoogleAccounts.fs(context, email)
                val engine = BackupEngine(
                    drive = fs,
                    userId = USER,
                    deviceId = com.deepak.photovault.AndroidDrivePlatform(context).deviceId,
                    reader = AndroidMediaReader(context),
                    index = index(context, email),
                    now = System::currentTimeMillis,
                    zone = TimeZone.currentSystemDefault(),
                    classify = { e ->
                        when (e) {
                            is GoogleStorageFull -> com.deepak.photovault.core.backup.BackupFailure(FailureReason.DRIVE_FULL)
                            is GoogleSignInNeeded -> com.deepak.photovault.core.backup.BackupFailure(FailureReason.OTHER, "Google asked to sign in again")
                            else -> BackupRunner.classify(e)
                        }
                    },
                    vault = vault,
                )
                val result = try {
                    engine.run(todo, settings, Long.MAX_VALUE, trigger) { p ->
                        status.value = status.value.copy(running = true, done = uploadedTotal + p.uploaded, account = email)
                        onProgress(uploadedTotal + p.uploaded, status.value.total)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GoogleSignInNeeded) {
                    GoogleAccounts.setNeedsSignIn(context, email, true)
                    continue
                }
                uploadedTotal += result.uploaded
                done += index(context, email).keys()
                todo = todo.filter { BackupSelection.key(it) !in done }
                when (result.failure) {
                    FailureReason.DRIVE_FULL -> GoogleAccounts.setFull(context, email, true)
                    null -> GoogleAccounts.setFull(context, email, false)
                    else -> message = result.failureDetail ?: result.failure?.plain
                }
            }
            refreshSpace(context)
            val allFull = todo.isNotEmpty() && accounts.all { GoogleAccounts.isFull(context, it) || GoogleAccounts.needsSignIn(context, it) } &&
                accounts.any { GoogleAccounts.isFull(context, it) }
            if (allFull) notifyFull(context)
            if (accounts.any { GoogleAccounts.needsSignIn(context, it) }) message = message ?: "A Google account needs you to sign in again."
            status.value = GoogleStatus(
                running = false,
                done = uploadedTotal,
                total = uploadedTotal + todo.size,
                lastAtMillis = System.currentTimeMillis(),
                message = message,
                allFull = allFull,
                left = todo.size,
            )
        } finally {
            if (status.value.running) status.value = status.value.copy(running = false)
            running.unlock()
        }
    }

    /** Space kept free in each account so Gmail and Google Photos still work: 200 MB. */
    private const val FULL_MARGIN = 200L * 1024 * 1024

    // ---- Scheduling ----

    private const val PERIODIC = "google_backup"
    private const val NOW = "google_backup_now"

    private fun constraints(context: Context): Constraints {
        val s = BackupSettingsStore(context).load()
        return Constraints.Builder()
            .setRequiredNetworkType(if (s.googleWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(s.googleChargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()
    }

    /** Every 12 hours while Google is chosen; stopped otherwise. Called whenever the settings change. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val s = BackupSettingsStore(context).load()
        if (!s.destination.usesGoogle || GoogleAccounts.list(context).isEmpty()) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<GoogleBackupWorker>(12, TimeUnit.HOURS).setConstraints(constraints(context)).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun backUpNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<GoogleBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    // ---- Notifications ----

    private const val CHANNEL = "google_backup"
    private const val ID_PROGRESS = 81
    private const val ID_FULL = 82

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Google Drive backup", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        11,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_BACKUP, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun foreground(context: Context, done: Int, total: Int): ForegroundInfo {
        channel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.deepak.photovault.NotificationIcon.of(context))
            .setContentTitle("Backing up to Google Drive")
            .setContentText(if (total > 0) "$done of $total" else null)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(ID_PROGRESS, n)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyFull(context: Context) {
        if (!allowed(context) || !com.deepak.photovault.NotifyPrefs.on(context, com.deepak.photovault.NotifyPrefs.BACKUP_PROBLEMS)) return
        channel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.deepak.photovault.NotificationIcon.of(context))
            .setContentTitle("Google Drive is full")
            .setContentText("Your Google accounts are full, so new photos aren't backed up there. Add another account or make space.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Your Google accounts are full, so new photos aren't backed up there. Add another Google account in Settings > Backup, or make space in Google."))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_FULL, n)
    }
}

class GoogleBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = GoogleBackup.foreground(applicationContext, 0, 0)

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        GoogleBackup.run(applicationContext, BackupTrigger.SCHEDULED) { done, total ->
            runCatching { setForegroundAsync(GoogleBackup.foreground(applicationContext, done, total)) }
        }
        return Result.success()
    }
}
