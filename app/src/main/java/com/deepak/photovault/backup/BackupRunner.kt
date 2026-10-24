package com.deepak.photovault.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.deepak.photovault.AndroidDrivePlatform
import com.deepak.photovault.core.backup.BackupCoordinator
import com.deepak.photovault.core.backup.BackupEngine
import com.deepak.photovault.core.backup.BackupFailure
import com.deepak.photovault.core.backup.BackupProgress
import com.deepak.photovault.core.backup.BackupRun
import com.deepak.photovault.core.backup.BackupSelection
import com.deepak.photovault.core.backup.BackupTrigger
import com.deepak.photovault.core.backup.FailureReason
import com.deepak.photovault.core.backup.RestoreEngine
import com.deepak.photovault.core.drive.DriveOffline
import com.deepak.photovault.core.household.HouseholdStore
import com.deepak.photovault.core.household.Membership
import com.deepak.photovault.core.media.MediaStoreLibrary
import com.deepak.photovault.core.smb.SmbDriveFileSystem
import com.deepak.photovault.core.vpn.SmbReachability
import com.deepak.photovault.core.vpn.VpnState
import com.deepak.photovault.core.vpn.WireGuardManager
import com.deepak.photovault.core.smb.SmbErrors
import com.deepak.photovault.ui.shell.BackupState
import com.deepak.photovault.ui.shell.DriveState
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.TimeZone
import java.io.IOException

/**
 * Runs one backup or restore at a time for this phone, from a background worker. Writes progress and results
 * into [state] so the Backup screen updates live, and keeps the history.
 */
