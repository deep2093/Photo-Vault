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
    private val backup: BackupState,
    private val scope: CoroutineScope,
    private val google: GoogleState? = null,
) : MediaActions by phone {
    private fun List<MediaItem>.paths() = mapNotNull { DriveTimeline.pathOf(it.uri) }.toSet()

    /** A photo saved only to Google (13 Nov). */
    private fun MediaItem.onGoogle() = DriveTimeline.pathOf(uri)?.let(::isGooglePath) == true

    /** After a change on the drive: re-read the lists so every screen agrees. */
    private suspend fun reread() {
        drive.loadBackupRecords(force = true)
        drive.loadDriveTrash()
        timelineRecords(drive.backupRecords, google?.records)?.let(gallery::setDriveRecords)
        drive.driveTrash?.let(gallery::setDriveTrash)
        drive.loadFamilyAlbum()
        drive.familyAlbumRecords?.let(gallery::updateFamilyAlbum)
    }

    override fun share(items: List<MediaItem>) {
        val (onDrive, onPhone) = items.partition { it.isDriveOnly }
        if (onPhone.isNotEmpty()) phone.share(onPhone)
        if (onDrive.isNotEmpty()) phone.shareDrive(onDrive)
    }

    override fun saveToPhone(items: List<MediaItem>) {
        // Google's and the family drive's both go through the same restore job (15 Nov).
        val paths = items.filter { it.isDriveOnly }.mapNotNull { DriveTimeline.pathOf(it.uri) }
        if (paths.isEmpty()) return phone.toast("Already on this phone.")
        backup.restoreSelected(paths)
        phone.toast("Saving ${paths.size} to this phone, back to their folders...")
    }

    override fun moveToRecentlyDeleted(items: List<MediaItem>, onDone: (Boolean) -> Unit) {
        // Only on Google (15 Nov): Google has no Recently deleted here, so a popup asks first and they go for good.
        val (onGoogle, rest) = items.partition { it.onGoogle() }
        if (onGoogle.isNotEmpty()) {
            val g = google ?: return onDone(false)
            // 18 Nov: a device the owner made view only can't delete what this person's other devices backed up.
            val paths = onGoogle.paths()
            if (!drive.mayDelete(g.records.orEmpty().filter { it.drivePath in paths })) {
                phone.toast(VIEW_ONLY_NOTE)
                return onDone(false)
            }
            g.askDelete(onGoogle.paths()) { ok ->
                if (rest.isEmpty()) onDone(ok) else moveToRecentlyDeleted(rest, onDone)
            }
            return
        }
        val (onDrive, onPhone) = items.partition { it.isDriveOnly }
        if (onDrive.isEmpty()) return phone.moveToRecentlyDeleted(items, onDone)
        // 18 Nov (Deepak: "deleting a drive photo takes time"): it leaves the app at once and the drive catches up in the
        // background, now or, if the drive can't be reached, the next time it can.
        drive.trashLater(onDrive.paths())?.let { why ->
            phone.toast(why)
            return onDone(false)
        }
        gallery.dropNow(onDrive.map { it.id }.toSet())
        scope.launch { finishDriveDeletes() }
        if (onPhone.isEmpty()) onDone(true) else phone.moveToRecentlyDeleted(onPhone, onDone)
    }

    /**
     * Moves drive photos deleted in the app to the drive's Recently deleted, then reads the lists again (18 Nov). True
     * when nothing is left waiting.
     */
    suspend fun finishDriveDeletes(): Boolean {
        if (drive.pendingTrash.isEmpty()) return true
        if (drive.finishPendingTrash()) reread()
        return drive.pendingTrash.isEmpty()
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
            val error = drive.deleteTrashedForever(onDrive.paths())
            if (error != null) phone.toast(error)
            val failed = error != null
            reread()
            if (onPhone.isEmpty()) onDone(!failed) else phone.deleteForever(onPhone, onDone)
        }
    }

    override fun shareToFamilyAlbum(items: List<MediaItem>) {
        if (items.any { it.onGoogle() }) return phone.toast("Photos only on Google Drive can't go to the Family Album.")
        scope.launch {
            val r = drive.addToFamilyAlbum(items)
            drive.familyAlbumRecords?.let(gallery::updateFamilyAlbum)
            phone.toast(
                when {
                    r.error != null -> r.error
                    r.added == 0 -> "Not shared: back these up to the drive first, then share them."
                    r.notBackedUp > 0 -> "Added ${r.added} to the Family Album. ${r.notBackedUp} not backed up yet, so they wait."
                    else -> "Added ${r.added} to the Family Album."
                },
            )
        }
    }

    override fun removeFromFamilyAlbum(items: List<MediaItem>) {
        scope.launch {
            val error = drive.removeFromFamilyAlbum(items)
            drive.familyAlbumRecords?.let(gallery::updateFamilyAlbum)
            phone.toast(error ?: "Removed from the Family Album. Only photos you added can be removed.")
        }
    }
}
