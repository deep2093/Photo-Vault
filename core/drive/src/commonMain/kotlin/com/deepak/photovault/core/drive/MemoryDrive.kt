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
