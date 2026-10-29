package com.deepak.photovault.core.backup

import com.deepak.photovault.core.media.MediaItem

/**
 * Free up space (29 Oct, Phase 6B-2): after a photo has been safely on the family drive for a few days, its copy on
 * the phone can go to the phone's Recently deleted (30 days to undo). Nothing is chosen here without a check:
 * [BackupEngine.verifyForRemoval] reads each file back from the drive first.
 */
object FreeUpSpace {
    /** The waits on offer, in days. Off (0) is the default and is its own choice. */
    val DAY_CHOICES = listOf(7, 30, 60, 90)

    private const val DAY_MS = 86_400_000L

    /** A phone photo with its record on the drive. */
    class Ready(val item: MediaItem, val record: BackupRecord)

    /** [ready] are old enough to check; [waiting] are backed up but not yet [days] old. */
    class Split(val ready: List<Ready>, val waiting: Int)

    /**
     * Phone photos whose backup is at least [days] old. Covers every album and favourites, not only the backed-up
     * folders. Private (encrypted) backups are left out for now: they can't be read back without the key here.
     */
    fun split(items: List<MediaItem>, records: Map<String, BackupRecord>, days: Int, nowMillis: Long): Split {
        if (days <= 0) return Split(emptyList(), 0)
        val ready = mutableListOf<Ready>()
        var waiting = 0
        for (item in items) {
            val record = records[BackupSelection.key(item)] ?: continue
            if (record.encrypted) continue
            if (nowMillis - record.savedAtMillis >= days * DAY_MS) ready += Ready(item, record) else waiting++
        }
        return Split(ready, waiting)
    }

    /** Sorts what passed the check into full-size copies and Storage saver ones (the drive holds only a smaller picture). */
    fun result(passed: List<Ready>, waiting: Int, failedCheck: Int): FreeUpResult {
        val full = passed.filter { !it.record.storageSaver }.map { it.item }
        return FreeUpResult(
            verified = full,
            smaller = passed.filter { it.record.storageSaver }.map { it.item },
            bytes = full.sumOf { it.sizeBytes },
            waiting = waiting,
            failedCheck = failedCheck,
        )
    }
}

/**
 * What a check found. [verified] came through the check at full size and may go; [smaller] passed too but the drive
 * has only the smaller Storage saver copy, so they wait for a clear OK from the person. Nothing here is ever removed by
 * the check itself.
 */
data class FreeUpResult(
    val verified: List<MediaItem> = emptyList(),
    val smaller: List<MediaItem> = emptyList(),
    /** Bytes the phone gets back from [verified]. */
    val bytes: Long = 0,
    /** Backed up, but not old enough yet. */
    val waiting: Int = 0,
    /** Didn't come through the check (missing, different, or no preview): they stay on the phone. */
    val failedCheck: Int = 0,
    /** A reason nothing was checked, in words a person can act on. */
    val message: String? = null,
) {
    val smallerBytes: Long get() = smaller.sumOf { it.sizeBytes }
    val nothingToDo: Boolean get() = verified.isEmpty() && smaller.isEmpty()
}