class BackupRunner(
    context: Context,
    private val state: BackupState,
    private val settingsStore: BackupSettingsStore,
    private val history: BackupHistoryStore,
    private val index: FileBackupIndex,
) {
    private val context = context.applicationContext
    private val platform = AndroidDrivePlatform(this.context)
    private val lock = Mutex()

    @Volatile private var current: Job? = null

    @Volatile private var stopRequested = false

    /** Null when another backup or restore is already running. */
    suspend fun backUp(trigger: BackupTrigger, onProgress: (BackupProgress) -> Unit = {}): BackupRun? {
        if (!lock.tryLock()) return null
        stopRequested = false
        state.running = true
        state.progress = null
        val started = System.currentTimeMillis()
        try {
            val run = try {
                coroutineScope {
                    val job = async { backUpAwayAware(trigger, onProgress) }
                    current = job
                    job.await()
                }
            } catch (e: CancellationException) {
                if (!stopRequested) throw e
                stopped(trigger, started)
            }
            history.add(run, BackupState.HISTORY_SIZE)
            state.finished(run)
            return run
        } finally {
            current = null
            state.running = false
            state.progress = null
            lock.unlock()
        }
    }

    /** Stops the running backup or restore. What was already verified stays backed up. */
    fun stop() {
        stopRequested = true
        current?.cancel()
    }

    /**
     * Away from home (Phase 5B-1): when the person allowed it and the drive can't be reached directly, turn the VPN
     * on for this backup only, then off right after. Needs the one-time Android VPN permission first.
     */
    private suspend fun backUpAwayAware(trigger: BackupTrigger, onProgress: (BackupProgress) -> Unit): BackupRun {
        val settings = settingsStore.load()
        val connection = platform.loadConnection()
        val vpn = WireGuardManager.get(context)
        if (!settings.awayBackup || connection == null || !vpn.store.hasConfig()) return backUpNow(trigger, onProgress)
        val host = SmbReachability.normalizeHost(connection.host)
        val reach = SmbReachability(context)
        if (reach.isDirectlyReachable(host) || reach.isReachableViaDefaultRoute(host)) return backUpNow(trigger, onProgress)
        val started = System.currentTimeMillis()
        fun failed(reason: FailureReason) = BackupRun(trigger, started, System.currentTimeMillis(), 0, 0, 0, 0, 0, reason, null)
        if (vpn.consentIntent() != null) return failed(FailureReason.VPN_PERMISSION)
        val hold = vpn.beginHold()
        try {
            if (vpn.connect(userInitiated = false) !is VpnState.Connected) return failed(FailureReason.VPN_FAILED)
            return backUpNow(trigger, onProgress)
        } finally {
            withContext(NonCancellable) {
                hold.release()
                vpn.disconnect(force = false, userInitiated = false)
            }
        }
    }

    private suspend fun backUpNow(trigger: BackupTrigger, onProgress: (BackupProgress) -> Unit): BackupRun {
        val started = System.currentTimeMillis()
        fun failed(reason: FailureReason, detail: String? = null) =
            BackupRun(trigger, started, System.currentTimeMillis(), 0, 0, 0, 0, 0, reason, detail)

        if (!hasPhotoAccess()) return failed(FailureReason.NO_PHOTO_ACCESS)
        // Each person chooses Normal or Private before anything is uploaded; Private uploads wait for step 4B.
        when (settingsStore.load().privacy) {
            com.deepak.photovault.core.backup.BackupPrivacy.UNSET -> return failed(FailureReason.PRIVACY_NOT_CHOSEN)
            com.deepak.photovault.core.backup.BackupPrivacy.PRIVATE -> return failed(FailureReason.PRIVATE_NOT_READY)
            com.deepak.photovault.core.backup.BackupPrivacy.NORMAL -> Unit
        }
        val connection = platform.loadConnection() ?: return failed(FailureReason.NO_DRIVE)
        if (connection.username.isNotBlank() && connection.password.isEmpty()) return failed(FailureReason.NO_PASSWORD)
        val drive = try {
            platform.openDetached(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return classify(e).let { failed(it.reason, it.detail) }
        }
        drive.use { fs ->
            val membership = try {
                HouseholdStore(fs) { System.currentTimeMillis() }.membership(platform.deviceId, platform.rememberedUserId())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return classify(e).let { failed(it.reason, it.detail) }
            }
            val joined = membership as? Membership.Joined ?: return failed(FailureReason.NOT_JOINED)
            val settings = settingsStore.load()
            val all = MediaStoreLibrary(context).loadAll()
            val items = BackupSelection.eligible(all, settings, onMobileData = onMobileData())
            val engine = BackupEngine(
                drive = fs,
                userId = joined.me.userId,
                deviceId = platform.deviceId,
                reader = AndroidMediaReader(context),
                index = index,
                now = System::currentTimeMillis,
                zone = TimeZone.currentSystemDefault(),
                classify = { classify(it) },
            )
            // One phone in the family at a time (24 Oct): wait for another phone's backup to finish, up to a while.
            val everyone = joined.household.members.map { it.userId }
            val turn = BackupCoordinator(fs, joined.me.userId, platform.deviceId, platform.deviceName, System::currentTimeMillis)
            val giveUpAt = System.currentTimeMillis() + MAX_WAIT_MS
            while (!runCatching { turn.tryStart(everyone) }.getOrDefault(true)) {
                state.waitingFor = runCatching { turn.othersRunning(everyone).firstOrNull()?.deviceName }.getOrNull() ?: "another phone"
                if (System.currentTimeMillis() > giveUpAt) {
                    state.waitingFor = null
                    return failed(FailureReason.BUSY)
                }
                delay(WAIT_STEP_MS)
            }
            state.waitingFor = null
            return try {
                coroutineScope {
                    val beat = launch {
                        while (true) {
                            delay(HEARTBEAT_MS)
                            turn.heartbeat()
                        }
                    }
                    try {
                        engine.run(items, settings, joined.me.limitGb * DriveState.GB, trigger) { p ->
                            state.progress = p
                            onProgress(p)
                        }
                    } finally {
                        beat.cancel()
                    }
                }
            } finally {
                withContext(NonCancellable) { turn.finish() }
            }
        }
    }

    /** Restores backed-up photos that aren't on this phone. Returns a line for the Backup screen. */
    suspend fun restoreMissing(only: Set<String>? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): String? {
        if (!lock.tryLock()) return null
        stopRequested = false
        state.restoring = true
        try {
            return try {
                coroutineScope {
                    val job = async { restoreNow(only, onProgress) }
                    current = job
                    job.await()
                }
            } catch (e: CancellationException) {
                if (!stopRequested) throw e
                "Restore stopped."
            }.also { state.restoreMessage = it }
        } finally {
            current = null
            state.restoring = false
            state.restoreProgress = null
            lock.unlock()
        }
    }

    private suspend fun restoreNow(only: Set<String>?, onProgress: (Int, Int) -> Unit): String {
        val connection = platform.loadConnection() ?: return FailureReason.NO_DRIVE.plain
        val fs: SmbDriveFileSystem = try {
            platform.openDetached(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return classify(e).message
        }
        fs.use {
            val membership = runCatching { HouseholdStore(fs) { System.currentTimeMillis() }.membership(platform.deviceId, platform.rememberedUserId()) }.getOrNull()
            val joined = membership as? Membership.Joined ?: return FailureReason.NOT_JOINED.plain
            val engine = RestoreEngine(fs, joined.me.userId) { classify(it) }
            val missing = try {
                engine.missing(MediaStoreLibrary(context).loadAll()).filter { only == null || it.drivePath in only }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return classify(e).message
            }
            if (missing.isEmpty()) return if (only != null) "Those are already on this phone." else "Everything on the drive is already on this phone."
            val result = engine.restore(missing, AndroidMediaWriter(context)) { done, total ->
                state.restoreProgress = done to total
                onProgress(done, total)
            }
            return buildString {
                append("Restored ${result.restored} of ${missing.size} to the \"${AndroidMediaWriter.RESTORED_FOLDER}\" album.")
                result.failure?.let { append(" ").append(it.message) }
            }
        }
    }

    private fun stopped(trigger: BackupTrigger, started: Long) =
        BackupRun(trigger, started, System.currentTimeMillis(), 0, 0, 0, 0, 0, FailureReason.STOPPED)

    private fun hasPhotoAccess(): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun onMobileData(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true

    companion object {
        /** How long a backup waits for another family phone's backup to finish before trying later. */
        private const val MAX_WAIT_MS = 20 * 60_000L
        private const val WAIT_STEP_MS = 60_000L
        private const val HEARTBEAT_MS = 60_000L
        /** SMB and network errors in words a person can act on. Never includes the password or server text. */
        fun classify(e: Throwable): BackupFailure {
            var cause: Throwable? = e
            while (cause != null) {
                when (cause) {
                    is SMBApiException -> return when (cause.status) {
                        NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_ACCOUNT_DISABLED -> BackupFailure(FailureReason.WRONG_PASSWORD)
                        NtStatus.STATUS_DISK_FULL -> BackupFailure(FailureReason.DRIVE_FULL)
                        NtStatus.STATUS_BAD_NETWORK_NAME -> BackupFailure(FailureReason.OTHER, "The shared folder wasn't found on the drive. Check Settings > Drive.")
                        else -> BackupFailure(FailureReason.OTHER, SmbErrors.describe(cause))
                    }
                    is DriveOffline, is java.net.UnknownHostException, is java.net.SocketTimeoutException,
                    is java.net.ConnectException, is java.net.NoRouteToHostException,
                    -> return BackupFailure(FailureReason.DRIVE_UNREACHABLE)
                }
                cause = cause.cause
            }
            return if (SmbErrors.isTransient(e) || e is IOException) {
                BackupFailure(FailureReason.DRIVE_UNREACHABLE)
            } else {
                BackupFailure(FailureReason.OTHER, SmbErrors.describe(e))
            }
        }
    }
}
