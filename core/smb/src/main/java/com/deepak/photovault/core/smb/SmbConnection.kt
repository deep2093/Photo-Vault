package com.deepak.photovault.core.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbProfile(
    val host: String,
    val share: String,
    /** Optional sub-folder inside the share, forward slashes, no leading/trailing slash. */
    val rootPath: String,
    val username: String,
    val password: String,
    val domain: String = "",
    val requireSigning: Boolean = false,
    val requireEncryption: Boolean = false,
) {
    // A data class would print the password in logs and crash reports.
    override fun toString(): String = "SmbProfile(host=$host, share=$share, rootPath=$rootPath, username=$username, password=***)"
}

data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val isReparsePoint: Boolean = false,
)

/**
 * A single SMB connection. All methods block, so call them from Dispatchers.IO.
 *
 * Ported from Veyra. Removed on purpose: recursive delete and tree copy (PhotoVault v1 never deletes on
 * the drive). Write/verify methods for backups are added in Phase 5, with their own tests.
 */
class SmbConnection(private val profile: SmbProfile) : Closeable {
    private var client: SMBClient? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    fun open() {
        close()
        val config = SmbConfig
            .builder()
            .withSigningRequired(profile.requireSigning)
            .withEncryptData(profile.requireEncryption)
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
        val newClient = SMBClient(config)
        try {
            val connection = newClient.connect(profile.host.trim())
            val auth = if (profile.username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(profile.username.trim(), profile.password.toCharArray(), profile.domain.trim())
            }
            val newSession = connection.authenticate(auth)
            if (profile.requireEncryption && !newSession.shouldEncryptData()) throw SmbSecurityException()
            val newShare = newSession.connectShare(profile.share.trim()) as DiskShare
            client = newClient
            session = newSession
            share = newShare
        } catch (t: Throwable) {
            runCatching { newClient.close() }
            throw t
        }
    }

    fun reconnect() = open()

