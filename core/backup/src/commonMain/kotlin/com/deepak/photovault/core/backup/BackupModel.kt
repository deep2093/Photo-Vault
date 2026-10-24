package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.ByteSource
import com.deepak.photovault.core.media.MediaItem
import kotlinx.serialization.Serializable

/** One verified file on the drive. Written to the drive's manifests and kept in this phone's index. */
@Serializable
data class BackupRecord(
    /** [BackupSelection.key] of the phone photo it came from. */
    val key: String,
    /** Inside the PhotoVault folder, e.g. users/<id>/albums/Camera/2026/10/IMG_1234.jpg (originals/... before 24 Oct). */
    val drivePath: String,
    val sha256: String,
    /** Size of the file on the drive (smaller than the phone's when Storage saver shrank it). */
    val sizeBytes: Long,
    val originalName: String,
    val takenAtMillis: Long,
    val mimeType: String,
    val isVideo: Boolean,
    val savedAtMillis: Long,
    val deviceId: String,
    val storageSaver: Boolean = false,
    /** The phone album it came from (since 24 Oct; older records leave it empty). For the Storage page. */
    val album: String? = null,
) {
    val fileName: String get() = drivePath.substringAfterLast('/')
}

/** A backup moved to the drive's Recently deleted (30 days), with the record it needs to come back. */
@Serializable
data class TrashedBackup(val record: BackupRecord, val trashPath: String, val deletedAtMillis: Long)

/** A batch of records, saved as its own file on the drive (create-new, never edited). */
@Serializable
data class Manifest(
    val formatVersion: Int = 1,
    val deviceId: String,
    val createdAtMillis: Long,
    val records: List<BackupRecord>,
    /** Drive paths deleted from the drive by their owner (23 Oct, Settings > Storage). Later manifests win. */
    val removed: List<String> = emptyList(),
)

/** A phone file ready to upload: the original, or a smaller copy made by Storage saver. */
class PreparedFile(
    val source: ByteSource,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val compressed: Boolean,
    private val cleanup: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        runCatching { source.close() }
        cleanup()
    }
}

/** Reads photos from the phone. Android: MediaStore (original bytes, location kept). */
interface MediaReader {
    suspend fun open(item: MediaItem, storageSaver: Boolean): PreparedFile
}

/** What this phone already knows is backed up. Android: a file in the app's private storage. */
interface BackupIndex {
    suspend fun keys(): Set<String>
    suspend fun add(records: List<BackupRecord>)

    /** Forgets photos whose backup was deleted from the drive, so a copy still on the phone is backed up again. */
    suspend fun remove(keys: Set<String>) = Unit
}

class MemoryBackupIndex : BackupIndex {
    val records = mutableListOf<BackupRecord>()
    override suspend fun keys(): Set<String> = records.map { it.key }.toSet()
    override suspend fun add(records: List<BackupRecord>) {
        this.records += records
    }

    override suspend fun remove(keys: Set<String>) {
        records.removeAll { it.key in keys }
    }
}

/** Why a backup stopped or didn't finish, in words a person can act on (feedback 24 Oct). */
enum class FailureReason(val plain: String) {
    NO_DRIVE("No drive is connected on this phone. Connect it in Settings > Drive."),
    NO_PASSWORD("The drive password isn't saved on this phone, so automatic backup can't sign in. Turn on Remember password in Settings > Drive."),
    NOT_JOINED("This phone isn't in the family on the drive yet. Open Family to join."),
    NO_PHOTO_ACCESS("Pixallery can't see your photos. Open the app and allow access."),
    DRIVE_UNREACHABLE("Couldn't reach the drive. Check that the router and its USB drive are on and that this phone is on the home Wi-Fi."),
    WRONG_PASSWORD("The drive refused the password. It may have been changed: enter it again in Settings > Drive."),
    SPACE_LIMIT_FULL("Your space on the drive is full. Ask the owner for more space."),
    DRIVE_FULL("The drive itself is full. Make room on it, then back up again."),
    CHECK_FAILED("Some files didn't match after uploading, so they weren't counted as backed up. They'll be tried again next time."),
    STOPPED("Backup was stopped. It carries on from where it stopped next time."),
    BUSY("Another phone in the family is backing up. This one takes its turn in about half an hour."),
    OTHER("Something went wrong while backing up."),
}

data class BackupFailure(val reason: FailureReason, val detail: String? = null) {
    val message: String get() = if (reason == FailureReason.OTHER && detail != null) detail else reason.plain
}

data class BackupProgress(
    val total: Int,
    val done: Int,
    val uploaded: Int,
    val failed: Int,
    val bytes: Long,
    val current: String? = null,
) {
    val fraction: Float get() = if (total == 0) 1f else done / total.toFloat()
}

@Serializable
enum class BackupTrigger(val label: String) { SCHEDULED("Scheduled"), MANUAL("Back up now"), RETRY("Retry") }

/** One finished backup, kept in this phone's history. */
@Serializable
data class BackupRun(
    val trigger: BackupTrigger,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    /** Photos and videos chosen for backup on this phone. */
    val eligible: Int,
    val alreadyBackedUp: Int,
    val uploaded: Int,
    val failedItems: Int,
    val bytes: Long,
    val failure: FailureReason? = null,
    val failureDetail: String? = null,
) {
    val succeeded: Boolean get() = failure == null && failedItems == 0
    val failureMessage: String? get() = failure?.let { BackupFailure(it, failureDetail).message }
}
