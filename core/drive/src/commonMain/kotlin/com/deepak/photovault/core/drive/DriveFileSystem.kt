package com.deepak.photovault.core.drive

/**
 * The PhotoVault folder on the family drive, as the rest of the app sees it. Every path is relative to that
 * folder ("system/household/v000001.json"), forward slashes, no leading slash. Implementations reject "..",
 * "." and empty segments, so nothing can reach outside the folder.
 *
 * Android: [com.deepak.photovault.core.smb] over SMB. Tests: [MemoryDrive].
 */
interface DriveFileSystem {
    /** Entries directly inside [path] ("" = the PhotoVault folder). Empty when the folder does not exist. */
    suspend fun list(path: String): List<DriveEntry>

    suspend fun exists(path: String): Boolean

    /** Creates [path] and any missing parents. Does nothing when it already exists. */
    suspend fun mkdirs(path: String)

    /** Reads a small file (settings, requests). Throws [DriveNotFound] when it does not exist. */
    suspend fun read(path: String): ByteArray

    /**
     * Writes [bytes] to [path] only if nothing exists there yet, and atomically: readers either see the whole
     * file or none of it. Returns false when [path] already existed (someone else got there first).
     * This is what makes family changes safe when two phones change things at the same moment.
     */
    suspend fun createNew(path: String, bytes: ByteArray): Boolean

    /** Deletes a file. Does nothing when it is already gone. Never deletes folders. */
    suspend fun delete(path: String)

    /** Total size of all files under [path], 0 when it does not exist. */
    suspend fun folderSize(path: String): Long

    /** Space on the whole drive (shared by everyone and everything on it). */
    suspend fun space(): DriveSpace
}

data class DriveEntry(val name: String, val isFolder: Boolean, val size: Long)

data class DriveSpace(val totalBytes: Long, val freeBytes: Long)

class DriveNotFound(path: String) : Exception("Not found: $path")

/** Path rules shared by every implementation. */
object DrivePaths {
    fun check(path: String): String {
        val p = path.replace('\\', '/').trim('/')
        require(p.split('/').none { it == "." || it == ".." } && !p.contains("//")) { "Invalid drive path" }
        return p
    }

    fun join(vararg parts: String): String = parts.map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")

    fun parent(path: String): String = path.substringBeforeLast('/', "")
}