    /** Lists a directory. [path] is relative to the share root, forward slashes, "" for the root. */
    fun list(path: String): List<SmbEntry> {
        val disk = share ?: throw IOException("Not connected")
        val smbPath = path.trim('/').replace('/', '\\')
        val result = ArrayList<SmbEntry>()
        for (info in disk.list(smbPath)) {
            val name = info.fileName
            if (name == "." || name == "..") continue
            val isDir = EnumWithValue.EnumUtils.isSet(
                info.fileAttributes,
                FileAttributes.FILE_ATTRIBUTE_DIRECTORY,
            )
            result.add(
                SmbEntry(
                    name = name,
                    isDirectory = isDir,
                    size = info.endOfFile,
                    modified = info.lastWriteTime.toEpochMillis(),
                    isReparsePoint = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT),
                ),
            )
        }
        return result
    }

    /** Opens a file for reading only. [path] is relative to the share root, forward slashes. */
    fun openFile(path: String): com.hierynomus.smbj.share.File {
        val disk = share ?: throw IOException("Not connected")
        return disk.openFile(
            path.trim('/').replace('/', '\\'),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    /**
     * Path containment. Every write-capable call goes through here: no empty, "." or ".." segments
     * and never the share root. Ported unchanged from Veyra.
     */
    private fun safePath(value: String): String {
        val normalized = value.replace('\\', '/').trim('/')
        require(normalized.isNotBlank() && normalized.split('/').none { it == "." || it == ".." || it.isBlank() }) {
            "Invalid or root file path"
        }
        return normalized.replace('/', '\\')
    }

    fun exists(path: String): Boolean {
        val disk = share ?: throw IOException("Not connected")
        val p = safePath(path)
        return disk.fileExists(p) || disk.folderExists(p)
    }

    /** Server file identity survives rename; use it to reconcile an interrupted operation. */
    fun identity(path: String): Long {
        val disk = share ?: throw IOException("Not connected")
        return disk.getFileInformation(safePath(path)).internalInformation.indexNumber.also {
            require(it != 0L) { "Server does not provide stable file identities" }
        }
    }

    private fun regularEntry(path: String): SmbEntry {
        val normalized=path.replace('\\','/').trim('/')
        safePath(normalized)
        val parent=normalized.substringBeforeLast('/',"")
        val leaf=normalized.substringAfterLast('/')
        val entries=list(parent)
        val entry=entries.firstOrNull { it.name==leaf } ?: entries.singleOrNull { it.name.equals(leaf,ignoreCase=true) } ?: throw java.io.FileNotFoundException("Source not found")
        require(!entry.isReparsePoint) { "Linked files and folders cannot be changed" }
        return entry
    }

    /**
     * Renames within the share. Never replaces an existing destination (rename with replace=false),
     * which is what the backup engine relies on to publish a verified temp file without overwriting.
     */
    fun renamePath(source: String, destination: String, directory: Boolean = false, cancelled: () -> Boolean = {false}) {
        val disk = share ?: throw IOException("Not connected")
        val src = safePath(source)
        val dst = safePath(destination)
        require(src != dst) { "Source and destination are the same" }
        regularEntry(source)
        if(cancelled())throw java.io.InterruptedIOException("Move cancelled")
        require(!disk.fileExists(dst) && !disk.folderExists(dst)) { "Destination already exists" }
        if (directory) {
            disk
                .openDirectory(
                    src,
                    EnumSet.of(AccessMask.DELETE, AccessMask.FILE_WRITE_ATTRIBUTES),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use {
                    if(cancelled())throw java.io.InterruptedIOException("Move cancelled")
                    it.rename(dst,false)
                }
        } else {
            disk
                .openFile(
                    src,
                    EnumSet.of(AccessMask.DELETE, AccessMask.FILE_WRITE_ATTRIBUTES),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use {
                    if(cancelled())throw java.io.InterruptedIOException("Move cancelled")
                    it.rename(dst,false)
                }
        }
    }

    // ---- PhotoVault writes (Phase 3). Each one stays inside safePath and never replaces an existing file. ----

    /** Creates [path] and missing parents. */
    fun mkdirs(path: String) {
        val disk = share ?: throw IOException("Not connected")
        val parts = path.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current/$part"
            val p = safePath(current)
            if (!disk.folderExists(p)) {
                try {
                    disk.mkdir(p)
                } catch (e: SMBApiException) {
                    // Another phone may have created it a moment ago.
                    if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION) throw e
                }
            }
        }
    }

    /** Reads a whole small file. Refuses anything over [limit] bytes. */
    fun readBytes(path: String, limit: Int = 4 * 1024 * 1024): ByteArray = openFile(safePath(path).replace('\\', '/')).use { file ->
        file.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) throw IOException("File too large")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    /**
     * Atomic create-new: writes a private temporary file, then renames it into place without replacing.
     * Returns false when [path] already exists. Readers never see a half-written file.
     */
    fun createNewFile(path: String, bytes: ByteArray): Boolean {
        val disk = share ?: throw IOException("Not connected")
        val target = safePath(path)
        if (disk.fileExists(target) || disk.folderExists(target)) return false
        val parent = path.replace('\\', '/').trim('/').substringBeforeLast('/', "")
        val temp = listOf(parent, ".pv-tmp-" + java.util.UUID.randomUUID()).filter { it.isNotEmpty() }.joinToString("/")
        disk.openFile(
            safePath(temp),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE,
            null,
        ).use { file -> file.outputStream.use { it.write(bytes); it.flush() } }
        return try {
            renamePath(temp, path)
            true
        } catch (e: IllegalArgumentException) {
            if (e.message != "Destination already exists") throw e
            false
        } catch (e: SMBApiException) {
            if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION) throw e
            false
        } finally {
            runCatching { if (disk.fileExists(safePath(temp))) disk.rm(safePath(temp)) }
        }
    }

    /**
     * Streams [source] into a new file (FILE_CREATE: fails if anything is there). Returns false when [path]
     * already exists. Used for backup's private temporary files; publishing is a separate no-replace rename.
     * [cancelled] is checked between chunks so a stopped backup ends quickly.
     */
    fun writeNewFile(path: String, source: com.deepak.photovault.core.drive.ByteSource, cancelled: () -> Boolean = { false }): Boolean {
        val disk = share ?: throw IOException("Not connected")
        val target = safePath(path)
        if (disk.fileExists(target) || disk.folderExists(target)) return false
        try {
            disk.openFile(
                target,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE,
                null,
            ).use { file ->
                file.outputStream.use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) throw java.io.InterruptedIOException("Backup stopped")
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (n > 0) out.write(buffer, 0, n)
                    }
                    out.flush()
                }
            }
        } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_OBJECT_NAME_COLLISION) return false
            throw e
        }
        return true
    }

    /** Reads a file of any size, chunk by chunk, into [sink]. */
    fun readStream(path: String, cancelled: () -> Boolean = { false }, sink: (ByteArray, Int) -> Unit) {
        openFile(safePath(path).replace('\\', '/')).use { file ->
            file.inputStream.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    if (cancelled()) throw java.io.InterruptedIOException("Backup stopped")
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) sink(buffer, n)
                }
            }
        }
    }

    /** Renames a file without ever replacing; false when [to] already exists. */
    fun renameNoReplace(from: String, to: String): Boolean = try {
        renamePath(from, to)
        true
    } catch (e: IllegalArgumentException) {
        if (e.message != "Destination already exists") throw e
        false
    } catch (e: SMBApiException) {
        if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION) throw e
        false
    }

    /** Deletes one file. Never folders. Does nothing when it is already gone. */
    fun deleteFile(path: String) {
        val disk = share ?: throw IOException("Not connected")
        val p = safePath(path)
        if (disk.fileExists(p)) disk.rm(p)
    }

    /** Total size of files under [path]; skips links. */
    fun folderSize(path: String, depthLimit: Int = 64): Long {
        var total = 0L
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(path.trim('/') to 0)
        while (pending.isNotEmpty()) {
            val (folder, depth) = pending.removeFirst()
            if (depth > depthLimit) continue
            val entries = try {
                list(folder)
            } catch (e: SMBApiException) {
                if (e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) continue else throw e
            }
            for (entry in entries) {
                if (entry.isReparsePoint) continue
                if (entry.isDirectory) pending.add("$folder/${entry.name}".trim('/') to depth + 1) else total += entry.size
            }
        }
        return total
    }

    /** Total and free bytes on the share's disk. */
    fun space(): Pair<Long, Long> {
        val disk = share ?: throw IOException("Not connected")
        val info = disk.shareInformation
        return info.totalSpace to info.freeSpace
    }

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        client = null
    }
}

