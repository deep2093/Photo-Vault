package com.deepak.photovault.core.drive

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory drive with the same rules as the real one: atomic create-new, no overwrite, path checks.
 * Used by tests (several "phones" can share one) and by screen previews.
 */
class MemoryDrive(
    private val total: Long = 2_000_000_000_000,
) : DriveFileSystem {
    private val lock = Mutex()
    private val files = LinkedHashMap<String, ByteArray>()
    private val folders = HashSet<String>().apply { add("") }

    /** Test hook: fails every call while set, as an unplugged drive would. */
    var offline = false

    private fun online() {
        if (offline) throw DriveOffline()
    }

    override suspend fun list(path: String): List<DriveEntry> = lock.withLock {
        online()
        val p = DrivePaths.check(path)
        val prefix = if (p.isEmpty()) "" else "$p/"
        val childFolders = folders.filter { it.startsWith(prefix) && it != p && !it.removePrefix(prefix).contains('/') && it.isNotEmpty() }
            .map { DriveEntry(it.removePrefix(prefix), true, 0) }
        val childFiles = files.filterKeys { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { (k, v) -> DriveEntry(k.removePrefix(prefix), false, v.size.toLong()) }
        childFolders + childFiles
    }

    override suspend fun exists(path: String): Boolean = lock.withLock {
        online()
        val p = DrivePaths.check(path)
        p in folders || p in files
    }

    override suspend fun mkdirs(path: String) = lock.withLock {
        online()
        var p = DrivePaths.check(path)
        require(p !in files) { "A file is in the way" }
        while (p.isNotEmpty()) {
            folders.add(p)
            p = DrivePaths.parent(p)
        }
    }

    override suspend fun read(path: String): ByteArray = lock.withLock {
        online()
        files[DrivePaths.check(path)]?.copyOf() ?: throw DriveNotFound(path)
    }

    override suspend fun createNew(path: String, bytes: ByteArray): Boolean = lock.withLock {
        online()
        val p = DrivePaths.check(path)
        require(DrivePaths.parent(p) in folders) { "Folder missing" }
        if (p in files || p in folders) return@withLock false
        files[p] = bytes.copyOf()
        true
    }

    /** Test hook: flips one byte of every file written by [writeNew] after this is set, like a bad cable. */
    var corruptWrites = false

    /** Test hook: the drive "unplugs" after this many more [writeNew] calls. */
    var failAfterWrites: Int? = null

    override suspend fun writeNew(path: String, source: ByteSource): Boolean {
        val buffer = ByteArray(8192)
        // Read outside the lock, as a real upload would.
        val chunks = ArrayList<ByteArray>()
        while (true) {
            val n = source.read(buffer)
            if (n < 0) break
            chunks.add(buffer.copyOf(n))
        }
        val bytes = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.forEach { c ->
            c.copyInto(bytes, offset)
            offset += c.size
        }
        return lock.withLock {
            failAfterWrites?.let { left ->
                if (left <= 0) offline = true
                failAfterWrites = left - 1
            }
            online()
            val p = DrivePaths.check(path)
            require(DrivePaths.parent(p) in folders) { "Folder missing" }
            if (p in files || p in folders) return@withLock false
            if (corruptWrites && bytes.isNotEmpty()) bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
            files[p] = bytes
            true
        }
    }

    override suspend fun readStream(path: String, sink: (ByteArray, Int) -> Unit) {
        val bytes = lock.withLock {
            online()
            files[DrivePaths.check(path)]?.copyOf() ?: throw DriveNotFound(path)
        }
        var o = 0
        while (o < bytes.size) {
            val n = minOf(8192, bytes.size - o)
            sink(bytes.copyOfRange(o, o + n), n)
            o += n
        }
    }

    override suspend fun rename(from: String, to: String): Boolean = lock.withLock {
        online()
        val f = DrivePaths.check(from)
        val t = DrivePaths.check(to)
        val bytes = files[f] ?: throw DriveNotFound(from)
        require(DrivePaths.parent(t) in folders) { "Folder missing" }
        if (t in files || t in folders) return@withLock false
        files.remove(f)
        files[t] = bytes
        true
    }

    /** Test helper: every file path, sorted. */
    suspend fun allFiles(): List<String> = lock.withLock { files.keys.sorted() }

    override suspend fun delete(path: String) = lock.withLock {
        online()
        files.remove(DrivePaths.check(path))
        Unit
    }

    override suspend fun folderSize(path: String): Long = lock.withLock {
        online()
        val p = DrivePaths.check(path)
        files.filterKeys { it.startsWith("$p/") }.values.sumOf { it.size.toLong() }
    }

    override suspend fun space(): DriveSpace = lock.withLock {
        online()
        DriveSpace(total, total - files.values.sumOf { it.size.toLong() })
    }
}

class DriveOffline : Exception("Drive not reachable")
