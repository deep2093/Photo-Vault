package com.deepak.photovault.duplicates

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepak.photovault.core.backup.isDriveOnly
import com.deepak.photovault.core.media.Duplicates
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaStoreLibrary
import com.deepak.photovault.ui.shell.DuplicatePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val sharedLock = Mutex()

/**
 * The duplicate finder on Android (Phase 11 part 3, 15 Nov). Each photo's fingerprint and sharpness come from the
 * phone's own small thumbnail (quick, no full photo opened); a hash of the whole file is worked out only for files
 * that share a size with another. All of it is kept in a small file in the app, so it is only done once per photo,
 * and is worked out ahead while charging. Nothing leaves the phone and nothing is removed here.
 */
class AndroidDuplicates(context: Context) : DuplicatePlatform {
    private val app = context.applicationContext
    private val file get() = File(app.filesDir, "duplicates/prints-v1.tsv")

    override val supported: Boolean get() = true

    override suspend fun prints(items: List<MediaItem>, onProgress: (Int, Int) -> Unit): Map<Long, Duplicates.Print> = sharedLock.withLock {
        withContext(Dispatchers.IO) {
            val phone = items.filterNot { it.isDriveOnly }
            val cache = ConcurrentHashMap(load())
            val hashWanted = Duplicates.sameSize(phone).map { it.id }.toHashSet()
            val todo = phone.filter { item ->
                val p = cache[Duplicates.cacheKey(item)]
                p == null || (item.id in hashWanted && p.content == null) || (!item.isVideo && p.picture == null && p.sharpness >= 0f)
            }
            val done = AtomicInteger(0)
            onProgress(0, todo.size)
            coroutineScope {
                // A few at a time: thumbnails come from the phone's own cache and hashing is mostly reading.
                todo.chunked(((todo.size + 3) / 4).coerceAtLeast(1)).map { part ->
                    async(Dispatchers.IO) {
                        for (item in part) {
                            ensureActive()
                            val key = Duplicates.cacheKey(item)
                            val had = cache[key] ?: Duplicates.Print()
                            val content = had.content ?: if (item.id in hashWanted) runCatching { hash(item) }.getOrNull() else null
                            val picture = if (item.isVideo || had.picture != null) Pair(had.picture, had.sharpness) else runCatching { look(item) }.getOrNull()
                            // -1 sharpness: the thumbnail couldn't be read; not tried again every time.
                            cache[key] = Duplicates.Print(content, picture?.first, picture?.second ?: if (item.isVideo) 0f else -1f)
                            val n = done.incrementAndGet()
                            if (n % 25 == 0 || n == todo.size) onProgress(n, todo.size)
                            if (n % 300 == 0) save(cache)
                        }
                    }
                }.awaitAll()
            }
            if (todo.isNotEmpty()) save(cache)
            phone.mapNotNull { item -> cache[Duplicates.cacheKey(item)]?.let { item.id to it } }.toMap()
        }
    }

    /** SHA-256 of the whole file. */
    private fun hash(item: MediaItem): String {
        val digest = MessageDigest.getInstance("SHA-256")
        app.contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        } ?: error("can't open")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Fingerprint and sharpness from the phone's small thumbnail. */
    private fun look(item: MediaItem): Pair<Long, Float> {
        val thumb = app.contentResolver.loadThumbnail(Uri.parse(item.uri), Size(256, 256), null)
        try {
            val tiny = Bitmap.createScaledBitmap(thumb, 9, 8, true)
            val grid = Bitmap.createScaledBitmap(thumb, 96, 96, true)
            val fingerprint = Duplicates.differenceHash(grey(tiny))
            val sharp = Duplicates.sharpness(grey(grid), 96, 96)
            if (tiny !== thumb) tiny.recycle()
            if (grid !== thumb) grid.recycle()
            return fingerprint to sharp
        } finally {
            thumb.recycle()
        }
    }

    private fun grey(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return IntArray(px.size) { i ->
            val c = px[i]
            (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
    }

    private fun load(): Map<String, Duplicates.Print> = runCatching {
        val f = file
        if (!f.exists()) return@runCatching emptyMap()
        f.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) null
            else p[0] to Duplicates.Print(p[1].ifEmpty { null }, p[2].ifEmpty { null }?.toLongOrNull(), p[3].toFloatOrNull() ?: 0f)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun save(cache: Map<String, Duplicates.Print>) = runCatching {
        val f = file
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { out ->
            for ((k, v) in cache) out.append(k).append('\t').append(v.content.orEmpty()).append('\t')
                .append(v.picture?.toString().orEmpty()).append('\t').append(v.sharpness.toString()).append('\n')
        }
        tmp.renameTo(f)
    }
}

/** Works out the duplicate finder's fingerprints ahead, only while charging, so the page opens quickly (15 Nov). */
class DuplicatePrepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val items = runCatching { MediaStoreLibrary(applicationContext).loadAll() }.getOrElse { return Result.success() }
        runCatching { AndroidDuplicates(applicationContext).prints(items) { _, _ -> } }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DuplicatePrepWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("duplicate_prep", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
