package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.ByteSource
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DrivePaths

/** The two kinds of encrypted file a Private person has on the drive. */
enum class ObjectKind(val folder: String, val extension: String) {
    MEDIA("objects", "pxo"),
    THUMB("thumbs", "pxt"),
}

/** An encrypted file did not pass its checks: wrong key, changed, cut short or swapped. Never a network problem. */
class ObjectAuthenticationFailed(message: String = "The encrypted file did not pass its checks") : Exception(message)

/**
 * Everything encryption-related the backup code needs for one Private person (Phase 4B, 25 Oct). The person's key
 * stays inside; the backup code only sees streams and sealed records. Android: Tink (docs/ENCRYPTION_PLAN.md).
 */
interface PrivateVault {
    /** A fresh random name for a stored file. Never derived from the photo. */
    fun newObjectId(): String

    /** [plain] as an encrypted stream, ready to be written to the drive. Closing it closes [plain]. */
    fun encrypt(plain: ByteSource, kind: ObjectKind, objectId: String): ByteSource

    /**
     * Reads an encrypted file with [readCipher] (which hands over the stored bytes chunk by chunk) and passes the
     * checked plain bytes to [out]. Throws [ObjectAuthenticationFailed] when any part fails its check, so a file
     * is never partly trusted; errors from reading the drive are passed on unchanged.
     */
    suspend fun decrypt(
        kind: ObjectKind,
        objectId: String,
        readCipher: suspend ((ByteArray, Int) -> Unit) -> Unit,
        out: (ByteArray, Int) -> Unit,
    )

    /** Small records (manifests, Recently deleted notes) sealed so only this person can read or change them. */
    fun sealRecord(plain: ByteArray, purpose: String): ByteArray

    /** The plain record, or null when it isn't this person's or was changed. */
    fun openRecord(sealed: ByteArray, purpose: String): ByteArray?
}

/** Where encrypted files live: `users/<id>/objects/ab/<id>.pxo` and `users/<id>/thumbs/ab/<id>.pxt`. */
object PrivatePaths {
    fun path(userFolder: String, kind: ObjectKind, id: String) =
        DrivePaths.join(userFolder, kind.folder, id.take(2), "$id.${kind.extension}")

    fun isObject(path: String) = path.endsWith(".pxo")

    /** The object's ID. A file in Recently deleted is named `<time>-<n>-<id>.pxo`; the ID has no dashes. */
    fun idOf(path: String): String = path.substringAfterLast('/').removeSuffix(".pxo").substringAfterLast('-')

    /** The thumbnail that belongs to an encrypted file, wherever the file is now (also in Recently deleted). */
    fun thumbFor(path: String): String? {
        if (!isObject(path)) return null
        val parts = path.trim('/').split('/')
        if (parts.size < 3 || parts[0] != "users") return null
        return path(DrivePaths.join(parts[0], parts[1]), ObjectKind.THUMB, idOf(path))
    }
}

/** Reads and checks one encrypted file of the drive, giving the plain bytes to [out]. */
suspend fun PrivateVault.decryptFile(drive: DriveFileSystem, path: String, kind: ObjectKind = ObjectKind.MEDIA, out: (ByteArray, Int) -> Unit) {
    decrypt(kind, PrivatePaths.idOf(path), { sink -> drive.readStream(path, sink) }, out)
}