class SmbSecurityException : IOException("Required SMB encryption was not negotiated")

object SmbErrors {
    /**
     * True when nothing answered at the address (timed out, refused, no route). False when a server did answer
     * and said no (wrong password, missing share, security): then trying other addresses wouldn't help.
     */
    fun isUnreachable(e: Throwable): Boolean {
        var cause: Throwable? = e
        var network = false
        while (cause != null) {
            when (cause) {
                is SMBApiException, is SmbSecurityException, is IllegalArgumentException -> return false
                is TransportException, is IOException -> network = true
            }
            cause = cause.cause
        }
        return network
    }

    /** Whether two IPv4 addresses (as 32-bit numbers) are in the same network of [prefixLength] bits. */
    fun sameNetwork(a: Int, b: Int, prefixLength: Int): Boolean {
        val mask = if (prefixLength <= 0) 0 else (-1 shl (32 - prefixLength.coerceAtMost(32)))
        return (a and mask) == (b and mask)
    }

    /** Addresses to try when [saved] stops answering: the same home network only, nearest numbers first. */
    fun nearbyFirst(saved: String, found: List<String>): List<String> {
        val prefix = saved.substringBeforeLast('.', "")
        val last = saved.substringAfterLast('.').toIntOrNull() ?: return emptyList()
        if (prefix.isEmpty()) return emptyList()
        return found
            .filter { it != saved && it.substringBeforeLast('.') == prefix }
            .mapNotNull { h -> h.substringAfterLast('.').toIntOrNull()?.let { h to kotlin.math.abs(it - last) } }
            .sortedBy { it.second }
            .map { it.first }
    }
    /** Turns an exception into a message that is useful to show on screen. Never includes the password. */
    fun describe(e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is SmbSecurityException -> return "This server did not negotiate the required SMB encryption. Check its SMB3 security settings."
                is IllegalArgumentException -> safeValidationMessage(cause.message)?.let { return it }
                is SMBApiException -> return describeStatus(cause)
                is java.io.FileNotFoundException -> return "File or folder no longer exists. Refresh the library."
                is UnknownHostException -> return "Server address not found. Check the address and that you are on the home Wi-Fi."
                is SocketTimeoutException -> return "The connection timed out. The router may be busy or the address may be wrong."
            }
            cause = cause.cause
        }
        // Raw exception messages can include a URL, username or share path.
        // Never surface arbitrary library exception text to the UI or scan logs.
        return when (e) {
            is TransportException, is IOException -> "Network problem. Check SMB address, Wi-Fi, permissions and connection."
            else -> "SMB operation failed (${e.javaClass.simpleName}). Check connection and access permissions."
        }
    }

    private fun safeValidationMessage(message: String?): String? = when (message) {
        "Invalid or root file path" -> "Choose a valid file or folder; the share root cannot be changed."
        "Source and destination are the same" -> "Choose a different destination."
        "Destination already exists" -> "The destination already exists. Choose another name or folder."
        "Cannot copy a folder into itself" -> "Choose a destination outside the source folder."
        else -> null
    }

    private fun describeStatus(e: SMBApiException): String = when (e.status) {
        NtStatus.STATUS_LOGON_FAILURE -> "Incorrect username or password."
        NtStatus.STATUS_ACCOUNT_DISABLED -> "This account is disabled on the server."
        NtStatus.STATUS_BAD_NETWORK_NAME -> "Share not found. Check the share name."
        NtStatus.STATUS_ACCESS_DENIED -> "Access denied."
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        -> "Folder not found."
        else -> "Server error: ${e.status}"
    }

    /** True for failures that a reconnect might fix (network drops), false for permission/path errors. */
    fun isTransient(e: Throwable): Boolean {
        var cause: Throwable? = e
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (cause != null && seen.add(cause)) {
            if (cause is SMBApiException || cause is SmbSecurityException || cause is IllegalArgumentException || cause is java.io.FileNotFoundException) return false
            if (cause is InterruptedException || cause is java.io.InterruptedIOException && cause !is SocketTimeoutException) return false
            cause = cause.cause
        }
        return e is TransportException || e is IOException || e is SMBRuntimeException
    }
}
