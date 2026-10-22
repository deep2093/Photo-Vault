package com.deepak.photovault.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.deepak.photovault.AndroidDrivePlatform
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
import com.deepak.photovault.core.smb.SmbErrors
import com.deepak.photovault.ui.shell.BackupState
import com.deepak.photovault.ui.shell.DriveState
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
                    val job = async { backUpNow(trigger, onProgress) }
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

    private suspend fun backUpNow(trigger: BackupTrigger, onProgress: (BackupProgress) -> Unit): BackupRun {
        val started = System.currentTimeMillis()
        fun failed(reason: FailureReason, detail: String? = null) =
            BackupRun(trigger, started, System.currentTimeMillis(), 0, 0, 0, 0, 0, reason, detail)

        if (!hasPhotoAccess()) return failed(FailureReason.NO_PHOTO_ACCESS)
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
            return engine.run(items, settings, joined.me.limitGb * DriveState.GB, trigger) { p ->
                state.progress = p
                onProgress(p)
            }
        }
    }

    /** Restores backed-up photos that aren't on this phone. Returns a line for the Backup screen. */
    suspend fun restoreMissing(onProgress: (Int, Int) -> Unit = { _, _ -> }): String? {
        if (!lock.tryLock()) return null
        stopRequested = false
        state.restoring = true
        try {
            return try {
                coroutineScope {
                    val job = async { restoreNow(onProgress) }
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

    private suspend fun restoreNow(onProgress: (Int, Int) -> Unit): String {
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
                engine.missing(MediaStoreLibrary(context).loadAll())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return classify(e).message
            }
            if (missing.isEmpty()) return "Everything on the drive is already on this phone."
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
