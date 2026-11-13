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
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.workDataOf
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    suspend fun records(context: Context): List<BackupRecord> =
        GoogleAccounts.list(context).flatMap { email -> accountRecords(context, email).orEmpty() }

    /**
     * The last list read from each account (14 Nov). A read that fails (no internet for a moment, Google busy) gives
     * this one instead of an empty list, so photos never vanish from the timeline or Storage and then come back.
     */
    private val lastGood = java.util.concurrent.ConcurrentHashMap<String, List<BackupRecord>>()

    /** One account's records, with timeline paths ("gdrive/<account>/users/me/..."); null when never read. */
    suspend fun accountRecords(context: Context, email: String): List<BackupRecord>? {
        val fs = GoogleAccounts.fs(context, email)
        val vaults = vaults(context)
        return try {
            val first = BackupCatalog(fs, USER, vaults.firstOrNull())
            var list = first.records()
            // Saved with the other key context (before or after joining a family): read those lists too.
            if (first.listsUnreadable > 0 && vaults.size > 1) {
                val have = list.map { it.drivePath }.toHashSet()
                list = list + BackupCatalog(fs, USER, vaults[1]).records().filter { it.drivePath !in have }
            }
            list.map { it.copy(drivePath = PATH_PREFIX + GoogleAccounts.slug(email) + "/" + it.drivePath) }
                .also { lastGood[email] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            lastGood[email]
        }
    }

    /** Every account's space and what Pixallery keeps there, for Settings > Storage (14 Nov). */
    suspend fun storage(context: Context): List<com.deepak.photovault.ui.shell.GoogleUsage> {
        try {
            refreshSpace(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return GoogleAccounts.list(context).map { email ->
            val (used, limit) = GoogleAccounts.space(context, email)
            val records = accountRecords(context, email)
            com.deepak.photovault.ui.shell.GoogleUsage.of(email, limit, used, records.orEmpty()).copy(listRead = records != null)
        }
    }

    /**
     * Deletes backups from Google Drive (Settings > Storage, 14 Nov, after a warning). Like the family drive's: the
     * deletion is written in the backup list first, then the files go. Ones still on this phone back up again next time.
     */
    suspend fun deleteBackups(context: Context, paths: Set<String>): String? {
        val byAccount = paths.mapNotNull { split(context, it) }.groupBy({ it.first }, { it.second })
        if (byAccount.isEmpty()) return "Those aren't on Google Drive."
        val deviceId = com.deepak.photovault.AndroidDrivePlatform(context).deviceId
        return try {
            for ((email, inner) in byAccount) {
                val wanted = inner.toSet()
                val prefix = PATH_PREFIX + GoogleAccounts.slug(email) + "/"
                val known = (lastGood[email] ?: accountRecords(context, email)).orEmpty()
                val records = known.filter { it.drivePath.removePrefix(prefix) in wanted }.map { it.copy(drivePath = it.drivePath.removePrefix(prefix)) }
                if (records.isEmpty()) continue
                BackupCatalog(GoogleAccounts.fs(context, email), USER, privateVault(context)).remove(records, deviceId, System.currentTimeMillis())
                index(context, email).remove(records.map { it.key }.toSet())
                val gone = records.map { prefix + it.drivePath }.toSet()
                lastGood[email]?.let { list -> lastGood[email] = list.filterNot { it.drivePath in gone } }
            }
            runCatching { refreshSpace(context) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoogleSignInNeeded) {
            "Google asked you to sign in again (Backup > Google Drive > Accounts)."
        } catch (e: Exception) {
            "Couldn't delete from Google Drive. Check the internet and try again."
        }
    }

    /**
     * Saves the chosen Google backups (timeline paths) to this phone's "Restored" album (14 Nov). Each download is
     * checked before it is kept; ones already on the phone are skipped.
     */
    suspend fun restore(context: Context, paths: Set<String>, onProgress: (Int, Int) -> Unit): String {
        val byAccount = paths.mapNotNull { split(context, it) }.groupBy({ it.first }, { it.second })
        val phone = MediaStoreLibrary(context).loadAll()
        val writer = com.deepak.photovault.backup.AndroidMediaWriter(context)
        var restored = 0
        var total = 0
        var problem: String? = null
        for ((email, inner) in byAccount) {
            val wanted = inner.toSet()
            val engine = com.deepak.photovault.core.backup.RestoreEngine(
                GoogleAccounts.fs(context, email),
                USER,
                { e ->
                    when (e) {
                        is GoogleSignInNeeded -> com.deepak.photovault.core.backup.BackupFailure(FailureReason.OTHER, "Google asked to sign in again")
                        else -> BackupRunner.classify(e)
                    }
                },
                privateVault(context),
            )
            val missing = try {
                engine.missing(phone).filter { it.drivePath in wanted }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "Couldn't read Google Drive. Check the internet and try again."
                continue
            }
            val before = restored
            val start = total
            total += missing.size
            if (missing.isEmpty()) continue
            val result = engine.restore(missing, writer) { done, _ -> onProgress(start + done, total) }
            restored = before + result.restored
            result.failure?.let { problem = it.message }
        }
        return when {
            total == 0 && problem == null -> "Those are already on this phone."
            else -> buildString {
                append("Restored $restored of $total to the \"${com.deepak.photovault.backup.AndroidMediaWriter.RESTORED_FOLDER}\" album.")
                problem?.let { append(" ").append(it) }
            }
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
                true
            } else {
                // Which key context it was saved with isn't written down: try this phone's in turn, as long as
                // nothing has been handed on yet.
                for (vault in vaults(context)) {
                    var wrote = false
                    val ok = runCatching {
                        vault.decrypt(kind, com.deepak.photovault.core.backup.PrivatePaths.idOf(inner), { cipher -> fs.readStream(inner, cipher) }) { b, n ->
                            wrote = true
                            sink(b, n)
                        }
                    }.onFailure { if (it is CancellationException) throw it }.isSuccess
                    if (ok) return true
                    if (wrote) return false
                }
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * This person's private key for Google (one key per person): with the family's context when this phone is in a
     * family (as before), otherwise Google's own (14 Nov: Private backup with Google only, key kept by a PIN).
     */
    private fun privateVault(context: Context): com.deepak.photovault.core.backup.PrivateVault? = vaults(context).firstOrNull()

    /** The contexts to try, the one used for new backups first. */
    private fun vaults(context: Context): List<com.deepak.photovault.core.backup.PrivateVault> {
        val google = com.deepak.photovault.core.crypto.PinEnvelope.GOOGLE
        val platform = com.deepak.photovault.AndroidDrivePlatform(context)
        val house = platform.rememberedHousehold()
        val me = platform.rememberedUserId()
        val family = if (house != null && me != null) com.deepak.photovault.PrivateKeys.vault(context, house.householdId, me) else null
        val own = com.deepak.photovault.PrivateKeys.vault(context, google.householdId, google.memberId)
        return listOfNotNull(family, own)
    }

    // ---- The PIN-locked spare key (14 Nov) ----

    private const val PIN_KEY = "users/$USER/keys/pin-key-v1.bin"

    suspend fun readPinKey(context: Context): ByteArray? {
        for (email in GoogleAccounts.list(context)) {
            val fs = GoogleAccounts.fs(context, email)
            val bytes = runCatching { if (fs.exists(PIN_KEY)) fs.read(PIN_KEY) else null }.getOrNull()
            if (bytes != null) return bytes
        }
        return null
    }

    /** In every account, so any one of them can bring the key back. */
    suspend fun savePinKey(context: Context, envelope: ByteArray): String? {
        val accounts = GoogleAccounts.list(context)
        if (accounts.isEmpty()) return "Add a Google account first."
        var saved = 0
        for (email in accounts) {
            val fs = GoogleAccounts.fs(context, email)
            val ok = runCatching {
                fs.mkdirs(com.deepak.photovault.core.drive.DrivePaths.parent(PIN_KEY))
                if (fs.exists(PIN_KEY)) fs.delete(PIN_KEY)
                fs.createNew(PIN_KEY, envelope)
            }.getOrDefault(false)
            if (ok) saved++
        }
        return if (saved > 0) null else "Couldn't save your key to Google. Check the internet and try again."
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
            // Google's own folders, photos and videos, quality and mobile-data choices (14 Nov).
            val settings = BackupSettingsStore(context).load().forGoogle()
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
            val at = System.currentTimeMillis()
            prefs(context).edit().putLong(KEY_LAST, at).apply()
            status.value = GoogleStatus(
                running = false,
                done = uploadedTotal,
                total = uploadedTotal + todo.size,
                lastAtMillis = at,
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

    private const val OLD_PERIODIC = "google_backup"
    private const val NOW = "google_backup_now"
    private const val TAG_SCHEDULED = "google-backup-scheduled"
    private const val KEY_LAST = "last_at"
    const val KEY_MANUAL = "manual"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduling = Mutex()

    private fun prefs(context: Context) = context.getSharedPreferences("google_backup", Context.MODE_PRIVATE)

    /** Wi-Fi unless mobile data is allowed for Google; a charger for scheduled backups when chosen. */
    private fun constraints(g: com.deepak.photovault.core.backup.BackupSettings, scheduled: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (g.mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresCharging(scheduled && g.chargingOnly)
        .build()

    /**
     * After a change to Google's schedule, accounts or where to back up: the waiting backup is replaced by one at
     * Google's own next day and time (14 Nov; before, it simply ran every 12 hours).
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { scheduleNext(app, exceptId = null) } }
    }

    /** At app start, and when other settings change: makes sure a Google backup is waiting, without moving one that is. */
    fun ensure(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                if (status.value.lastAtMillis == null) {
                    prefs(app).getLong(KEY_LAST, 0L).takeIf { it > 0 }?.let { status.value = status.value.copy(lastAtMillis = it) }
                }
                val wm = WorkManager.getInstance(app)
                wm.cancelUniqueWork(OLD_PERIODIC)
                val waiting = wm.getWorkInfosByTagFlow(TAG_SCHEDULED).first()
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
                if (!waiting || !wanted(app)) scheduleNext(app, exceptId = null)
            }
        }
    }

    private fun wanted(context: Context): Boolean {
        val s = BackupSettingsStore(context).load()
        return s.destination.usesGoogle && s.forGoogle().schedule.enabled && GoogleAccounts.list(context).isNotEmpty()
    }

    internal suspend fun scheduleNext(context: Context, exceptId: java.util.UUID?) {
        scheduling.withLock { scheduleNextLocked(context, exceptId) }
    }

    private suspend fun scheduleNextLocked(context: Context, exceptId: java.util.UUID?) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(OLD_PERIODIC)
        // Cancel waiting ones only, never a backup that is running right now.
        wm.getWorkInfosByTagFlow(TAG_SCHEDULED).first()
            .filter { it.id != exceptId && it.state == WorkInfo.State.ENQUEUED }
            .forEach { wm.cancelWorkById(it.id) }
        if (!wanted(context)) return
        val g = BackupSettingsStore(context).load().forGoogle()
        val now = System.currentTimeMillis()
        val at = g.schedule.nextRun(now, TimeZone.currentSystemDefault())
        val request = OneTimeWorkRequestBuilder<GoogleBackupWorker>()
            .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(g, scheduled = true))
            .addTag(TAG_SCHEDULED)
            .build()
        wm.enqueue(request)
    }

    /** "Back up now": no charger needed; waits for Wi-Fi unless mobile data is allowed for Google. */
    fun backUpNow(context: Context) {
        val g = BackupSettingsStore(context).load().forGoogle()
        val request = OneTimeWorkRequestBuilder<GoogleBackupWorker>()
            .setConstraints(constraints(g, scheduled = false))
            .setInputData(workDataOf(KEY_MANUAL to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
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

    override suspend fun doWork(): Result = kotlinx.coroutines.coroutineScope {
        runCatching { setForeground(getForegroundInfo()) }
        // The notification follows the progress (done of total), updated from here, not from inside the backup.
        val progress = MutableStateFlow(0 to 0)
        val updater = launch {
            progress.collect { (done, total) -> runCatching { setForeground(GoogleBackup.foreground(applicationContext, done, total)) } }
        }
        val manual = inputData.getBoolean(GoogleBackup.KEY_MANUAL, false)
        GoogleBackup.run(applicationContext, if (manual) BackupTrigger.MANUAL else BackupTrigger.SCHEDULED) { done, total -> progress.value = done to total }
        updater.cancel()
        // The next scheduled time, counted from now.
        if (!manual) runCatching { GoogleBackup.scheduleNext(applicationContext, exceptId = id) }
        Result.success()
    }
}
