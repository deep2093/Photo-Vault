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
