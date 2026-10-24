package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepak.photovault.core.backup.BackupProgress
import com.deepak.photovault.core.backup.BackupRun
import com.deepak.photovault.core.backup.BackupSettings
import kotlinx.datetime.TimeZone

/** What only the phone can do for backup: saving choices, running and scheduling the work, notifications. */
interface BackupPlatform {
    fun loadSettings(): BackupSettings

    /** Saves and reschedules. */
    fun saveSettings(settings: BackupSettings)

    fun loadHistory(): List<BackupRun>

    /** Keys of everything this phone knows is backed up (see BackupSelection.key). */
    suspend fun backedUpKeys(): Set<String>

    fun backUpNow()

    /** Stops a running backup; what was verified stays backed up. */
    fun stop()

    /** Downloads backed-up photos that aren't on this phone. */
    fun restoreMissing()

    /** Downloads the chosen backups (drive paths) that aren't on this phone (Settings > Storage, 23 Oct). */
    fun restoreSelected(drivePaths: List<String>) = Unit

    /** Forgets photos whose backup was deleted from the drive, so copies still on the phone back up again. */
    suspend fun forgetBackedUp(keys: Set<String>) = Unit

    /** On mobile data right now (backup then waits for Wi-Fi unless mobile data is allowed). */
    fun onMobileData(): Boolean

    fun notificationsAllowed(): Boolean

    /** Android may delay scheduled work for apps it puts to sleep. */
    fun batteryRestricted(): Boolean

    fun now(): Long
}

/** For previews and tests. */
class MemoryBackupPlatform(private var settings: BackupSettings = BackupSettings()) : BackupPlatform {
    var nowMillis = 0L
    val started = mutableListOf<String>()
    override fun loadSettings() = settings
    override fun saveSettings(settings: BackupSettings) {
        this.settings = settings
    }
    override fun loadHistory(): List<BackupRun> = emptyList()
    override suspend fun backedUpKeys(): Set<String> = emptySet()
    override fun backUpNow() {
        started += "now"
    }
    override fun stop() {
        started += "stop"
    }
    override fun restoreMissing() {
        started += "restore"
    }
    override fun onMobileData() = false
    override fun notificationsAllowed() = true
    override fun batteryRestricted() = false
    override fun now() = nowMillis
}

/**
 * Backup as the screens see it. The backup itself runs in the background (Android WorkManager) and writes its
 * progress and results here, so the screen shows them live.
 */
@Stable
class BackupState(private val platform: BackupPlatform) {
    var settings by mutableStateOf(platform.loadSettings())
        private set

    var running by mutableStateOf(false)

    var restoring by mutableStateOf(false)

    var progress by mutableStateOf<BackupProgress?>(null)

    /** Restore progress: done to total. */
    var restoreProgress by mutableStateOf<Pair<Int, Int>?>(null)

    /** A short line about the last restore, shown on the Backup screen. */
    var restoreMessage by mutableStateOf<String?>(null)

    /** Newest first. */
    var history by mutableStateOf(platform.loadHistory())
        private set

    var backedUpKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    fun update(change: (BackupSettings) -> BackupSettings) {
        settings = change(settings)
        platform.saveSettings(settings)
    }

    fun backUpNow() = platform.backUpNow()

    fun stop() = platform.stop()

    fun restoreMissing() = platform.restoreMissing()

    /** Brings the chosen backups back to the phone (Settings > Storage). */
    fun restoreSelected(drivePaths: List<String>) = platform.restoreSelected(drivePaths)

    /** After backups were deleted from the drive: copies still on the phone back up again next time. */
    suspend fun forgetBackedUp(keys: Set<String>) {
        platform.forgetBackedUp(keys)
        backedUpKeys = backedUpKeys - keys
    }

    fun onMobileData() = platform.onMobileData()

    fun notificationsAllowed() = platform.notificationsAllowed()

    fun batteryRestricted() = platform.batteryRestricted()

    suspend fun refreshKeys() {
        backedUpKeys = platform.backedUpKeys()
    }

    /** Called by the background work when a backup ends. */
    fun finished(run: BackupRun) {
        history = (listOf(run) + history).take(HISTORY_SIZE)
    }

    fun nextRunMillis(zone: TimeZone = TimeZone.currentSystemDefault()): Long? =
        settings.schedule.takeIf { it.enabled }?.nextRun(platform.now(), zone)

    fun now() = platform.now()

    companion object {
        const val HISTORY_SIZE = 30
    }
}
