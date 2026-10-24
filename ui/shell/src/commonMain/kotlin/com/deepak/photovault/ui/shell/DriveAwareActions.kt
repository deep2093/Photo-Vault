package com.deepak.photovault.ui.shell

import com.deepak.photovault.core.backup.DriveTimeline
import com.deepak.photovault.core.backup.isDriveOnly
import com.deepak.photovault.core.media.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sends photos that live only on the family drive to the drive's own Recently deleted, and everything else to the
 * phone's (Phase 5A, 25 Oct). The screens keep calling the same actions.
 */
class DriveAwareActions(
    private val phone: MediaActions,
    private val drive: DriveState,
    private val gallery: GalleryState,
    private val scope: CoroutineScope,
) : MediaActions by phone {
    private fun List<MediaItem>.paths() = mapNotNull { DriveTimeline.pathOf(it.uri) }.toSet()

    /** After a change on the drive: re-read the lists so every screen agrees. */
    private suspend fun reread() {
        drive.loadBackupRecords(force = true)
        drive.loadDriveTrash()
        drive.backupRecords?.let(gallery::setDriveRecords)
        drive.driveTrash?.let(gallery::setDriveTrash)
    }

    override fun share(items: List<MediaItem>) {
        val (onDrive, onPhone) = items.partition { it.isDriveOnly }
        if (onPhone.isNotEmpty()) phone.share(onPhone)
        if (onDrive.isNotEmpty()) phone.shareDrive(onDrive)
    }

    override fun moveToRecentlyDeleted(items: List<MediaItem>, onDone: (Boolean) -> Unit) {
        val (onDrive, onPhone) = items.partition { it.isDriveOnly }
        if (onDrive.isEmpty()) return phone.moveToRecentlyDeleted(items, onDone)
        scope.launch {
            val failed = drive.trashBackups(onDrive.paths()) != null
            reread()
            if (onPhone.isEmpty()) onDone(!failed) else phone.moveToRecentlyDeleted(onPhone, onDone)
        }
    }

    // In Recently deleted a drive item's uri points at its place in the drive's trash.
    override fun restore(items: List<MediaItem>) {
        val (onDrive, onPhone) = items.partition { it.isDriveOnly }
        if (onPhone.isNotEmpty()) phone.restore(onPhone)
        if (onDrive.isNotEmpty()) scope.launch {
            drive.restoreBackups(onDrive.paths())
            reread()
        }
    }

    override fun deleteForever(items: List<MediaItem>, onDone: (Boolean) -> Unit) {
        val (onDrive, onPhone) = items.partition { it.isDriveOnly }
        if (onDrive.isEmpty()) return phone.deleteForever(items, onDone)
        scope.launch {
            val failed = drive.deleteTrashedForever(onDrive.paths()) != null
            reread()
            if (onPhone.isEmpty()) onDone(!failed) else phone.deleteForever(onPhone, onDone)
        }
    }
}
