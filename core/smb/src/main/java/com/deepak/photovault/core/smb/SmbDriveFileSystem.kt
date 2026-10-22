package com.deepak.photovault.core.smb

import com.deepak.photovault.core.drive.DriveEntry
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.drive.DrivePaths
import com.deepak.photovault.core.drive.DriveSpace
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * The PhotoVault folder on an SMB share. [root] is the folder inside the share chosen at setup
 * ("PhotoVault" by default); every path is kept inside it.
 *
 * One connection, used one call at a time. A dropped connection (Wi-Fi change, router restart) is reopened
 * once and the call retried; permanent errors (wrong password, missing share) are not retried.
 */
class SmbDriveFileSystem(private val profile: SmbProfile, root: String) : DriveFileSystem, Closeable {
    private val root = DrivePaths.check(root)
    private val lock = Mutex()
    private var connection: SmbConnection? = null

    private fun full(path: String): String = DrivePaths.join(root, DrivePaths.check(path))

    private suspend fun <T> call(block: (SmbConnection) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            val first = runCatching { block(connected()) }
            first.getOrElse { error ->
                if (!SmbErrors.isTransient(error)) throw error
                // One clean reconnect, then a single retry.
                connection?.close()
                connection = null
                block(connected())
            }
        }
    }

    private fun connected(): SmbConnection = connection ?: SmbConnection(profile).also {
        it.open()
        connection = it
    }

    /** Opens the connection now, so setup can report a wrong password or address straight away. */
    suspend fun check() = call { }

    override suspend fun list(path: String): List<DriveEntry> = call { c ->
        try {
            c.list(full(path)).map { DriveEntry(it.name, it.isDirectory, it.size) }
        } catch (e: SMBApiException) {
            if (e.notFound()) emptyList() else throw e
        }
    }

    override suspend fun exists(path: String): Boolean = call { c -> c.exists(full(path)) }

    override suspend fun mkdirs(path: String) = call { c -> c.mkdirs(full(path)) }

    override suspend fun read(path: String): ByteArray = call { c ->
        try {
            c.readBytes(full(path))
        } catch (e: SMBApiException) {
            if (e.notFound()) throw DriveNotFound(path) else throw e
        }
    }

    override suspend fun createNew(path: String, bytes: ByteArray): Boolean = call { c -> c.createNewFile(full(path), bytes) }

    override suspend fun delete(path: String) = call { c -> c.deleteFile(full(path)) }

    override suspend fun folderSize(path: String): Long = call { c -> c.folderSize(full(path)) }

    override suspend fun space(): DriveSpace = call { c -> c.space().let { (total, free) -> DriveSpace(total, free) } }

    override fun close() {
        connection?.close()
        connection = null
    }

    private fun SMBApiException.notFound() =
        status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND
}
