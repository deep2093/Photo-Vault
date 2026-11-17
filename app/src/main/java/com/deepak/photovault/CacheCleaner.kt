package com.deepak.photovault

import android.content.Context
import java.io.File

/**
 * Plain (unscrambled) copies the app makes for a moment (A8, 17 Nov): a private drive photo being viewed, a Private
 * backup being made normal, an edit or trim being saved, a file being shared, a smaller copy made for backup, a
 * Secured folder video playing. Each job deletes its own copy when it ends; this catches the ones left behind when
 * the app was closed or the phone switched off part-way.
 *
 * It runs once each time the app starts, on a background thread, and only deletes files from before that start, so
 * nothing the app is using right now is touched. Files handed to another app (sharing) are kept for a quarter of an
 * hour, so a share that is still being read is not cut off.
 */
object CacheCleaner {
    /** Shared copies are kept this long, so the app they were shared to can finish reading them. */
    const val SHARE_KEEP_MS = 15 * 60 * 1000L

    /** Living photo layers and videos (copies of the person's own photos) are kept up to this size. */
    private const val LIVING_LIMIT_BYTES = 400L * 1024 * 1024

    fun sweep(context: Context, startedAt: Long) {
        val cache = context.applicationContext.cacheDir ?: return
        val before = startedAt - 1_000
        // Whole folders of short-lived plain copies: anything from before this start is a leftover.
        for (name in listOf("convert", "edits", "storage-saver", "secured_play")) {
            deleteOlder(File(cache, name), before)
        }
        // Shared copies: also wait a quarter of an hour.
        val shareBefore = minOf(before, System.currentTimeMillis() - SHARE_KEEP_MS)
        for (name in listOf("drive-shared", "secured_share")) deleteOlder(File(cache, name), shareBefore)
        // Opened drive photos stay cached to open quickly (up to 800 MB), except private ones, which were decrypted to
        // be shown: those go at every start, with any half-downloaded file.
        File(cache, "drive-originals").listFiles()?.forEach { f ->
            if ((f.name.endsWith(".pxo") || f.name.endsWith(".part")) && f.lastModified() < before) f.delete()
        }
        // A WireGuard file from an invite, read and deleted at once unless the app was closed in between.
        cache.listFiles { f -> f.isFile && f.name.startsWith("invite-wg") }?.forEach { if (it.lastModified() < before) it.delete() }
        // Living photos: half-made files, and the oldest once the folder is over its limit.
        val living = File(cache, "living")
        living.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { if (it.lastModified() < before) it.delete() }
        trim(living, LIVING_LIMIT_BYTES)
        // A Secured folder restore from the drive that was cut off (its files are still encrypted, but no use).
        deleteOlder(File(context.applicationContext.filesDir, "secured_dl"), before)
    }

    /** Deletes everything inside [dir] (not [dir] itself) last changed before [before]. */
    fun deleteOlder(dir: File, before: Long) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleteOlder(child, before)
                if (child.listFiles().isNullOrEmpty()) child.delete()
            } else if (child.lastModified() < before) {
                child.delete()
            }
        }
    }

    /** Oldest files first until [dir] is no bigger than [limit]. */
    private fun trim(dir: File, limit: Long) {
        val files = dir.listFiles { f -> f.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limit) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }
}
